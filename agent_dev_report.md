### fusion-core Development Report

**1. Build Status:** Success

Reproduce the entire gate with a single command:

```bash
cd fusion-core && ./scripts/validate.sh     # exit 0 == everything below passed
```

| # | Step | Command | Result | Exit |
|---|---|---|---|---|
| 1 | Formatting | `cargo fmt --check` | PASS — 0 diffs | 0 |
| 2 | Zero-mock audit | `python3 scripts/audit_no_mocks.py` | PASSED | 0 |
| 3 | Lints | `cargo clippy --all-targets -- -D warnings` | PASS — 0 warnings | 0 |
| 4 | Tests (debug) | `cargo test` | PASS — 116 passed, 0 failed, 0 ignored | 0 |
| 5 | Tests (release) | `cargo test --release` | PASS — 116 passed, 0 failed, 0 ignored | 0 |
| 6 | Reproducible build | `cargo build --release --locked` | PASS — lockfile intact | 0 |
| 7 | Docs | `cargo doc --no-deps` | PASS — 0 warnings | 0 |
| 8 | Native artifact | `target/release/libfusion_core.so` | PASS — 7,980,672 bytes | 0 |
| 9 | Bindings | `uniffi-bindgen generate --language kotlin` | PASS | 0 |
| 10 | FFI runtime smoke test | `kotlinc` + `java` against the real `.so` | PASS — 12/12 assertions | 0 |

Toolchain: `rustc 1.97.1` / `cargo 1.97.1`, `alloy 2.5.0`, `uniffi 0.32.2`,
JDK 17.0.20 + `kotlinc`, JNA 5.10.0. Validated 2026-10-13.

The gate is deliberately fail-loud: steps 9-10 **skip and mark the run failed** if
`kotlinc` or a JNA jar is absent, so a CI box without a JDK can never silently report a
green build it did not actually verify.

---

**2. Test Coverage Summary:**

| Module | Passing | Total | Notes |
|---|---|---|---|
| Guardrails | **49** | 49 | `src/guardrails.rs`, incl. ledger persistence |
| Executor | **25** | 25 | 15 unit (`src/executor.rs`) + 10 mocked-provider integration |
| Model parsing | **13** | 13 | `src/models.rs` |
| Config | **11** | 11 | `src/config.rs` |
| Router / FFI | **18** | 18 | 15 unit + 3 `lib.rs` export tests |
| **Total** | **116** | **116** | |

Guardrails, executor, model parsing, config and router each have full branch-level
coverage of every public rule and error path. Edge cases explicitly covered, per spec §3:

* **Velocity limit** — exactly at `$500/day` passes; `$500.01` is rejected; five successive
  `$100` transfers exhaust the cap and a sixth is refused *without* spending budget.
* **Transaction size** — exactly `$100` passes; `$100.01` rejected.
* **Unregistered token** — unknown symbol and out-of-whitelist address both rejected; the
  native-asset sentinel (`0x0`) is **not** whitelisted by default, so ETH transfers are
  opt-in.
* **Amount coercion attack** — `amount: 1.0` paired with `amount_minor: "1000000000"` is
  rejected as `amount_mismatch`; this is the bypass that would otherwise walk a trillion
  tokens past a `$100` ceiling.
* **`NaN` / `±Infinity`** — rejected before any comparison (every comparison against `NaN`
  is `false`, so an unguarded `NaN` would pass both the size and velocity checks).
* **Ledger persistence** — round-trip, nested-directory creation, UTC day rollover,
  corrupt JSON, future `version`, negative spend, atomic `.tmp`+`rename` writes.
* **Ledger restart semantics** — reserved budget is refunded on failure; nothing is written
  to disk until a broadcast succeeds; a fresh engine reloads the committed day's spend.
* **Key handling** — valid/invalid/whitespace/no-`0x` keys, and assertions that the secret
  never appears in `Debug`, in any error string, or in the JSON returned to Kotlin.
* **Malformed JSON** — missing fields, wrong types, unknown fields, unknown action
  variants, truncated documents, `1e400` out-of-range literals.
* **Signing** — signature recovers to the signer address; signatures differ per chain id
  (EIP-155 replay protection); signing is deterministic.

---

**3. Warnings/Errors Encountered (if any):**

*Build-time, all resolved:*

1. **UniFFI 0.32 requires `uniffi::setup_scaffolding!("fusion_core")` in the crate root.**
   Without it the crate compiles but `uniffi-bindgen` aborts with
   `Unknown namespace for Func(... execute_intent)` because no namespace metadata blob is
   embedded in the `cdylib`. The macro also emits the `UniFfiTag` marker type, so a
   hand-written one must not be added.
