# fusion-core-android

Jetpack Compose app for the Fusion Wallet: a three-destination shell (Chat, Tokens,
Ledger) over a Settings drawer and a system-log terminal sheet, wired to the Rust policy
engine's boundary but currently running on demo data — see *Wiring* below.

Built and verified on a real device (API 36): three destinations with direction-aware
transitions, a two-row health header, a working settings drawer, a correct back stack
(sheet, then drawer, then back to Chat, then exit), an adaptive launcher icon and a
cold-start splash.

## Layout

```
fusion-core-android/
├── settings.gradle.kts              root name: fusion-wallet
├── build.gradle.kts                 plugin aliases, applied in :app
├── gradle.properties
├── gradle/libs.versions.toml        version catalog (single source of truth)
├── local.properties                 SDK path, machine-specific, gitignored
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── drawable-nodpi/       11 processed glyphs (see Drawables)
        │   ├── font/                 Inter + JetBrains Mono (variable, OFL)
        │   ├── mipmap*/              launcher
        │   ├── values/               strings, themes, colours
        │   └── xml/                  backup + data-extraction rules
        └── java/app/fusion/wallet/
            ├── MainActivity.kt
            └── ui/
                ├── MainViewModel.kt       StateFlows + demo pipeline
                ├── DemoData.kt            frozen values + DemoIntentSource
                ├── FusionWalletApp.kt     drawer + scaffold + nav
                ├── theme/                  Obsidian tokens, type scale, shapes
                ├── components/             app bar, bottom bar, card, pill, meter
                ├── chat/                   ChatFeed, PreFlightCard, PromptComposer
                ├── ledger/                 LedgerScreen
                ├── tokens/                 TokensScreen
                ├── settings/               SettingsDrawer
                ├── terminal/               SystemLogTerminalSheet
                └── preview/                @Preview states for Studio
```

## Viewing the UI

`ui/preview/DemoPreviews.kt` holds six `@Preview` states — Chat idle, the Pre-Flight
card, the executed/blocked history, Ledger, Tokens and the Settings drawer. They live in
`main`, not `test`, so they render in Android Studio's preview pane with no device and no
test harness. Open the file and use the preview selector.

There is no automated screenshot test: Paparazzi 1.3.5 cannot resolve AGP 8.11, and
Roborazzi 1.76 is compiled against Kotlin 2.3 while this project is on 2.0.21. Both are
toolchain-version dead ends rather than anything wrong with the UI.

## Versions

Pinned to what is already in the local Gradle cache, so the first sync is warm:

| Tool | Version |
|---|---|
| AGP | 8.11.0 |
| Kotlin | 2.0.21 |
| Compose BOM | 2025.01.00 |
| compileSdk / targetSdk | 36 |
| minSdk | 24 |
| Java / JVM target | 17 |

## Wiring to the Rust core

The UniFFI-generated bindings are **not** a separate Gradle module — they are a single
generated source file, added to the app source set directly:

```kotlin
sourceSets["main"].java.srcDir("$rootDir/../fusion-core-kotlin/src/main/kotlin")
```

That directory is produced by:

```bash
cd ../fusion-core && cargo build --release --lib
./target/release/uniffi-bindgen generate \
  --library target/release/libfusion_core.so \
  --language kotlin \
  --out-dir ../fusion-core-kotlin/src/main/kotlin
```

The resulting package is `app.fusion.wallet.core`, giving
`app.fusion.wallet.core.executeIntent(...)` and
`app.fusion.wallet.core.configureWallet(...)`.

## Before the first build

1. `local.properties` must point at the SDK:
   ```
   sdk.dir=/home/<user>/Android/Sdk
   ```
2. The native library must be placed in `app/src/main/jniLibs/<abi>/` — see the README
   in that directory. Without it the app launches and the UniFFI call throws
   `UnsatisfiedLinkError`.

## Drawables

Processed assets live in `app/src/main/res/drawable-nodpi/`. `nodpi`, not `drawable/`,
because a bitmap in a densityless `drawable/` is treated as mdpi and silently upscaled on
xxhdpi+ — lint flagged 11 `IconLocation` warnings before the move. Regenerate with:

```bash
python3 ../scripts/process_graphics.py --style glyph-only \
    --dest app/src/main/res/drawable-nodpi
```

`--style glyph-only` is deliberate for this app: the source images are a coloured glyph
on a near-black rounded tile on a white canvas, and that tile is invisible against
`CanvasBackground #08090C`. Glyph-only removes both the white canvas and the tile, so each
drawable is just the glyph on transparency. `--style tile` keeps the rounded tile.

Categories are handled separately, because one pipeline does not fit all of them:

- `ic_*` icons and badges — glyph-only keyed to transparency.
- `ic_fusion_logo` — cropped to its alpha box. It is a 2.8:1 lockup, and forcing it into
  a square box rendered the brand about 9dp tall inside a 30dp slot.
- `ic_launcher_foreground` — fitted to 85% of the adaptive-icon mask rather than the whole
  108dp layer, which a circular launcher mask would crop.
- `img_*` illustrations — background kept; it is the artwork.

## Two things that will bite the first person to wire up a screen

1. **`executeIntent` blocks.** It is a synchronous UniFFI export that drives its own tokio
   runtime. Every call site must be wrapped in `withContext(Dispatchers.IO)` or the UI
   will ANR. Full details in `../KOTLIN_INTEGRATION.md`.
2. **`configureWallet` must run first.** Android processes cannot set `FUSION_*`
   environment variables, so the engine is unconfigured until
   `app.fusion.wallet.core.configureWallet(json)` is called once during startup. The
   first successful call wins; later calls are refused by design.

## Build

Verified on 2026-10-13 — `./gradlew assembleDebug assembleRelease lintDebug` is green.

| Artifact | Size |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 21 MB |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 15 MB |

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew lintDebug
```

Lint reports 15 warnings and 0 errors. All of them are "a newer version is available" notices
for AGP and the AndroidX libraries; the pins are intentional — they match what is already
in the local Gradle cache and the AGP 8.11 / Gradle 8.13 pairing (AGP 8.11 does not work on
Gradle 9.6+, hence the wrapper pin). Bump them together, never one at a time.

Release builds are unsigned and minification is off for now; enable `isMinifyEnabled` and a
signing config when there is something worth shipping.