# K4 Android real-state monitor

`WalletState.kt` is the UI contract (first published in commit `a0c8868`). Money is
`BigInteger` raw USDC units, formatted to **six decimal places** by `Money.display`.
Unavailable state is null, not a synthetic zero. Later contract changes are additive.

`MainViewModel(Application)` exposes `state` and `walletState` as
`StateFlow<data.WalletState>`, using `ChainWalletRepository.fromAssets(app)` in
**both debug and release**. It refreshes on construction, every ten seconds after a
refresh completes, and via `refresh()`. No debug fixture fallback occurs on failure.
The view-model coroutine stops when the Activity-scoped view model is cleared.

The repository uses `HttpURLConnection`, HTTPS, and only four allowed RPC methods:
`eth_chainId`, `eth_call`, `eth_getLogs`, `eth_getBlockByNumber`. Default endpoint:
`https://sepolia.base.org`; override with `fromAssets(context, rpcUrl)` or inject a
`RpcTransport` for JVM tests. No private keys, signer, transaction submission,
approval, pause, or unwind writes are present in this data layer.

## Snapshot consistency and failures

1. Check RPC chain ID against the bundled Base Sepolia deployment (84532).
2. Obtain one latest block number, hash, and chain timestamp. Pin **every view call**
   and the inclusive log range end to that number. ERC-4626 conversions use the
   shares read at that same block.
3. Decode views strictly. A malformed word, revert, missing response, failed vault
   read (even with zero shares), invalid enum, HTTP error, or missing payment event
   fails the entire refresh. No failed read becomes zero.
4. Resolve event block times with `eth_getBlockByNumber`; check each returned hash
   against its log. Recheck the snapshot block hash before publishing. A final head
   read only supplies freshness metadata; its balances are never mixed into the snapshot.
5. Publish the complete snapshot atomically. On error retain the last good values,
   set `status=ERROR`, and expose `error`. `refreshing` does not clear existing data.
   Cancellation propagates without turning it into an RPC error.

`WalletState.isStale()` checks the 30-second / five-observed-block TTL, rollback,
missing data, and error status. Pass a newer observed head if the UI has one.
The initial history scan can exceed five blocks on a public endpoint; display it
as stale until a subsequent warm refresh. A snapshot is monitor data and never an
authorization to sign or plan. This is the SL4 path, not the full planning snapshot:
Aave liquidity-rate sampling is not implemented here.

History starts at manifest `deploymentBlock=48317685`. `eth_getLogs` initially
requests at most 1,000 blocks, reducing the page size on provider range/response
limits (the public endpoint accepted 125-block pages during capture). A failed
page never silently drops a range. Subsequent successful refreshes retain old
history and reread the last 64 blocks, replacing recent logs to handle shallow
reorganizations. Historical headers are cached by hash. Actions are the newest 50
`ActionExecuted` logs; evidence is the newest 100 module logs. Neither uses the
manifest's `handoffSnapshot` as live state. Reverted transactions have no module
logs, so the historical 30-row competition evidence bundle is used **only by tests**;
the live evidence list is observed logs and does not claim to include those reverts.

## Contract reads

Addresses come from the byte-for-byte copy of umbrella `deployments/84532.json`
in `assets/deployments/84532.json`.

