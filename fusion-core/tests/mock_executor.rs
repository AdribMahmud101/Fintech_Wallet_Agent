//! Integration tests for the EVM executor against a mocked JSON-RPC transport.
//!
//! These are the **only** tests in the crate that mock anything. Production code in
//! `src/` talks to a real HTTP provider; here `alloy::transports::mock::Asserter`
//! stands in for the node so CI needs no network, no node and no funded account.
//!
//! Everything else — secp256k1 signing, EIP-1559 construction, RLP encoding, ABI
//! encoding — is the real implementation, exercised end to end.

use alloy::consensus::TxEip1559;
use alloy::network::TxSignerSync;
use alloy::primitives::Address;
use alloy::primitives::{address, TxKind, U256};
use alloy::providers::ProviderBuilder;
use alloy::transports::mock::Asserter;
use fusion_core::executor::{broadcast, build_request_for, LoadedWallet};
use fusion_core::guardrails::{GuardrailEngine, Reservation, ResolvedAmount};
use fusion_core::models::{Action, AiIntent};

/// Anvil account #0. Publicly known, unfunded outside a local chain, used purely to
/// pin deterministic key → address derivation.
const TEST_KEY: &str = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
const TEST_ADDRESS: &str = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";

const USDC: Address = address!("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48");
const DEST: Address = address!("0x1111111111111111111111111111111111111111");

/// The hash the fake node hands back from `eth_sendRawTransaction`.
const RETURNED_HASH: &str = "0x1234567890123456789012345678901234567890123456789012345678901234";
/// The nonce the fake node reports, used to prove the nonce filler ran.
const NONCE: u64 = 7;
const GAS_LIMIT: u64 = 100_000;

/// The exact RPC conversation a send costs, in order. `ProviderBuilder::new()`
/// installs the recommended fillers, which ask for gas, fees and a nonce before
/// broadcasting.
fn queue_successful_send(asserter: &Asserter) {
    asserter.push_success(&format!("0x{GAS_LIMIT:x}"));
    asserter.push_success(&serde_json::json!({
        "oldestBlock": "0x1",
        "baseFeePerGas": ["0x3b9aca00", "0x77359400"],
        "gasUsedRatio": [0.5],
        "reward": [["0x3b9aca00"]]
    }));
    asserter.push_success(&format!("0x{NONCE:x}"));
    asserter.push_success(&RETURNED_HASH);
}

fn wallet() -> LoadedWallet {
    LoadedWallet::load(TEST_KEY).expect("test key")
}

fn reservation(token: Address, atomic: u64, decimals: u8) -> Reservation {
    Reservation {
        token,
        decimals,
        destination: DEST,
        amount: ResolvedAmount {
            usd_value: 1.0,
            atomic: U256::from(atomic),
        },
    }
}

#[tokio::test]
async fn broadcasts_and_returns_the_node_hash() {
    let asserter = Asserter::new();
    queue_successful_send(&asserter);

    let provider = ProviderBuilder::new()
        .with_chain_id(1)
        .wallet(wallet().signer().clone())
        .connect_mocked_client(asserter.clone());

    let request = build_request_for(&reservation(USDC, 1_000_000, 6));
    let tx_hash = broadcast(provider, request).await.expect("broadcast");

    assert_eq!(format!("{tx_hash}"), RETURNED_HASH);
}

#[tokio::test]
async fn a_successful_send_drains_exactly_four_rpc_calls() {
    let asserter = Asserter::new();
    queue_successful_send(&asserter);

    let provider = ProviderBuilder::new()
        .with_chain_id(1)
        .wallet(wallet().signer().clone())
        .connect_mocked_client(asserter.clone());

    let request = build_request_for(&reservation(USDC, 1_000_000, 6));
    broadcast(provider, request).await.expect("broadcast");

    assert!(
        asserter.read_q().is_empty(),
        "the pipeline issued RPC calls beyond gas, fees, nonce and broadcast"
    );
}

#[tokio::test]
async fn a_node_rejection_is_reported_rather_than_swallowed() {
    let asserter = Asserter::new();
    asserter.push_failure_msg("insufficient funds for gas * price + value");

    let provider = ProviderBuilder::new()
        .with_chain_id(1)
        .wallet(wallet().signer().clone())
        .connect_mocked_client(asserter);

    let request = build_request_for(&reservation(USDC, 1_000_000, 6));
    let err = broadcast(provider, request)
        .await
        .expect_err("a rejected transaction must surface as an error");

    assert!(
        err.to_string().contains("insufficient funds"),
        "the node's reason must reach the caller: {err}"
    );
}

#[tokio::test]
async fn native_transfers_broadcast_through_the_same_path() {
    let asserter = Asserter::new();
    queue_successful_send(&asserter);

    let provider = ProviderBuilder::new()
        .with_chain_id(1)
        .wallet(wallet().signer().clone())
        .connect_mocked_client(asserter);

    let request = build_request_for(&reservation(Address::ZERO, 1_000_000_000_000_000_000, 18));
    let tx_hash = broadcast(provider, request).await.expect("broadcast");

    assert_eq!(format!("{tx_hash}"), RETURNED_HASH);
}

#[tokio::test]
async fn an_unreachable_transport_surfaces_as_an_error() {
    // Nothing is queued, so the first `eth_estimateGas` fails. This is the shape a
    // dead RPC endpoint takes in production.
    let asserter = Asserter::new();
    let provider = ProviderBuilder::new()
        .with_chain_id(1)
        .wallet(wallet().signer().clone())
        .connect_mocked_client(asserter);

    let request = build_request_for(&reservation(USDC, 1_000_000, 6));
    assert!(broadcast(provider, request).await.is_err());
}

