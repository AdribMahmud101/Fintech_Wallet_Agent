# K5 Android UI: Screen Specifications and State Contract

All UI components render exclusively from `app.fusion.wallet.data.WalletState` exposed by `MainViewModel`. Numbers use integer `BigInteger` units formatted via `Money.display` retaining exact USDC precision.

---

## 1. Treasury (Home)
- **Component**: `app.fusion.wallet.ui.treasury.TreasuryScreen`
- **WalletState fields rendered**:
  - `status`: `LoadStatus.LOADING`, `READY`, `ERROR`
  - `source`: `DataSource.LIVE`, `DEMO` (controls `DEMO` badge visibility)
  - `chainId`: 84532 (`badge_network` "Base Sepolia · TEST")
  - `blockNumber`: Last-refresh block number (`Block #48320776`)
  - `blockTime`: Timestamp of snapshot block
  - `refreshing`: Active poll spinner
  - `isStale()`: 30-second / 5-block freshness latch banner
  - `accountingMatches` / `accountingError`: Accounting consistency latch banner
  - `treasury`:
    - `balance`: Total treasury cash
    - `safeAddress`: Elided Safe multisig contract address
    - `symbol`: Asset symbol (`USDC`)
  - `buckets`:
    - `total`: Total portfolio assets (`cash + vaults`)
    - `reserve`: `min(cash, owed)` set aside for bills
    - `owed`: Full declared obligations amount
    - `buffer`: `min(free, bufferTarget)` safety cushion
    - `surplus`: `max(free - buffer, 0)` spare cash / safe to invest
    - `shortfall`: `max(owed - cash, 0)` shortfall amount
    - `bufferTarget`: Target operating buffer
  - `vaults`:
    - `name`: Vault name (e.g. `Aave waUSDC`, `KavachiDemoVault`)
    - `address`: Vault contract address
    - `assets`: Marked assets
    - `sharesRaw`: Exact share balance
    - `maxWithdraw`: Maximum withdrawable assets
    - `exposureLimit`: Policy exposure cap
    - `simulatedYield`: `true` on Demo Vault (shows `SIMULATED` pill), `false` on Aave waUSDC (shows `Real` pill)
  - `obligations`: Unpaid vs total bills count and next due bill preview
- **States**:
  - `LOADING`: Skeletons / progress indicator, never synthetic zeros
  - `READY`: Full bento hero, four buckets segment bar, vault positions grid
  - `EMPTY`: "No account data yet" message if treasury and buckets are null
  - `ERROR`: Network error notice with retry button
  - `STALE`: Yellow warning banner if snapshot is >30s or >5 blocks behind
  - `ACCOUNTING_MISMATCH`: Red warning banner if obligations Merkle sum mismatches contract reserve

---

## 2. Agent
- **Component**: `app.fusion.wallet.ui.agent.AgentScreen`
- **WalletState fields rendered**:
  - `status`, `refreshing`, `error`, `source`
  - `module`:
    - `paused`: Guardian pause state (`ARMED` vs `PAUSED`)
    - `dailyUsed`, `dailyRemaining`: Daily velocity tracker
    - `policy`: `permissionTier`, `microMax`, `delayedMax`, `hardMax`, `operatingBuffer`, `delaySeconds`, `guardian`
  - `queue`:
    - `id`: Action queue ID
    - `kind`: `DEPOSIT`, `WITHDRAW`, `REBALANCE`, `PAY_OBLIGATION`
    - `amount`: Action amount in USDC
    - `readyAt`: Target readiness chain timestamp (drives live countdown)
    - `expiresAt`: Expiry chain timestamp
    - `status`: `WAITING`, `READY`, `EXPIRED`
    - `payee`: Target recipient address / label
    - `decisionHash`: Cryptographic decision commitment (short form)
    - `expectedNonce`: Nonce order
  - `recentActions`:
    - `id`: Executed action ID
    - `kind`: Action kind
    - `amount`: Executed amount
    - `payee`: Recipient address / label
    - `txHash`: Transaction hash (BaseScan link `https://sepolia.basescan.org/tx/<hash>`)
    - `decisionHash`: Indexed on-chain decision hash
    - `blockNumber`: Execution block number
    - `blockTime`: Execution timestamp
  - `payees`: Cross-referenced to render verified human-readable payee labels
- **States**:
  - `LOADING`: Skeleton loader
  - `EMPTY_QUEUE`: "Queue empty · No actions waiting"
  - `EMPTY_FEED`: "No actions executed yet."
  - `STOP_DIALOG`: Confirmation dialog before guardian pause; displays "not wired in monitor build" upon confirmation
  - `COUNTDOWN_ACTIVE`: Live ticking countdown to `readyAt` (`Ready in mm:ss`)
  - `COUNTDOWN_READY`: Ready indicator once delay period elapses

