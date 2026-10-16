use serde::{Deserialize, Serialize};

/// The set of on-chain operations the edge model is allowed to request.
///
/// The enum is the *only* vocabulary the executor understands: no raw `to`, calldata,
/// or value may be supplied by the caller, so an unrecognised action is rejected at
/// deserialization time rather than being silently coerced into a transfer.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Action {
    Transfer,
    Swap,
    Stake,
}

/// A structured intent produced by the on-device Kotlin/MNN model.
///
/// Unknown fields are rejected outright (`deny_unknown_fields`): a model that invents
/// extra keys is evidence of a drifted or tampered payload, not something to ignore.
#[derive(Debug, Clone, PartialEq, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AiIntent {
    pub action: Action,
    /// Human-denominated amount (USD or whole token units). Non-finite and negative
    /// values are rejected by the guardrails, never by serde, so that the caller gets
    /// a `BlockedByGuardrail` result rather than a deserialization error.
    pub amount: f64,
    /// Optional exact amount in the token's smallest unit, as a decimal digit string.
    /// When present this is authoritative for ERC-20 transfers, sidestepping `f64`
    /// rounding entirely.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub amount_minor: Option<String>,
    pub token_in: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token_out: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub destination_address: Option<String>,
}

/// Terminal state of an intent.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum TxStatus {
    Success,
    Failed,
    BlockedByGuardrail,
}

/// JSON payload returned across the FFI boundary on *every* path, including panics.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct TransactionResult {
    pub status: TxStatus,
    pub tx_hash: Option<String>,
    pub message: String,
}

impl TransactionResult {
    /// A confirmed broadcast, carrying the on-chain transaction hash.
    pub fn success(tx_hash: String, message: impl Into<String>) -> Self {
        Self {
            status: TxStatus::Success,
            tx_hash: Some(tx_hash),
            message: message.into(),
        }
    }

    /// A broadcast that never happened: guardrail rejection, bad input, or RPC error.
    pub fn blocked(message: impl Into<String>) -> Self {
        Self {
            status: TxStatus::BlockedByGuardrail,
            tx_hash: None,
            message: message.into(),
        }
    }

    /// An execution failure *after* the guardrails passed.
    pub fn failed(message: impl Into<String>) -> Self {
        Self {
            status: TxStatus::Failed,
            tx_hash: None,
            message: message.into(),
        }
    }

    /// Serializes to the JSON string handed back to Kotlin.
    ///
    /// Serialization of this struct cannot fail (no maps, no floats), so a failure here
    /// would be a programmer error rather than a runtime condition.
    pub fn to_json_string(&self) -> String {
        serde_json::to_string(self).expect("TransactionResult is infallibly serializable")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_complete_transfer_intent() {
        let raw = r#"{
            "action": "transfer",
            "amount": 25.5,
            "token_in": "USDC",
            "token_out": null,
            "destination_address": "0x1111111111111111111111111111111111111111"
        }"#;

        let intent: AiIntent = serde_json::from_str(raw).expect("valid intent");

        assert_eq!(intent.action, Action::Transfer);
        assert_eq!(intent.amount, 25.5);
        assert_eq!(intent.token_in, "USDC");
        assert_eq!(intent.token_out, None);
        assert_eq!(
            intent.destination_address.as_deref(),
            Some("0x1111111111111111111111111111111111111111")
        );
        assert_eq!(intent.amount_minor, None);
    }

    #[test]
    fn parses_a_minimal_intent_with_optional_fields_absent() {
        let raw = r#"{"action":"stake","amount":1,"token_in":"WETH"}"#;

        let intent: AiIntent = serde_json::from_str(raw).expect("valid minimal intent");

        assert_eq!(intent.action, Action::Stake);
        assert_eq!(intent.token_out, None);
        assert_eq!(intent.destination_address, None);
        assert_eq!(intent.amount_minor, None);
    }

    #[test]
    fn parses_every_action_variant() {
        for (raw, expected) in [
            (
                r#"{"action":"transfer","amount":1,"token_in":"USDC"}"#,
                Action::Transfer,
            ),
            (
                r#"{"action":"swap","amount":1,"token_in":"USDC"}"#,
                Action::Swap,
            ),
            (
                r#"{"action":"stake","amount":1,"token_in":"WETH"}"#,
                Action::Stake,
            ),
        ] {
            let intent: AiIntent = serde_json::from_str(raw).unwrap();
            assert_eq!(intent.action, expected);
        }
    }

