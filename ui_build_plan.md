# Fusion Wallet — UI Build Plan

Derived from `layout.md` + `styling.md`, against the **actual** state of `fusion-core-android/`,
the validated Rust core in `fusion-core/`, and the glyph-only drawables produced by
`scripts/process_graphics.py`.

Read the §2 gaps first. Three of them change the Rust FFI surface, and one of them is a
direct contradiction between `layout.md` and a security decision already shipped in the core.

---

## 1. What already exists

| Area | Status |
|---|---|
| `fusion-core/` Rust engine | 116 tests green, `scripts/validate.sh` exit 0, Kotlin bindings generated and FFI-verified |
| `fusion-core-android/` | Gradle 8.13 / AGP 8.11 / Kotlin 2.0.21 / Compose BOM 2024.09.00, `assembleDebug` green |
| Theme | **wrong palette** — scaffold shipped an invented blue `#2E5AAC`, light-first. Must be replaced. |
| UI | empty `Scaffold` + placeholder text |
| Drawables | 11 processed PNGs in `res/drawable/`, glyph-only, all accent colours verified |

**Theme delta to close:** `ui/theme/Color.kt` + `Theme.kt` currently define `FusionPrimary=0xFF2E5AAC`
and default `darkTheme = false`. `styling.md` specifies an Obsidian dark system and
`layout.md` §4 says *"dark color scheme defaults"*. This is a straight replacement, not a tweak.

---

## 2. Gaps and conflicts (the important part)

### 2.1 The Pre-Flight card cannot be built as specified — the Rust core has no preview API

`layout.md` View 2 requires a Pre-Flight Guardrail Card with four evaluated rules and a
`Confirm & Execute` button. Our FFI surface today is:

```
execute_intent(json_payload, private_key) -> String   // guardrails AND broadcast, one shot
configure_wallet(settings_json)          -> String
```

There is no way to evaluate without broadcasting. The four rows the card shows map 1:1 onto
`guardrails.rs`, so the honest fix is to expose them rather than re-implement them in Kotlin
(re-implementing guarantees the UI drifts from the engine).

**Add to `fusion-core/src/router.rs` + `lib.rs`:**

```rust
#[uniffi::export]
pub fn preview_intent(json_payload: String) -> String   // needs NO private key

#[uniffi::export]
pub fn wallet_status() -> String      // daily_spent_usd, daily_cap_usd, max_tx_usd, chain_id, configured
```

`preview_intent` must be **read-only**: evaluate, report, *never* reserve. Then
`execute_intent` reserves on its own evaluate, so there is no double-spend and the ledger
stays correct. Returning the rules as structured JSON lets the card render pass/warn/block
per row without string-matching:

```jsonc
{
  "status": "would_pass",                 // would_pass | would_block
  "intent": { "action":"transfer", "token":"USDC", "amount_usd":10.0,
              "destination":"0x71C8...8A9", "atomic":"10000000" },
  "rules": [
    {"key":"whitelist_target","label":"Whitelist Target","detail":"0x71C8...8A9","state":"pass","code":""},
    {"key":"tx_size_cap",     "label":"Tx Size Cap",     "detail":"$10.00 / $100.00","state":"pass","code":""},
    {"key":"daily_velocity",  "label":"Daily Velocity",  "detail":"$60.00 / $500.00","state":"warn","code":""},
    {"key":"atomic_precision","label":"Atomic Precision","detail":"10000000 Wei Match","state":"pass","code":""}
  ],
  "daily_spent_usd": 60.0, "daily_cap_usd": 500.0
}
```

Note `preview_intent` deliberately takes **no** private key — the card can render before the
user has unlocked anything, and the secret never crosses into the preview path.

> **Cost:** this touches the already-validated Rust core. `scripts/validate.sh` must be re-run
> before any UI work proceeds past Phase 2.

### 2.2 The Settings drawer contradicts a shipped security decision

`layout.md` View 4 wants a live RPC-URL field, velocity slider and whitelist toggles. But
`configure_wallet` **refuses to reconfigure after first call** — deliberately, so a compromised
UI thread cannot re-point a running wallet at an attacker-controlled RPC.

Three ways out; recommended is the first:

1. **Split immutability.** `rpc_url` + `chain_id` are pinned permanently at first configure
   (these are the dangerous ones). Limits and whitelist become runtime-mutable via a new
   `update_settings(json)` export that refuses to touch the RPC. Satisfies the drawer without
   reopening the hole.
2. Read-only settings with "applies on next launch". Least code, worst UX.
3. Allow full reconfiguration. Reverts the security fix — not recommended.

### 2.3 Missing: wallet address, balances