---

## 3. Bills & Vendors
- **Component**: `app.fusion.wallet.ui.bills.BillsScreen`
- **WalletState fields rendered**:
  - `status`, `refreshing`, `error`, `source`
  - `buckets.owed`: Total declared bills owed
  - `payees`:
    - `id`: Payee registry ID
    - `address`: Recipient Ethereum address
    - `label`: Vendor business name / label
    - `labelHash`: Preimage commitment
    - `status`: `ACTIVE`, `COOLING`, `CANCELLED`
    - `activeAt`: Chain timestamp when cooling period ends
    - `sanctioned`: Strict boolean check against `TestSanctionsList` (shows `SANCTIONED (TEST LIST)`)
  - `obligations`:
    - `id`: Bill commitment ID
    - `payeeId`: Matching payee reference
    - `amount`: Bill amount in USDC
    - `dueAt`: Unix timestamp due date
    - `paid`: Strict boolean paid marker read from contract `isPaid`
- **States**:
  - `VENDORS_TAB`: List of registered payees
  - `BILLS_TAB`: List of committed obligations
  - `COOLING_VENDOR`: Displays amber `COOLING` status, live chain-time countdown (`Usable in mm:ss`), and §17 warning banner:
    *"Warning: New bank details. Cooling period active until [time] to prevent Business Email Compromise (BEC) and unauthorized payee substitution. The agent cannot pay this vendor until cooling completes."*
  - `ACTIVE_VENDOR`: Green `ACTIVE` verified badge
  - `OVERDUE_BILL`: Red `OVERDUE` badge if `dueAt < now` and unpaid
  - `PAID_BILL`: Green `PAID` badge
  - `SCHEDULED_BILL`: Amber `SCHEDULED` badge
  - `EMPTY_VENDORS`: "No registered vendors."
  - `EMPTY_BILLS`: "No bills recorded."

---

## 4. Safety
- **Component**: `app.fusion.wallet.ui.proof.ProofScreen`
- **WalletState fields rendered**:
  - `status`, `refreshing`, `error`, `source`
  - `evidence`: Filtered for `it.kind.contains("REVERT")` to populate blocked attack attempts
    - `id`: Evidence row ID
    - `kind`: Revert error / invariant reason (e.g. `INTENTIONAL_REVERT`)
    - `txHash`: Reverted transaction hash with direct BaseScan link
    - `blockNumber`: On-chain block where revert was confirmed
    - `blockTime`: Timestamp
    - `decisionHash`: Decision hash associated with revert
    - `explorerUrl`: BaseScan URL
- **Honesty Guarantees**:
  - Never displays hard-coded counts; all metrics are dynamic from evidence logs.
  - ASR and scorecard derived strictly from observed evidence logs; unmeasured metrics labelled "not measured" (§18.3, §17.10).
  - Emergency Unwind card with confirmation dialog; displays "Emergency unwind: not wired in monitor build" upon confirmation.
- **States**:
  - `NO_REVERTS`: "No revert events observed on this deployment."
  - `REVERTS_OBSERVED`: Dynamic count of blocked attacks with full per-revert explorer cards.
  - `UNWIND_DIALOG`: Confirmation dialog explaining Safe withdrawal and agent shutdown.

---

## 5. Judge Mode
- **Component**: `app.fusion.wallet.ui.judge.JudgeScreen`
- **WalletState fields rendered**:
  - `status`, `refreshing`, `error`, `source`
  - `treasury`: Safe multi-sig and USDC asset addresses
  - `module`: KavachiModule contract address
  - `evidence`: Full unfiltered evidence ledger
    - `id`: Evidence ID
    - `kind`: Event name / error kind
    - `txHash`: Transaction hash
    - `blockNumber`: Block number
    - `blockTime`: Timestamp
    - `decisionHash`: Anchor decision commitment
    - `explorerUrl`: Direct BaseScan link
- **Special Panels**:
  - **Contract Verification Panel**: All 6 core BaseSepolia deployment contracts (`Safe`, `Module`, `Vault A`, `Vault B`, `Asset`, `SanctionsList`) with one-tap copy and BaseScan links.
  - **Proof Explanation**: Each evidence row maps its kind to an explicit description of what invariant it proves.
  - **Honest Disclosures (§21.2)**: Upfront statements declaring that contracts are competition prototypes, yields are testnet/simulated, sanctions are test list, and keys are software-held.
- **States**:
  - `LOADING`: Skeleton indicator
  - `READY`: Full interactive evidence audit ledger
  - `EMPTY`: "No evidence logs found on chain."
