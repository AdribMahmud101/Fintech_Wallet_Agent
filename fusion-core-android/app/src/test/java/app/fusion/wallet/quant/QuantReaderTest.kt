package app.fusion.wallet.quant

import app.fusion.wallet.chain.*
import app.fusion.wallet.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class QuantReaderTest {
    private val vault="0x1111111111111111111111111111111111111111"
    private val aToken="0x2222222222222222222222222222222222222222"
    private val variable="0x3333333333333333333333333333333333333333"
    private val hash="0x"+"ab".repeat(32)
    private fun abi(vararg values: BigInteger)="0x"+values.joinToString("") { Abi.uintWord(it) }
    private fun word(value: Long)=BigInteger.valueOf(value)
    @Test fun reserveUsesUnderlyingBalanceAndBothDebtTokensAndHistoricalState() {
        val tags=mutableListOf<String>()
        val rpc=RpcTransport { method, args -> when(method) {
            "eth_getBlockByNumber" -> {
                val past=args.getString(0)==ChainReader.tag(6800)
                JSONObject().put("number",ChainReader.tag(if(past)6800 else 50000)).put("hash",hash).put("timestamp",ChainReader.tag(if(past)13600 else 100000))
            }
            "eth_call" -> {
                tags+=args.getString(1)
                val tx=args.getJSONObject(0);val data=tx.getString("data")
                when {
                    data==QuantReader.RESERVE_CALL -> abi(*Array(15) { i -> when(i) {
                        2 -> BigInteger("2000000000000000000000000")
                        8 -> BigInteger(aToken.drop(2),16)
                        10 -> BigInteger(variable.drop(2),16)
                        else -> BigInteger.ZERO
                    } })
                    data.startsWith(Views.BALANCE) -> { assertTrue(data.endsWith(Abi.addressWord(aToken)));abi(word(150)) }
                    data==QuantReader.TOTAL_SUPPLY -> { assertEquals(variable,tx.getString("to"));abi(word(850)) }
                    data==QuantReader.INCOME_CALL -> abi(BigInteger("1000000000000000000000000000"))
                    data=="0x313ce567" -> abi(word(8))
                    data=="0xfeaf968c" -> abi(word(1),word(200000000000),word(99999),word(100000),word(1))
                    data.startsWith(Views.PREVIEW_DEPOSIT) -> abi(word(100))
                    data.startsWith(Views.CONVERT_TO_ASSETS) -> abi(word(9999999))
                    else -> error("Unexpected view $data")
                }
            }
            else -> error("Unexpected RPC $method")
        } }
        val input=QuantReader(ChainReader(rpc)) { error("Primary history should work") }
            .read(vault,BlockHeader(50000,hash,100000),gasRunway(BigInteger.ZERO,listOf(word(3),word(4))),word(10000000))
        assertEquals("4",input.getString("medianGasWei")) // ceil 3.5 wei
        val reserve=input.getJSONObject("reserve")
        assertEquals("150",reserve.getString("availableRaw"));assertEquals("850",reserve.getString("variableDebtRaw"));assertEquals("0",reserve.getString("stableDebtRaw"))
        assertEquals(6800,reserve.getInt("pastBlock"));assertEquals("1",input.getString("depositSlippageRaw"))
        assertEquals(setOf(ChainReader.tag(50000),ChainReader.tag(6800)),tags.toSet())
    }
    @Test fun missingArchiveTriesPublicnodeAndRetainsSpotMeasurement() {
        var fallbackAttempted=false
        val rpc=RpcTransport { method,args ->
            when(method) {
                "eth_getBlockByNumber" -> {
                    if(args.getString(0)==ChainReader.tag(6800)) error("historical state unavailable")
                    JSONObject().put("number",ChainReader.tag(50000)).put("hash",hash).put("timestamp",ChainReader.tag(100000))
                }
                "eth_call" -> {
                    val data=args.getJSONObject(0).getString("data")
                    when {
                        data==QuantReader.RESERVE_CALL -> abi(*Array(15) { i -> when(i) { 2 -> word(42);8 -> BigInteger(aToken.drop(2),16);else -> BigInteger.ZERO } })
                        data.startsWith(Views.BALANCE) -> abi(word(150))
                        data==QuantReader.INCOME_CALL -> abi(BigInteger.TEN.pow(27))
                        else -> error("optional read unavailable")
                    }
                }
                else -> error("Unexpected method")
            }
        }
        val input=QuantReader(ChainReader(rpc)) { fallbackAttempted=true;error("publicnode history unavailable") }
            .read(vault,BlockHeader(50000,hash,100000),null,word(10000000))
        assertTrue(fallbackAttempted);assertEquals("42",input.getJSONObject("reserve").getString("liquidityRateRay"))
        assertFalse(input.getJSONObject("reserve").has("pastIndex"));assertFalse(input.has("medianGasWei"));assertFalse(input.has("oracle"))
    }
    @Test fun displayDoesNotTurnUnknownInputsIntoZero() {
        val text=quantSummary(JSONObject().put("apyLabel","UNMEASURED"))
        assertTrue(text.contains("Utilization not measured"));assertTrue(text.contains("withdrawable now not measured"));assertTrue(text.contains("break-even not measured"))
        assertEquals(word(10),nextQuantSize(word(100),word(10),word(50),word(100)))
        assertEquals(BigInteger.ZERO,nextQuantSize(word(100),word(10),word(101),word(100)))
    }
}
