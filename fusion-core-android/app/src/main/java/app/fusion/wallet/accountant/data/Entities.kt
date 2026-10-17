package app.fusion.wallet.accountant.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Embedded accounting store (Room/SQLite) for the Accountant Agent.
 *
 * Rules that apply to every entity here:
 *  - Money is a base-10 STRING of raw integer units ("1500000000" = 1,500 USDC at 6 decimals).
 *    Never Double/Float: the spec forbids floating point in any financial path. Use [Raw] to do math.
 *  - Everything in this file is PRIVATE data and stays on the device. What may leave the device is
 *    decided in `accountant.cloud` and nowhere else; see `DataClass`.
 *  - Chain facts (balances, aToken balances) are snapshots tagged with the block they were read at,
 *    so a later reconcile can tell fresh from stale. Rows are appended, never rewritten in place.
 */

enum class AccountRole { OWNER_TREASURY, AGENT_SESSION, GUARDIAN }
enum class AssetKind { NATIVE, ERC20, ATOKEN, VAULT_SHARE }
enum class LendingProtocol { AAVE_V3, ERC4626_DEMO }
enum class ObligationStatus { DRAFT, ACTIVE, SETTLED, CANCELLED }
enum class LedgerKind { DEPOSIT, WITHDRAW, PAYMENT, INCOME, FEE, TRANSFER }
enum class Direction { IN, OUT }

/** Mirrors the spec's transaction lifecycle; success is only ever CONFIRMED/RECONCILED. */
enum class TxStatus { PLANNED, SIGNED, BROADCAST, UNKNOWN, MINED, CONFIRMED, RECONCILED, FAILED }

/**
 * The only category vocabulary the cloud ever sees. User-chosen labels live in
 * [CategoryEntity.label] (private); this enum is the safe projection.
 */
enum class FlowCategory { PAYROLL, SUPPLIERS, INFRASTRUCTURE, TAX, YIELD, OTHER }

enum class InsightSource { LOCAL, CLOUD }
enum class Severity { INFO, NOTICE, WARNING, CRITICAL }
enum class InsightTopic { LIQUIDITY, RESERVES, CONCENTRATION, YIELD, CASHFLOW, OBLIGATIONS, GENERAL }
enum class EgressStatus { BLOCKED_BY_GUARD, NOT_ENABLED, LIMIT_REACHED, SENT_OK, FAILED, REJECTED_REPLY }

@Entity(tableName = "account", indices = [Index(value = ["chainId", "address"], unique = true)])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Private user label, e.g. "Main treasury". Never sent anywhere. */
    val label: String,
    val role: AccountRole,
    val chainId: Long,
    /** Lower-case 0x address. PRIVATE. */
    val address: String,
    /** Operating buffer B in raw units of the unit asset (spec: protected threshold = R + B). */
    val operatingBufferRaw: String = "0",
    /** Estimated native-gas cost of one agent action (wei). An ESTIMATE used only for the gas-runway display. */
    val estFeePerActionWei: String = "30000000000000",
    val createdAt: Long,
)

@Entity(tableName = "asset", indices = [Index(value = ["chainId", "address"], unique = true)])
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chainId: Long,
    /** Token contract address (lower-case); "native" for the gas token. */
    val address: String,
    val symbol: String,
    val decimals: Int,
    val kind: AssetKind,
    /** Pegged 1:1 to the treasury unit (USDC). Only stable assets count toward buckets. */
    val isStable: Boolean,
    /** For ATOKEN / VAULT_SHARE: the underlying asset this position wraps. */
    val underlyingAssetId: Long? = null,
)

