# K24 — Agent, Safety and Bills

Implements UX_PLAN §4 using K23 `FusionColors`, `FusionType`, `FusionSpacing`, `BentoTile`,
`StatusChip`, `AmountText`, `ProvenanceChip`, `TxLink`, and `SkeletonTile` without modifying the
shared design system. Screen-local ring and connected-flow additions are requested in
`NEEDS_FROM_K23.md`. The only shell change is importing/mounting `SafetyScreen` for Safety and its
legacy Proof alias in `FusionWalletApp.kt`; Judge uses the existing `ShellViewModel` destination.

## Tile → source → loading / empty / error

| Screen / tile | Real source and actions | Loading / empty / error behavior |
| --- | --- | --- |
| Agent snapshot | `MainViewModel.state`: source, captured time, status, block. Refresh calls `vm.refresh()`. | Cached/stale and DEMO labels are explicit. RPC error remains visible with refresh; absence of a block is never presented as live. |
| How it decides | Architectural flow from K17: snapshot facts → eligible Rust candidates A–G plus H (Hold) → on-device model with AHEM-G → policy preview → session signer → contract. Vertical Row nodes have Canvas arrow connectors and readable descriptions. | Expand/collapse has a 48 dp control. This explains the real pipeline; it does not claim every layer ran for a dry run or Hold. It explicitly says ineligible candidate letters are omitted. |
| Agent controls | `AgentRuntime.state.running/error`; Run invokes `vm.runAgentTick(dryRun)`. Dry run starts checked. Unchecking requires a second explicit live confirmation before invoking the live callback. | While running, Run and the mode switch are disabled. Successful Hold/dry run clearly says no transaction; confirmed execution snackbar has a transaction action. Failures show readable errors. Existing session-lock and signer rules remain authoritative. |
| Latest decision | `AgentRuntime.state.result.reportJson/passportJson`; if absent, background read of private device `agent/last_tick.json`. `DecisionDetails` parses actual candidate summaries, choice, reason, `preview.checks`, planner kind, snapshot block, hash and execution receipt. | Skeleton while a first run is pending. Missing file says no decision and gives Run as the next action. Invalid JSON says the report is unavailable. Missing checks, candidate list, planner or timings say not recorded; absent tx says no transaction. Chosen candidate has a text chip plus raised surface. |
| Decision timings | Report `model.load.coldLoadMs`, `model.timings.decodeMs` and `model.timings.totalMs`, parsed as BigDecimal. | A missing decode field is shown as not recorded; total inference time is separately labelled and never relabelled as decode. No estimated timing. |
| Passport copy / transaction | Recorded `decisionHash` goes to Android clipboard with snackbar feedback. `txHash` comes from report or confirmed execution. | No invented transaction for Hold, dry run, blocked or missing execution. Actual receipts use K23 `TxLink`. |
| Guardian Stop (Agent and Safety) | `vm.requestGuardianPause()`; module paused flag controls the button. Confirmation explains that completion requires a confirmed receipt and verified paused state. | Paused state is textual. Pending confirmation disables repeated submissions. Callback failure shows the signer error; success snackbar provides a BaseScan action using the confirmed hash. This control never claims an unsigned request stopped the module. |
| Daily limit ring | `module.dailyUsed`, `module.policy.dailyLimit`, `module.dailyRemaining`. BigInteger ratio becomes integer basis points before Canvas coordinates. Full used, limit and remaining amounts accompany the ring. | Skeleton until initial load; absent policy says unavailable. A zero/nonpositive denominator produces an unknown ring, not a fabricated zero-percent usage. Over-limit geometry clamps to 100% while actual amounts remain visible. |
| Queue | `WalletState.queue`: action kind, amount, payee, readyAt, expiresAt, decision hash. Payee names use existing registry/catalog resolution. | Empty only when a module snapshot exists; otherwise unavailable/skeleton. Once-per-second countdown uses recorded deadlines minus device epoch time. At readyAt it says awaiting execution; expiry is strictly after expiresAt, matching contract semantics. It never calls an elapsed queue paid. |
| Decision history | `WalletState.recentActions`: human kind title, amount, resolved payee, block/time, recorded provenance and actual transaction. Current/persisted in-app confirmed tx is App; other source labels are preserved. | Initial skeleton; explicit empty or network-unavailable message. No sample decisions or synthetic history. Scope inherits the repository's 50-action retention. |
| Bills / Vendors switch | Saved segmented selection; Bills is the initial segment. | Both segments have 48 dp controls and real corresponding lists. |
| Bill | `WalletState.obligations`: amount, dueAt, paid; payee ID resolves to the existing vendor catalog/registry and supplies the avatar initial. USDC icon is K23 drawable. | Skeleton on first load. Missing matching commitment metadata says unavailable; an empty validated commitment says no bills. Accounting errors remain visible. Due date includes timezone. `paid` is authoritative. |
| Bill queue status | Extra read-only `getQueuedAction` at the repository snapshot block supplies indexed `obligationIdHash`; only the identical bill receives Queued / Ready and the live countdown. | Pending/unavailable identifier is stated explicitly. Different bills sharing a vendor and amount are never assumed to share the queue. Expired actions do not mark bills queued. |
| Paid bill receipt | `BillEvidenceReader` reads observed `ObligationPaid` event blocks, joins by indexed obligation ID, and supplies the actual tx to K23 `TxLink`. | A paid bill without retained receipt history remains Paid with “receipt not in loaded history”; no guessed link. Extra RPC failure is reported and retried on the next refreshed snapshot. |
| Vendor | `WalletState.payees`: address, activeAt, cancelled flag, sanctions result. Name comes from the existing known-vendor catalog, falling back to a short real identifier. | Empty/error/loading states distinguish no registry entries from unavailable RPC. ACTIVE / COOLING / CANCELLED and clear / BLOCKED sanctions results are textual. Wallet snapshot freshness applies to the sanctions result. |
| Vendor cooling ring | `activeAt` minus current device time; the total registered cooling period is `activeAt` minus the observed `PayeeAdded` block time. Latest indexed registration tx is joined by payee ID. | Countdown stays available while registration detail loads. Unknown total duration shows an unknown ring with explanation. The denominator never guesses a past cooling period from today's policy setting. Countdown reaching zero updates ACTIVE; cancelled vendors remain CANCELLED. |
| Vendor links | Indexed `PayeeAdded` registration tx → `TxLink`; real address → BaseScan address link. | Missing registration receipt is stated; the address link remains functional. |
| Add vendor / commit bills | Safe address from live `treasury.safeAddress`, with deployment manifest fallback. Opens that treasury at Safe with owner 2-of-2 explanation. | No local dead form. Missing deployment address gives explicit unavailable text. Missing link handler gives snackbar feedback. |
| Safety score | `ArenaEvidenceParser` counts actual scenarios with stopped=true, validates the JSON summary, and parses `asr.ahem-g`; held-out ASR is parsed from the committed K20 Markdown result table. | Bundled snapshot paints after IO load; network refresh retains it with a visible refresh error if offline. Invalid/missing evidence never becomes a zero-success claim. Frozen and held-out metrics stay separate from the escaped arena scenario count. |
| Safety five-layer diagram | Recorded `stopping_layer`, only for stopped scenarios, grouped into AHEM-G, validator, policy, signer and contract. Candidate policy and policy preview share the policy node. | Missing arena means counts unavailable. Zero counts mean measured scenarios assigned no stops to that layer. Durable journal and implementation pin remain separately labelled additional controls; they are not reassigned to signer, validator or contract. |
| Blocked attacks | Each stopped scenario's description, measured layer, original evidence references/test names, plus any recorded Base Sepolia revert receipt. | Explicit unavailable / skeleton if evidence cannot load. Anvil hashes are linked to their persisted raw evidence files, never to BaseScan. Source test names remain visible. |
| Known limits | Every scenario not marked stopped, plus original evaluation limitations. A12 description and signer escape note are preserved, including the no-broadcast qualification. | Missing evidence is unavailable, never “no known limits.” Empty known limits only follows a valid measured evidence set. |
| Emergency unwind | Explanation of the owner's module emergency-unwind action; real treasury Safe link to collect both owners' signatures. | No disabled placeholder action or implied guardian unwind authority. Missing Safe address is explicit. Link-handler failure uses snackbar. |
| Judge shortcut | Existing `ShellViewModel.navigate(NavRoute.Judge)`. | Always a functional 48 dp button to the existing evidence/commands screen. |

