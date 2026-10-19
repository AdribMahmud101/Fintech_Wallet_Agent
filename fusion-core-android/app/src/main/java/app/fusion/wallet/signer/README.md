# Base Sepolia guardian signer (K12)

This is a guardian-only bound service, `exported=false`, in the `:signer`
process. Its Messenger protocol accepts only an empty pause request from the
app UID. It does not accept destinations, calldata, keys, fees or chain IDs
from callers. The service pins chain 84532, HTTPS RPC
`https://sepolia.base.org`, module
`0xA560ec10259657E9bADd297E68286d2838Cd5a8f`, and guardian
`0x46B8E40EB9767BD46E03C5eCF398C10E1372251E`.

The only signed call is `pause(bytes32)` (selector `0xed56531a`), with reason
`keccak256("APP_GUARDIAN_STOP")`, zero ETH value and an empty access list.
The EIP-1559 chain ID, pending nonce, base fee, priority fee, gas estimate and
guardian balance are read from RPC. Chain ID must match the pinned chain.
Fee = 2 × base fee + priority fee (minimum priority fee 1,000,000 wei), capped
at 10 gwei. Gas = 120% of the estimate, minimum 100,000, maximum 500,000.
All quantities are integer `BigInteger` values in wei.

`MainViewModel.requestGuardianPause(): Result<String>` binds to this service.
Success requires a mined receipt with status 1, the expected sender and module,
and `getState().paused == true` at the receipt block. Repeated contract pauses
are idempotent and need not emit another `Paused` event. Confirmation here means
one mined receipt, not finalized settlement. The caller should show the hash
and `https://sepolia.basescan.org/tx/<hash>`, show `exceptionOrNull()?.message`
on failure, and disable repeated confirmation taps while the request runs.

### K11 integration handoff (UI ownership)

The K12 task explicitly reserves the rest of `ui/**` for K11. The existing
`AgentScreen` Stop dialog is therefore unchanged in this commit and still
shows the monitor placeholder until K11 mounts `GuardianStopDialog`. The
complete dialog implementation lives in this signer directory: it shows
pending state, receipt-confirmed success, transaction hash, BaseScan link,
errors and retry, and disables repeated taps while sending. The supplied
`K11-stop-dialog.patch` replaces just the old confirmation block with:

```kotlin
app.fusion.wallet.signer.GuardianStopDialog(
    requestPause = vm::requestGuardianPause,
    onDismiss = { showStopDialog = false },
)
```

Apply from the submodule root when integrating K11/K12:

```sh
git apply --check fusion-core-android/app/src/main/java/app/fusion/wallet/signer/K11-stop-dialog.patch
git apply fusion-core-android/app/src/main/java/app/fusion/wallet/signer/K11-stop-dialog.patch
```

K12 does not claim that the unmodified K5 screen calls the new method. The
supervisor must integrate this UI seam before the live in-app demonstration.

## Import the demo key at runtime

Build and install the **debug** APK. Launch the debug-only import activity:

```sh
adb shell am start -n app.fusion.wallet/.signer.GuardianImportActivity
```

The operator obtains `GUARDIAN_PRIVATE_KEY` from the gitignored operator
`/root/projects/fusion_wallet/.env.local` and enters it in the masked screen.
Do not put it in an adb argument, broadcast extra, shell history, source file,
BuildConfig, resource, asset, screenshot, build output or chat. Do not run a
shell command that prints it. This implementation deliberately accepts **no
key intent extras**. If using paste, the operator must trust the keyboard and
clipboard and clear clipboard history afterwards.

Tap **Import guardian**. Import validates that the key derives the deployed
guardian address, then encrypts it immediately. The screen clears its input,
disables repeat imports, blocks screenshots, opts out of autofill, requests no
IME personalized learning, and never saves the text in instance state. It runs
in `:signer`, so the main process never receives the key. No import activity or
manifest entry is included in a release build. Release use requires separately
provisioning a key; this card provides only the debug demo import path.

No key is preloaded. Opening the import screen does not send a transaction.
The live supervisor should import and fund the guardian with Base Sepolia ETH,
then confirm Stop in the app. **Do not run a live pause during parallel agent
work.** This card's tests and signing vectors never broadcast.

## Storage and threat model

