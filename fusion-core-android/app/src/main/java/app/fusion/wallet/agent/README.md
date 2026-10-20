# In-app agent execution

`MainViewModel.runAgentTick(dryRun)` calls `AgentRuntime`: live K4 snapshot →
fusion-core candidates → MNN/AHEM-G in `:inference` → canonical passport →
policy preview. HOLD creates no transaction. The AI Agent screen supports manual preview/live
runs and foreground Autopilot (`autopilot/`). Autopilot runs immediately when
switched on, then 1/5/15 minutes after each completed tick (default 5). Its **Run
now** uses the same guarded path. Leaving the foreground cancels the worker;
returning starts one tick with no background catch-up. **Stop** turns scheduling
off immediately; guardian pause also stops it before contacting the signer.

Autopilot refreshes the live snapshot and records HOLD for paused/unconfigured
modules, expired/inactive sessions, read-only access or tier, guardian stop,
missing network, stale data, or mismatched bill accounting. Missing session key
or broadcast lease selects an explicitly labelled preview. Live mode uses only
the existing runtime, signer and matching relay lease; no signer authority is
added. The app-private, atomic journal keeps the latest 50 ticks, checks and
verified receipt hashes across relaunch. Interrupted live attempts remain
**execution not confirmed**, because cancelling UI work cannot undo an already
submitted transaction. Journal failures turn automatic execution off.

For an instantaneous executable choice, `dryRun=false` also invokes the private
`SessionSignerService` in `:signer`. It independently checks the report against
fresh chain state and contract pins, simulates the module call, bounds gas/fees,
and signs with the Keystore-wrapped session key. Immediately before key access,
its Kotlin allowlist checks the configured module and chain, zero value, and an
`execute` or `queueAction` selector derived from the contract ABI. Native signing
repeats those checks and ABI-decodes the entire call, returning a typed
`SignerRefused::...` error without signing any other transaction. Deployment
configuration is a separate native argument, never taken from the prepared tx.
The umbrella script `scripts/android_planner/generate_session_allowlist.py`
regenerates selectors and the golden app test vector (`--check` checks freshness). It journals the signed attempt
before sending and verifies the receipt, sender, calldata and `ActionExecuted`
event before returning a confirmed transaction. Guardian keys use their existing
separate envelope; no signer/ source is modified.

Broadcast mode requires a matching request lease from the edge helper while that
helper holds `/root/projects/fusion_wallet/.session.lock`. The relay admits
`eth_sendRawTransaction` only for that lease. The helper retains the flock until
the receipt is resolved, including a timed-out app reply with a durable attempt.
The relay forwards live RPC; all planning and session signing run in Android.

The debug-only `AgentControlReceiver` triggers the same runtime through
`AgentControlActivity`, which mounts the same `AgentRunCard` used on the AI Agent
screen. It accepts a mode and request ID, with no keys or candidate overrides.
The debug `SessionImportActivity` imports raw session bytes through a transient
local socket restricted to shell/root peers. Model files remain in app-private
files storage and are checked against `ProvisionedHashes`.

The umbrella repository's `scripts/android_planner/execute_owner_setup.py
--broadcast --refresh --run-app --local-host` is the authorized OnePlus runbook.
It holds the shared flock while preparing/simulating a fresh owner commitment,
executing the 2-of-2 Safe setup, provisioning public bills, triggering the app,
and recording the verified APP receipt. Owner setup and APP agent transactions
are recorded separately in `evidence/app/agent/`.
