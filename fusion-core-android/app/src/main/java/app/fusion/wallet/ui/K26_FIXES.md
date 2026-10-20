# K26 device-found polish

| Defect | Change | Verification |
| --- | --- | --- |
| 1. Home brand icons | Official Base in-product network mark; USDC circle within viewport and compact SVG arcs normalized to cubic curves for Android. Sources pinned in drawable/ICONS.md. | BrandVectorTest parses both vectors on JVM, checking viewport, paths, fill rules, foreground and circle geometry. Also rendered and visually inspected both at 192×192 with headless Java2D: USDC 4,771 white / 24,517 blue pixels; Base 12,799 white / 16,489 blue pixels. |
| 2. Duplicate network badge | Removed the hero network row; top bar retains Base Sepolia · TEST. | Source check: no network badge in TreasuryHero; assembleDebug. |
| 3. Striped bucket bar | Removed hatch drawing. Exact cumulative boundaries partition cash into one solid segment per nonzero bucket. All labels/values remain, including 0.00. | BucketSegmentsTest: proportions, zero buckets, contiguous 100% coverage, huge integer amounts. |
| 4. Missing balance history | Reverse exact USDC Transfer deltas from current Safe cash; include module checkpoints without double debits. Chart ≥4 points, otherwise stat; clearly labelled Safe cash (vault yield is not inferred). Persist series with snapshot; shared index wiring in defect 5. | BalanceReconstructionTest: incoming/outgoing, duplicates, same block, self transfer, module payment, huge integers, reorg/inconsistent history. |
| 5. Repeated slow scans | One deployment-scoped private persisted EventIndex supplies Home history, full Bills receipts, policy vendors and Activity. Resume cursor is independent of snapshot height; 64-block replacement handles shallow reorgs. 200-block chunks, three filters per HTTP batch, four parallel ranges, shared 20 RPC calls/s budget with bounded rate-limit retry, 4 s connect / 6 s read timeouts. Vendor/sanctions views batched. | EventIndexTest: restart retains old events, cursor overlap, no duplicate self-transfers, bounded parallelism/ranges, failed-page resume, corrupt cache, rollback/reorg. Existing chain replay assertions retained; transport now honours address/topics. LiveEventIndexTest validates real chain and times cold/resume. |
| 6. Raw timestamps and enum codes | Shared API-24-compatible local date/time formatter accepts ISO offsets, microseconds, epoch seconds/milliseconds, and gives an honest unavailable label for invalid input. Safety now says “Measured 19 Oct, 19:15 UTC” on a UTC device. Applied to Proof, Agent, Activity and Judge; readable status, reason, planner and event labels replace raw codes. | ReadableValuesTest covers offsets, fractional precision, day rollover, seconds/milliseconds, malformed dates and enum/planner labels. Screen-source sweep covers remaining date/status displays. |

## Live index measurement (host JVM, public Base Sepolia RPC)

Measured 20 Oct 2026 against block **48349167**, hash `0xc3209ee13a9ab60afd2b6ed81d832fa192276307478cb0d1bbd01b4cb563a1f0`.
Safe `0x029869910714D9e09b854a2688311AFd42fBb44E`, asset `0xba50Cd2A20f6DA35D788639E581bca8d0B5d4D5f` (manifest values).

| Operation | Time | Observations |
| --- | --- | --- |
| First complete index, empty disk cache | 39,105 ms | 474 logical log filters in HTTP batches; public RPC has a 200-block range limit and 25 calls/s cap. Initial sequential-filter implementation measured 65,452 ms / 765 log calls; batching, exact 200-block pages and pacing reduced work. |
| New EventIndex instance restored from disk; refresh latest 64 blocks | 667 ms | 3 logical log filters in one HTTP batch; older vendors and receipts retained. |
| Result | — | 15 USDC transfers, 50 module events, 34 reconstructed points, 8 distinct paid-bill receipts, 3 vendor registrations; current Safe cash 9,399.001235 USDC. |

Reproduce opt-in network measurement: `K26_LIVE=1 ./gradlew --no-configuration-cache --max-workers=1 testDebugUnitTest --tests '*LiveEventIndexTest'`.
The cold scan remains subject to public RPC range/rate limits and runs after the balance snapshot paints. Each completed contiguous page is persisted, so failure resumes rather than restarting deployment history. Normal refresh replaces the latest 64 blocks and appends new blocks. Bills reads the same full index, including receipts older than the previous 100-row retention.

Device measurement attempted: SDK `adb` is x86-64 on this aarch64 host. Native `/usr/bin/adb devices -l` works but lists only unauthorized `127.0.0.1:5555` and `emulator-5554`; `adb -s eb3cea7b get-state` returns device not found. Tecno USB is not reachable here as noted in the assignment. No device UI/render timing is claimed.

## Final validation

- Required `./gradlew testDebugUnitTest assembleDebug && unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -q libfusion_planner_android.so` exited **0**. JVM suite: **177 cases, 174 passed, 3 existing/added opt-in network/device checks skipped, zero failures/errors**. The opt-in live index test was separately enabled and passed against the public RPC.
- Both requested native build scripts completed. They derive ROOT from their absolute script path, so their output landed in `/root/projects/fusion_wallet/src/Fintech_Wallet_Agent/.../jniLibs/arm64-v8a`, despite invocation from this umbrella worktree. Copied those built `.so` files into this worktree's ignored `jniLibs/arm64-v8a`; APK now includes `libfusion_planner_android.so`, `libfusion_android_core.so`, MNN and libllm. No native binaries committed.
- The pre-existing `stashIcons`/`restoreIcons` Gradle tasks can fail with stale configuration-cache resource inputs because they move `ICONS.md`. Cleared only the generated project configuration cache before the final standard command; intermediate verification used `--no-configuration-cache --max-workers=1`. Build scripts are outside this card's owned paths and were not edited.
- `git diff --check` passed. All six defect changes committed inside the submodule on `card/K26`; no merge or push.
