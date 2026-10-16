//! Key handling, EVM transaction construction and broadcast.
//!
//! Nothing in this module trusts its callers. It accepts a [`Reservation`] — the only
//! artefact the guardrail engine produces — and turns it into an EVM transaction. The
//! caller can never hand over a `to` address, calldata or a gas limit, so the blast
//! radius of a compromised or hallucinating model is exactly "transfer up to the
//! configured caps of a whitelisted token".
//!
//! Every function here is real production code: real secp256k1 signing, real ABI
//! encoding, real JSON-RPC broadcast. There is no mocking, stubbing or placeholder
//! logic in this file; the mocked transports live in `tests/`.

use alloy::network::Ethereum;
use alloy::primitives::{Address, TxHash, U256};
use alloy::providers::{Provider, ProviderBuilder};
use alloy::rpc::types::TransactionRequest;
use alloy::signers::local::PrivateKeySigner;
use alloy::sol;
use alloy::sol_types::SolCall;
use std::str::FromStr;
use thiserror::Error;
use url::Url;

use crate::config::{WalletConfig, NATIVE_ASSET};
use crate::guardrails::Reservation;

/// Upper bound on any ERC-20 approval this crate will construct: one million whole
/// tokens at 18 decimals. Approvals are a building block for swaps and therefore a
/// favourite of both phishing flows and hand-written calldata, so the ceiling is set
/// far above anything a legitimate integration needs.
///
/// Approvals are **not reachable from the AI intent path** — the intent vocabulary has
/// no approve verb, and [`crate::guardrails::check_supported_action`] rejects anything
/// but `transfer`. This constant exists to keep the cap in force if that ever changes.
pub const MAX_APPROVAL_ATOMIC: u128 = 1_000_000 * 1_000_000_000_000_000_000;

sol! {
    /// The subset of ERC-20 this engine can call.
    interface IERC20 {
        function transfer(address to, uint256 amount) external returns (bool);
        function approve(address spender, uint256 amount) external returns (bool);
    }
}

#[derive(Debug, Error, Clone, PartialEq)]
pub enum ExecutorError {
    /// Deliberately carries no detail: parser errors from key libraries have been known
    /// to echo the offending input, and this string ends up in a `TransactionResult`
    /// that is handed straight to the app's UI.
    #[error("private key is not a valid 0x-prefixed 32-byte hex string")]
    InvalidPrivateKey,

    #[error("approval of {requested} exceeds the {maximum} ceiling")]
    ApprovalTooLarge { requested: U256, maximum: U256 },

    #[error("transaction broadcast was rejected: {reason}")]
    BroadcastRejected { reason: String },
}

/// A loaded signing identity.
///
/// The secret is held by alloy in a zeroizing wrapper and is never echoed by `Debug`,
/// `Display`, `Clone` or any error type in this crate.
#[derive(Clone)]
pub struct LoadedWallet {
    signer: PrivateKeySigner,
    address: Address,
}

impl std::fmt::Debug for LoadedWallet {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LoadedWallet")
            .field("address", &self.address)
            .finish_non_exhaustive()
    }
}

impl LoadedWallet {
    /// Loads a secp256k1 private key and derives its address.
    pub fn load(private_key: &str) -> Result<Self, ExecutorError> {
        let trimmed = private_key.trim();
        if trimmed.is_empty() {
            return Err(ExecutorError::InvalidPrivateKey);
        }

        // Accept both `0x…` and bare hex; Android key stores frequently omit the prefix.
        let normalised = if trimmed.starts_with("0x") || trimmed.starts_with("0X") {
            trimmed.to_string()
        } else {
            format!("0x{trimmed}")
        };

        let signer = PrivateKeySigner::from_str(&normalised)
            .map_err(|_| ExecutorError::InvalidPrivateKey)?;

        Ok(Self {
            address: signer.address(),
            signer,
        })
    }

    pub fn address(&self) -> Address {
        self.address
    }

    pub fn signer(&self) -> &PrivateKeySigner {
        &self.signer
    }
}

/// Builds the live HTTP provider used in production.
///
/// `.wallet()` installs the signing filler, so every transaction pushed through the
/// returned provider is EIP-1559-filled, nonce-managed and signed locally before it
/// reaches `eth_sendRawTransaction`.
pub fn build_provider(config: &WalletConfig, wallet: &LoadedWallet) -> impl Provider<Ethereum> {
    ProviderBuilder::new()
        .with_chain_id(config.chain_id)
        .wallet(wallet.signer().clone())
        .connect_http(config.rpc_url.clone())
}