    #[test]
    fn parses_exact_amount_minor_field() {
        let raw = r#"{
            "action":"transfer","amount":0.1,"amount_minor":"100000",
            "token_in":"USDC","destination_address":"0x2222222222222222222222222222222222222222"
        }"#;

        let intent: AiIntent = serde_json::from_str(raw).expect("valid intent");

        assert_eq!(intent.amount_minor.as_deref(), Some("100000"));
    }

    #[test]
    fn rejects_missing_required_fields() {
        for raw in [
            r#"{"amount":1,"token_in":"USDC"}"#,
            r#"{"action":"transfer","token_in":"USDC"}"#,
            r#"{"action":"transfer","amount":1}"#,
            r#"{}"#,
        ] {
            let err = serde_json::from_str::<AiIntent>(raw);
            assert!(err.is_err(), "expected missing-field error for {raw}");
        }
    }

    #[test]
    fn rejects_unknown_fields() {
        let raw = r#"{
            "action":"transfer","amount":1,"token_in":"USDC","gas_limit":"unlimited"
        }"#;

        let err =
            serde_json::from_str::<AiIntent>(raw).expect_err("unknown field must be rejected");

        assert!(err.to_string().contains("unknown field"), "{err}");
    }

    #[test]
    fn rejects_unrecognised_action() {
        let raw = r#"{"action":"drain","amount":1,"token_in":"USDC"}"#;

        let err =
            serde_json::from_str::<AiIntent>(raw).expect_err("unknown action must be rejected");

        assert!(err.to_string().contains("unknown variant"), "{err}");
    }

    #[test]
    fn rejects_wrongly_typed_amount() {
        for raw in [
            r#"{"action":"transfer","amount":"100","token_in":"USDC"}"#,
            r#"{"action":"transfer","amount":null,"token_in":"USDC"}"#,
            r#"{"action":"transfer","amount":[100],"token_in":"USDC"}"#,
            r#"{"action":"transfer","amount":{},"token_in":"USDC"}"#,
        ] {
            assert!(
                serde_json::from_str::<AiIntent>(raw).is_err(),
                "expected type error for {raw}"
            );
        }
    }

    #[test]
    fn rejects_out_of_range_number_literals() {
        // serde_json refuses `1e400` outright ("number out of range") rather than yielding
        // an infinity, so the JSON boundary can never carry a non-finite amount. The
        // guardrails still re-check finiteness for values constructed in-process.
        let err = serde_json::from_str::<AiIntent>(
            r#"{"action":"transfer","amount":1e400,"token_in":"USDC"}"#,
        )
        .expect_err("out-of-range literal must be rejected");

        assert!(err.to_string().contains("number out of range"), "{err}");
    }

    #[test]
    fn rejects_malformed_json() {
        for raw in [
            "",
            "{",
            "null",
            "[{\"action\":\"transfer\"}]",
            r#"{"action":"transfer","amount":1,"token_in":"USDC",}"#,
        ] {
            assert!(
                serde_json::from_str::<AiIntent>(raw).is_err(),
                "expected malformed-json error for {raw:?}"
            );
        }
    }

    #[test]
    fn result_constructors_set_status_and_hash() {
        let ok = TransactionResult::success("0xabc".into(), "sent");
        assert_eq!(ok.status, TxStatus::Success);
        assert_eq!(ok.tx_hash.as_deref(), Some("0xabc"));

        let blocked = TransactionResult::blocked("over tx size limit");
        assert_eq!(blocked.status, TxStatus::BlockedByGuardrail);
        assert_eq!(blocked.tx_hash, None);

        let failed = TransactionResult::failed("rpc down");
        assert_eq!(failed.status, TxStatus::Failed);
        assert_eq!(failed.tx_hash, None);
    }

    #[test]
    fn serializes_result_to_expected_json_contract() {
        let json = TransactionResult::blocked("nope").to_json_string();

        assert_eq!(
            json,
            r#"{"status":"blocked_by_guardrail","tx_hash":null,"message":"nope"}"#
        );
    }

    #[test]
    fn result_round_trips_through_json() {
        let original = TransactionResult::success("0xdeadbeef".into(), "ok");
        let parsed: TransactionResult = serde_json::from_str(&original.to_json_string()).unwrap();
        assert_eq!(original, parsed);
    }
}