2. **`uniffi`'s `cli` feature cannot live in `[dev-dependencies]`.** `cargo build --bin`
   does not resolve dev-dependencies, so `uniffi::uniffi_bindgen_main()` failed to
   compile. Moved `cli` into `[dependencies]`; clap/uniffi_bindgen are compile-time only
   and are not linked into `libfusion_core.so`.
3. **alloy 2.x API drift from 1.x.** `.wallet()` must be called *before* `.connect_http()`
   (the latter terminates the builder), `TransactionRequest::to` is `TxKind::Call` not
   `TxTo::Addr`, `input` is a `TransactionInput { input, data }` struct not `Option<Bytes>`,
   and `SolCall::abi_decode` takes one argument.
4. **`uniffi::uniffi_bindgen_main` needed a stale `.so` rebuild.** Regenerating bindings
   against an out-of-date `libfusion_core.so` yields the same namespace error; run
   `cargo build --release --lib` first.
5. **`ktlint` not installed**, so the generated Kotlin is unformatted. This is cosmetic —
   the file compiles (verified against JNA 5.10).

*Deliberate design decisions worth flagging:*

6. **`Swap` and `Stake` are blocked, not implemented.** The spec's executor asks for
   transfers and ERC-20 `transfer`/`approve`; swaps would need a router allowlist that does
   not exist. Rather than build an unsigned/unbounded swap path, the guardrails reject
   them with `unsupported_action`. `approve` *is* fully implemented and tested
   (`build_erc20_approve`, capped at `MAX_APPROVAL_ATOMIC`) but is unreachable from the AI
   intent path, which has no approve verb.
7. **Added a second FFI export, `configure_wallet(settings_json)`.** UniFFI 0.32 dropped its
   `vars` feature, and an Android/iOS process cannot set `FUSION_*` environment variables.
   Without this the wallet is undeployable on the stated target platform. `execute_intent`
   is unchanged from the spec. The JSON is fed through `WalletConfig::from_source`, i.e. the
   *same* parser and validation as the environment, so the two paths cannot drift. The first
   successful call wins; later calls are refused so a compromised UI cannot re-point a
   running wallet at a different RPC.
8. **USD valuation is nominal.** Limits are checked against the human-readable `amount` as
   USD; there is no price oracle, so a WETH "transfer" is valued as though 1 WETH == $1.
   The exact on-chain quantity always comes from `amount_minor` / a decimal-exact f64 parse,
   so no value is ever mis-transferred — but the *limit* is only as accurate as the model's
   USD figure. Production needs a price feed before non-stablecoin limits mean anything.
9. **`serialize` error cannot occur** for `TransactionResult` (no maps, no floats), so
   `to_json_string` uses `expect`. Deliberate, and documented in place.

---

**4. Alloy Provider Status:**

The executor's broadcast path is generic over `P: Provider<Ethereum>`, so the identical
production code path runs against a real HTTP transport in production and against
alloy's mock transport in CI.

**Production** (`src/executor.rs::build_provider`, ~zero mocking):

```rust
ProviderBuilder::new()
    .with_chain_id(config.chain_id)
    .wallet(wallet.signer().clone())
    .connect_http(config.rpc_url.clone())
```

`reqwest` + `rustls` over validated `http`/`https` URLs. `.wallet()` installs the signing
filler, so every transaction is EIP-1559-filled, nonce-managed and locally signed before
`eth_sendRawTransaction`. The provider is built per call from the `WalletConfig`; no global
HTTP state.

**Tests** (`tests/mock_executor.rs`, the only mocking in the repo):

`ProviderBuilder::new().with_chain_id(1).wallet(signer).connect_mocked_client(asserter)`
with an `alloy::transports::mock::Asserter` FIFO. The exact four-RPC conversation of a send
was established empirically and pinned in the test:

| # | Method | Mocked response |
|---|---|---|
| 1 | `eth_estimateGas` | `0x186a0` |
| 2 | `eth_feeHistory` | `{oldestBlock, baseFeePerGas, gasUsedRatio, reward}` |
| 3 | `eth_getTransactionCount` | `0x7` |
| 4 | `eth_sendRawTransaction` | fixed 32-byte hash |

`a_successful_send_drains_exactly_four_rpc_calls` asserts the queue is empty afterwards,
which pins the pipeline to exactly those calls and fails if an extra network round trip is
ever introduced. Additional integration cases: node rejection (the node's reason string is
asserted to reach the caller), unreachable transport, and native-transfer routing.

Signing itself is tested for real, with no mock at all: a signature produced by the loaded
private key is verified to recover to the expected address, to differ between chain ids,
and to be deterministic.

