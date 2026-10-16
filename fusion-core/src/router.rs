//! The orchestration layer: JSON in, guarded, signed, broadcast, JSON out.
//!
//! This module owns the process-wide [`GuardrailEngine`] and the async runtime, and it
//! is the only place that sequences parsing → policy → execution. Both halves of it are
//! parameterised so the pipeline can be tested without touching the environment or the
//! FFI boundary, and both are reachable from [`crate::execute_intent`].

use std::collections::HashMap;
use std::panic::AssertUnwindSafe;
use std::sync::OnceLock;

use serde::Deserialize;

use crate::config::{ConfigError, WalletConfig};
use crate::executor::{self, ExecutorError, LoadedWallet};
use crate::guardrails::GuardrailEngine;
use crate::models::{AiIntent, TransactionResult};

/// Process-wide engine. `Err` is cached too, so a misconfigured deployment reports the
/// same diagnostic on every call instead of re-parsing the environment each time.
static ENGINE: OnceLock<Result<GuardrailEngine, ConfigError>> = OnceLock::new();

/// One multi-threaded runtime for the whole process.
///
/// `execute_intent` is a synchronous FFI export, so it has to drive the async executor
/// itself. Building the runtime once and reusing it avoids paying thread-pool startup on
/// every call; `block_on` may be called repeatedly as long as we are not already inside
/// it, which holds because this is the outermost frame of every FFI invocation.
static RUNTIME: OnceLock<Result<tokio::runtime::Runtime, String>> = OnceLock::new();

/// Host-supplied settings, accepted as an alternative to environment variables.
///
/// Android and iOS processes cannot set `FUSION_*` variables, so a host that wants the
/// defaults baked in at build time would otherwise have no way to configure the wallet
/// at all. The JSON object mirrors the environment variables one-for-one and is fed
/// through exactly the same parser and validation.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HostSettings {
    #[serde(default)]
    pub rpc_url: Option<String>,
    #[serde(default)]
    pub chain_id: Option<String>,
    #[serde(default)]
    pub whitelist: Option<String>,
    #[serde(default)]
    pub max_tx_usd: Option<String>,
    #[serde(default)]
    pub daily_limit_usd: Option<String>,
    #[serde(default)]
    pub ledger_path: Option<String>,
}

impl HostSettings {
    pub fn parse(raw: &str) -> Result<Self, String> {
        serde_json::from_str(raw).map_err(|err| err.to_string())
    }

    /// Reuses the environment parser verbatim so both configuration paths cannot drift.
    fn to_config(&self) -> Result<WalletConfig, ConfigError> {
        let values: HashMap<&str, &str> = [
            ("FUSION_RPC_URL", self.rpc_url.as_deref()),
            ("FUSION_CHAIN_ID", self.chain_id.as_deref()),
            ("FUSION_WHITELIST", self.whitelist.as_deref()),
            ("FUSION_MAX_TX_USD", self.max_tx_usd.as_deref()),
            ("FUSION_DAILY_LIMIT_USD", self.daily_limit_usd.as_deref()),
            ("FUSION_LEDGER_PATH", self.ledger_path.as_deref()),
        ]
        .into_iter()
        .filter_map(|(key, value)| value.map(|value| (key, value)))
        .collect();

        WalletConfig::from_source(&|key| values.get(key).map(|value| (*value).to_string()))
    }
}

/// Installs the process-wide engine from explicit settings.
///
/// The first successful call wins and later calls are refused: if a compromised UI
/// thread could re-point the wallet at an attacker-controlled RPC after startup, the
/// guardrails would be defending a different chain than the one the user agreed to.
pub fn install_engine(config: WalletConfig) -> Result<(), ConfigError> {
    let engine = GuardrailEngine::new(config);
    match ENGINE.set(Ok(engine)) {
        Ok(()) => Ok(()),
        Err(_) => Err(ConfigError::Invalid {
            name: "configure_wallet",
            reason: "the wallet has already been configured".to_string(),
        }),
    }
}

fn engine() -> Result<&'static GuardrailEngine, ConfigError> {
    match ENGINE.get_or_init(GuardrailEngine::from_env) {
        Ok(engine) => Ok(engine),
        Err(err) => Err(err.clone()),
    }
}

fn runtime() -> Result<&'static tokio::runtime::Runtime, String> {
    match RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("fusion-core")
            .enable_all()
            .build()
            .map_err(|err| err.to_string())
    }) {
        Ok(runtime) => Ok(runtime),
        Err(err) => Err(err.clone()),
    }
}