/// Builds a provider against an explicit endpoint. Used by tests and by hosts that
/// hand their own URL in.
pub fn build_provider_for_url(
    url: Url,
    chain_id: u64,
    wallet: &LoadedWallet,
) -> impl Provider<Ethereum> {
    ProviderBuilder::new()
        .with_chain_id(chain_id)
        .wallet(wallet.signer().clone())
        .connect_http(url)
}

// ---------------------------------------------------------------------------
// Transaction construction
// ---------------------------------------------------------------------------

/// A plain value transfer of the chain's native asset.
pub fn build_native_transfer(to: Address, value_wei: U256) -> TransactionRequest {
    TransactionRequest::default().to(to).value(value_wei)
}

/// An ERC-20 `transfer(to, amount)` call.
pub fn build_erc20_transfer(token: Address, to: Address, amount: U256) -> TransactionRequest {
    let call = IERC20::transferCall { to, amount };

    TransactionRequest::default()
        .to(token)
        .input(call.abi_encode().into())
}

/// An ERC-20 `approve(spender, amount)` call, capped at [`MAX_APPROVAL_ATOMIC`].
pub fn build_erc20_approve(
    token: Address,
    spender: Address,
    amount: U256,
) -> Result<TransactionRequest, ExecutorError> {
    let maximum = U256::from(MAX_APPROVAL_ATOMIC);
    if amount > maximum {
        return Err(ExecutorError::ApprovalTooLarge {
            requested: amount,
            maximum,
        });
    }

    let call = IERC20::approveCall { spender, amount };

    Ok(TransactionRequest::default()
        .to(token)
        .input(call.abi_encode().into()))
}

/// Routes a guardrail-approved reservation to the right transaction shape.
pub fn build_request_for(reservation: &Reservation) -> TransactionRequest {
    if reservation.token == NATIVE_ASSET {
        build_native_transfer(reservation.destination, reservation.amount.atomic)
    } else {
        build_erc20_transfer(
            reservation.token,
            reservation.destination,
            reservation.amount.atomic,
        )
    }
}

