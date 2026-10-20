package app.fusion.wallet.advisor

import app.fusion.wallet.chain.Abi
import app.fusion.wallet.data.PermissionTier
import app.fusion.wallet.data.WalletState
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/** The editable fields of IKavachiModule.Policy, in raw units. Guardian and vault addresses are not editable here. */
data class PolicyFields(
    val permissionTier: PermissionTier,
    val operatingBufferRaw: BigInteger,
    val microMaxRaw: BigInteger,
    val delayedMaxRaw: BigInteger,
    val hardMaxRaw: BigInteger,
    val dailyLimitRaw: BigInteger,
    val delaySeconds: Long,
    val maxShareSlippageBps: Long,
    val payeeDelaySeconds: Long,
    val payEarlyWindowSeconds: Long,
    val vaultCapARaw: BigInteger,
    val vaultCapBRaw: BigInteger,
)

data class VaultSlot(val slot: String, val name: String, val address: String)

/** The on-chain policy the proposal is diffed against, read from the live snapshot. */
data class CurrentPolicy(
    val chainId: Long,
    val safe: String,
    val module: String,
    val guardian: String,
    val policyEpoch: BigInteger,
    val vaultA: VaultSlot,
    val vaultB: VaultSlot,
    val fields: PolicyFields,
) {
    /** What the model sees: field values and vault names, no addresses. */
    fun toModelJson(): JSONObject = JSONObject()
        .put("permissionTier", fields.permissionTier.name)
        .put("operatingBufferUsdc", usdc(fields.operatingBufferRaw))
        .put("microMaxUsdc", usdc(fields.microMaxRaw))
        .put("delayedMaxUsdc", usdc(fields.delayedMaxRaw))
        .put("hardMaxUsdc", usdc(fields.hardMaxRaw))
        .put("dailyLimitUsdc", usdc(fields.dailyLimitRaw))
        .put("delaySeconds", fields.delaySeconds)
        .put("maxShareSlippageBps", fields.maxShareSlippageBps)
        .put("payeeDelaySeconds", fields.payeeDelaySeconds)
        .put("payEarlyWindowSeconds", fields.payEarlyWindowSeconds)
        .put("vaultCapsUsdc", JSONObject().put("A", usdc(fields.vaultCapARaw)).put("B", usdc(fields.vaultCapBRaw)))
        .put("vaultNames", JSONObject().put("A", AdvisorEgress.redact(vaultA.name).take(40)).put("B", AdvisorEgress.redact(vaultB.name).take(40)))

    companion object {
        /** Null unless the snapshot is ready and holds a configured module with exactly two vault limits. */
        fun from(state: WalletState): CurrentPolicy? {
            if (state.status != app.fusion.wallet.data.LoadStatus.READY) return null
            val module = state.module?.takeIf { it.configured } ?: return null
            val safe = state.treasury?.safeAddress ?: return null
            if (state.vaults.size != 2 || state.vaults[0].address.equals(state.vaults[1].address, true)) return null
            val (a, b) = state.vaults
            val p = module.policy
            return CurrentPolicy(
                state.chainId, safe, module.address, p.guardian, module.policyEpoch,
                VaultSlot("A", a.name, a.address), VaultSlot("B", b.name, b.address),
                PolicyFields(
                    p.permissionTier, p.operatingBuffer.raw, p.microMax.raw, p.delayedMax.raw, p.hardMax.raw, p.dailyLimit.raw,
                    p.delaySeconds, p.maxShareSlippageBps.toLong(), p.payeeDelaySeconds, p.payEarlyWindowSeconds,
                    a.exposureLimit.raw, b.exposureLimit.raw,
                ),
            )
        }
    }
}

enum class Effect { LOOSER, TIGHTER, NEUTRAL }

data class PolicyDiffRow(val field: String, val label: String, val current: String, val proposed: String, val effect: Effect)

/** The exact Safe transaction the 2-of-2 owners must sign. The app never signs it. */
data class SafeTransaction(val chainId: Long, val safe: String, val to: String, val value: String, val data: String, val operation: Int) {
    fun toJson(): String = JSONObject()
        .put("chainId", chainId)
        .put("safe", safe)
        .put("to", to)
        .put("value", value)
        .put("data", data)
        .put("operation", operation)
        .put("function", PolicyCalldata.SIGNATURE)
        .put("signers", "Safe 2-of-2 owners")
        .put("note", PolicyProposal.OWNER_LABEL)
        .toString(2)
}

data class PolicyProposal(
    val proposed: PolicyFields,
    val diff: List<PolicyDiffRow>,
    val warnings: List<String>,
    val rationale: String,
    val safeTx: SafeTransaction,
) {
    companion object {
        const val OWNER_LABEL = "Needs owner signatures — the AI cannot apply this"
    }
}