**`src/` contains no mock logic** — enforced by `scripts/audit_no_mocks.py`, which strips
every `#[cfg(test)]` item and fails the build on any mock transport, `todo!`,
`unimplemented!`, placeholder marker or hardcoded hash. It currently passes, with the two
mainnet token addresses explicitly allow-listed as configuration data.

---

**5. Code Snippet:**

Exact implementation of `execute_intent` from `fusion-core/src/lib.rs`:

```rust
#[uniffi::export]
pub fn execute_intent(json_payload: String, private_key: String) -> String {
    // The caller hands us a heap-allocated secret; wrap it so it is wiped rather than
    // left sitting in freed memory for the rest of the process's life.
    let private_key = Zeroizing::new(private_key);
    let engine = router::current_engine();

    router::execute(engine, &json_payload, private_key.as_str())
}
```

For completeness, the orchestration it delegates to (`fusion-core/src/router.rs`), which is
where the guardrail→executor sequencing and the ledger commit/release live:

```rust
pub fn route(engine: &GuardrailEngine, json_payload: &str, private_key: &str) -> TransactionResult {
    let intent: AiIntent = match serde_json::from_str(json_payload) {
        Ok(intent) => intent,
        Err(err) => return TransactionResult::blocked(format!(
            "intent was rejected by the guardrails [malformed_intent]: {err}")),
    };

    let wallet = match LoadedWallet::load(private_key) {
        Ok(wallet) => wallet,
        Err(err) => return TransactionResult::failed(err.to_string()),
    };

    let reservation = match engine.evaluate(&intent) {
        Ok(reservation) => reservation,
        Err(err) => return TransactionResult::blocked(format!(
            "intent was rejected by the guardrails [{}]: {err}", err.code())),
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
        Ok(tx_hash) => match engine.commit(&reservation) {
            Ok(()) => TransactionResult::success(format!("{tx_hash}"), /* … */),
            // A confirmed transaction is reported as a success even if the ledger write
            // failed: the money has moved, and losing that fact would be the worse failure.
            Err(err) => TransactionResult::success(format!("{tx_hash}"), /* … */),
        },
        Err(err) => {
            engine.release(&reservation);   // nothing was sent; refund the budget
            TransactionResult::failed(format!("broadcast failed: {err}"))
        }
    }
}
```

---

## Strict Validation Results

Raw output of the final `./scripts/validate.sh` run (exit `0`, `ALL VALIDATION STEPS PASSED`).

### Step 2 — zero-mock audit of `src/`

```
$ python3 scripts/audit_no_mocks.py
AUDIT PASSED — src/ (excluding #[cfg(test)]) contains no mock transports,
stub macros, placeholder markers or hardcoded hashes.
exit=0
```

Method: every `#[cfg(test)]`-annotated item is brace-matched out of each `src/**/*.rs`,
comments and attributes are stripped, then the remaining production text is scanned for
`todo!`, `unimplemented!`, `panic!(`, `FIXME`, `XXX`, `dummy`, `placeholder`, `Asserter`,
`MockTransport`, `mocked_client`, `stub`, `fake`, and any `0x` literal ≥16 hex digits.
Exactly two literals are allow-listed — the mainnet USDC and WETH addresses in
`config.rs`, which are configuration data and are pinned to their byte arrays by
`config::tests::builtin_asset_literals_agree_with_their_bytes`.

### Step 3 — clippy, forced rebuild

```
$ cargo clean -p fusion-core          # 2188 files, 1.3 GiB removed
$ cargo clippy --all-targets -- -D warnings
0                                    # grep -cE '^(warning|error)' == 0
    Finished `dev` profile [unoptimized + debuginfo] target(s)
clippy_exit=0
```

The clean rebuild matters: a cached `cargo clippy` prints "Finished" without re-emitting
warnings, so a from-scratch run is the only way to be sure.

### Steps 4 & 5 — tests in both profiles

```
$ cargo test
test result: ok. 106 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out   (src/ unit)
test result: ok.  10 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out   (tests/mock_executor.rs)
test result: ok.   0 passed  (bin target — no tests)
test result: ok.   0 passed  (doc-tests)

$ cargo test --release
test result: ok. 106 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
test result: ok.  10 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
test result: ok.   0 passed
test result: ok.   0 passed
```

Both profiles run identically. `--release` matters for a signed-transaction crate: it
catches any behaviour that only holds because of `debug_assertions`.

### Step 6 — reproducibility

```
$ cargo build --release --locked --lib
    Finished `release` profile [optimized] target(s)
locked_exit=0
```

### Step 7 — docs

