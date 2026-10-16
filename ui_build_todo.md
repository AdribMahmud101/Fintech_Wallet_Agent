# UI Build Todo

Status after the demo-UI pass. Everything here uses mock data; no engine wiring yet.

## Done

- [x] **P0 Foundations** — Obsidian tokens replace the scaffold's invented blue; dark-only;
      measured WCAG substitutions (`TextMuted #7A8194`, button `#5F62EE`, focus `#5A6478`);
      Inter + JetBrains Mono vendored as variable fonts with `wght` axis on API 26+;
      four-level surfaces via CompositionLocal
- [x] **P2 Shell & navigation** — `ModalNavigationDrawer` + `Scaffold`; `TopHealthAppBar`
      with brand, address, velocity sliver and MNN/Rust pills; three destinations
- [x] **P3 Chat + Pre-Flight** — `LazyColumn` feed, five message states, Pre-Flight card
      rendering four rule rows with pass/warn/block, confirm/cancel; mic present but disabled
      rather than wired to a no-op
- [x] **P4 Log terminal + Settings** — `ModalBottomSheet` mono feed, key redacted; settings
      drawer with daily-cap slider, whitelist toggles, thread selector; RPC shown read-only
      with "pinned at first launch" because the engine refuses to re-point
- [x] **P5 Ledger view** — daily spend vs cap, active limits, engine facts, explicit
      demo-state disclaimer
- [x] Six `@Preview` states in `ui/preview/DemoPreviews.kt` for Studio review
- [x] Drawables moved to `drawable-nodpi` (clears 11 `IconLocation` + 4 `IconDuplicatesConfig`)
- [x] JNA bumped 5.10.0 → 5.19.1 (clears 3 `Aligned16KB`); FFI smoke test re-run green on 5.19.1
- [x] Unused strings removed (6 `UnusedResources`)

## Deferred to the integration pass

- [ ] **P1 Rust FFI surface** — `preview_intent` (read-only, no private key), `wallet_status`,
      `wallet_address`; regenerate bindings; re-run `fusion-core/scripts/validate.sh`.
      `DemoIntentSource` is the seam: swap it for a real `IntentSource` and no composable changes.
- [ ] **P6 Token portfolio** — needs a real `balanceOf` path in Rust. The view currently says
      so rather than showing invented balances.
- [ ] **Settings/RPC conflict** — split immutability: pin `rpc_url`+`chain_id`, add
      `update_settings` for limits/whitelist only
- [ ] MNN runtime behind the existing `IntentSource` interface
- [ ] Voice input (mic is disabled, not stubbed)

## Known gaps

- [ ] No automated screenshot test — Paparazzi 1.3.5 can't resolve AGP 8.11; Roborazzi 1.76
      is built against Kotlin 2.3 vs this project's 2.0.21. Both are toolchain dead ends.
      Previews cover review in the meantime.
- [ ] `ic_launcher_foreground` fills 100% of its frame; the system adaptive mask will crop it.
      Needs re-authoring at 432x432 with the art inside the 66% safe zone.
- [ ] Release APK is unsigned with minification off; enable `isMinifyEnabled` before shipping.
      `material-icons-extended` adds size that R8 will strip once minify is on.

## Lint baseline

14 warnings, 0 errors: 12 version pins (AGP / androidx deliberately pinned to match the
local cache) + 2 `IconColors` false positives (lint treats `ic_action_*` filenames as
action-bar icons; they are full-colour glyphs).