/**
 * ABI encoder for KavachiModule.configurePolicy(Policy). Its body is byte-identical to the
 * getPolicy() return data, so [app.fusion.wallet.chain.ContractDecoders.policy] decodes it.
 */
object PolicyCalldata {
    const val SIGNATURE =
        "configurePolicy((uint8,uint256,uint256,uint256,uint256,uint256,uint32,uint16,address,(address,uint256)[],uint32,uint32))"
    const val SELECTOR = "0x7b6d8b05"
    private const val HEAD_WORDS = 12

    /** [vaults] are (address, cap) pairs; the contract requires them sorted by address. */
    fun encode(f: PolicyFields, guardian: String, vaults: List<Pair<String, BigInteger>>): String {
        val sorted = vaults.sortedBy { BigInteger(it.first.removePrefix("0x"), 16) }
        val w = StringBuilder()
        fun u(v: BigInteger) { w.append(Abi.uintWord(v)) }
        fun n(v: Long) = u(BigInteger.valueOf(v))
        n(0x20L) // offset of the dynamic Policy tuple
        n(f.permissionTier.ordinal.toLong())
        u(f.operatingBufferRaw); u(f.microMaxRaw); u(f.delayedMaxRaw); u(f.hardMaxRaw); u(f.dailyLimitRaw)
        n(f.delaySeconds); n(f.maxShareSlippageBps)
        w.append(Abi.addressWord(guardian))
        n((HEAD_WORDS * 32).toLong()) // vaults array offset, relative to the tuple start
        n(f.payeeDelaySeconds); n(f.payEarlyWindowSeconds)
        n(sorted.size.toLong())
        sorted.forEach { (address, cap) -> w.append(Abi.addressWord(address)); u(cap) }
        return SELECTOR + w
    }
}

/**
 * Deterministic gate for the model's policy JSON:
 * `{"schema": "fw.advisor.policy.v1", "changes": {...}, "rationale": string}`.
 * Unknown keys, wrong types, out-of-range values, a policy KavachiPolicyLib would revert, or no
 * change at all reject the whole proposal. Only then is calldata built.
 */
object PolicyProposalValidator {
    const val SCHEMA = "fw.advisor.policy.v1"
    const val MAX_RATIONALE = 400
    /** Sanity cap far above any demo treasury; amounts above it are a model error, not a goal. */
    val MAX_AMOUNT_RAW: BigInteger = BigInteger.valueOf(10_000_000L).multiply(BigInteger.TEN.pow(6))

    private val ROOT_KEYS = setOf("schema", "changes", "rationale")
    private val AMOUNTS = linkedMapOf(
        "operatingBufferUsdc" to "Operating buffer",
        "microMaxUsdc" to "Instant limit per move",
        "delayedMaxUsdc" to "Delayed limit per move",
        "hardMaxUsdc" to "Hard limit per move",
        "dailyLimitUsdc" to "Daily limit",
    )
    private val INTS = linkedMapOf(
        "delaySeconds" to "Queue delay (s)",
        "maxShareSlippageBps" to "Max share slippage (bps)",
        "payeeDelaySeconds" to "New payee cooling (s)",
        "payEarlyWindowSeconds" to "Pay-early window (s)",
    )
    private val CHANGE_KEYS = AMOUNTS.keys + INTS.keys + setOf("permissionTier", "vaultCapsUsdc")