/** Point-in-time wallet balance of one asset, tagged with the block it was read at. */
@Entity(
    tableName = "balance_snapshot",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(AssetEntity::class, ["id"], ["assetId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("accountId", "assetId", "blockNumber"), Index("assetId")],
)
data class BalanceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val assetId: Long,
    val blockNumber: Long,
    val blockHash: String,
    val balanceRaw: String,
    val observedAt: Long,
)

/**
 * A lending / yield position (Aave V3 supply, or the spec's ERC-4626 demo vault).
 *
 * For Aave: [positionAssetId] is the aToken; its balanceOf() already accrues interest, so
 * [suppliedRaw] is the aToken balance at [blockNumber] and [scaledBalanceRaw] is the aToken's
 * scaledBalanceOf (principal in ray-index terms), letting us recompute accrual between reads.
 * [liquidityRateRay] is Aave's `currentLiquidityRate` verbatim (an APR in ray, 1e27 = 100%). It is an
 * APR (simple annual rate), not an APY. [sharesRaw] holds ERC-4626 shares (for the `waUSDC` wrapper)
 * while [suppliedRaw] is always the asset value, so share price drift is visible.
 */
@Entity(
    tableName = "lending_position",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(AssetEntity::class, ["id"], ["underlyingAssetId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(AssetEntity::class, ["id"], ["positionAssetId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("accountId", "protocol", "underlyingAssetId", "blockNumber"), Index("underlyingAssetId"), Index("positionAssetId")],
)
data class LendingPositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val protocol: LendingProtocol,
    /** Pool / vault contract. PRIVATE-ish (public on chain, but never sent to the cloud). */
    val marketAddress: String,
    val underlyingAssetId: Long,
    val positionAssetId: Long,
    val suppliedRaw: String,
    val scaledBalanceRaw: String = "0",
    val sharesRaw: String = "0",
    /** Liquidity index in ray (1e27) at read time; "0" if the protocol has none. */
    val liquidityIndexRay: String = "0",
    val liquidityRateRay: String = "0",
    val borrowedRaw: String = "0",
    val blockNumber: Long,
    val observedAt: Long,
)

@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Private free-form label ("Payroll – Dhaka office"). Never sent anywhere. */
    val label: String,
    /** Safe projection used in any cloud brief. */
    val kind: FlowCategory,
)

@Entity(
    tableName = "obligation",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(AssetEntity::class, ["id"], ["assetId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("accountId", "status", "dueAt"), Index("assetId"), Index("categoryId"), Index(value = ["idHash"], unique = true)],
)
data class ObligationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    /** bytes32 hex committed on-chain (spec M0.4). PRIVATE. */
    val idHash: String,
    /** Counterparty / invoice reference text. PRIVATE. */
    val counterparty: String,
    val assetId: Long,
    val amountRaw: String,
    val dueAt: Long,
    val categoryId: Long?,
    val status: ObligationStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "ledger_entry",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(AssetEntity::class, ["id"], ["assetId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("accountId", "occurredAt"), Index("assetId"), Index("categoryId"), Index("txHash")],
)
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val assetId: Long,
    val kind: LedgerKind,
    val direction: Direction,
    val amountRaw: String,
    val categoryId: Long?,
    val status: TxStatus,
    /** PRIVATE: tx hash and memo never leave the device. */
    val txHash: String?,
    val memo: String?,
    val blockNumber: Long?,
    val occurredAt: Long,
)

/** Derived 3-bucket view, stored so history can be charted without recomputing old state. */
@Entity(
    tableName = "bucket_snapshot",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId", "observedAt")],
)
data class BucketSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val freeRaw: String,
    val reservedRaw: String,
    val deployedRaw: String,
    val sweepableRaw: String,
    val shortfallRaw: String,
    val observedAt: Long,
)

/** Advisory text produced by the Accountant (local rules or the cloud agent). Read-only to the rest of the app. */
@Entity(tableName = "insight", indices = [Index("createdAt")])
data class InsightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: InsightTopic,
    val severity: Severity,
    val text: String,
    val source: InsightSource,
    /** Set when source == CLOUD so the UI can show exactly what was sent for it. */
    val egressId: Long? = null,
    val createdAt: Long,
)

/**
 * Audit row for every attempt to talk to the cloud model, including blocked ones. Stores the exact
 * sanitized payload so the user can inspect precisely what left the phone.
 */
@Entity(tableName = "egress_log", indices = [Index("createdAt")])
data class EgressLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val provider: String,
    val model: String,
    val purpose: String,
    val status: EgressStatus,
    /** Exact JSON sent (post-guard). Empty if blocked before building. */
    val payloadJson: String,
    @ColumnInfo(defaultValue = "")
    val payloadSha256: String,
    val payloadBytes: Int,
    /** Why it was blocked/failed; "" on success. Never contains payload content. */
    val detail: String,
    val httpCode: Int? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val consentVersion: Int,
)

/** Single-row settings table (id is always 1). Cloud use is OFF until the user opts in. */
@Entity(tableName = "privacy_settings")
data class PrivacySettingsEntity(
    @PrimaryKey val id: Int = 1,
    val cloudEnabled: Boolean = false,
    val consentVersion: Int = 0,
    val consentedAt: Long? = null,
    /** Hard cap on a single outbound payload, bytes. */
    val maxPayloadBytes: Int = 4096,
    /** Max cloud calls per rolling 24h. */
    val dailyCallLimit: Int = 20,
    val model: String = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
)

