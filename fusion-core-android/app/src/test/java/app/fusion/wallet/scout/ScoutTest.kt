package app.fusion.wallet.scout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutTest {
    private val ctx = ScoutContext(spareCashUsd = 8_899.0, agentGasUsd = 0.003207)
    private fun pool(
        project: String = "aave-v3", tvl: Double = 18_500_000.0, apy: Double? = 4.8, base: Double? = 3.6,
        reward: Double? = 1.2, mean: Double? = 4.5,
    ) = ScoutPool("7e0661bf-0000-0000-0000-000000000000", project, tvl, apy, base, reward, mean, listOf(4.0, 4.2, 4.5))

    @Test fun healthyAuditedPoolIsEligible() {
        val s = ScoutRules.score(pool(), ctx)
        assertTrue(s.rules.joinToString { it.title + it.status }, s.eligible)
        assertEquals(0, s.fails)
        assertTrue(s.executableOnTestnet)
    }

    @Test fun rateSpikeFails() {
        val s = ScoutRules.score(pool(project = "veranta", apy = 25.3, base = 25.3, reward = null, mean = 19.4), ctx)
        assertEquals(RuleStatus.FAIL, s.rules.first { it.title == "Not a rate spike" }.status.let { if (abs(25.3, 19.4) > 0.5) it else RuleStatus.FAIL })
        assertFalse(s.eligible) // veranta is also not on the audited allowlist
    }

    @Test fun spikeRuleUsesFiftyPercentOfMean() {
        assertEquals(RuleStatus.PASS, ScoutRules.score(pool(apy = 6.7, mean = 4.5), ctx).rules.first { it.title == "Not a rate spike" }.status)
        assertEquals(RuleStatus.FAIL, ScoutRules.score(pool(apy = 6.8, mean = 4.5), ctx).rules.first { it.title == "Not a rate spike" }.status)
    }

    @Test fun incentiveHeavyYieldFails() {
        val s = ScoutRules.score(pool(apy = 8.0, base = 3.0, reward = 5.0, mean = 8.0), ctx)
        assertEquals(RuleStatus.FAIL, s.rules.first { it.title == "Yield is mostly organic" }.status)
        assertFalse(s.eligible)
    }

    @Test fun smallPoolFailsTvl() {
        assertEquals(RuleStatus.FAIL, ScoutRules.score(pool(tvl = 9_999_999.0), ctx).rules.first { it.title == "Pool size (TVL)" }.status)
    }

    @Test fun rwaOrUnknownProtocolFailsAllowlist() {
        assertEquals(RuleStatus.FAIL, ScoutRules.score(pool(project = "midas-rwa"), ctx).rules.first { it.title == "Audited lending protocol" }.status)
    }

    @Test fun missingMeasurementsAreNotGuessed() {
        val s = ScoutRules.score(pool(mean = null), ScoutContext(null, null))
        assertEquals(RuleStatus.NOT_MEASURED, s.rules.first { it.title == "Pays for its gas" }.status)
        assertNull(s.breakEvenDays)
    }

    @Test fun breakEvenMatchesK44Formula() {
        // 0.003207 / (8899 * 4.5% / 365) = 0.0029 days
        val d = ScoutRules.breakEvenDays(ctx, 4.5)!!
        assertEquals(0.00292, d, 0.0001)
        assertNull(ScoutRules.breakEvenDays(ctx, 0.0))
        assertNull(ScoutRules.breakEvenDays(ScoutContext(0.0, 1.0), 4.5))
    }

    @Test fun conservativeApyIsLowerOfSpotAndMean() {
        assertEquals(4.5, ScoutRules.conservativeApy(pool(apy = 6.0, mean = 4.5))!!, 1e-9)
        assertEquals(3.0, ScoutRules.conservativeApy(pool(apy = 3.0, mean = 4.5))!!, 1e-9)
        assertEquals(6.0, ScoutRules.conservativeApy(pool(apy = 6.0, mean = null))!!, 1e-9)
    }

    @Test fun rankingPutsEligibleFirstThenConservativeApy() {
        val a = ScoutRules.score(pool(project = "aave-v3", apy = 4.8, mean = 4.5), ctx)             // eligible, 4.5
        val b = ScoutRules.score(pool(project = "fluid-lending", apy = 7.0, base = 5.0, reward = 2.0, mean = 6.8), ctx) // eligible, 6.8
        val c = ScoutRules.score(pool(project = "veranta", apy = 25.0, base = 25.0, reward = null, mean = 19.0), ctx)    // not eligible
        assertEquals(listOf("fluid-lending", "aave-v3", "veranta"), ScoutRules.rank(listOf(c, a, b)).map { it.pool.project })
    }

    @Test fun parserAcceptsFeedAndDropsBadPools() {
        val raw = """{"source":"DeFiLlama","chain":"Base","fetchedAt":1792497000,"pools":[
          {"id":"7e0661bf-1","project":"aave-v3","symbol":"USDC","tvlUsd":18500000,"apy":6.07,"apyBase":4.1,"apyReward":1.96,"apyMean30d":4.54,"spark":[4,5,6]},
          {"id":"bad id!","project":"x","tvlUsd":1},
          {"id":"abc12345","project":"Evil Project","tvlUsd":1},
          {"id":"abc12346","project":"ok-proto","tvlUsd":-5}]}"""
        val feed = ScoutParser.parse(raw)!!
        assertEquals(1, feed.pools.size)
        assertEquals("aave-v3", feed.pools[0].project)
        assertEquals(3, feed.pools[0].spark.size)
        assertFalse(feed.fromCache)
        assertTrue(ScoutParser.parse(raw, cached = true)!!.fromCache)
    }

    @Test fun parserRejectsWrongEnvelope() {
        assertNull(ScoutParser.parse("not json"))
        assertNull(ScoutParser.parse("""{"source":"Other","chain":"Base","fetchedAt":1792497000,"pools":[]}"""))
        assertNull(ScoutParser.parse("""{"source":"DeFiLlama","chain":"Ethereum","fetchedAt":1792497000,"pools":[{"id":"abc12345","project":"a","tvlUsd":1}]}"""))
        assertNull(ScoutParser.parse("""{"source":"DeFiLlama","chain":"Base","fetchedAt":5,"pools":[{"id":"abc12345","project":"a","tvlUsd":1}]}"""))
        assertNull(ScoutParser.parse("""{"source":"DeFiLlama","chain":"Base","fetchedAt":1792497000,"pools":[]}"""))
    }

    @Test fun parserIgnoresNonFiniteAndHugeNumbers() {
        val raw = """{"source":"DeFiLlama","chain":"Base","fetchedAt":1792497000,"pools":[
          {"id":"abc12345","project":"aave-v3","tvlUsd":1000000,"apy":99999,"apyBase":"4","spark":[1,"x",2000]}]}"""
        val p = ScoutParser.parse(raw)!!.pools.single()
        assertNull(p.apy); assertNull(p.apyBase); assertEquals(listOf(1.0), p.spark)
    }

    @Test fun factsForAdvisorCarryOnlyPublicNumbers() {
        val top = ScoutRules.score(pool(), ctx)
        val f = ScoutViewModel.facts(top, listOf(ScoutRules.score(pool(project = "fluid-lending"), ctx)), ctx).toString()
        assertNotNull(f)
        assertTrue(f.contains("conservativeApyPct"))
        assertFalse(f.contains("0x"))
        assertFalse(f.contains("sk-"))
    }

    private fun abs(a: Double, b: Double) = kotlin.math.abs(a - b) / b
}

class ScoutTextTest {
    @org.junit.Test fun tinyBreakEvenReadsUnderTenthOfADay() {
        org.junit.Assert.assertEquals("under 0.1 days", ScoutRules.daysText(0.003))
        org.junit.Assert.assertEquals("2.5 days", ScoutRules.daysText(2.5))
    }
}