| Contract | Manifest key / deployed address | Function | Use |
| --- | --- | --- | --- |
| Aave test USDC | `asset` · `0xba50Cd2A20f6DA35D788639E581bca8d0B5d4D5f` | `balanceOf(safe)` | Treasury cash |
| Aave waUSDC | `vaultA` · `0xf430cb6E2b85f99222fBFA6dFEa18Ff60FA6B32a` | `balanceOf(safe)` | Exact vault shares |
| Aave waUSDC | `vaultA` | `convertToAssets(shares)` | Marked assets, not guaranteed cash |
| Aave waUSDC | `vaultA` | `maxWithdraw(safe)` | Available withdrawal assets |
| Aave waUSDC | `vaultA` | `previewDeposit(1000000)` / `previewWithdraw(1000000)` | Share quotes for exactly one USDC |
| KavachiDemoVault | `vaultB` · `0xf052C0ED8404adEaAEA773A5F8Ae4628134aff6D` | Same five functions as vault A | Real on-chain demo-vault position, explicitly SIMULATED yield |
| KavachiModule | `module` · `0xA560ec10259657E9bADd297E68286d2838Cd5a8f` | `getPolicy()` | Permission tier, buffer, micro/delayed/hard/daily limits, delay, slippage, guardian, vault exposure limits, payee delay/early window |
| KavachiModule | `module` | `getState()` | Paused/configured, epoch/hashes/nonce, reserve/count, session and daily usage |
| KavachiModule | `module` | `getQueue()` | Digest, action kind/amount/recipient/decision hash/nonce, readiness and expiry |
| KavachiModule | `module` | `getPayee(payeeIdHash)` | Account, label hash, activation time, cancellation |
| KavachiModule | `module` | `isPaid(obligationIdHash)` | Paid markers for every bundled bill in the matching commitment |
| Test sanctions list | `sanctionsList` · `0xd2716e08BCe6eb5B1f01329FF5E1a2dd0468B678` | `isSanctioned(payee.account)` | Strict boolean; TEST LIST |
| KavachiModule | `module` | `eth_getLogs` (all module topics) | Discover payee IDs, execution/payment events, and live evidence |
| Block headers | RPC | `eth_getBlockByNumber` | Snapshot identity, per-log timestamps, reorg and freshness checks |
| Chain | RPC | `eth_chainId` | Reject a different network |

Module events emit payee additions even though storage is in
`KavachiPayeeRegistry`. `getPayee` delegates to that registry. Labels are stored
on-chain only as hashes: without a verified preimage the UI gets `Payee <ID>` and
the exact `labelHash`, not an invented business name. Status is COOLING before
`activeAt`, ACTIVE at/after it, or CANCELLED. Sanctioned is a separate field.

Payments match `ObligationPaid` to the closest preceding payment log in the same
transaction as `ActionExecuted`; other action kinds have no payee. `decisionHash`
is the indexed on-chain value. `SCRIPT` / `deterministic` identify the deployment's
SL4 producer configuration, not a provenance claim encoded in the event. The data
layer does not claim to verify passport content from the decision hash alone.

The bundled `obligations-84532.json` contains bill records, not balances. For a
matching root, the repository reads every paid marker and checks total count and
unpaid sum against `getState`. Any root/sum/count discrepancy produces
`accountingMatches=false` and `accountingError=ACCOUNTING_MISMATCH...` while still
showing accurate on-chain balances. K5 must surface that latch; planning/signing
must remain disabled. Daily usage is zero after a chain-day rollover until the
module records the new day, matching contract accounting.

## Buckets (§7.9)

For cash C, declared owed R, operating buffer target B, and marked vault assets V:
`reserve=min(C,R)`, `free=max(C-R,0)`, `buffer=min(free,B)`,
`surplus=max(free-B,0)`, `shortfall=max(R-C,0)`, `deployed=V`, `total=C+V`.
`owed` remains the full R even during a shortfall. Paying a bill lowers C and R
equally and leaves free cash unchanged. Every operation uses integer units.

## Native-core fallback

Attempted the prescribed v4 build with the Edge environment:

```
source /root/projects/fusion_wallet/scripts/edge_android_env.sh
cd src/fusion-core
cargo build --locked --target aarch64-linux-android --release --lib
```

It failed before compilation: `rustup could not choose a version of cargo to run,
because one wasn't specified explicitly, and no default is configured`.
The permitted Kotlin fallback is implemented in `chain/Abi.kt` and
`chain/ContractDecoders.kt`, using the **actual** umbrella
`src/contracts/src/IKavachiModule.sol` and `src/fusion-core/src/snapshot.rs` types.
Selectors/topics were generated with `cast sig` / `cast keccak`. No generated
UniFFI binding was hand-edited; this repository never invokes the old native core.

## K5 UI integration and legacy preview boundary

Collect `MainViewModel.state`, render the nullable fields, use `dataLabel`, and
show error/stale/accounting states. `source=DEMO` requires a visible **DEMO** label
on every fixture-fed screen; the new view model defaults to LIVE in all variants.
No transaction control is implemented by K4.

