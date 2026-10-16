# Fusion Wallet

A fiat-to-crypto wallet front end built as two halves that meet at a UniFFI boundary:
a Rust **policy engine** that decides whether a transaction may proceed, and a
Jetpack Compose **Android app** that asks it.

The engine's job is refusal. Every transfer is parsed into an intent, run through
guardrails (per-transaction cap, daily velocity cap, destination allowlist), and
either handed to an executor with a decrypted key or blocked with a reason the UI can
show. The app never holds a private key in memory longer than the call that uses it.

> **Status: demo UI on mock data.** The Android app renders a complete, interactive
> interface, but the chat pipeline is fed by `DemoIntentSource` rather than the Rust
> engine, and balances are fixture data. The engine itself is real and tested — see
> [Status](#status) for exactly what is wired and what is not.

---

## Layout

```
fusion-core/            Rust policy engine (cdylib + UniFFI)
├── src/                guardrails, router, wallet, ledger persistence
├── tests/              mock_executor.rs, kotlin FFI smoke test
└── scripts/            validate.sh, audit_no_mocks.py
fusion-core-kotlin/     Kotlin side of the FFI surface
fusion-core-android/    Jetpack Compose app
graphics/               raw AI-generated artwork, before processing
scripts/                process_graphics.py — asset pipeline
```

## Requirements

| | version |
|---|---|
| Rust | edition 2021 |
| JDK | 17+ |
| Android SDK | compileSdk 36, minSdk 24, NDK 30 |
| Gradle | 8.13 via wrapper (system Gradle 9.x is incompatible with AGP 8.11) |
| Compose BOM | 2025.01.00 |

## Building

**Rust engine — validate everything:**

```bash
cd fusion-core
./scripts/validate.sh
```

`validate.sh` is a strict gate: clippy with `-D warnings`, `cargo fmt --check`, the full
test suite, an audit that the `src/` tree contains no mocks, and — when `kotlinc` is
available — a JNA smoke test across the FFI boundary. Steps needing Java are skipped
with a loud notice rather than silently passing.

**Android app:**

```bash
cd fusion-core-android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug            # 0 errors expected
```

**Native library** (needed to run; not committed — 7MB regenerable binary):

```bash
cd fusion-core
cargo ndk -t arm64-v8a build --release
cp target/aarch64-linux-android/release/libfusion_core.so \
   ../fusion-core-android/app/src/main/jniLibs/arm64-v8a/
```

**Assets** — see [Graphics](#graphics). Generated drawables are committed; `graphics/`
holds the unprocessed sources.

## Status

**Real and tested**

- Rust guardrail engine: 121 tests green, clippy clean, zero mocks in `src/`
- Ledger persistence with atomic writes
- Android build, lint (0 errors), and on-device behaviour on API 36
- Adaptive launcher icon and cold-start splash

**Demo / not wired**

- The chat pipeline runs on `DemoIntentSource`. MNN inference, live RPC balances and
  real transaction execution are not connected.
- The FFI boundary is exercised by the Kotlin smoke test, but the UI does not call it
  yet. `DemoIntentSource` is the single seam where the real engine drops in.
- The release APK is unsigned and unminified.

## Graphics

`graphics/` holds raw exports; `scripts/process_graphics.py` turns them into Android
drawables. It keys backgrounds by flood fill rather than a global colour key, because
several assets use white for both the canvas *and* interior artwork.

```bash
python3 scripts/process_graphics.py --style glyph-only --replace-placeholders
python3 scripts/process_graphics.py --dry-run     # analyse, write nothing
```

Source files may name themselves after the asset they replace (`new_foreground.png` →
`ic_launcher_foreground.png`) via `SOURCE_ALIASES`. Each category is treated
accordingly — logos are cropped to their alpha box, launcher art is fitted to the
adaptive-icon mask, illustrations keep their own background.

## Licence

Fonts are Inter and JetBrains Mono (SIL Open Font License 1.1).