```
$ cargo doc --no-deps
0   # warning count
```

This step initially **failed** the strict bar: `resolve_amount`'s doc comment used an
intra-doc link to the private `AMOUNT_RECONCILE_TOLERANCE`. Fixed by stating the tolerance
in prose; the crate now documents with zero warnings.

### Step 8 — artifact

```
$ ls -la target/release/libfusion_core.so
-rwxr-xr-x  7980672  target/release/libfusion_core.so
```

### Steps 9-10 — bindings and live FFI round trip

Bindings are deleted and regenerated from the release `cdylib` before being compiled, so
stale-metadata bugs cannot hide:

```
$ rm -rf ../fusion-core-kotlin/src/main/kotlin
$ cargo run --release --bin uniffi-bindgen -- generate \
    --library target/release/libfusion_core.so --language kotlin \
    --out-dir ../fusion-core-kotlin/src/main/kotlin
Code generation complete, formatting with ktlint (use --no-format to disable)
Warning: Unable to auto-format fusion_core.kt using ktlint: … NotFound   # cosmetic only

$ kotlinc fusion_core.kt FusionCoreSmokeTest.kt -cp jna-5.10.0.jar -include-runtime
compile_exit=0

$ javap app.fusion.wallet.core.Fusion_coreKt
  public static final void uniffiEnsureInitialized();
  public static final java.lang.String configureWallet(java.lang.String);
  public static final java.lang.String executeIntent(java.lang.String, java.lang.String);
```

Then the generated Kotlin was run against the **real** `libfusion_core.so` over JNA — not a
stub, not a mock:

```
[1] configureWallet
      {"status":"success","tx_hash":"configured","message":"wallet engine configured from host settings"}
  PASS  configure succeeds
  PASS  configure is refused twice
[2] guardrail rejections (no network involved)
  PASS  blocks malformed json
  PASS  blocks unknown action
  PASS  blocks unlisted token
  PASS  blocks missing destination
  PASS  blocks over tx size limit
  PASS  blocks amount_minor mismatch
[3] bad credentials
  PASS  bad key fails, not blocks
  PASS  key never echoed
[4] real broadcast attempt against a dead RPC endpoint
      {"status":"failed","tx_hash":null,"message":"broadcast failed: transaction broadcast was rejected: error sending request for url (http://127.0.0.1:8545/)"}
  PASS  dead RPC reports failed
  PASS  no tx hash on failure
[5] daily budget was not consumed by any failure above
  PASS  50 failed transfers never hit the daily cap
SMOKE TEST PASSED (0 failures)
smoke_exit=0
```

What this end-to-end run proves that the unit tests cannot:

* the `cdylib` loads and its exported FFI symbols are correctly named and linked;
* the generated Kotlin → Rust boundary marshals strings both ways;
* the JSON contract is exactly what `KOTLIN_INTEGRATION.md` documents;
* guardrail rule codes reach the Kotlin caller unchanged;
* a real reqwest broadcast is genuinely attempted (the error is reqwest's own
  `error sending request for url`), and its failure path releases the reserved budget —
  50 consecutive failed transfers still leave the $500 daily cap untouched;
* a confirmed transaction that fails to persist its ledger entry is still reported as a
  success, so a full disk can never lose the fact that money moved.

### Dependency audit

`cargo tree -d` shows only ordinary semver-compatible duplicates inside the alloy/tokio
tree (`base64` 0.22/0.23, `bytes`, `block-buffer`, …) — no unexpected version skew and no
hand-patched dependency. The lockfile is committed and `--locked` builds succeed.

---

## Deliverables

```
fusion_core_plan.md                       approved plan + the 3 mandatory corrections
KOTLIN_INTEGRATION.md                     Android integration (Dispatchers.IO requirement)
fusion-core/
  Cargo.toml  uniffi.toml  .gitignore
  scripts/audit_no_mocks.py               enforces the zero-mock rule in src/
  scripts/validate.sh                     the 10-step strict validation gate
  tests/kotlin/FusionCoreSmokeTest.kt     live FFI smoke test against the real .so
  src/lib.rs                              uniffi exports (execute_intent, configure_wallet)
  src/models.rs                           AiIntent / Action / TransactionResult
  src/config.rs                           WalletConfig + env overrides
  src/guardrails.rs                       rules + persisted velocity ledger
  src/executor.rs                         key loading, ABI encoding, signing, broadcast
  src/router.rs                           parse → guard → execute → report
  src/bin/uniffi-bindgen.rs
  tests/mock_executor.rs                  mocked-provider integration tests
fusion-core-kotlin/src/main/kotlin/app/fusion/wallet/core/fusion_core.kt   generated
```