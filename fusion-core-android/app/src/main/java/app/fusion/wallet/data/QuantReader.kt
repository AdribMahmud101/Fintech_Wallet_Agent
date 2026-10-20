package app.fusion.wallet.data

import app.fusion.wallet.chain.*
import org.json.JSONObject
import java.math.BigInteger
import java.math.RoundingMode

/** Public, block-pinned measurements only. Native fusion-core owns the decision formulas. */
class QuantReader(private val reader: ChainReader, private val history: () -> ChainReader = {
    ChainReader(JsonRpcClient("https://base-sepolia-rpc.publicnode.com"))
}) {
    fun read(vault: String, block: BlockHeader, gas: AgentGas?, size: BigInteger): JSONObject {
        val inputs = JSONObject().put("blockNumber", block.number).put("vault", vault)
        gas?.medianCostWei?.let { inputs.put("medianGasWei", it.setScale(0, RoundingMode.CEILING).toPlainString()) }
        runCatching {
            val reserve = reader.call(ChartEvidenceReader.AAVE_POOL, RESERVE_CALL, block.number)
            require(reserve.size >= 15)
            val aToken = reserve.address(8)
            require(aToken != ContractDecoders.ZERO_ADDRESS)
            val available = reader.call(ChartEvidenceReader.AAVE_USDC, Views.BALANCE + Abi.addressWord(aToken), block.number).requireSize(1).uint(0)
            fun debt(address: String): BigInteger = if (address == ContractDecoders.ZERO_ADDRESS) BigInteger.ZERO
                else reader.call(address, TOTAL_SUPPLY, block.number).requireSize(1).uint(0)
            val variable = debt(reserve.address(10))
            val stable = debt(reserve.address(9))
            val index = reader.call(ChartEvidenceReader.AAVE_POOL, INCOME_CALL, block.number).requireSize(1).uint(0)
            require(index.signum() > 0)
            val measured = JSONObject().put("availableRaw", available.toString()).put("variableDebtRaw", variable.toString())
                .put("stableDebtRaw", stable.toString()).put("liquidityRateRay", reserve.uint(2).toString()).put("liquidityIndex", index.toString())
            // A historical state call, not an inferred previous rate. Try the live
            // transport first, then publicnode, binding the fallback to this head.
            val past = runCatching { pastIndex(reader, block) }.getOrElse {
                runCatching {
                    val archive = history()
                    require(archive.chainId() == 84532L && archive.block(ChainReader.tag(block.number)).hash.equals(block.hash, true))
                    pastIndex(archive, block)
                }.getOrNull()
            }
            past?.let { (header, value) -> measured.put("pastIndex", value.toString()).put("pastTimestamp", header.timestamp).put("pastBlock", header.number) }
            inputs.put("reserve", measured)
        }
        runCatching {
            val decimals = reader.call(ETH_USD_FEED, "0x313ce567", block.number).requireSize(1).int(0)
            val round = reader.call(ETH_USD_FEED, "0xfeaf968c", block.number).requireSize(5)
            // int256 must be positive; a negative ABI word is not a price.
            require(round.uint(1).bitLength() < 256)
            inputs.put("oracle", JSONObject().put("feed", ETH_USD_FEED).put("decimals", decimals)
                .put("answer", round.uint(1).toString()).put("roundId", round.uint(0).toString())
                .put("answeredInRound", round.uint(4).toString()).put("updatedAt", round.long(3)))
        }
        runCatching {
            val shares = reader.call(vault, Views.PREVIEW_DEPOSIT + Abi.uintWord(size), block.number).requireSize(1).uint(0)
            val assets = reader.call(vault, Views.CONVERT_TO_ASSETS + Abi.uintWord(shares), block.number).requireSize(1).uint(0)
            inputs.put("quotedSizeRaw", size.toString()).put("depositSlippageRaw", (size-assets).max(BigInteger.ZERO).toString())
        }
        require(reader.block(ChainReader.tag(block.number)).hash.equals(block.hash, true)) { "Quant snapshot reorganized" }
        return inputs
    }
    private fun pastIndex(source: ChainReader, block: BlockHeader): Pair<BlockHeader, BigInteger> {
        require(block.number > 43_200)
        val past = source.block(ChainReader.tag(block.number - 43_200)) // Base's 2s block interval
        require(block.timestamp-past.timestamp in 23*3600L..25*3600L)
        val value = source.call(ChartEvidenceReader.AAVE_POOL, INCOME_CALL, past.number).requireSize(1).uint(0)
        require(value.signum() > 0)
        return past to value
    }
    companion object {
        // Chainlink's Base Sepolia ETH/USD proxy; heartbeat 1,200s; 8 decimals.
        const val ETH_USD_FEED = "0x4aDC67696bA383F43DD60A9e78F2C97Fbbfc7cb1"
        const val TOTAL_SUPPLY = "0x18160ddd"
        val RESERVE_CALL = ChartEvidenceReader.RESERVE_DATA + Abi.addressWord(ChartEvidenceReader.AAVE_USDC)
        val INCOME_CALL = "0xd15e0053" + Abi.addressWord(ChartEvidenceReader.AAVE_USDC)
    }
}

/** Next instant deposit size, without increasing any owner-set limit. */
fun nextQuantSize(surplus: BigInteger, cap: BigInteger, exposure: BigInteger, limit: BigInteger): BigInteger =
    surplus.min(cap).min((limit-exposure).max(BigInteger.ZERO)).max(BigInteger.ZERO)

/** Explain an excluded yield move; callers first establish that no eligible move remains. */
fun economicHoldReason(report: JSONObject): String? {
    val candidates = report.optJSONArray("candidates") ?: return null
    val rejected = (0 until candidates.length()).map { candidates.getJSONObject(it) }
        .firstOrNull { it.optString("status") == "UNPROFITABLE_AFTER_GAS" } ?: return null
    val size = rejected.optString("sizeRaw").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        ?.movePointLeft(6)?.stripTrailingZeros()?.toPlainString() ?: return null
    val days = rejected.optString("breakEvenDaysHundredths").toBigDecimalOrNull()
        ?.movePointLeft(2)?.setScale(0, RoundingMode.CEILING)?.toPlainString()
    return if (days != null) "$size USDC breaks even in $days days; outside the 14-day window"
        else "$size USDC has no profitable break-even after gas"
}

/** Plain report text shared by Home, Preview and the persisted Autopilot checks. */
fun quantSummary(report: JSONObject): String {
    fun value(key: String): String? = if (report.isNull(key)) null else report.optString(key).takeIf { it.isNotBlank() }
    val utilization = value("utilizationBps")?.toBigDecimal()?.movePointLeft(2)?.stripTrailingZeros()?.toPlainString()
    val liquidity = value("withdrawableNowRaw")?.toBigDecimal()?.movePointLeft(6)?.stripTrailingZeros()?.toPlainString()
    val next = report.optJSONArray("candidates")?.optJSONObject(0)
    val days = next?.takeUnless { it.isNull("breakEvenDaysHundredths") }?.optString("breakEvenDaysHundredths")
        ?.toBigDecimalOrNull()?.movePointLeft(2)?.stripTrailingZeros()?.toPlainString()
    val status = next?.optString("status") ?: "UNMEASURED"
    return "Utilization ${utilization?.let { "$it%" } ?: "not measured"} · withdrawable now ${liquidity?.let { "$it USDC" } ?: "not measured"}" +
        "\n${report.optString("apyLabel", "UNMEASURED")} · next move: ${status}" +
        (days?.let { " · break-even $it days" } ?: if (status == "UNPROFITABLE_AFTER_GAS") " · no finite break-even" else " · break-even not measured")
}
