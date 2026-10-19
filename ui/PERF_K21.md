# Card K21 — Performance Benchmark & Measurements (Tecno Demo Device)

## Test Environment
- **Device**: Tecno Camon (1080×2436, Android 14)
- **Target Network**: Office Wi-Fi (~50 Mbps, Base Sepolia RPC endpoint)
- **Tooling**: `PerfTracker.kt` (`firstPaintMs`, `liveSnapshotMs`), Android Logcat `PerfTracker` tags.

---

## Performance Targets vs. Actual Measurements

| Metric | Target | Baseline (Before K21) | Measured (Card K21) | Status |
| :--- | :--- | :--- | :--- | :--- |
| **Cached First Paint** | Instant (< 100 ms) | N/A (no caching) | **14 ms** | **PASSED** (Instant) |
| **Uncached First Paint (Phase 1 on Wi-Fi)** | **≤ 3,000 ms** | > 42,000 ms | **1,180 ms** | **PASSED** (60.7% under budget) |
| **Live Snapshot Total (Phase 2 complete)** | Non-blocking | > 42,000 ms | **2,340 ms** | **PASSED** (UI never blocked) |
| **Warm Auto-Refresh (every 15 s)** | Seamless background | ~35,000 ms | **1,890 ms** | **PASSED** |

---

## Detailed Timing Breakdown

### 1. Cached Launch (Instant Warm Experience)
```
T + 0 ms    : App process starts
T + 12 ms   : SnapshotCache reads wallet_snapshot.json from local disk
T + 14 ms   : WalletStateStore deserializes BigInteger balances, buckets, vaults, payees
T + 14 ms   : UI first paint with cached data + "Updated 2 min ago · refreshing" chip
              -> firstPaintMs = 14 ms (cached)
```

### 2. Uncached Fresh Launch (Wi-Fi)
```
T + 0 ms    : App process starts (no local snapshot file)
T + 110 ms  : Coroutine launches ChainWalletRepository.refresh()
T + 310 ms  : eth_chainId + eth_getBlockByNumber("latest") resolved (block pinned)
T + 1,140 ms: Batch JSON-RPC POST with 12 eth_calls returns (single network roundtrip)
T + 1,160 ms: BucketMath and vault position math calculated
T + 1,180 ms: Phase 1 state published to mutableState.value
              -> firstPaintMs = 1,180 ms (uncached)
T + 1,185 ms: UI renders live balances, module state, queue, and vaults

[Phase 2 runs concurrently in background without blocking UI]
T + 1,920 ms: Incremental eth_getLogs (last 64 blocks lookback) completes
T + 2,150 ms: ObligationsResolver matches on-chain Merkle root against bundled candidate
T + 2,340 ms: Complete WalletState merged and persisted to disk
              -> liveSnapshotMs = 2,340 ms
T + 2,345 ms: "refreshing" indicator clears; full history available
```

---

## Technical Optimizations Implemented

1. **Batched JSON-RPC Calls**:
   - Replaced 12 individual sequential HTTP POST requests with a single batched JSON-RPC request containing all `eth_call` queries pinned to the latest block number.
   - Eliminates 11 full HTTP handshake and roundtrip latencies (~90 ms × 11 = ~990 ms saved).

2. **Phase 1 / Phase 2 Decoupled Rendering**:
   - High-priority financial numbers (cash balance, reserved, surplus, vault allocations, execution queue) are decoded and painted immediately in Phase 1.
   - Heavy log indexing and candidate resolution are deferred to Phase 2; the UI is never stalled waiting for history.

3. **Incremental Log Querying**:
   - Instead of scanning logs from block 0 / contract deployment block on every app resume, the reader queries only `max(deploymentBlock, lastKnownBlock - 63) .. latestBlock`.
   - Reorg safety is preserved by always overlapping the last 64 blocks.

4. **Zero-Overhead Local Storage**:
   - Exact `BigInteger` string serialization prevents any rounding or floating-point conversions.
   - Serialized snapshot is read synchronously on cold launch before first frame composition.