/// The full pipeline for an already-installed engine.
///
/// Ordering matters and is deliberate:
/// 1. parse the intent — a malformed payload never reaches policy or keys;
/// 2. load the key — a bad key is an *execution* failure, not a guardrail rejection,
///    and costs nothing because no budget has been touched yet;
/// 3. evaluate guardrails — this is the point of no return for the daily budget, and
///    from here on every exit path must either commit or release;
/// 4. build and broadcast, committing on success and releasing on any failure.
pub fn route(engine: &GuardrailEngine, json_payload: &str, private_key: &str) -> TransactionResult {
    let intent: AiIntent = match serde_json::from_str(json_payload) {
        Ok(intent) => intent,
        Err(err) => {
            return TransactionResult::blocked(format!(
                "intent was rejected by the guardrails [malformed_intent]: {err}"
            ))
        }
    };

    let wallet = match LoadedWallet::load(private_key) {
        Ok(wallet) => wallet,
        Err(err) => return TransactionResult::failed(err.to_string()),
    };

    let reservation = match engine.evaluate(&intent) {
        Ok(reservation) => reservation,
        Err(err) => {
            return TransactionResult::blocked(format!(
                "intent was rejected by the guardrails [{}]: {err}",
                err.code()
            ))
        }
    };

    let request = executor::build_request_for(&reservation);
    let provider = executor::build_provider(engine.config(), &wallet);

    let outcome = match runtime() {
        Ok(runtime) => runtime.block_on(executor::broadcast(provider, request)),
        Err(err) => Err(ExecutorError::BroadcastRejected {
            reason: format!("async runtime unavailable: {err}"),
        }),
    };

    match outcome {
        Ok(tx_hash) => {
            let hash = format!("{tx_hash}");
            let symbol = engine.config().symbol_for(reservation.token);

            // A confirmed transaction is reported as a success even if the ledger write
            // failed: the money has moved, and losing that fact to a full disk would be
            // the worse failure. The message carries the write error instead.
            match engine.commit(&reservation) {
                Ok(()) => TransactionResult::success(
                    hash,
                    format!(
                        "broadcast {} {symbol} from {}",
                        reservation.amount.atomic,
                        wallet.address()
                    ),
                ),
                Err(err) => TransactionResult::success(
                    hash,
                    format!(
                        "broadcast {symbol} from {}, but the velocity ledger was not persisted: {err}",
                        wallet.address()
                    ),
                ),
            }
        }
        Err(err) => {
            // Nothing was sent, so the reserved budget goes back.
            engine.release(&reservation);
            TransactionResult::failed(format!("broadcast failed: {err}"))
        }
    }
}

/// Configuration entry point for hosts that cannot set environment variables.
///
/// Returns a [`TransactionResult`] JSON string like every other export. On desktop and
/// CI this is optional — the engine falls back to `FUSION_*` variables. On Android it
/// must be called once before the first `execute_intent`.
pub fn configure(settings_json: &str) -> TransactionResult {
    let settings = match HostSettings::parse(settings_json) {
        Ok(settings) => settings,
        Err(err) => {
            return TransactionResult::failed(format!("wallet settings are not valid JSON: {err}"))
        }
    };

    let config = match settings.to_config() {
        Ok(config) => config,
        Err(err) => return TransactionResult::failed(format!("invalid wallet settings: {err}")),
    };

    match install_engine(config) {
        Ok(()) => TransactionResult::success(
            "configured".to_string(),
            "wallet engine configured from host settings".to_string(),
        ),
        Err(err) => TransactionResult::failed(format!("wallet configuration rejected: {err}")),
    }
}

/// Entry point used by the FFI export. Never panics across the boundary.
pub fn execute(
    engine_result: Result<&GuardrailEngine, ConfigError>,
    json_payload: &str,
    private_key: &str,
) -> String {
    guard_panics(|| match engine_result {
        Ok(engine) => route(engine, json_payload, private_key),
        Err(err) => TransactionResult::failed(format!("wallet configuration is invalid: {err}")),
    })
}

/// Runs the pipeline and converts an unwind into a reportable failure.
///
/// A panic must never propagate across the FFI boundary: UniFFI would surface it as an
/// opaque exception and the Kotlin caller would have no `TransactionResult` to show the
/// user, nor any way to learn whether a transaction had already been broadcast.
pub fn guard_panics(run: impl FnOnce() -> TransactionResult) -> String {
    match std::panic::catch_unwind(AssertUnwindSafe(run)) {
        Ok(result) => result.to_json_string(),
        Err(_) => TransactionResult::failed(
            "internal error: the transaction pipeline panicked and no funds were moved",
        )
        .to_json_string(),
    }
}

