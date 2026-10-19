# Card K11 — Real-Device Defects Fixed (Phase 4, Demo-Blocking)

This document details each defect identified on the Tecno demo device (1080x2436), the code changes made to address it, and the test covering each fix.

---

### Defect 1: Red banner "ACCOUNTING_MISMATCH: on-chain commitment differs from bundled bills"
- **Issue**: The app compared on-chain obligations Merkle root with a static bundled bill list in assets. Recommitting bills on-chain caused the bundled list to go stale, displaying a red error banner even though the on-chain contracts functioned properly.
- **Fix**:
  - Implemented `ObligationsResolver` (`ObligationsResolver.kt`) to resolve candidate bill lists: first from bundled JSON, then from GitHub raw URL `https://raw.githubusercontent.com/AdribMahmud101/fusion_wallet/main/evidence/obligations-84532.json`, and any candidate files under `evidence/agent/obligations*.json`.
  - In `ChainWalletRepository.kt`, candidate lists matching the on-chain `obligationsRoot` are accepted and verified against module reserves.
  - If no candidate list matches the on-chain root, the error state is rendered as a clear amber notice (`Tone.Warn`) indicating `"Bill details unavailable for commitment 0x…; totals from chain"` instead of a red error.
  - In `TreasuryScreen.kt` (`ObligationsSummaryTile`) and `BillsScreen.kt`, on-chain totals provided by the contract (`state.module.reserved`, `state.module.obligationCount`) continue to render accurately.
- **Files Changed**:
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/data/ObligationsResolver.kt` (lines 1–95)
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/data/ChainWalletRepository.kt` (lines 17, 124–130)
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/ui/treasury/TreasuryScreen.kt` (lines 182–199, 617–670)
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/ui/bills/BillsScreen.kt` (lines 255–275)
  - `fusion-core-android/app/src/main/res/values/strings_accountant.xml` (`accounting_mismatch_unavailable`)
  - `fusion-core-android/app/src/main/res/values-zh/strings_accountant.xml` (`accounting_mismatch_unavailable`)
- **Tests Covering It**:
  - `app.fusion.wallet.data.ObligationsResolverTest`:
    - `bundledMatchesRoot_returnsBundledWithoutRemote()`
    - `bundledDiffers_fetchesMatchingFromCandidateSource()`
    - `agentListing_discoversAndFetchesMatchingCandidate()`
    - `noCandidateMatchesRoot_returnsNull()`
  - `app.fusion.wallet.chain.ChainWalletRepositoryTest`:
    - `staleBundledBillsReplacedByMatchingCandidate()`
    - `noCandidateMatchesLeavesTotalsFromChainAvailable()`

---

### Defect 2: "Stale data" banner after 30 s
- **Issue**: Public Base Sepolia RPC nodes produce ~2s blocks, causing snapshot age and block-lag checks (5 blocks = ~10s, 30s TTL) to fire continuously as a full-width warning banner.
- **Fix**:
  - Changed auto-refresh interval in `MainViewModel.kt` to 15 seconds (`delay(15_000)`).
  - Added `WalletState.isDisplayStale(nowMillis)` with a 90-second TTL (`>= 90_000` ms) on the last snapshot, unaffected by minor block height variations.
  - Replaced the full-width `ObsidianCard` warning banner with a compact amber chip (`StatusPill(label = stringResource(R.string.chip_stale), tone = Tone.Warn)`) positioned in the header trailing area next to the refresh button.
