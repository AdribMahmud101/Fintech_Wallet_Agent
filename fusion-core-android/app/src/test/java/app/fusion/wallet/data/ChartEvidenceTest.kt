package app.fusion.wallet.data

import app.fusion.wallet.chain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.ZonedDateTime
import java.util.TimeZone
import java.io.File

class ChartEvidenceTest {
    private fun raw(n: Long)=BigInteger.valueOf(n)*Money.SCALE
    @Test fun runwayUsesObservedWindowAndThresholds() {
        val end=40*86400L
        assertNull(cashRunway(raw(100),raw(10),end-6*3600,end).days)
        assertEquals("Not enough history yet",cashRunway(raw(100),raw(10),end-6*3600,end).status)
        val early = cashRunway(raw(100),raw(10),end-6*86400,end)
        assertEquals("Early estimate · 6 days of data",early.status)
        assertEquals(0, early.days!!.compareTo(java.math.BigDecimal("60.00000000")))
        assertEquals("No outflow recorded",cashRunway(raw(100),raw(0),0,end).status)
        assertEquals("Healthy",cashRunway(raw(200),raw(100),0,end).status)
        assertEquals("Keep an eye on it",cashRunway(raw(70),raw(100),0,end).status)
        assertEquals("Low",cashRunway(raw(69),raw(100),0,end).status)
        assertEquals("Keep an eye on it",cashRunway(raw(200)-BigInteger.ONE,raw(100),0,end).status)
        assertEquals(0,cashRunway(raw(100),raw(100),end-7*86400,end).days!!.compareTo(7.toBigDecimal()))
        assertEquals(0,cashRunway(raw(0),raw(100),0,end).days!!.signum())
    }
    private fun timestamp(s: String)=ZonedDateTime.parse(s).toEpochSecond()
    @Test fun weeklyBucketingUsesLocalMondayAndDst() {
        val zone=TimeZone.getTimeZone("America/New_York")
        val now=timestamp("2026-11-02T12:00:00-05:00[America/New_York]")
        val sunday=timestamp("2026-11-01T23:59:59-05:00[America/New_York]")
        val monday=timestamp("2026-11-02T00:00:00-05:00[America/New_York]")
        val payments=listOf(ChartPayment(sunday,raw(1),"a","a"),ChartPayment(monday,raw(2),"b","b"),ChartPayment(now+1,raw(50),"future","a"))
        val weeks=weeklySpending(payments,now,zone) { it.recipient }
        assertEquals(4,weeks.size)
        assertTrue(weeks[0].totals.isEmpty())
        assertEquals(raw(1),weeks[2].totals["a"])
        assertEquals(raw(2),weeks[3].totals["b"])
        // The fall-back week is 169 hours, rather than a fixed 7*24-hour interval.
        assertEquals(169*3600L,weeks[3].startSeconds-weeks[2].startSeconds)
    }
    @Test fun aaveRayCompoundsToSupplyApy() {
        assertEquals(0,ChartEvidenceReader.supplyApyPercent(BigInteger.ZERO).signum())
        val fivePercentRay=BigInteger.TEN.pow(27)/BigInteger.valueOf(20)
        val apy=ChartEvidenceReader.supplyApyPercent(fivePercentRay)
        assertTrue(apy>"5.12710".toBigDecimal() && apy<"5.12712".toBigDecimal())
    }
    @Test fun frozenAndHeldOutMetricsComeFromEvidence() {
        val metrics=BenchmarkMetrics.parse(File("src/main/res/raw/k28_benchmarks.md").readText())
        assertEquals(listOf(0,9,0,13),metrics.map { it.numerator })
        assertEquals(listOf(30,30,20,20),metrics.map { it.denominator })
    }
    @Test fun decisionMixRequiresRecordedProvenanceAndExcludesDryRuns() {
        fun tx(n: Int)="0x"+n.toString(16).padStart(64,'0')
        val index=TxIndex.parse(JSONArray().apply {
            listOf("APP","AGENT","SCRIPT","OWNER").forEachIndexed { i,source -> put(JSONObject()
                .put("txHash",tx(i+1)).put("source",source).put("kind","DEPOSIT").put("evidenceFile","test")) }
        }.toString())
        val actions=(1..5).map { n->AgentAction("$n",ActionKind.DEPOSIT,Money.ZERO,null,tx(n),"hash$n",1,1) }
        val report=JSONObject().put("source","APP").put("dryRun",false).put("decisionHash","hold-hash")
            .put("passportJson",JSONObject().put("route","HOLD").toString())
        assertEquals(BigInteger.valueOf(2),decisionMix(actions+actions[0],index,report.toString())["DEPOSIT"])
        assertEquals(BigInteger.ONE,decisionMix(actions,index,report.toString())["HOLD"])
        report.put("dryRun",true)
        assertEquals(BigInteger.ZERO,decisionMix(actions,index,report.toString())["HOLD"])
        report.put("dryRun",false).put("source","SCRIPT")
        assertEquals(BigInteger.ZERO,decisionMix(actions,index,report.toString())["HOLD"])
    }
    @Test fun fullIndexOutflowDoesNotDoubleCountBillsOrIncludeVaultDeposits() {
        val d=Deployment.parse(javaClass.classLoader!!.getResource("chain/deployment.json")!!.readText())
        val head=BlockHeader(d.deploymentBlock+2,"0x"+"ab".repeat(32),40*86400L)
        val vendor="0x"+"cc".repeat(20)
        fun hash(n: Int)="0x"+n.toString(16).padStart(64,'0')
        fun transfer(n: Int,to: String,amount: Long)=ChainLog(d.asset,
            listOf(BalanceReconstruction.TRANSFER,"0x"+Abi.addressWord(d.safe),"0x"+Abi.addressWord(to)),
            "0x"+Abi.uintWord(raw(amount)),hash(n),head.number,head.hash,n.toLong())
        val bill=ChainLog(d.module,listOf(ModuleEvents.PAID,hash(100),hash(101),"0x"+Abi.addressWord(vendor)),
            "0x"+listOf(raw(10),BigInteger.ZERO,BigInteger.ONE).joinToString("") { Abi.uintWord(it) },hash(1),head.number,head.hash,100)
        // More than the repository's preview limit: the chart reader must retain all 60 actions.
        val executed=List(60) { i -> ChainLog(d.module,listOf(ModuleEvents.EXECUTED,hash(200+i),hash(300),hash(400+i)),
            "0x"+List(8) { BigInteger.ZERO }.joinToString("") { Abi.uintWord(it) },hash(500+i),head.number,head.hash,200L+i) }
        val older=transfer(1000,vendor,1000).copy(blockNumber=head.number-1,blockHash=hash(9999))
        val oldHeader=BlockHeader(head.number-1,hash(9999),9*86400L)
        val extraSend=transfer(5,vendor,5).copy(txHash=hash(1)) // a second send inside the bill transaction
        val logs=listOf(bill,transfer(1,vendor,10),transfer(2,vendor,20),transfer(3,d.vaultA,99),transfer(4,d.safe,999),extraSend,older) + executed
        var rateRead=false
        val reader=ChainReader(RpcTransport { method,params -> when(method) {
            "eth_getBlockByNumber"->JSONObject().put("number",ChainReader.tag(d.deploymentBlock)).put("hash",hash(999)).put("timestamp",ChainReader.tag(0))
            "eth_call"->{
                assertEquals(head.number,ChainReader.quantity(params.getString(1)))
                assertEquals(ChartEvidenceReader.AAVE_POOL,params.getJSONObject(0).getString("to"))
                assertEquals(ChartEvidenceReader.RESERVE_DATA+Abi.addressWord(ChartEvidenceReader.AAVE_USDC),params.getJSONObject(0).getString("data"))
                rateRead=true
                "0x"+List(15) { i->Abi.uintWord(if (i==2) BigInteger.TEN.pow(27)/BigInteger.valueOf(20) else BigInteger.ZERO) }.joinToString("")
            }
            else->error(method)
        } })
        val evidence=ChartEvidenceReader.read(EventIndex.Snapshot(head.number,head.hash,logs+logs[0],mapOf(head.number to head,oldHeader.number to oldHeader)),d,head,reader,raw(100))
        assertEquals(raw(35),evidence.outflowRaw)
        assertEquals(raw(100),evidence.cashRaw)
        assertEquals(1,evidence.payments.size)
        assertEquals(60,evidence.actions.size)
        assertTrue(rateRead)
        assertNotNull(evidence.aaveApyPercent)
    }

}
