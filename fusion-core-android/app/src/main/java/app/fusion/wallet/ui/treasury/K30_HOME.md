# K30 Home actions and tiles

Receive encodes the displayed Safe address offline with ZXing 3.5.3, provides real
clipboard feedback, labels Base Sepolia testnet explicitly, and opens BaseScan through
`startActivitySafe`. Earn and Preview next move call `runAgentTick(dryRun = true)`.
The result sheet describes the selected action, its snapshot and policy checks; it
computes bill coverage from the decision snapshot, includes owner/queue requirements,
and makes no rate claim. A failed policy preview describes a hold.

The agent allowance uses the module's normalized daily usage and live policy limits.
Vault withdrawal availability uses `maxWithdraw`; technical fields are in the info
sheet. The allocation uses funded bills, surplus and vault assets, so unfunded bills
never inflate total assets. Agent gas reads session ETH at the displayed block and
uses up to 20 recent indexed receipts, verifying sender, transaction and canonical
block hashes, including any recorded L1/operator fee. Unknown receipt history hides
the move estimate. Receipts and headers are batched to avoid serial refresh latency.

## Unresolved signer dependency

`signer/GuardianSignerClient.kt` exposes only `requestPause(Context)`.
`GuardianSignerService` accepts only empty PAUSE IPC. There is no callable guardian
`cancelQueuedAction(digest)` API. The card explicitly forbids changing `signer/**`.

The queue banner therefore shows a live readyAt countdown, conditional limit/coverage
text and the requested Safe co-sign explanation/link, plus the cancellation limitation
and import guidance when needed. It does not offer a transaction-producing Cancel
button or claim a pause cancels anything. Completing K30 requires a signer-owner API
or explicit scope permission to add digest-bound cancellation in `:signer`, with a
confirmation dialog, verified receipt and success snackbar with transaction link.

## Validation

Both native build scripts passed. `testDebugUnitTest assembleDebug` and the APK check
for `libfusion_planner_android.so` exited 0. JVM suite: 214 tests, 0 failures/errors,
3 existing skips. Twelve new tests cover offline QR decoding/payload rejection,
allowance boundaries, exact median gas math, pinned balances and receipt validation,
empty/nonempty queue visibility, safety-cushion coverage, and preview route text.