The base app's mounted shell still collects `AccountantViewModel` and its Room
read model, **not MainViewModel**. Those screens and that adapter are K5-owned and
were not modified. K5 must bind them to this contract for the shipped screens to
show this live state. The original `ui/DemoData.kt` and preview UI models retain
legacy floating-point preview types. `MainViewModel.kt` keeps only the three DTO /
interface declarations they need to compile, with `BuildConfig.DEBUG` checks on
construction of demo intents/results; no live code instantiates or executes
those types. Their removal together with the old preview screens is a K5 change.
All K4 data/chain models, calculations and network decoding use integer money.

## Live sample and independent cast check

Captured **2026-10-19** through `ChainWalletRepository` by the opt-in JVM
`LiveSnapshotTest`, not from the manifest handoff:

- Block **48320776**, hash `0x8b61fc5cadfa29f456470e15fb8c8ff22c846833d7d918bb6a894090dc3de350`.
- Chain timestamp **1792409840**.
- Cash **9467001235** raw = **9467.001235 USDC**.
- Owed/reserve **50000000** raw = **50.000000 USDC**; shortfall **0**.
- Buffer target and funded buffer **500000000** raw = **500.000000 USDC**.
- Surplus **8917001235** raw = **8917.001235 USDC**.
- Vault A shares/assets **0/0**; vault B shares/assets **0/0**; total **9467001235**.
- Configured **true**, paused **true**, active session **false**, queue **empty**.
- Daily limit **4000000000**, used **2093000000**, remaining **1907000000** raw.
- Five bill records, four paid; two ACTIVE payees and one COOLING payee.
- Five executed agent actions and 25 observed module event evidence rows.

Raw RPC requests/results are in `app/src/test/resources/chain/live-snapshot.json`.
Independent `cast call` commands and full outputs are in
`data/live-cast-48320776.txt`; cash, module fields, policy limits and both vault
share balances match the Kotlin snapshot exactly. Example:

```bash
cast call 0xba50Cd2A20f6DA35D788639E581bca8d0B5d4D5f \
  'balanceOf(address)(uint256)' 0x029869910714D9e09b854a2688311AFd42fBb44E \
  --block 48320776 --rpc-url https://sepolia.base.org
# 9467001235 [9.467e9]  (cast's display annotation; exact integer above)
```

## Verification

From `fusion-core-android`:

```bash
source /root/projects/fusion_wallet/scripts/edge_android_env.sh
./gradlew testDebugUnitTest assembleDebug
```

Default tests are offline and replay the captured real RPC bytes. They compare
ABI results with the deployment handoff and verified competition events, exercise
bucket boundaries and uint256 precision, and check failed reads, wrong chains,
reorganizations, accounting mismatches, and log pagination. Existing tests are
unchanged. The optional live test is skipped unless explicitly enabled:

```bash
FUSION_CAPTURE_DIR=/tmp/fusion-live ./gradlew testDebugUnitTest \
  --tests '*LiveSnapshotTest' --rerun-tasks
# Optional HTTPS endpoint override: FUSION_RPC_URL=...
```

A new capture is a separate artifact; do not overwrite the pinned offline fixture
without reviewing the assertions tied to block 48320776.

### K44 measured yield pre-processor

`QuantReader` reads Base Sepolia Aave USDC available underlying and stable + variable
supply debt at the decision block, the normalized liquidity index now and about
24h earlier (primary RPC, then publicnode), and the pinned Chainlink ETH/USD round.
`fusion-core::quant` owns all integer utilization, APY and break-even formulas.
K33's actual receipt median supplies the gas amount; absent measurements stay
UNMEASURED. The deposit quote supplies the round-trip asset loss, rather than
charging the slippage tolerance as an invented fee. Safety withdrawals use
available underlying; deposits require <=85% utilization and <=14-day break-even.

Home uses the same native report as candidate generation. `AgentRuntime` publishes
`quant` in `last_tick.json`; Preview and Autopilot display it, and advisor consumers
can use that structured field without trusting prose. Only admitted action labels
reach the planner. The signer independently refreshes the pool, index, oracle and
quote, and re-runs the quant preview before signing.

Run `scripts/android_planner/build_core.sh` before Android JVM tests. It builds
both Android and host core libraries; `QuantNativeTest` exercises the production
Rust formulas through generated Kotlin bindings and host JNA. `FUSION_HOST_CORE_LIB`
can override the host-library directory; by default it uses this workspace's
`src/fusion-core/target/debug`. Host libraries are never packaged in the APK.
