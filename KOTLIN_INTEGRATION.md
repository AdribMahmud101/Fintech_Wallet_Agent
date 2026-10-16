# Kotlin / Android Integration Notes

Generated bindings: `fusion-core-kotlin/src/main/kotlin/app/fusion/wallet/core/fusion_core.kt`
Native library: `libfusion_core.so` (Rust `cdylib`, UniFFI 0.32.2, namespace `fusion_core`)

Public surface (both verified to compile against JNA 5.10 and export as top-level functions):

```kotlin
package app.fusion.wallet.core

fun uniffiEnsureInitialized()
fun configureWallet(settingsJson: String): String
fun executeIntent(jsonPayload: String, privateKey: String): String
```

---

## ⚠️ Mandatory: call `executeIntent` off the main thread

`executeIntent` is a **synchronous** UniFFI export. Internally it builds its own dedicated
tokio runtime and calls `block_on`, so it occupies the calling thread for the whole RPC
round trip (fee estimation, nonce lookup, broadcast — typically 200 ms to several seconds
on mobile networks).

**Invoking it on the Android Main Thread will block the UI and can trigger an ANR.**

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.fusion.wallet.core.configureWallet
import app.fusion.wallet.core.executeIntent

suspend fun send(intentJson: String, privateKey: String): WalletResult =
    withContext(Dispatchers.IO) {          // <-- REQUIRED, never the main thread
        val raw = executeIntent(intentJson, privateKey)
        WalletResult.fromJson(raw)
    }
```

Rules:

* Never call `executeIntent` or `configureWallet` from a `@Composable`, a `Clickable`
  handler, or any code running on the main looper without `withContext(Dispatchers.IO)`.
* Do the JNI + JSON work on `Dispatchers.IO`; do the Compose state update back on
  `Dispatchers.Main`.
* The Rust side is internally synchronised (a process-wide engine behind a `Mutex`), so
  concurrent calls from different coroutines are safe — but each one blocks its own
  thread, so do not fan them out unboundedly.

---

## Startup sequence

Android and iOS processes cannot set their own environment, so `FUSION_*` variables are
unavailable. `configureWallet` feeds the exact same parser and validation, and **must be
called once before the first `executeIntent`**.

```kotlin
// Application.onCreate() — also off the main thread for safety
withContext(Dispatchers.IO) {
    val settings = JSONObject().apply {
        put("rpc_url", BuildConfig.FUSION_RPC_URL)
        put("chain_id", "1")
        put("whitelist", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48,0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2")
        put("max_tx_usd", "100")
        put("daily_limit_usd", "500")
        put("ledger_path", File(filesDir, "fusion/ledger.json").absolutePath)
    }

    val result = configureWallet(settings.toString())
    check(result.contains("\"status\":\"success\"")) { "wallet config failed: $result" }
}
```

`ledger_path` must point inside app-private storage
(`context.filesDir`), otherwise the app cannot write it and the daily velocity cap will
not persist across restarts.

The first successful call wins; later calls return `status: "failed"`. This is deliberate
— a compromised UI thread must not be able to re-point a running wallet at an
attacker-controlled RPC endpoint.

---

## Input / output contract

### `executeIntent` input

```json
{
  "action": "transfer",
  "amount": 25.0,
  "amount_minor": "25000000",
  "token_in": "USDC",
  "token_out": null,
  "destination_address": "0x1111111111111111111111111111111111111111"
}
```

* `action` — only `"transfer"` is executable. `"swap"` and `"stake"` are rejected by the
  guardrails (they would need a router allowlist this build does not have).
* `amount` — USD notional, used for every limit check. Must be finite and > 0.
* `amount_minor` — optional exact amount in the token's smallest unit. When present it is
  authoritative for the on-chain transfer, but it must agree with `amount` or the intent
  is rejected.
* `token_in` — `"ETH"`, `"USDC"`, `"WETH"`, or a raw `0x…` contract address that is on the
  whitelist.
* `destination_address` — required, `0x`-prefixed, non-zero.
* Unknown fields are rejected outright.

### `executeIntent` output

```json
{ "status": "success", "tx_hash": "0x…", "message": "…" }
{ "status": "blocked_by_guardrail", "tx_hash": null, "message": "… [tx_size_exceeded] …" }
{ "status": "failed", "tx_hash": null, "message": "…" }
```

`status` is `"success"`, `"blocked_by_guardrail"` or `"failed"`; the `message` for a
guardrail rejection always carries a bracketed rule code
(`unsupported_action`, `amount_not_finite`, `amount_not_positive`, `amount_minor_invalid`,
`amount_not_representable`, `amount_mismatch`, `unknown_token`, `token_not_whitelisted`,
`destination_missing`, `destination_invalid`, `destination_is_zero`, `tx_size_exceeded`,
`daily_limit_exceeded`, `ledger_io`).

`status == "success"` means the node accepted the broadcast — not that it was mined. The
`tx_hash` must still be watched for confirmation.

**A private key is never echoed into `message`.** It is wrapped in a zeroizing buffer at
the FFI boundary and cleared on drop.

---

## Gradle

```kotlin
// build.gradle.kts
dependencies {
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

Place `libfusion_core.so` in `src/main/jniLibs/<abi>/`:

```
jniLibs/arm64-v8a/libfusion_core.so   # cargo ndk -t arm64-v8a build --release
jniLibs/armeabi-v7a/libfusion_core.so
jniLibs/x86_64/libfusion_core.so
```

```bash
# From fusion-core/
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 build --release
```

---

## Regenerating the bindings

```bash
cd fusion-core
cargo build --release --lib
./target/release/uniffi-bindgen generate \
  --library target/release/libfusion_core.so \
  --language kotlin \
  --out-dir ../fusion-core-kotlin/src/main/kotlin
```

`uniffi-bindgen` tries to run `ktlint` on the output; when it is not installed the file is
still generated correctly, only unformatted. Formatting is cosmetic — the file compiles.