* `wallet_status()` (§2.1) covers the ledger + TopBar progress bar.
* The TopBar needs the **wallet address**, which is derived from the private key. Add
  `wallet_address(private_key) -> String`, or cache the address in Kotlin after first unlock.
* **Token Portfolio view** (balances) needs an RPC `balanceOf` call the Rust core does not have.
  Defer to a later phase — do **not** fake balances in the UI, because a wallet showing
  invented balances is worse than one showing nothing.

### 2.4 Out of scope for the first pass: MNN and voice

`layout.md` references Qwen-1.5B INT4 local inference, MNN thread counts, and a voice mic.
None of that exists. Phase 0 ships a `FakeIntentSource` that emits canned intents so the chat
stream, pre-flight card and execution flow are all exercisable end-to-end. Real MNN is a
separate phase behind the same interface — the UI must not know which one it is talking to.

---

## 3. Accessibility: the spec's own tokens fail WCAG AA

Measured, not assumed. Two tokens in `styling.md` do not pass:

| Pair | Ratio | AA (4.5) | AA Large (3.0) |
|---|---|---|---|
| `TextMuted #6B7280` on `Canvas #08090C` | **4.12** | **FAIL** | pass |
| `TextMuted #6B7280` on `Surface1 #101217` | **3.88** | **FAIL** | pass |
| `#FFFFFF` on `AccentPrimary #6366F1` (the Confirm button) | **4.47** | **FAIL** (by 0.03) | pass |
| `AccentPrimary #6366F1` as text on `Surface1` | **4.19** | **FAIL** | pass |
| `TextPrimary #F9FAFB` on `Canvas` | 19.05 | pass | pass |
| `TextSecondary #9CA3AF` on `Canvas` | 7.84 | pass | pass |
| Success / Warning / Error on `Canvas` | 7.85 / 9.27 / 5.29 | pass | pass |
| `AccentAiViolet #8B5CF6` on `Canvas` | 4.70 | pass | pass |

Fixes, with measured results:

| Token | Spec value | Use instead | Result |
|---|---|---|---|
| `TextMuted` | `#6B7280` | **`#7A8194`** | 4.81 on Surface1, 5.12 on Canvas — AA |
| Button fill | `#6366F1` | **`#5F62EE`** | white label 4.70 — AA |
| Indigo as *text* | `#6366F1` | **`#5A5DE8`** | 3.72 on Surface1 — AA Large only, so never body-size text |
| `BorderFocused` | `#374151` | **`#5A6478`** | 3.15 on Surface1 — meets 1.4.11 non-text 3:1 |

`BorderSubtle #232733` on canvas is **1.34**. That is fine for decorative card outlines
(`styling.md` explicitly wants flat, subtle). It is **not** fine for anything conveying state
or interactivity — those must use `BorderFocused #5A6478`.

Keep the spec hex values as documented constants and add the accessible variants alongside, so
the design system stays traceable to `styling.md` rather than silently diverging.

---

## 4. Dependency changes

Verified reachable (Google Maven / Maven Central) before planning on them.

| Dependency | Status | Action |
|---|---|---|
| `material3` 1.3.1 | cache has 1.3.0 (BOM 2024.09.00) | bump BOM, or pin `material3` directly |
| `material3-window-size-class` 1.3.1 | **not cached** | add — downloads |
| `material-icons-extended` 1.7.5 | cache has 1.7.0 | bump |
| `navigation-compose` 2.8.4 | **not cached** | add — downloads, largest new dep |
| `lifecycle-runtime-compose` 2.8.7 | cached | add |
| `coil-compose` 2.7.0 | **not cached** | add, or defer — all our art is local `res/drawable`, Coil earns nothing yet |
| `jna` `@aar` | 5.6.0 cached | use `5.10.0@aar` to match the verified FFI version |

Recommendation: **skip Coil in phase 1.** Every asset is a packaged drawable; adding an image
loader for zero remote images is dead weight in the APK. Add it when a CDN-backed token list
actually appears.

`material-icons-extended` is ~15 MB before shrinking. We now have real branded icons for the
MNN, Rust, token and shield cases, so pull in `material-icons-core` plus individual icons only
where the branded set has no equivalent.

---

## 5. Drawable mapping

Every icon referenced in `layout.md` as an emoji now resolves to a real glyph-only drawable:

| Emoji in spec | Drawable | Used in |
|---|---|---|
| — | `ic_fusion_logo` (512) | TopAppBar brand mark |
| ⚡ | `ic_badge_mnn` (violet) | `⚡ MNN 1.5B` engine pill |
| 🛡️ | `ic_badge_rust` (emerald) | `🛡️ RUST OK` engine pill |
| 🛡️ (error) | `ic_block_shield` (crimson) | Blocked-state banner, Pre-Flight header |
| — | `ic_action_send` | Chat prompt chips, submit affordance |
| — | `ic_action_terminal` | `>_` drawer toggle for the log sheet |
| — | `ic_asset_usdc`, `ic_asset_weth` | Token rows, whitelist toggles |
| — | `ic_badge_n8n` | Cloud-routing engine chip |
| — | `img_empty_chat` | ChatFeed `Idle` empty state |

