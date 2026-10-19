# K23 screens and data sources

Shared design API first landed in `8d637b3`: `FusionColors`, `FusionType`, `FusionShape`,
`FusionSpacing`, `BentoTile`, `StatusChip`, `AmountText`, `ProvenanceChip`, `TxLink`, `SkeletonTile`.
These names remain stable. The app's Material theme now consumes those colors and type families.

| Screen / tile | Real source / behavior |
| --- | --- |
| Shell: Home / Bills / Agent / Activity / Safety | `NavRoute` enum; `ShellViewModel` stores navigation events in `SavedStateHandle`. Avatar navigates to Settings and opens the existing `ProfileSheet` until K22 supplies the replacement. Judge mode remains reachable there. |
| Home title | Localized title/subtitle; only a 48 dp refresh action shares the row. Snapshot freshness is in the hero. Shared `ScreenHeader` puts trailing controls below the subtitle for Bills, Agent, Safety, and Judge too. |
| Treasury hero | `MainViewModel.state` / `ChainWalletRepository`: `buckets.total` is Safe USDC plus vault assets; `treasury.balance` is separately labelled Safe cash. Network is the repository's Base Sepolia deployment. `capturedAtEpochMillis` drives the once-per-second age label; refreshing or stale snapshots are labelled cached. Null balances show unavailable, never zero. |
| Cash buckets | `buckets.reserve` (owed covered by cash), `buffer`, `surplus` partition 100% of Safe cash. Actual `owed`, `shortfall`, and deployed assets are separately labelled. BigInteger sums and decimal ratios; only dimensionless drawing ratios become Float. Solid / diagonal / vertical patterns and numbered labels distinguish all segments. Zero cash shows an empty track. |
| Balance history | `BalanceHistoryViewModel` reads the manifest RPC, validates chain ID, and samples up to 30 actual event blocks from `WalletState.evidence` / `recentActions` within 30 days. At each block it reads Safe asset balance, both vault share balances, and each `convertToAssets`; verifies the block hash before/after. Includes Aave and simulated-vault assets, consistently with the hero. Chart positions use real timestamps. Reads run on IO after initial paint. |
| History: 7 d / 30 d | Filters verified block samples using wall-clock time. Both toggles remain available with fewer than four points; then a current-total stat replaces the chart. Failure shows archive unavailable, with a Home refresh retry. The Canvas has a text summary, range, date labels, solid line and point markers. No inferred/interpolated historical balances. Samples only cover the module-event history supplied by the repository; transfers between samples are not shown. |
| Home agent | On-chain module configured/paused/session-active flags; `AgentRuntime.state` supplies last in-app decision, progress, and errors. Run calls existing `MainViewModel.runAgentTick(true)` (explicitly labelled dry run). Stop mounts existing `GuardianStopDialog` with `requestGuardianPause`; successful confirmed pause shows snackbar and persistent `TxLink`. |
| Aave waUSDC vault | `WalletState.vaults`: converted assets, shares, max withdraw, policy exposure cap. Official Aave ghost vector. |
| Demo vault | Same chain-read vault position; explicitly labelled simulated yield. No invented APY. |
| Next bill | Earliest unpaid `WalletState.obligations`; payee ID resolves against `payees`; amount compared with on-chain policy thresholds for tier. Due-day count derives from `dueAt`. Missing commitment metadata is stated honestly. |
| Ask accountant | Existing `AskAccountantSheet` and `AccountantViewModel`, retained as a working interaction. |
| Activity timeline | Every supplied module `EvidenceRow`, unmatched `AgentAction`, and bundled `assets/evidence/base-sepolia.json` row; blocked fallback rows from `MainViewModel.safety`. Distinct events from the same transaction remain visible. Matching frozen transactions enrich recorded provenance without duplicating their live logs. Module history inherits repository retention limits (100 logs / 50 actions). |
| Activity provenance and filters | Recorded source maps to Agent / Owner / App / Script; SCRIPT is never relabelled Agent or Owner based on action kind. The current confirmed in-app runtime transaction is labelled App. All / Agent / Owner / App / Blocked filters operate on this combined timeline, and empty matches say so. |
| Activity row | Human title from event/action kind; raw amount and payee label only when recorded. Missing amounts, counterparties, or timestamps display unavailable. `TxLink` opens the actual explorer transaction. Row tap opens details. |
| Activity detail sheet | Exact six-decimal amount, selectable/copyable passport hash if recorded, block, counterparty, evidence scenario, explorer link. Lazily fetches `eth_getTransactionReceipt` from manifest HTTPS RPC; validates chain, tx hash, block number and block hash before showing actual gas units, timestamp and confirmation/revert status. Missing receipt shows unavailable with Retry; gas is never estimated. |

Amounts are integer/BigDecimal throughout. Canvas geometry alone uses Float. Chart motion is
200 ms and skips animation when system animator scale is zero; skeletons are static.

## Validation

Run from `fusion-core-android` after sourcing `scripts/edge_android_env.sh` in the umbrella repo:

```
./gradlew testDebugUnitTest assembleDebug
test -s app/src/main/res/drawable/ICONS.md
```

Validation completed on 2026-10-19: `testDebugUnitTest assembleDebug` and the nonempty
`ICONS.md` check exited 0. Unit results: 135 tests, 134 passed, one existing skipped test,
zero failures/errors. All five added K23 behavior tests passed.

Additional K23 unit coverage exercises signed/large/exact formatting, percentage calculations,
provenance preservation, same-transaction events, blocked evidence, and filtering.

Device inspection: the available ADB endpoints are unauthorized in this workspace. No device
screenshot or visual runtime pass is claimed. Title crowding is addressed structurally across
shared screen headers; a physical-device pass remains desirable after authorizing ADB.