All external launch calls use `context.startActivitySafe(intent)`, including snackbar transaction
actions. K23 `TxLink` already uses the same helper. Money remains BigInteger/BigDecimal; only
integer basis points become floating drawing coordinates. Rings and loading skeletons are static,
respecting system Remove animations. Noninteractive chips always have status text.

## Evidence provenance and refresh

`res/values/k24_evidence.xml` contains a mechanically projected snapshot of the umbrella repository's
`evidence/results/latest.json` (generated_at `2026-10-19T19:15:51.279410+00:00`, evidence git_commit
`44b4df34999da12c8ff678c64389a25de58783b5`) and the original `evidence/planner/TUNING_K20.md` text.
Only unrelated receipt payloads/model hashes are omitted from the arena resource; scenario IDs,
descriptions, actual stopping layers, explanatory notes, evidence references, ASR, summary and
limitations are retained. This keeps the offline APK usable without putting typed scores in UI code
or modifying `data/` or older fixtures. Tests compare the projection with the original source fixture.

The parser currently yields 23/24 stopped; raw layer counts are contract 18, candidate policy 2,
policy preview 1, durable journal 1, implementation pin 1. The canonical diagram therefore shows
0 → 0 → 3 → 0 → 18, with the two additional controls listed immediately below. A12 is escaped.
Frozen ASR is 0/30; held-out ASR is 0/20. These are off-chain corpus admission metrics, not a claim
that A12 or all possible on-chain attacks were stopped. Android execution and dedicated x402
budget enforcement are not claimed by these evaluation results.

