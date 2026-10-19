# K5 UI Integration: Needs from K4

## Contract Status
All required read-only data contract fields in `app.fusion.wallet.data.WalletState` are provided and verified against Base Sepolia live state (block `#48320776`+).

## Missing Callbacks / Actions (Wired as "Not Wired" in Monitor Build)

1. **Guardian Pause (Agent Stop Button)**:
   - **Spec requirement (§17.12)**: Big Stop button with confirmation dialog triggers on-chain guardian pause.
   - **Current K4 status**: `MainViewModel` is scoped to SL4 live monitoring only (`ChainWalletRepository` is read-only RPC view calls). No pause callback or signing flow is exposed.
   - **K5 UI behaviour**: Confirmation dialog is fully implemented. When confirmed, the UI displays: `"Guardian pause: not wired in monitor build"`.
   - **Requested from K4/Signer**: Expose a suspend function or callback on `MainViewModel` (e.g. `suspend fun requestGuardianPause()`) when signer/module integration is ready.

2. **Emergency Unwind (Safety Screen)**:
   - **Spec requirement (§17.10)**: Emergency unwind card with long-press/button confirmation to pull all vault assets back to Safe cash and halt the agent.
   - **Current K4 status**: No unwind write transaction is implemented in the monitor repository.
   - **K5 UI behaviour**: Confirmation dialog is fully implemented. When confirmed, the UI displays: `"Emergency unwind: not wired in monitor build"`.
   - **Requested from K4/Signer**: Expose an unwind request callback on `MainViewModel` (e.g. `suspend fun requestEmergencyUnwind()`) when available.

## Missing Data Fields Rendered as "—"
- When `WalletState.blockNumber` is null (initial network probe before first block read), the block header displays `Block —`.
- When an action or evidence row has no `decisionHash`, the decision hash displays `—`.
- If an address is unresolvable or missing, it renders as `—`.
- No numbers, balances, or counts are fabricated.
