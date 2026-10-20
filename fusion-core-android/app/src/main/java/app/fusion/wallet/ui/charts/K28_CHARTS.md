# K28 charts and evidence contract

All money stays in `BigInteger` raw USDC units or `BigDecimal`. Only dimensionless
ratios and canvas coordinates become `Float`. Charts are read-only and create no
transaction or signing authority. They use `FusionChart` reference surfaces (18 dp radius, #111418 fill, #1F242B border, 16 dp inner padding);
large accessibility font sizes stack the paired Home tiles. All interactive
controls retain Material's 48 dp targets. Charts expose labels and accessibility
summaries; color is supplementary. `rememberChartProgress` skips animation when
Android's animator duration scale is zero, and Compose observes system motion
scaling for the animation.

| Chart / placement | Actual data source | Missing / insufficient / zero state | JVM test |
| --- | --- | --- | --- |
| Spare cash hero / Home | `BucketMath`: Safe cash less owed and safety cushion; chain policy + `getState` reserved amount | Balance unavailable; coverage remains unverified until commitment metadata matches; shortfall shows exact unfunded amount | Existing `AbiAndBucketsTest`, `BucketAndBriefTest` |
| Where it sits / Home | `Buckets`: surplus; funded reserve + funded buffer; current converted vault assets. These partition current Safe cash + vault assets exactly | Unavailable until buckets load; zero total shows text and zero percentages, no donut shape; unfunded bills are disclosed separately | `ChartMathTest.segmentPercentagesRoundAndSumTo100`, `negativeSegmentsRejected`; existing `BucketSegmentsTest` |
| Cash runway / Home | Full shared `EventIndex` USDC transfers out of Safe over the trailing 30 days, excluding vault deposits and self-transfers; Safe balance at the same pinned block. Transfers include bill payments and owner sends, each once. Observation begins at deployment block timestamp | Under 7 days: “Not enough history yet”; no outflow: “No outflow recorded”; unavailable index: no gauge. Denominator uses actual observed days, capped at 30. Arc scale is 90 days, clamped; numeric days remain uncapped | `ChartEvidenceTest.runwayUsesObservedWindowAndThresholds`, `fullIndexOutflowDoesNotDoubleCountBillsOrIncludeVaultDeposits`; `ChartMathTest.gaugeClampHandlesZeroAndOverLimit` |
| Total, last 30 days / Home | K26 reconstructed `balanceHistory` from Safe USDC transfers + pinned balance | Fewer than 4 unique samples: stat tile and sample count. This is explicitly **Safe cash**, not historical total treasury: historical vault valuations are unavailable. No interpolation across invented samples and no substituted vault history | `ChartMathTest.monotoneCubicNeverOvershootsIncludingFlatAndReversals`, `duplicatePlotTimesRejected`, `paddedBalanceRangeKeepsVariationFundingCliffsAndConstantSeries`, `balanceCaptionUsesRealChangeAndNeverDividesByZero`; existing `BalanceReconstructionTest` |
| Spending by week / Home | All `ObligationPaid` events in shared index; exact event amount; vendor label from K27 tx index, then registered payee, then explicit abbreviated address. Current + previous 3 local Monday weeks; 3 largest vendor totals + Other | Missing index: text only. Empty observed weeks: flat placeholder, zero totals. Partial history and the current partial week are disclosed in the info sheet | `ChartEvidenceTest.weeklyBucketingUsesLocalMondayAndDst`, `fullIndexOutflowDoesNotDoubleCountBillsOrIncludeVaultDeposits`; `ChartMathTest.compactBarLabelsPreserveScaleAndSmallNonzeroAmounts` |
| Interest per year / Home | Aave V3 Pool `0x8bAB6d1b75f19e9eD9fCe8b9BD338844fF79aE27`, `getReserveData(0xba50Cd2A20f6DA35D788639E581bca8d0B5d4D5f)`, ABI word 2 `currentLiquidityRate`, pinned to snapshot block. Ray APR converted to annual compounded supply APY with decimal arithmetic. Bar scale is the largest available annual rate shown (currently the single live Aave rate) | Failed read: “Live rate unavailable”, no bar. Demo `KavachiDemoVault` has sponsor `drip` donations and **no configured rate getter**: “simulated” with an empty track; the info sheet says “simulated — no live rate” | `ChartEvidenceTest.aaveRayCompoundsToSupplyApy`, `fullIndexOutflowDoesNotDoubleCountBillsOrIncludeVaultDeposits` |
| Worth knowing / Home | Deterministic statements using exact surplus / vault amounts, next verified unpaid bill and due timestamp, runway and observed outflow | Missing balances: explanation. Unverified bill metadata does not generate a next-bill claim. Runway insight omitted without sufficient history | Existing bucket / obligations tests; `ChartEvidenceTest.runwayUsesObservedWindowAndThresholds` |
| Decision mix / AI Agent | Full indexed executions joined to K27 tx index; only explicitly AGENT / APP provenance. PAY / DEPOSIT / WITHDRAW counts, plus latest saved live HOLD in `agent/last_tick.json` or runtime report | Missing event history: text. No qualifying records: no shape. No inferred SCRIPT provenance. Dry runs and rebalances excluded. Only one Hold report is durably available; info sheet states that complete Hold history is unavailable and lists exact decision counts | `ChartEvidenceTest.decisionMixRequiresRecordedProvenanceAndExcludesDryRuns` |
| Daily limit ring / AI Agent | Current module `dailyUsed`, `policy.dailyLimit`, `dailyRemaining` | Missing module: unavailable. Zero limit: text, no ring; zero use draws empty track | `ChartMathTest.gaugeClampHandlesZeroAndOverLimit`; existing `ScreenMathTest` |
| Stopped at each layer / Safety | Existing `SafetyScreenState.arena` parser for `evidence/results/latest.json`: actual `stopping_layer` on stopped scenarios, never expected-layer credit. Denominator = all recorded scenarios | Missing arena: text; zero stopped at a layer: empty track. Additional recorded controls appear by name | Existing `ArenaEvidenceTest`; `ChartMathTest.gaugeClampHandlesZeroAndOverLimit` |
| ASR / FBR benchmarks / Safety | Bundled byte-for-byte `res/raw/k28_benchmarks.md` copy of `evidence/planner/TUNING_K20.md`: frozen + held-out AHEM-G ASR / all-benign FBR rows | Parser failure: unavailable; zero ASR: empty track. Snapshot source, corpus and metric meanings are in the info sheet; denominators remain on every bar; no numeric constants substituted on failure | `ChartEvidenceTest.frozenAndHeldOutMetricsComeFromEvidence` |
| Paid per vendor / Bills | Paid obligations in the current verified commitment, summed by registered vendor. Bars relative to largest vendor total | Unmatched commitment: no bars. No paid bills: text. Scope says current bill commitment, rather than lifetime payments | Existing `ObligationsResolverTest`, `BillEvidenceTest`; `ChartMathTest.gaugeClampHandlesZeroAndOverLimit` |

## Numerical and evidence boundaries

- Percentages use largest remainder allocation, deterministic ties, total 100 for
  any nonzero distribution. Zero-total segments all show 0 rather than a fake ring.
- Area lines use weighted harmonic monotone tangents and endpoint-bounded Bezier
  controls. Actual block sample timestamp spacing remains; only the final verified point has a marker. A
  constant series stays flat. The last verified point has a glow, not an extra
  fabricated observation. Change uses the first/last recorded samples in the window,
  and the info sheet discloses the actual observed date range; zero initial balance shows amount change instead of an
  undefined percent.
- Bucketing uses `Calendar` in local time, including 169/167-hour DST weeks; future
  payments are excluded. A deployment younger than the four-week window is labeled
  with its observed start date, so placeholders do not imply older observed zeros.
- Runway counts actual ERC-20 debits once. `ObligationPaid` supplies vendor spending
  groups, never another cash debit. Vault deposits and self-transfers are excluded.
- Optional rate/analytics failure cannot replace balances with zero or fail the
  wallet refresh. The index and rate call share the pinned snapshot block, whose
  hash is rechecked before publishing. Cached snapshots without chart evidence
  show the corresponding unavailable messages until a successful refresh. In-memory
  chart evidence stays visible during a refresh, using its own pinned cash balance
  and rate block number so partial refreshes cannot mix old outflow with new cash.
- Pool API reference: https://www.aave.com/docs/aave-v3/smart-contracts/pool
- Aave ray APR → supply APY reference: https://github.com/aave/protocol-subgraphs
  (`((1 + liquidityRate / 10^27 / 31536000)^31536000 - 1) * 100`).

## Validation

From umbrella root run `bash scripts/android_planner/build.sh` then
`bash scripts/android_planner/build_core.sh`. From `fusion-core-android`, source
`/root/projects/fusion_wallet/scripts/edge_android_env.sh`, then run
`./gradlew testDebugUnitTest assembleDebug` and verify the APK contains
`libfusion_planner_android.so` with `unzip -l`.

The direct Base Sepolia `cast call` to the Pool succeeded during implementation;
`cast sig 'getReserveData(address)'` confirmed `0x35ea6a75`. Emulator screenshot
validation was unavailable because both attached ADB targets were unauthorized.
No fixtures are injected into live screen state.

## Revision 3 visual contract

Studied both owner references in `docs/ux/reference/home_top.png` and
`home_bottom.png` before editing. Home uses 18 dp screen padding, 12 dp gaps,
equal-height 50/50 columns (stacked for large accessibility text), and the
reference's sans values. The hero is 32 sp with baseline-aligned 16 sp USDC;
chart titles are single-line 14 sp regular #8B949E. Vault values are 26 sp sans.
The hero has the diagonal #0F2A22 → #0E1512 gradient.

- Allocation: 122 dp ring, 17 dp butt stroke, 2° gaps, compact center (e.g. 9.4K),
  8 dp legend dots, single-line labels and always-visible percentages.
- Runway: same ring dimensions, #262B33 track, clockwise progress from 12 o'clock,
  rounded amber progress cap and a colored textual status. Insufficient history stays
  text-only; no decorative ring is substituted. The 270° progress scale is 90 days.
- Balance: 120 dp plot, 2.5 dp sky line, 28%-to-transparent fill, 7 dp end dot with
  14 dp glow. No axes, grid or per-sample markers. The 10%-padded actual min/max
  range preserves constant series and funding cliffs without forcing a zero baseline.
- Spending: 24 dp rounded pills, up to 22 dp gaps (contracted on narrow phones),
  4 dp empty stubs, `3w / 2w / 1w / Now`, teal/coral primary palette. Compact
  totals appear only in right-aligned vendor legend rows, never under columns.
- Interest: 15 sp label and value share a row above an 8 dp sky pill. The demo
  row says `Demo vault   simulated` with an empty track. A missing live read says
  `unavailable`, with its meaning in the info sheet.
- Chart methods, provenance, partial-history caveats, exact counts, block numbers,
  date range and rate scale open in a scrollable bottom sheet from a small ⓘ
  with a required 48 dp target. Nothing was removed from the evidence contract.
- Home order: hero, allocation/runway, balance, spending/interest, Worth knowing,
  AI Agent, vault positions. Existing next-bill and accountant actions follow.

Final revision 3 validation: both native build scripts exited 0. The exact
`./gradlew testDebugUnitTest assembleDebug && unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -q libfusion_planner_android.so`
command exited 0 after the shared-component review. JVM results: 202 tests,
0 failures, 0 errors, 3 existing opt-in skips. The APK includes
`lib/arm64-v8a/libfusion_planner_android.so`. `git diff --check` passes.
ADB device screenshot review remains unavailable: both attached targets are
unauthorized. No fixture data or preview state is injected into live screens.
