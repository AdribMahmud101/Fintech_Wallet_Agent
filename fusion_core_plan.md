# `fusion-core` Implementation Plan (Approved)

Spec source: `fusion_core_specification.md`
Status: Approved for execution. Corrections below are **mandatory**.

---

## Mandatory Correction 1 — File-Backed Ledger (Step 3)

The daily velocity limit **must survive an app/process restart**.

### Requirements

1. `GuardrailEngine` owns a `Ledger { day: u32, spent_usd: f64 }` in memory **and** a
   `OnceLock<Mutex<Ledger>>`-style process-wide store so the FFI entry point (which is called
   repeatedly from Kotlin) shares one ledger rather than one per call.
2. On initialization, if `FUSION_LEDGER_PATH` is set, **attempt to load** state from that file.
   - Parse failure / unreadable file / corrupt JSON ⇒ log-free recovery: fall back to
     `Ledger::empty()` (fail **closed** on the file being unreadable is NOT acceptable here —
     a missing ledger must not brick the wallet; instead the engine falls back to empty and
     will rewrite on the next successful commit).
   - A `day` value in the file that differs from today (UTC) triggers a **day rollover**
     reset, which is also persisted back on the next commit.
3. On **transaction success only**, write the new state back to the file (atomic:
   write to `<path>.tmp`, then `fs::rename` over `<path>`) so a crash mid-write cannot
   corrupt the ledger.
4. On **failure**, do **not** commit. Any amount reserved during evaluation is released
   back so a blocked/failed send never permanently consumes budget.
5. If `FUSION_LEDGER_PATH` is unset, the engine is in-memory only and persists nothing.

### Serialization format
```json
{ "version": 1, "day": 20345, "spent_usd": 137.5 }
```
Unknown/missing fields are tolerated on read (forward compatibility); unknown `version`
values are treated as a reset.

---

## Mandatory Correction 2 — Zero Mock / Stub Logic in `src/`

`src/` contains **only real, deployable implementation**.

### Forbidden in `src/`
- `todo!()`, `unimplemented!()`, `unreachable!()` used as placeholders
- stubbed functions, placeholder returns, `Default::default()` standing in for real values
- any RPC mocking, fake signer, or canned tx hash
- hard-coded `dummy`/`example` transaction hashes

### Required
- **Real** EVM transaction construction: native `TransactionRequest` value transfers and
  real ERC-20 `transfer`/`approve` calldata via `sol!` ABI encoding.
- **Real** secp256k1 signing through `alloy_signer_local::PrivateKeySigner` (key parsed
  via `FromStr`, zeroized by alloy, never logged, never echoed into `TransactionResult.message`).
- **Real** guardrail enforcement on the real code path.
- **Real** broadcast via the HTTP `Provider` built from `config.rs`.

### Where mocking lives
`tests/` only. Alloy's `connect_mocked_client` / `Asserter` are used exclusively from
`tests/mock_executor.rs` and `#[cfg(test)]` submodules, driven by canned responses for
`eth_chainId`, `eth_getTransactionCount`, `eth_estimateGas`, fee methods and
`eth_sendRawTransaction`.

---

## Correction 3 — Kotlin Integration Note (Step 6)

`execute_intent` is exported **synchronously** and internally drives its own dedicated
`tokio::runtime::Runtime` (approved, avoids nested-runtime deadlocks with UniFFI).

Because it blocks the calling thread, the Android frontend **must** call it off the main
thread:

```kotlin
val resultJson = withContext(Dispatchers.IO) {
    FusionCoreBinding.execute_intent(intentJson, privateKey)
}
```

Calling it directly on the Android Main Thread will block the UI and can trigger an ANR.
This requirement is documented in `KOTLIN_INTEGRATION.md` and in the generated binding notes.

---

## Approved Architecture (unchanged)

| Crate | Version | Notes |
|---|---|---|
| `alloy` | `2.5` (`default-features = false`) | lean feature set; `alloy::transports::mock::Asserter` available to `tests/` |
| `serde` / `serde_json` | `1` | `derive` |
| `tokio` | `1` | `rt-multi-thread` |
| `uniffi` | `0.32.2` | features `cli` + `tokio` |
| `thiserror` | `2` | error enums |

`crate-type = ["lib", "cdylib"]`.

### Layout
```
fusion-core/
├── Cargo.toml
├── uniffi.toml                 # namespace "fusion", kotlin bindings config
├── .gitignore
├── src/
│   ├── lib.rs                  # mod decls + #[uniffi::export] execute_intent
│   ├── models.rs               # AiIntent, Action, TransactionResult, TxStatus
│   ├── guardrails.rs           # pure check_* + GuardrailEngine + persisted Ledger
│   ├── config.rs               # WalletConfig: defaults + env overrides
│   ├── executor.rs             # real signer load, real tx build, generic submit
│   └── bin/uniffi-bindgen.rs   # uniffi::uniffi_bindgen_main()
└── tests/
    ├── guardrails_edge.rs
    ├── models_parse.rs
    └── mock_executor.rs        # alloy Asserter-backed integration tests
```

### Steps
1. **Scaffolding** — `cargo new fusion-core --lib`, `Cargo.toml`, `uniffi.toml`, `.gitignore`, `cargo check`.
2. **Models** — `models.rs` + deserialization tests (malformed, missing, unknown field).
3. **Guardrails** — pure `check_*` fns + `GuardrailEngine` with `FUSION_LEDGER_PATH`
   persistence, atomic commit on success, release on failure. 100% test coverage target.
4. **Config** — `WalletConfig::from_env()`, mainnet USDC/WETH whitelist defaults.
5. **Executor** — real signer, real tx construction, generic `submit<P: Provider>`.
6. **FFI** — `router.rs` + `#[uniffi::export] execute_intent` (sync, internal runtime).
7. **Kotlin bindings** — `uniffi-bindgen generate --library ... --language kotlin`.
8. **Report** — `agent_dev_report.md` per spec §5 template.

### Verification
`cargo fmt --check`, `cargo clippy -- -D warnings`, `cargo test` — all green.
Android cross-build via the installed NDK is out of scope for the mandatory deliverable.