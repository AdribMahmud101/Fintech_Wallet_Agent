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

K38: the live runtime now reads Aave's current liquidity APR at the decision
snapshot block using the same Pool/USDC addresses as Home. It floors ray APR to
basis points for core integer economics and records the raw ray value, block hash
and source in `rateEvidence`. An unavailable rate supplies no yield-deposit rate;
`files/agent/rates.json` no longer overrides live decisions. The full-profile
1 USDC minimum 30-day net benefit and 0.05 USDC fee assumption remain unchanged.
Thus a <=10 USDC allocation at approximately 0.21% APR correctly remains HOLD
under this yield strategy; it must not be advertised as a profitable deposit.

Before inference, the runtime also reads `convertToShares` and the matching
`previewDeposit`/`previewWithdraw` for each exact candidate amount at the snapshot
block. Native core binds the policy's floor/ceiling to those share units and
recomputes candidate IDs and the set digest. Quotes and their block hash are
recorded in the report. The chosen action is never rewritten after inference;
the independent signer simulation and on-chain share checks still apply.


For the explicit K38 round-trip request, use the separate owner strategy runbook:
`scripts/android_planner/execute_owner_allocation.py --broadcast --run-app --local-host`.
Without `--broadcast`, it only prepares and simulates a reviewable proposal. Both
Safe owners sign an inert zero-value EOA calldata commitment authorizing exactly
one <=10 USDC deposit and one later withdrawal. This is a gas-paying operational
rehearsal, explicitly separate from profitable-yield decisions. No contract
policy limit, session allowance, expiry, or economics constant is increased.
The mandate binds Base Sepolia, Safe, module, waUSDC, current policy hash and
session ID, consecutive module nonces and an expiry prepared one hour ahead, within the active session. Runtime
and independent signer both verify its canonical live receipt, Safe success,
2-of-2 ownership and exact calldata. The owner tx hash is bound into candidate
reason, candidate-set digest and canonical passport. The Android model chooses
A/H afresh for each phase; the host supplies no choice override. The mandate
file is removed only after both confirmed APP receipts, or ceases authorizing
once consumed/expired. Owner authorization is indexed as OWNER; session-key
asset-management receipts are separately APP with the Android planner ID.

`--resume --run-app --local-host` reuses the verified existing owner mandate
without reading owner keys or sending another OWNER tx. A model HOLD remains a
recorded HOLD; no candidate override or deterministic fallback is used. Verified
authorization is described in core candidate facts; the separate untrusted-note
review still runs and cannot authorize policy changes. The live loopback
transport preserves read batches, a shared per-item read budget and top-level
range errors, and the agent repository reuses the verified durable event index.
The original 30-second/five-block freshness and accounting checks are unchanged.

Receipt completion is independent of transaction execution. Transient receipt
read failures retry without resending. If a durable attempt remains, automatic
recovery and debug `reconcile` mode use the original report/passport under a new
edge lease, and require `reconcileOnly=true`. Without a journal this mode refuses
before new action verification or key access. It preserves the original MNN
choice and verifies the canonical receipt/event; it never invokes a new choice,
signature or broadcast. The task's two original sends were recovered this way.