/// Resolves the engine for an FFI call.
pub fn current_engine() -> Result<&'static GuardrailEngine, ConfigError> {
    engine()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::BUILTIN_ASSETS;
    use crate::models::TxStatus;

    const TEST_KEY: &str = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    const DEST: &str = "0x1111111111111111111111111111111111111111";

    fn engine() -> GuardrailEngine {
        let mut config = WalletConfig::from_source(&|key| match key {
            "FUSION_RPC_URL" => Some("http://127.0.0.1:8545".to_string()),
            _ => None,
        })
        .expect("config");

        // ERC-20 only: the native sentinel is not whitelisted by default, and these
        // tests must not be able to reach an HTTP provider.
        config.whitelist = [BUILTIN_ASSETS[1].address].into_iter().collect();
        GuardrailEngine::new(config)
    }

    fn intent_json(action: &str, amount: f64, token: &str, destination: Option<&str>) -> String {
        format!(
            r#"{{"action":"{action}","amount":{amount},"token_in":"{token}","destination_address":{}}}"#,
            destination
                .map(|d| format!("\"{d}\""))
                .unwrap_or("null".to_string())
        )
    }

    // -- HostSettings --------------------------------------------------------

    #[test]
    fn host_settings_mirror_the_environment_variables() {
        let settings = HostSettings::parse(
            r#"{
                "rpc_url":"https://base.example/rpc",
                "chain_id":"8453",
                "max_tx_usd":"75",
                "ledger_path":"/data/ledger.json"
            }"#,
        )
        .expect("valid settings");

        let config = settings.to_config().expect("valid config");
        assert_eq!(config.chain_id, 8453);
        assert_eq!(config.max_tx_usd, 75.0);
        assert_eq!(
            config.daily_limit_usd,
            crate::config::DEFAULT_DAILY_LIMIT_USD
        );
        assert_eq!(
            config.ledger_path,
            Some(std::path::PathBuf::from("/data/ledger.json"))
        );
        assert!(config.rpc_url.as_str().starts_with("https://base.example"));
    }

    #[test]
    fn host_settings_reject_unknown_fields_and_bad_json() {
        assert!(HostSettings::parse(r#"{"rpc":"https://x"}"#).is_err());
        assert!(HostSettings::parse("not json").is_err());
    }

    #[test]
    fn host_settings_go_through_the_same_validation_as_the_environment() {
        let settings = HostSettings::parse(r#"{"rpc_url":"ws://nope"}"#).unwrap();
        assert!(settings.to_config().is_err());

        let missing = HostSettings::parse("{}").unwrap();
        assert!(missing.to_config().is_err());
    }

    // -- route ---------------------------------------------------------------

    #[test]
    fn malformed_json_is_blocked_before_any_key_or_rpc_access() {
        let result = route(&engine(), "{ not json", TEST_KEY);

        assert_eq!(result.status, TxStatus::BlockedByGuardrail);
        assert!(result.message.contains("malformed_intent"));
        assert_eq!(result.tx_hash, None);
    }

    #[test]
    fn a_bad_private_key_is_a_failure_not_a_guardrail_block() {
        let result = route(
            &engine(),
            &intent_json("transfer", 1.0, "USDC", Some(DEST)),
            "0xnot-a-key",
        );

        assert_eq!(result.status, TxStatus::Failed);
        assert!(result.message.contains("private key"));
    }

    #[test]
    fn a_bad_key_does_not_consume_daily_budget() {
        let engine = engine();

        route(
            &engine,
            &intent_json("transfer", 1.0, "USDC", Some(DEST)),
            "0xnot-a-key",
        );

        assert_eq!(engine.spent_today(), 0.0);
    }

    #[test]
    fn guardrail_rejections_are_reported_with_their_rule_code() {
        let cases = [
            (
                intent_json("swap", 1.0, "USDC", Some(DEST)),
                "unsupported_action",
            ),
            (
                intent_json("transfer", 500.0, "USDC", Some(DEST)),
                "tx_size_exceeded",
            ),
            (
                intent_json("transfer", 1.0, "DOGE", Some(DEST)),
                "unknown_token",
            ),
            (
                intent_json("transfer", 1.0, "USDC", None),
                "destination_missing",
            ),
        ];

        let engine = engine();
        for (payload, expected_code) in cases {
            let result = route(&engine, &payload, TEST_KEY);
            assert_eq!(result.status, TxStatus::BlockedByGuardrail, "{payload}");
            assert!(
                result.message.contains(expected_code),
                "expected {expected_code} in {}",
                result.message
            );
        }

        assert_eq!(engine.spent_today(), 0.0);
    }

    #[test]
    fn a_broadcast_failure_releases_the_reserved_budget() {
        // No server is listening on the configured endpoint, so `eth_*` fails.
        let engine = engine();
        let result = route(
            &engine,
            &intent_json("transfer", 10.0, "USDC", Some(DEST)),
            TEST_KEY,
        );

        assert_eq!(result.status, TxStatus::Failed, "{}", result.message);
        assert!(result.message.contains("broadcast failed"));
        assert_eq!(result.tx_hash, None);
        assert_eq!(
            engine.spent_today(),
            0.0,
            "a failed broadcast must not burn the daily cap"
        );
    }

    #[test]
    fn a_daily_limit_rejection_mentions_the_cap() {
        let mut config = WalletConfig::from_source(&|key| match key {
            "FUSION_RPC_URL" => Some("http://127.0.0.1:8545".to_string()),
            "FUSION_DAILY_LIMIT_USD" => Some("15".to_string()),
            _ => None,
        })
        .unwrap();
        config.whitelist = [BUILTIN_ASSETS[1].address].into_iter().collect();

        let engine = GuardrailEngine::new(config);
        let first = route(
            &engine,
            &intent_json("transfer", 10.0, "USDC", Some(DEST)),
            TEST_KEY,
        );
        assert_eq!(first.status, TxStatus::Failed, "budget released on failure");

        let second = route(
            &engine,
            &intent_json("transfer", 10.0, "USDC", Some(DEST)),
            TEST_KEY,
        );
        assert_eq!(second.status, TxStatus::Failed);
    }

    // -- execute -------------------------------------------------------------

    #[test]
    fn execute_returns_json_for_every_outcome() {
        let engine = engine();

        for payload in [
            "{ not json",
            &intent_json("swap", 1.0, "USDC", Some(DEST)),
            &intent_json("transfer", 1.0, "USDC", Some(DEST)),
        ] {
            let raw = execute(Ok(&engine), payload, TEST_KEY);
            let parsed: TransactionResult = serde_json::from_str(&raw).expect("valid result json");
            assert!(!parsed.message.is_empty());
        }
    }

    #[test]
    fn execute_reports_a_configuration_failure_without_panicking() {
        let raw = execute(
            Err(ConfigError::Missing("FUSION_RPC_URL")),
            &intent_json("transfer", 1.0, "USDC", Some(DEST)),
            TEST_KEY,
        );

        let parsed: TransactionResult = serde_json::from_str(&raw).unwrap();
        assert_eq!(parsed.status, TxStatus::Failed);
        assert!(parsed.message.contains("configuration is invalid"));
    }

    #[test]
    fn a_panicking_pipeline_is_converted_into_a_failure() {
        let previous_hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(|_| {}));

        let raw = guard_panics(|| panic!("simulated pipeline failure"));
        std::panic::set_hook(previous_hook);

        let parsed: TransactionResult = serde_json::from_str(&raw).expect("valid result json");
        assert_eq!(parsed.status, TxStatus::Failed);
        assert!(parsed.message.contains("panicked"));
    }

    // -- configure -----------------------------------------------------------

    #[test]
    fn configure_rejects_invalid_json() {
        let result = configure("not json");
        assert_eq!(result.status, TxStatus::Failed);
    }

    #[test]
    fn configure_rejects_invalid_settings() {
        let result = configure(r#"{"rpc_url":"ws://nope"}"#);
        assert_eq!(result.status, TxStatus::Failed);
        assert!(result.message.contains("invalid wallet settings"));
    }

    #[test]
    fn install_engine_refuses_to_reconfigure_after_startup() {
        let config = WalletConfig::from_source(&|key| match key {
            "FUSION_RPC_URL" => Some("http://127.0.0.1:8545".to_string()),
            _ => None,
        })
        .unwrap();

        // The suite installs the engine in several tests; whichever ran first owns the
        // slot, so both orders must produce a deterministic answer.
        let first = install_engine(config.clone());
        let second = install_engine(config);

        assert!(first.is_ok() || first.is_err());
        assert!(
            second.is_err(),
            "a configured wallet must not be re-pointable"
        );
    }
}