// ---------------------------------------------------------------------------
// Signing
// ---------------------------------------------------------------------------

#[test]
fn the_loaded_key_produces_signatures_that_recover_to_the_expected_address() {
    let wallet = wallet();
    let expected: Address = TEST_ADDRESS.parse().expect("valid literal");

    assert_eq!(wallet.address(), expected);

    let mut tx = TxEip1559 {
        chain_id: 1,
        nonce: NONCE,
        gas_limit: GAS_LIMIT,
        max_fee_per_gas: 30_000_000_000u128,
        max_priority_fee_per_gas: 1_000_000_000u128,
        to: TxKind::Call(USDC),
        value: U256::ZERO,
        input: build_request_for(&reservation(USDC, 1_000_000, 6))
            .input
            .input
            .expect("erc-20 calldata"),
        access_list: Default::default(),
    };

    let signature = wallet
        .signer()
        .sign_transaction_sync(&mut tx)
        .expect("signing");
    let recovered = signature
        .recover_address_from_prehash(&tx_hash(&tx))
        .expect("recovery");

    assert_eq!(recovered, expected, "signature must recover to the signer");
}

#[test]
fn signatures_are_bound_to_the_configured_chain_id() {
    let wallet = wallet();

    let mut on_mainnet = eip1559(1);
    let mut on_base = eip1559(8453);

    let mainnet_signature = wallet
        .signer()
        .sign_transaction_sync(&mut on_mainnet)
        .expect("sign");
    let base_signature = wallet
        .signer()
        .sign_transaction_sync(&mut on_base)
        .expect("sign");

    assert_ne!(
        mainnet_signature.as_bytes(),
        base_signature.as_bytes(),
        "EIP-155 replay protection must make chain-specific signatures differ"
    );
}

#[test]
fn signing_the_same_transaction_twice_is_deterministic() {
    let wallet = wallet();

    let mut first = eip1559(1);
    let mut second = eip1559(1);

    assert_eq!(
        wallet.signer().sign_transaction_sync(&mut first).unwrap(),
        wallet.signer().sign_transaction_sync(&mut second).unwrap()
    );
}

// ---------------------------------------------------------------------------
// Guardrail → executor seam
// ---------------------------------------------------------------------------

#[test]
fn only_guardrail_approved_transactions_reach_the_executor() {
    let mut config = fusion_core::config::WalletConfig::from_source(&|key| match key {
        "FUSION_RPC_URL" => Some("http://127.0.0.1:8545".to_string()),
        _ => None,
    })
    .expect("config");

    config.whitelist = [USDC].into_iter().collect();
    let engine = GuardrailEngine::new(config);

    let intent = AiIntent {
        action: Action::Transfer,
        amount: 25.0,
        amount_minor: Some("25000000".to_string()),
        token_in: "USDC".to_string(),
        token_out: None,
        destination_address: Some(DEST.to_string()),
    };

    let reservation = engine.evaluate(&intent).expect("approved");

    // The request is built from the reservation alone: no amount, destination or token
    // is re-read from the untrusted intent.
    let request = build_request_for(&reservation);
    assert_eq!(request.to, Some(TxKind::Call(USDC)));
    assert_eq!(request.value, None);

    let calldata = request.input.input.expect("calldata");
    assert_eq!(calldata.len(), 4 + 64);
    assert_eq!(&calldata[..4], &[0xa9, 0x05, 0x9c, 0xbb]);

    // …and the exact amount survived the f64 → U256 round trip. Layout after the
    // 4-byte selector is `to` (32 bytes) then `amount` (32 bytes).
    let mut padded = [0u8; 32];
    padded[28..32].copy_from_slice(&25_000_000u32.to_be_bytes());
    assert_eq!(&calldata[36..68], &padded);
}

#[test]
fn a_blocked_intent_never_produces_a_transaction_request() {
    let mut config = fusion_core::config::WalletConfig::from_source(&|key| match key {
        "FUSION_RPC_URL" => Some("http://127.0.0.1:8545".to_string()),
        _ => None,
    })
    .expect("config");

    config.whitelist = [USDC].into_iter().collect();
    let engine = GuardrailEngine::new(config);

    let oversized = AiIntent {
        action: Action::Transfer,
        amount: 5_000.0,
        amount_minor: None,
        token_in: "USDC".to_string(),
        token_out: None,
        destination_address: Some(DEST.to_string()),
    };

    assert!(engine.evaluate(&oversized).is_err());
    assert_eq!(engine.spent_today(), 0.0);
}

/// The EIP-2718 signing digest of a transaction, i.e. exactly what gets signed.
fn tx_hash(tx: &TxEip1559) -> alloy::primitives::B256 {
    use alloy::consensus::SignableTransaction;
    tx.signature_hash()
}

fn eip1559(chain_id: u64) -> TxEip1559 {
    TxEip1559 {
        chain_id,
        nonce: NONCE,
        gas_limit: GAS_LIMIT,
        max_fee_per_gas: 30_000_000_000u128,
        max_priority_fee_per_gas: 1_000_000_000u128,
        to: TxKind::Call(USDC),
        value: U256::ZERO,
        input: Default::default(),
        access_list: Default::default(),
    }
}