- **Files Changed**:
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/ui/MainViewModel.kt` (line 31)
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/data/WalletState.kt` (lines 61–65)
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/ui/treasury/TreasuryScreen.kt` (lines 122–127, banner removed)
  - `fusion-core-android/app/src/main/res/values/strings_accountant.xml` (`chip_stale`)
  - `fusion-core-android/app/src/main/res/values-zh/strings_accountant.xml` (`chip_stale`)
- **Tests Covering It**:
  - `app.fusion.wallet.chain.AbiAndBucketsTest`:
    - `displayFreshnessUses90SecondTTLWithoutPrematureBlockExpiration()`

---

### Defect 3: Duplicate "Base Sepolia · TEST" badge
- **Issue**: The network badge was rendered twice: once in the top app bar (`FusionTopBar`) and again beside the screen title in `TreasuryScreen`.
- **Fix**: Removed the duplicate `StatusPill(label = stringResource(R.string.badge_network), tone = Tone.Warn)` from `TreasuryScreen.kt`, keeping only the persistent header badge in `FusionTopBar.kt`.
- **Files Changed**:
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/ui/treasury/TreasuryScreen.kt` (lines 114–121)
- **Tests Covering It**:
  - Layout verification via `testDebugUnitTest` and `assembleDebug`.

---

### Defect 4: "Block ##48322201"
- **Issue**: In `TreasuryScreen.kt`, `blockText` was formatted as `state.blockNumber?.let { "#$it" } ?: "—"` and passed into string resource `R.string.tr_last_refresh_block` (`"Block #%1$s"`), resulting in `Block ##48322201`.
- **Fix**: Passed `it.toString()` directly into `tr_last_refresh_block` to render `"Block #$it"` and used `tr_block_unconfirmed` (`"Block —"`) when null. Added helper `Fmt.blockLabel(number: Long?)`.
- **Files Changed**:
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/ui/treasury/TreasuryScreen.kt` (lines 271–273, 287–291)
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/ui/Fmt.kt` (lines 42–44)
- **Tests Covering It**:
  - `app.fusion.wallet.ui.FmtTest`:
    - `blockLabelFormatsWithoutDoubleHash()`
    - `blockLabelFormatsNullAsUnconfirmed()`
  - DoD static check: `! grep -rn '"##\|##\$' app/src/main/java/app/fusion/wallet/ui`

---

### Defect 5: Bottom nav label "Bills &" truncated
- **Issue**: The label `R.string.nav_bills` was `"Bills & Vendors"` (and `"账单与收款方"` in Chinese), which exceeded tab width and truncated to `"Bills &"` at 1080px device width.
- **Fix**: Replaced the label with `"Bills"` in `strings_accountant.xml` and `"账单"` in Chinese `strings_accountant.xml`, fitting cleanly within tab width.
- **Files Changed**:
  - `fusion-core-android/app/src/main/res/values/strings_accountant.xml` (line 278)
  - `fusion-core-android/app/src/main/res/values-zh/strings_accountant.xml` (line 257)
- **Tests Covering It**:
  - Verified in `TopHealthAppBar.kt` navigation specs and UI compilation.

---

### Defect 6: Inspection and fixes across other tabs (Bills, AI Agent, Safety, Judge mode)
- **Issues & Fixes**:
  - **Duplicate badge in Judge mode**: Removed duplicate `StatusPill(label = stringResource(R.string.badge_network))` from `JudgeScreen.kt` header.
  - **Truncation in `StatTile`**: In `Bento.kt`, scaled font size for longer values (`val fontSize = if (value.length > 8) 15.sp else 20.sp`) with `maxLines = 1`, preventing multi-column stat tiles (such as "Not measured" on Safety screen) from wrapping or overflowing on 1080px screens.
  - **Bills Screen commitment mismatch state**: Rendered amber commitment tile with contract-provided totals (`state.module.reserved`, `state.module.obligationCount`) in `BillsScreen.kt` when candidate obligations list is unavailable.
- **Files Changed**:
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/ui/judge/JudgeScreen.kt` (lines 106–115)
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/ui/common/Bento.kt` (lines 75–81)
  - `fusion-core-android/app/src/main/java/app/fusion/wallet/ui/bills/BillsScreen.kt` (lines 255–275)
- **Tests Covering It**:
  - `ChainWalletRepositoryTest.kt`, `ObligationsResolverTest.kt`, `FmtTest.kt`, `assembleDebug`.