    fun validate(raw: String, current: CurrentPolicy): Checked<PolicyProposal> {
        val root = try { JSONObject(raw) } catch (e: JSONException) { return Checked.Rejected("not json") }
        val reasons = mutableListOf<String>()
        root.keys().asSequence().filter { it !in ROOT_KEYS }.forEach { reasons += "unknown key: ${it.take(30)}" }
        if (root.opt("schema") != SCHEMA) reasons += "schema must be $SCHEMA"
        val rationale = when (val r = root.opt("rationale")) {
            null -> ""
            is String -> { AdvisorText.problem(r, MAX_RATIONALE)?.let { reasons += "rationale: $it" }; AdvisorText.clean(r) }
            else -> { reasons += "rationale must be text"; "" }
        }
        val changes = root.opt("changes") as? JSONObject
        if (changes == null) {
            reasons += "changes must be an object"
            return Checked.Rejected(reasons)
        }
        changes.keys().asSequence().filter { it !in CHANGE_KEYS }.forEach { reasons += "unknown change: ${it.take(30)}" }

        val c = current.fields
        fun amount(key: String, now: BigInteger): BigInteger =
            if (!changes.has(key)) now else parseAmount(changes.opt(key))?.also { if (it > MAX_AMOUNT_RAW) reasons += "$key above sanity cap" }
                ?: now.also { reasons += "$key must be a USDC amount with at most 6 decimals" }
        fun integer(key: String, now: Long): Long =
            if (!changes.has(key)) now else parseInt(changes.opt(key)) ?: now.also { reasons += "$key must be a whole number" }

        val tier = if (!changes.has("permissionTier")) c.permissionTier else
            PermissionTier.entries.firstOrNull { it.name == changes.opt("permissionTier") } ?: c.permissionTier.also { reasons += "permissionTier must be one of ${PermissionTier.entries.joinToString("|")}" }
        var capA = c.vaultCapARaw
        var capB = c.vaultCapBRaw
        if (changes.has("vaultCapsUsdc")) {
            val caps = changes.opt("vaultCapsUsdc") as? JSONObject
            if (caps == null) reasons += "vaultCapsUsdc must be an object" else {
                caps.keys().asSequence().filter { it != "A" && it != "B" }.forEach { reasons += "unknown vault slot: ${it.take(10)}" }
                if (caps.has("A")) capA = amount2(caps.opt("A"), "vaultCapsUsdc.A", reasons) ?: capA
                if (caps.has("B")) capB = amount2(caps.opt("B"), "vaultCapsUsdc.B", reasons) ?: capB
            }
        }
        val p = PolicyFields(
            tier,
            amount("operatingBufferUsdc", c.operatingBufferRaw), amount("microMaxUsdc", c.microMaxRaw),
            amount("delayedMaxUsdc", c.delayedMaxRaw), amount("hardMaxUsdc", c.hardMaxRaw), amount("dailyLimitUsdc", c.dailyLimitRaw),
            integer("delaySeconds", c.delaySeconds), integer("maxShareSlippageBps", c.maxShareSlippageBps),
            integer("payeeDelaySeconds", c.payeeDelaySeconds), integer("payEarlyWindowSeconds", c.payEarlyWindowSeconds),
            capA, capB,
        )
        reasons += contractRules(p)
        if (reasons.isEmpty() && p == c) reasons += "no change from the current on-chain policy"
        if (reasons.isNotEmpty()) return Checked.Rejected(reasons.distinct())

        val diff = diff(current, p)
        val warnings = buildList {
            if (diff.any { it.effect == Effect.LOOSER }) add("Loosens at least one limit. Review each row marked looser.")
            if (p.permissionTier.ordinal > c.permissionTier.ordinal) add("Raises the agent's permission tier.")
            add("Applying resets the module: it pauses, ends the agent session and bumps the policy epoch. Owners must start a new session and resume.")
        }
        val data = PolicyCalldata.encode(p, current.guardian, listOf(current.vaultA.address to p.vaultCapARaw, current.vaultB.address to p.vaultCapBRaw))
        val tx = SafeTransaction(current.chainId, current.safe, current.module, "0", data, 0)
        return Checked.Valid(PolicyProposal(p, diff, warnings, rationale, tx))
    }

    /** Mirrors KavachiPolicyLib.validateAndHash; anything it would revert is rejected here first. */
    fun contractRules(p: PolicyFields): List<String> = buildList {
        if (p.microMaxRaw.signum() <= 0) add("instant limit must be above 0")
        if (p.delayedMaxRaw < p.microMaxRaw) add("delayed limit must be at least the instant limit")
        if (p.hardMaxRaw < p.delayedMaxRaw) add("hard limit must be at least the delayed limit")
        if (p.dailyLimitRaw < p.hardMaxRaw) add("daily limit must be at least the hard limit")
        if (p.delaySeconds !in 120..900) add("queue delay must be 120–900 s")
        if (p.maxShareSlippageBps !in 0..1000) add("slippage must be 0–1000 bps")
        if (p.payeeDelaySeconds !in 3600..604800) add("new payee cooling must be 3600–604800 s")
        if (p.payEarlyWindowSeconds !in 0..2592000) add("pay-early window must be 0–2592000 s")
        if (p.vaultCapARaw.signum() <= 0 || p.vaultCapBRaw.signum() <= 0) add("vault caps must be above 0")
    }

