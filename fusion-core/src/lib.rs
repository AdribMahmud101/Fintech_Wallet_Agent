//! `fusion-core` — a deterministic, memory-safe EVM execution engine for edge AI
//! wallet intents.
//!
//! The crate is organised so that trust decreases as you read it:
//!
//! | Module | Trust | Responsibility |
//! |---|---|---|
//! | [`models`] | untrusted input | strict deserialization of the model's JSON |
//! | [`config`] | host-controlled | RPC endpoint, limits, asset allowlist |
//! | [`guardrails`] | policy | economic rules + the persisted velocity ledger |
//! | [`executor`] | trusted code | key handling, ABI encoding, signing, broadcast |
//! | [`router`] | orchestration | parse → guard → execute → report |
//!
//! The FFI surface is intentionally tiny. [`execute_intent`] takes a JSON string and a
//! private key and returns a JSON string; it never panics across the boundary, and it
//! never accepts a destination, a callee, calldata or a gas limit from the caller.

pub mod config;
pub mod executor;
pub mod guardrails;
pub mod models;
pub mod router;

use zeroize::Zeroizing;

// Emits the FFI scaffolding functions, the `UniFfiTag` marker type and the namespace
// metadata blob that `uniffi-bindgen` reads out of the cdylib. The argument is the
// namespace the generated Kotlin bindings live in.
uniffi::setup_scaffolding!("fusion_core");

/// Executes one AI intent end to end.
///
/// This is a **synchronous** export: it drives its own tokio runtime, so it blocks the
/// calling thread for the duration of the RPC round trip. Kotlin callers must therefore
/// invoke it from `Dispatchers.IO` (see `KOTLIN_INTEGRATION.md`) — calling it on the
/// Android main thread will freeze the UI and can trigger an ANR.
///
/// # Arguments
/// * `json_payload` — an [`models::AiIntent`] serialised as JSON.
/// * `private_key` — a 32-byte secp256k1 key, `0x`-prefixed or bare hex. The string is
///   zeroized on drop and is never logged or echoed into the returned message.
///
/// # Returns
/// A [`models::TransactionResult`] serialised as JSON. Every path returns a result:
/// malformed input, guardrail rejection, bad credentials, RPC failure and success are
/// all distinguishable by the `status` field.
#[uniffi::export]
pub fn execute_intent(json_payload: String, private_key: String) -> String {
    // The caller hands us a heap-allocated secret; wrap it so it is wiped rather than
    // left sitting in freed memory for the rest of the process's life.
    let private_key = Zeroizing::new(private_key);
    let engine = router::current_engine();

    router::execute(engine, &json_payload, private_key.as_str())
}

/// Supplies wallet settings explicitly.
///
/// Optional on desktop and CI, where the `FUSION_*` environment variables are read
/// automatically. **Required on Android and iOS**, where a process cannot set its own
/// environment: call this once during startup, before the first
/// [`execute_intent`].
///
/// The JSON object accepts exactly the environment variable names:
///
/// ```json
/// {
///   "rpc_url": "https://mainnet.infura.io/v3/<key>",
///   "chain_id": "1",
///   "whitelist": "0xA0b8…eB48,0xC02a…6Cc2",
///   "max_tx_usd": "100",
///   "daily_limit_usd": "500",
///   "ledger_path": "/data/data/app.fusion.wallet/files/ledger.json"
/// }
/// ```
///
/// The first successful call wins. Later calls are refused so that a compromised UI
/// cannot re-point a running wallet at a different RPC endpoint.
#[uniffi::export]
pub fn configure_wallet(settings_json: String) -> String {
    router::configure(&settings_json).to_json_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exports_are_present_and_linkable() {
        // Guards against a UniFFI macro regression silently dropping an export.
        let _ = execute_intent as fn(String, String) -> String;
        let _ = configure_wallet as fn(String) -> String;
    }

    #[test]
    fn execute_intent_never_panics_on_junk_input() {
        for (payload, key) in [("", ""), ("null", "0x"), ("{}", TEST_KEY), ("[]", TEST_KEY)] {
            let raw = execute_intent(payload.to_string(), key.to_string());
            let parsed: models::TransactionResult =
                serde_json::from_str(&raw).expect("always returns result JSON");
            assert!(!parsed.message.is_empty());
        }
    }

    #[test]
    fn a_bad_key_never_appears_in_the_response() {
        let raw = execute_intent(
            r#"{"action":"transfer","amount":1,"token_in":"USDC"}"#.to_string(),
            "0xsupersecretnotavalidkey".to_string(),
        );

        assert!(!raw.contains("supersecret"), "key leaked in: {raw}");
    }

    const TEST_KEY: &str = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
}
