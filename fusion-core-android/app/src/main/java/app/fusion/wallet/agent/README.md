# In-app agent execution

`MainViewModel.runAgentTick(dryRun)` calls `AgentRuntime`: live K4 snapshot →
fusion-core candidates → MNN/AHEM-G in `:inference` → canonical passport →
policy preview. The AI Agent screen's **Run agent now** button uses dry-run mode.
HOLD creates no transaction. This is a manual foreground runtime.

For an instantaneous executable choice, `dryRun=false` also invokes the private
`SessionSignerService` in `:signer`. It independently checks the report against
fresh chain state and contract pins, simulates the module call, bounds gas/fees,
and signs with the Keystore-wrapped session key. It journals the signed attempt
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