// ---- The AI agent as an accountable participant -------------------------------------------------

enum class AgentStatus { ARMED, PAUSED, HALTED }
enum class Tier { IMMEDIATE, DELAYED, OWNER_REQUIRED }
enum class DecisionKind { SWEEP_TO_VAULT, WITHDRAW_TO_CASH, REBALANCE, HOLD }
enum class DecisionStatus { PROPOSED, QUEUED, EXECUTED, CANCELLED, EXPIRED, BLOCKED, HELD }
enum class CheckState { PASS, FAIL, SKIPPED }
enum class DecisionOrigin { AGENT, ARENA_ATTACK }
enum class ArenaScenario { PROMPT_INJECTION, DEPEG, CAP_BREACH, RESERVE_BREACH, DRIP_DRAIN, QUEUE_BYPASS, CRASH_RECOVERY }
enum class ArenaOutcome { BLOCKED, SAFE_CHOICE, NOT_BLOCKED, ERROR }

/**
 * The owner's signed delegation to the agent: single row (id = 1). Mirrors what the on-chain wallet
 * enforces; the app only DISPLAYS it. [policyHash] is the on-chain mandate fingerprint (public).
 */
@Entity(tableName = "mandate")
data class MandateEntity(
    @PrimaryKey val id: Int = 1,
    val agentLabel: String,
    val status: AgentStatus,
    val immediateMaxRaw: String,
    val delayedMaxRaw: String,
    val hardMaxRaw: String,
    val dailyLimitRaw: String,
    val spentTodayRaw: String,
    val delaySeconds: Int,
    val sessionExpiresAt: Long,
    val policyHash: String,
    val updatedAt: Long,
)

/**
 * One thing the agent proposed, and what every safety layer said about it. Labels are human-safe
 * words ("Cash", "Aave USDC vault"), never addresses.
 */
@Entity(tableName = "agent_decision", indices = [Index("createdAt")])
data class AgentDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val origin: DecisionOrigin,
    val kind: DecisionKind,
    val assetsRaw: String,
    val decimals: Int,
    val symbol: String,
    /** Label KEY ("CASH", "AAVE", "VAULT", "UNKNOWN"), translated by the UI; unknown keys display as-is. */
    val fromLabel: String,
    val toLabel: String,
    val tier: Tier,
    val status: DecisionStatus,
    val readyAt: Long? = null,
    val expiresAt: Long? = null,
    /** Short human reason from the planner. Untrusted text; display only. */
    val rationale: String = "",
    /** e.g. "LFM2.5-230M" or "rules". */
    val chosenBy: String,
    val modelCheck: CheckState,
    val rustCheck: CheckState,
    val signerCheck: CheckState,
    val contractCheck: CheckState,
    /** Which layer stopped it, e.g. "MODEL", "RUST", "SIGNER", "CONTRACT:ReserveViolation". */
    val blockedBy: String? = null,
    val txHash: String? = null,
)

@Entity(tableName = "arena_result", indices = [Index("ranAt")])
data class ArenaResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ranAt: Long,
    val scenario: ArenaScenario,
    val outcome: ArenaOutcome,
    val layer: String,
    val detail: String,
)

// ---- Demo sign-in ------------------------------------------------------------------------------

/** What a signed-in person is allowed to do in the UI. Money limits are enforced by the contract, not by this. */
enum class UserRole { OWNER, MANAGER, AUDITOR }

/** Avatar glyph. INITIALS draws the person's initials; the rest are vector icons (no emoji, no network). */
enum class AvatarIcon { INITIALS, PERSON, BRIEFCASE, SHIELD, STAR, ROCKET, LEAF }

/**
 * A demo account. Passwords are never stored: only a per-user random salt and a PBKDF2 hash.
 * [avatarColor] indexes the fixed palette in the UI (0..5).
 */
@Entity(tableName = "app_user", indices = [Index(value = ["email"], unique = true)])
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val email: String,
    val displayName: String,
    val role: UserRole,
    val company: String,
    val avatarColor: Int,
    val avatarIcon: AvatarIcon,
    val pwSalt: String,
    val pwHash: String,
    val pwIterations: Int,
    val createdAt: Long,
)
