# K33: balance without a move estimate

Live read-only Base Sepolia probes on 2026-10-20 reproduced the zero-sample
failure. The diagnostic output is in [agent-gas-k33-live.json](agent-gas-k33-live.json).
No device cache was available; the K32 reproduction explicitly simulates a cache
already warmed by `rememberTxIndex` using the same GitHub URL as the app.

## Root cause

`AgentGasReader` requests missing receipts in chunks of 50. The current index
contains 37 entries, so K32 sends one 37-item HTTP batch. Base Sepolia counts
individual batch items against its 25 requests/second limit and returns a mix
of successful receipts and `-32007: 25/second request limit reached` errors.
`JsonRpcClient.batch` throws on the first error; all successful receipts in that
batch are lost. `AgentGasReader` catches the exception and continues with zero
cached receipts. All three attempts failed in the live reproduction (12 item
errors per attempt), leaving the pinned balance but no median or move count.
The old budget only delayed the next request after reserving `calls * 50ms`;
it could not make an oversized burst valid. K32's earlier probe concealed the
difference by using 20-item batches and sleeping 1.1 seconds before each request.

The transport now splits every batch into at most 20 items and shares a rolling
20-call/second burst budget across clients for the same endpoint. Retries apply
to each bounded chunk. The original result ordering and all-or-nothing failure
behavior are preserved.

## Other paths checked

- **Index:** GitHub returned 37 unique entries (26 SCRIPT, 5 OWNER, 4 AGENT,
  2 APP). It contains 17 transactions actually sent by the active session,
  despite only four carrying the AGENT evidence label. Eligibility still uses
  `receipt.from`, not the metadata source label. The repository previously read
  only disk, while Compose refreshed it asynchronously. A missing/stale disk
  cache could independently produce zero samples on first refresh. Phase 2 now
  refreshes its own index, retaining a valid disk cache when GitHub fails.
- **Receipts and headers:** with bounded bursts all 37 receipts and all 17
  distinct candidate headers returned successfully; the other 20 receipts were
  correctly excluded by sender. No future receipts were selected.
- **Hashes and time:** every candidate header matched the receipt block hash.
  All 17 timestamps were within 30 days of the pinned snapshot (roughly 14–22
  hours old). The final snapshot hash recheck passed. The age window stays intact.
- **Case:** `TxIndex.parse` lowercases transaction hashes before inserting them
  into the receipts map. The existing lowercase eviction key therefore matches;
  sender, transaction hash and block hash checks are case insensitive. A
  regression test verifies eviction and refetch with an uppercase receipt hash.

At fixed snapshot `0x2e1dee9` the session
`0xb52cb48ec1cc5ad1171c83f162228e31752f87fa` held
`17641102153994847` wei. Its 17 actual receipt fee samples had median
`1342800000259` wei, yielding approximately **13,137 moves**. Fees include
`gasUsed * effectiveGasPrice + l1Fee + operatorFee` when those optional fees
exist. Empty, unavailable, or exclusively old histories still show balance only.
These are historical measurements, not a guaranteed future transaction cost.

## Reproduce

From the umbrella worktree:

```sh
python3 src/Fintech_Wallet_Agent/scripts/reproduce_agent_gas.py --mode k32 --warm-cache --capture /tmp/k33-old.json
python3 src/Fintech_Wallet_Agent/scripts/reproduce_agent_gas.py --capture /tmp/k33-fixed.json
```

`--mode k32` without `--warm-cache` simulates an empty cache and reports zero
index entries. `--tx-index PATH` seeds a cache from an actual device copy for
inspection; K32 uses it directly, while fixed mode refreshes GitHub and falls
back to that cache on download failure. The probe follows the app's 50-entry
reader chunks, transport splitting/budget/retries, full-batch rejection, top-20
session candidates, header checks, 30-day cutoff and final snapshot recheck.
It reports batch errors and filter counts; `--capture` retains raw HTTP evidence.

## Validation

Regression tests cover a 37-entry index against a provider returning partial
rate-limit errors above 25 items, 17 candidate headers, receipt caching, mixed
case sender/hash handling, reorganized receipt eviction, the exact 30-day
boundary, absent cache loading without UI, offline cache fallback, and rolling
burst reservations across concurrent clients.

The opt-in `AgentGasLiveTest` also passed through the actual Kotlin
`JsonRpcClient`, `TxIndexCache.refresh` and `AgentGasReader` at snapshot
`0x2e1df4f` (hash
`0x271e90b626e08dc019729da3bcb2e000380a1a0ac00197dd20f36a1ab3c82ffe`).
It loaded 37 index entries and returned the same balance, median and 13,137
moves as the Python probe. Run it from `fusion-core-android` with:

```sh
FUSION_AGENT_GAS_LIVE=1 ANDROID_HOME=/opt/android-sdk GRADLE_USER_HOME=/root/.gradle ./gradlew testDebugUnitTest --tests '*AgentGasLiveTest'
```

```sh
ANDROID_HOME=/opt/android-sdk GRADLE_USER_HOME=/root/.gradle ./gradlew testDebugUnitTest assembleDebug
```

Passed: 230 tests, zero failures/errors, four opt-in tests skipped in the default
run. Both native build scripts completed; the assembled APK contains the planner,
core and MNN libraries.