All are glyph-only on transparency, so they need no tinting — pass them straight to
`Image(painterResource(...))`. Do **not** apply `ColorFilter` to them; their accent colour is
already the semantic colour.

Keep them as `painterResource` bitmaps rather than converting to vector drawables — the
`graphics.md` recommendation to use vectors was written before these PNGs existed, and the
glyph-only keying cannot be reproduced faithfully by hand-tracing a path.

---

## 6. Phases

Each phase ends with a green `./gradlew assembleDebug`, and Phase 2 additionally re-runs
`fusion-core/scripts/validate.sh`.

**P0 — Foundations**
Replace `ui/theme/*` with Obsidian tokens + the accessible variants from §3. Dark-only default.
Add typography scale (Inter primary, JetBrains Mono engine), MaterialTheme wiring, `WindowSizeClass`.
Fonts: neither Inter nor JetBrains Mono ships with Android. Either vendor the OFL fonts into
`res/font/` (Phase 0, recommended — the 11sp mono terminal is central to this design) or fall
back to `FontFamily.SansSerif` / `Monospace` and accept the drift.
*Exit:* theme renders, 3 nested surfaces visually distinct, contrast table re-measured in a test.

**P1 — Rust FFI surface** *(blocks everything real)*
Add `preview_intent`, `wallet_status`, `wallet_address`; regenerate Kotlin bindings; re-run
`validate.sh` + the FFI smoke test.
*Exit:* `preview_intent` returns per-rule states; smoke test covers the new exports.

**P2 — Shell & navigation**
`Scaffold` + `TopHealthAppBar` (brand, address, velocity mini-bar, MNN/Rust pills) +
`ModalNavigationDrawer` + `NavHost` over Chat / Tokens / Ledger.
*Exit:* navigates between 3 destinations, TopBar reflects `wallet_status()`.

**P3 — Chat + Pre-Flight**
`LazyColumn` chat feed, the four-state message model (Idle/Parsing/Evaluating/Executed/Blocked),
`PreFlightGuardrailCard` rendering `preview_intent`'s rules, confirm/cancel.
*Exit:* a canned intent walks Idle → Parsing → card → Executed against a mocked source.

**P4 — Log terminal + Settings**
`ModalBottomSheet` mono log feed; `SettingsDrawer` wired to `update_settings` per §2.2 option 1,
with RPC fields shown read-only + "pinned at first launch".
*Exit:* logs stream redacted (`***key_redacted***`), slider persists across restart.

**P5 — Ledger view**
Daily spend vs cap, per-rule limits, whitelist. Reads `wallet_status()`; no invented data.
*Exit:* figures match the Rust ledger exactly.

**P6 — Token portfolio**
Only after a real `balanceOf` path exists in Rust. Before that, the view ships empty with the
`img_empty_chat` treatment. Never mock balances.

---

## 7. Risk register

| Risk | Impact | Mitigation |
|---|---|---|
| Adding FFI exports reopens a validated crate | Core regressions | `validate.sh` is the gate; it caught real bugs before |
| Two guardrail implementations (Rust + Kotlin) drift | UI lies about engine state | Kotlin must render Rust's JSON, never recompute rules |
| `navigation-compose` not in cache | First sync downloads | Pre-warm in P2; pin the version |
| `material-icons-extended` bloat | APK size | core + individual icons only |
| MNN is not built | Chat is not "AI" yet | interface seam in P3; fake source is swappable |
| Live settings vs pinned RPC | Security regression | §2.2 option 1 |
| Drawables in densityless `drawable/` | Blurry on xxhdpi+ | 11 open `IconLocation` warnings; density buckets or `drawable-nodpi` |

---

## 8. Deliberately out of scope

Multi-sig keys (single signer in the core), hardware wallets, voice input, MNN runtime,
swap/stake execution (the core rejects them with `unsupported_action` by design), and the
`ic_launcher_foreground` adaptive safe-zone fix (still open — the art fills 100% of its frame
and will be cropped by the system mask).

---

## 9. Verification per phase

```bash
cd fusion-core && ./scripts/validate.sh            # after any Rust change
cd fusion-core-android && ./gradlew assembleDebug lintDebug
```

Add a Compose UI test asserting the Obsidian surfaces render distinctly and that the
Pre-Flight card reflects `preview_intent` states verbatim. Treat any new lint warning as a
failure — the baseline is 10 version-pin warnings, and it is currently 33 because of the
drawables.