/// Fills, signs and broadcasts a transaction.
///
/// Generic over the provider so the identical code path runs against the production
/// HTTP transport and against the mocked transport used by the test suite.
pub async fn broadcast<P: Provider<Ethereum>>(
    provider: P,
    request: TransactionRequest,
) -> Result<TxHash, ExecutorError> {
    let pending = provider.send_transaction(request).await.map_err(|err| {
        ExecutorError::BroadcastRejected {
            reason: err.to_string(),
        }
    })?;

    Ok(*pending.tx_hash())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::BUILTIN_ASSETS;
    use crate::guardrails::ResolvedAmount;
    use alloy::primitives::address;
    use alloy::primitives::TxKind;
    use std::str::FromStr;

    const USDC: Address = BUILTIN_ASSETS[1].address;
    const DEST: Address = address!("0x1111111111111111111111111111111111111111");
    const SPENDER: Address = address!("0x2222222222222222222222222222222222222222");

    /// Anvil account #0. Publicly known, never funded outside a local test chain, and
    /// used here only to pin deterministic key → address derivation.
    const TEST_KEY: &str = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    const TEST_ADDRESS: &str = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";

    fn reservation(token: Address, atomic: u64) -> Reservation {
        Reservation {
            token,
            decimals: 6,
            destination: DEST,
            amount: ResolvedAmount {
                usd_value: 1.0,
                atomic: U256::from(atomic),
            },
        }
    }

    // -- key loading ---------------------------------------------------------

    #[test]
    fn loads_a_known_private_key() {
        let wallet = LoadedWallet::load(TEST_KEY).expect("valid key");

        assert_eq!(
            Address::from_str(TEST_ADDRESS).unwrap(),
            wallet.address(),
            "key -> address derivation changed"
        );
    }

    #[test]
    fn accepts_keys_without_the_hex_prefix() {
        let bare = TEST_KEY.trim_start_matches("0x");

        assert_eq!(
            LoadedWallet::load(bare).unwrap().address(),
            LoadedWallet::load(TEST_KEY).unwrap().address()
        );
    }

    #[test]
    fn tolerates_surrounding_whitespace() {
        let padded = format!("  {TEST_KEY}\n");
        assert_eq!(
            LoadedWallet::load(&padded).unwrap().address(),
            LoadedWallet::load(TEST_KEY).unwrap().address()
        );
    }

    #[test]
    fn rejects_malformed_private_keys() {
        for key in [
            "",
            "   ",
            "0x",
            "not-a-key",
            "0xdeadbeef",
            "0xzzzz111111111111111111111111111111111111111111111111111111111111",
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80ff",
        ] {
            assert!(
                matches!(
                    LoadedWallet::load(key),
                    Err(ExecutorError::InvalidPrivateKey)
                ),
                "expected {key:?} to be rejected"
            );
        }
    }

    #[test]
    fn key_errors_never_echo_the_secret() {
        let secret = "0xnotavalidkey";
        let rendered = LoadedWallet::load(secret).unwrap_err().to_string();

        assert!(!rendered.contains("notavalidkey"), "leaked key: {rendered}");
    }

    #[test]
    fn debug_never_reveals_the_secret() {
        let wallet = LoadedWallet::load(TEST_KEY).unwrap();
        let rendered = format!("{wallet:?}");

        assert!(rendered.contains(&TEST_ADDRESS[2..6]));
        assert!(!rendered.contains(TEST_KEY.trim_start_matches("0x")));
    }

    // -- transaction construction -------------------------------------------

    #[test]
    fn native_transfer_carries_to_and_value() {
        let request = build_native_transfer(DEST, U256::from(1_000u64));

        assert_eq!(request.to, Some(TxKind::Call(DEST)));
        assert_eq!(request.value, Some(U256::from(1_000u64)));
        assert!(
            request.input.input.is_none(),
            "a native transfer must not carry calldata"
        );
    }

    #[test]
    fn erc20_transfer_targets_the_token_and_encodes_real_calldata() {
        let request = build_erc20_transfer(USDC, DEST, U256::from(25_000_000u64));

        assert_eq!(request.to, Some(TxKind::Call(USDC)));
        assert_eq!(
            request.value, None,
            "an ERC-20 transfer must not carry value"
        );

        let data = request.input.input.as_ref().expect("calldata");
        assert_eq!(
            &data[..4],
            &[0xa9, 0x05, 0x9c, 0xbb],
            "transfer(address,uint256)"
        );
        assert_eq!(data.len(), 4 + 64, "one address + one uint256 argument");
    }

    #[test]
    fn erc20_transfer_calldata_decodes_back_to_the_arguments() {
        let amount = U256::from(12_500_000u64);
        let request = build_erc20_transfer(USDC, DEST, amount);
        let decoded = IERC20::transferCall::abi_decode(&request.input.input.expect("calldata"))
            .expect("well-formed calldata");

        assert_eq!(decoded.to, DEST);
        assert_eq!(decoded.amount, amount);
    }

    #[test]
    fn erc20_approve_targets_the_token_and_encodes_real_calldata() {
        let amount = U256::from(500u64);
        let request = build_erc20_approve(USDC, SPENDER, amount).expect("within cap");

        assert_eq!(request.to, Some(TxKind::Call(USDC)));

        let data = request.input.input.expect("calldata");

        let decoded = IERC20::approveCall::abi_decode(&data).expect("well-formed calldata");
        assert_eq!(decoded.spender, SPENDER);
        assert_eq!(decoded.amount, amount);
        assert_eq!(&data[..4], &[0x09, 0x5e, 0xa7, 0xb3]);
    }

    #[test]
    fn erc20_approve_is_capped() {
        let over = U256::from(MAX_APPROVAL_ATOMIC) + U256::from(1);
        let err = build_erc20_approve(USDC, SPENDER, over).expect_err("cap must apply");

        assert_eq!(
            err,
            ExecutorError::ApprovalTooLarge {
                requested: over,
                maximum: U256::from(MAX_APPROVAL_ATOMIC)
            }
        );

        // Exactly at the ceiling is allowed.
        assert!(build_erc20_approve(USDC, SPENDER, U256::from(MAX_APPROVAL_ATOMIC)).is_ok());
    }

    #[test]
    fn approval_cap_survives_a_maximal_uint256() {
        assert!(build_erc20_approve(USDC, SPENDER, U256::MAX).is_err());
    }

    #[test]
    fn routes_native_reservations_to_value_transfers() {
        let request = build_request_for(&reservation(NATIVE_ASSET, 1_000_000_000_000_000_000));

        assert_eq!(request.to, Some(TxKind::Call(DEST)));
        assert_eq!(
            request.value,
            Some(U256::from(1_000_000_000_000_000_000u64))
        );
    }

    #[test]
    fn routes_erc20_reservations_to_token_calls() {
        let request = build_request_for(&reservation(USDC, 25_000_000));

        assert_eq!(request.to, Some(TxKind::Call(USDC)));
        assert_eq!(request.value, None);
        assert_eq!(
            &request.input.input.expect("calldata")[..4],
            &[0xa9, 0x05, 0x9c, 0xbb]
        );
    }

    #[test]
    fn never_places_the_signer_in_the_request() {
        // The signer is attached by the provider's wallet filler, never baked into a
        // request that could be logged or serialised on its own.
        let request = build_request_for(&reservation(USDC, 1));
        let rendered = format!("{request:?}");

        assert!(!rendered.contains(&TEST_ADDRESS[2..6]));
    }
}