    private fun diff(current: CurrentPolicy, p: PolicyFields): List<PolicyDiffRow> {
        val c = current.fields
        val rows = mutableListOf<PolicyDiffRow>()
        fun money(key: String, label: String, a: BigInteger, b: BigInteger, higherIsLooser: Boolean) {
            if (a != b) rows += PolicyDiffRow(key, label, usdc(a), usdc(b), if ((b > a) == higherIsLooser) Effect.LOOSER else Effect.TIGHTER)
        }
        fun number(key: String, label: String, a: Long, b: Long, higherIsLooser: Boolean) {
            if (a != b) rows += PolicyDiffRow(key, label, a.toString(), b.toString(), if ((b > a) == higherIsLooser) Effect.LOOSER else Effect.TIGHTER)
        }
        if (c.permissionTier != p.permissionTier) rows += PolicyDiffRow("permissionTier", "Permission tier", c.permissionTier.name, p.permissionTier.name,
            if (p.permissionTier.ordinal > c.permissionTier.ordinal) Effect.LOOSER else Effect.TIGHTER)
        money("operatingBufferUsdc", AMOUNTS.getValue("operatingBufferUsdc"), c.operatingBufferRaw, p.operatingBufferRaw, higherIsLooser = false)
        money("microMaxUsdc", AMOUNTS.getValue("microMaxUsdc"), c.microMaxRaw, p.microMaxRaw, true)
        money("delayedMaxUsdc", AMOUNTS.getValue("delayedMaxUsdc"), c.delayedMaxRaw, p.delayedMaxRaw, true)
        money("hardMaxUsdc", AMOUNTS.getValue("hardMaxUsdc"), c.hardMaxRaw, p.hardMaxRaw, true)
        money("dailyLimitUsdc", AMOUNTS.getValue("dailyLimitUsdc"), c.dailyLimitRaw, p.dailyLimitRaw, true)
        number("delaySeconds", INTS.getValue("delaySeconds"), c.delaySeconds, p.delaySeconds, higherIsLooser = false)
        number("maxShareSlippageBps", INTS.getValue("maxShareSlippageBps"), c.maxShareSlippageBps, p.maxShareSlippageBps, true)
        number("payeeDelaySeconds", INTS.getValue("payeeDelaySeconds"), c.payeeDelaySeconds, p.payeeDelaySeconds, higherIsLooser = false)
        number("payEarlyWindowSeconds", INTS.getValue("payEarlyWindowSeconds"), c.payEarlyWindowSeconds, p.payEarlyWindowSeconds, true)
        money("vaultCapsUsdc.A", "Vault cap · ${current.vaultA.name}", c.vaultCapARaw, p.vaultCapARaw, true)
        money("vaultCapsUsdc.B", "Vault cap · ${current.vaultB.name}", c.vaultCapBRaw, p.vaultCapBRaw, true)
        return rows
    }

    private fun amount2(v: Any?, key: String, reasons: MutableList<String>): BigInteger? {
        val a = parseAmount(v)
        if (a == null) reasons += "$key must be a USDC amount with at most 6 decimals"
        else if (a > MAX_AMOUNT_RAW) reasons += "$key above sanity cap"
        return a
    }

    /** USDC decimal (string or number) → raw 6-decimal units; null if negative, non-finite or over-precise. */
    fun parseAmount(v: Any?): BigInteger? {
        val d = when (v) {
            is String -> v.trim().takeIf { Regex("^[0-9]{1,12}(\\.[0-9]{1,6})?$").matches(it) }?.toBigDecimalOrNull()
            is Int, is Long -> BigDecimal.valueOf((v as Number).toLong())
            is BigDecimal -> v
            is BigInteger -> BigDecimal(v)
            is Double -> if (v.isFinite()) BigDecimal(v.toString()) else null
            else -> null
        } ?: return null
        if (d.signum() < 0) return null
        return try { d.movePointRight(6).toBigIntegerExact() } catch (e: ArithmeticException) { null }
    }

    /** Whole JSON numbers only; strings and fractions are rejected. */
    fun parseInt(v: Any?): Long? = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is BigInteger -> v.takeIf { it.bitLength() < 63 }?.toLong()
        is BigDecimal -> try { v.toBigIntegerExact().takeIf { it.bitLength() < 63 }?.toLong() } catch (e: ArithmeticException) { null }
        is Double -> if (v.isFinite() && v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong() else null
        else -> null
    }

    /** The goal payload sent to the model. */
    fun request(goal: String, current: CurrentPolicy): String = JSONObject()
        .put("schema", "fw.advisor.policygoal.v1")
        .put("goal", AdvisorEgress.redact(goal).take(AdvisorConfig.MAX_GOAL_CHARS))
        .put("current", current.toModelJson())
        .put("rules", JSONArray(contractRuleText))
        .toString()

    private val contractRuleText = listOf(
        "microMaxUsdc > 0", "delayedMaxUsdc >= microMaxUsdc", "hardMaxUsdc >= delayedMaxUsdc", "dailyLimitUsdc >= hardMaxUsdc",
        "120 <= delaySeconds <= 900", "0 <= maxShareSlippageBps <= 1000", "3600 <= payeeDelaySeconds <= 604800",
        "0 <= payEarlyWindowSeconds <= 2592000", "vault caps > 0", "permissionTier in READ_ONLY|OWNER_APPROVED|AUTONOMOUS",
    )
}