An AES-256-GCM key in `AndroidKeyStore` wraps the software secp256k1 key.
StrongBox is requested on supported devices, falling back to the regular
Keystore if StrongBox is unavailable. The envelope has a fresh 96-bit IV, a
128-bit authentication tag, version byte and authenticated chain/purpose label.
Only the envelope is stored in app-private `noBackupFilesDir/signer/guardian.enc`.
It is excluded from backup and device transfer. Encryption uses atomic writes.
Missing or invalidated wrapping keys fail closed; there is no plaintext fallback.
This guardian unwrap requires no biometric so the Stop action can proceed in
one confirmation. That differs intentionally from owner approval keys.

Wrapping protects a copied ciphertext file at rest and detects tampering. It
is **not hardware Ethereum signing**: secp256k1 signing runs in software, and
the raw key briefly exists in signer-process memory. Byte arrays are wiped in
`finally`, but JVM `String`, `BigInteger`, crypto-internal and IME copies cannot
be reliably zeroized. Root, debugging, memory inspection, malicious keyboards,
an unlocked compromised device or arbitrary code executing as the app UID can
defeat these protections. All APK processes share the same UID and filesystem;
process separation limits accidental exposure and crashes, not a hostile main
process. The service and import path have no logging sinks; exceptions from RPC
or cryptography are reduced to fixed public error messages across IPC.

The pinned RPC is a trusted availability/integrity dependency. Network loss,
congestion, exhausted ETH or an unavailable Keystore can prevent pause. Already
broadcast agent transactions can still execute before pause is mined. This
service does not implement the broader inference-process local stop latch.

## Retry and crash behavior

Before broadcasting, persist the hash and signed public transaction bytes in
`noBackupFilesDir/signer/pending.json`. A mutex serializes requests. A retry or
process restart checks for a receipt and reuses these exact bytes, never signs
a second nonce while unresolved. The service polls every two seconds for up to
60 receipt reads; network timeouts can extend elapsed time. The client unbinds
after 240 seconds. Errors never claim an on-chain pause. On timeout, check the
hash in BaseScan before retrying. Unbinding or killing the app cannot revoke a
transaction already sent to the network.

A mined revert clears the journal; a successful verified pause clears it too.
An unresolved/rejected tx stays journaled. Fund an underfunded guardian and
retry the same bytes. If the nonce was consumed elsewhere or fee conditions
changed, an operator must reconcile the hash and nonce first; this version has
no fee replacement UI. Only after reconciling should the operator clear app
data and re-import (clearing data removes the key envelope and journal).

## Owner restart: 2-of-2 Safe path

The guardian cannot resume, configure policy, approve agent actions or send
Safe funds away. `pause` invalidates the session and queue and increments the
policy epoch; repeated pause is a no-op. Both Safe owners must reconcile
outstanding transactions and sign the required Safe transaction through the
threshold-2 Safe owner workflow (Safe web app or the operator's Safe scripts).
Re-arm with a fresh valid `startSession(...)` and `resume()` in one ordered Safe
MultiSend batch; include `configurePolicy`/`commitObligations` first if changing
them, because those actions also pause and clear the session. Both owners must
sign the same Safe transaction on Base Sepolia. Execute through the Safe, then
verify on-chain `paused == false`, the new session and policy epoch. An agent
restart or a guardian signature alone is insufficient.

## Offline verification

```sh
source /root/projects/fusion_wallet/scripts/edge_android_env.sh
./gradlew testDebugUnitTest assembleDebug
```

Tests include byte-for-byte EIP-1559 vectors produced offline by Foundry
`cast mktx` 1.8.5 with the public test scalar 1 (never the deployed guardian),
AES-GCM authentication/random IVs, error redaction and a source-level prohibition
on logging sinks in both main and debug signer code. RPC fakes verify chain
checks, fee/gas inputs, insufficient funds, receipt validation, timeout,
lost broadcast replies, retry journal reuse, reverts and concurrent calls.

The task's additional literal grep gate already fails in the K4/K5 base on 44
lines across five files, all inherited public data: event topics in
`chain/ContractDecoders.kt`, the cast capture in `data/live-cast-48320776.txt`,
the preview transaction hash in `ui/preview/DemoPreviews.kt`, and code/transaction
hashes and Merkle proofs in the two deployment JSON assets. The grep's exclusions
only recognize labels on the same line. K12 adds no matching lines. Those files
belong to K11, so K12 leaves them and the task's check unchanged; the full literal
definition-of-done command consequently exits 1 despite a successful build and
tests. Do not treat public 32-byte hashes as proof of a private-key leak.