`SafetyScreenModel` refreshes both source files over HTTPS on IO after rendering the snapshot;
manual refresh is available. Requests have four-second connect/read timeouts and a 512 KiB cap.
Only a successfully parsed pair replaces the last snapshot. Source state and measurement date are
visible. Original URLs:

- https://raw.githubusercontent.com/AdribMahmud101/fusion_wallet/main/evidence/results/latest.json
- https://raw.githubusercontent.com/AdribMahmud101/fusion_wallet/main/evidence/planner/TUNING_K20.md

`BillEvidenceReader` uses the existing read-only transport, validates the manifest chain and pinned
snapshot hash before/after reads, and restricts log enrichment to events already observed by the
repository. Cache keys include observed event identities, so a replaced event does not reuse an
orphaned registration/payment. It never signs, broadcasts or invents missing bill metadata.

## Validation

From `fusion-core-android`:

```sh
source /root/projects/fusion_wallet/scripts/edge_android_env.sh
./gradlew testDebugUnitTest assembleDebug
! grep -rn 'context[.]startActivity[(]' app/src/main/java
```

K24 adds 16 JVM tests covering current measured layer attribution (including extra controls), ASR
source parsing, invalid/missing evidence, Base Sepolia versus Anvil links, offline resource escaping,
recorded decision parsing, missing timing fields, uint256 money precision, zero/capped gauges,
countdown overflow/readiness/expiry boundaries, and exact bill-ID joins. Existing tests are unchanged.
The full suite has 151 tests: 150 passed, one existing skip, zero failures/errors.

`aapt2 dump resources` confirms the evidence snapshots are packaged as readable string resources.
ADB endpoints in this workspace are unauthorized; no device screenshot, live signing or physical
layout validation is claimed. The build produces `app/build/outputs/apk/debug/app-debug.apk`.
