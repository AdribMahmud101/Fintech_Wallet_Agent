package app.fusion.wallet.agent

import app.fusion.wallet.chain.Abi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

/** Synthetic ABI fixtures only; never claimed as on-chain authorization. */
class OwnerAllocationMandateTest {
    private class Fixture {
        fun address(n: Int) = "0x" + n.toString(16).padStart(2, '0').repeat(20)
        val safe=address(0x11); val module=address(0x22); val vault=address(0x44); val owner=address(0x77); val approver=address(0x88)
        val hash="0x"+"aa".repeat(32); val blockHash="0x"+"bb".repeat(32)
        val session="0x"+"cc".repeat(32); val policy="0x"+"dd".repeat(32)
        var amount=10_000_000L; var threshold=2; var chain="0x14a34"; var canonical=true; var success=true; var target=owner; var value=0L
        val manifest=JSONObject().put("chainId",84532).put("rpcUrl","https://unit.invalid").put("deploymentBlock",0)
            .put("explorerBaseUrl","https://unit.invalid").put("safe",safe).put("module",module).put("asset",address(0x33))
            .put("vaultA",vault).put("vaultB",address(0x55)).put("sanctionsList",address(0x66))
            .put("roles",JSONObject().put("owner",owner)).put("owners",JSONArray().put(owner).put(approver))
        val snapshot=JSONObject().put("blockNumber",2).put("blockTimestamp",100)
            .put("policy",JSONObject().put("microMaxRaw","10000000"))
            .put("moduleState",JSONObject().put("sessionId",session).put("policyHash",policy).put("sessionExpiresAt",2000)
                .put("actionNonce","10").put("reservedRaw","0"))
        fun word(n: Long)=Abi.uintWord(BigInteger.valueOf(n))
        fun input(): String {
            val payload="b0c49d37"+word(84532)+Abi.addressWord(safe)+Abi.addressWord(module)+Abi.addressWord(vault)+session.drop(2)+policy.drop(2)+word(amount)+word(10)+word(1000)
            return "0x6a761202"+Abi.addressWord(target)+word(value)+word(320)+word(0).repeat(6)+word(672)+word(292)+payload.padEnd(640,'0')+word(0)
        }
        fun rpc(method: String, params: JSONArray): Any = when(method) {
            "eth_chainId" -> chain
            "eth_getCode" -> "0x"
            "eth_getBlockByNumber" -> JSONObject().put("hash",if(canonical) blockHash else session)
            "eth_getTransactionByHash" -> JSONObject().put("hash",hash).put("from",owner).put("to",safe).put("chainId","0x14a34")
                .put("blockHash",blockHash).put("input",input())
            "eth_getTransactionReceipt" -> JSONObject().put("transactionHash",hash).put("from",owner).put("to",safe)
                .put("status",if(success) "0x1" else "0x0").put("blockNumber","0x1").put("blockHash",blockHash)
                .put("logs",JSONArray().put(JSONObject().put("address",safe).put("topics",JSONArray().put("0x442e715f626346e8c54381002da614f62bee8d27386535b2521ec8540898556e"))))
            "eth_call" -> if(params.getJSONObject(0).getString("data")=="0xe75235b8") "0x"+word(threshold.toLong())
                else "0x"+word(32)+word(2)+Abi.addressWord(owner)+Abi.addressWord(approver)
            else -> error("Unexpected unit RPC")
        }
        fun verify()=OwnerAllocationMandate.verify(manifest,snapshot,hash,::rpc)
    }
    @Test fun batchedProofPreservesChecksAndUsesTwoReadBatches() {
        val f=Fixture(); var batches=0
        fun verify()=OwnerAllocationMandate.verify(f.manifest,f.snapshot,f.hash,f::rpc,{ calls ->
            batches++; calls.map { (method,args) -> f.rpc(method,args) }
        })
        assertEquals("DEPOSIT",verify()!!.getString("phase")); assertEquals(2,batches)
        f.threshold=1; assertTrue(runCatching { verify() }.isFailure)
    }
    @Test fun permitsExactlyTwoNoncePhasesThenExpires() {
        val f=Fixture(); assertEquals("DEPOSIT",f.verify()!!.getString("phase"))
        f.snapshot.getJSONObject("moduleState").put("actionNonce","11"); assertEquals("WITHDRAW",f.verify()!!.getString("phase"))
        f.snapshot.getJSONObject("moduleState").put("actionNonce","12"); assertNull(f.verify())
        f.snapshot.getJSONObject("moduleState").put("actionNonce","10"); f.snapshot.put("blockTimestamp",1000); assertNull(f.verify())
    }
    @Test fun rejectsMoreThanTenUsdcAndMissingOwnerThreshold() {
        val f=Fixture(); f.amount=10_000_001; assertTrue(runCatching { f.verify() }.isFailure)
        f.amount=10_000_000; f.threshold=1; assertTrue(runCatching { f.verify() }.isFailure)
    }
    @Test fun rejectsWrongChainReorgAndFailedSafeTransaction() {
        val f=Fixture(); f.chain="0x1"; assertTrue(runCatching { f.verify() }.isFailure)
        f.chain="0x14a34"; f.canonical=false; assertTrue(runCatching { f.verify() }.isFailure)
        f.canonical=true; f.success=false; assertTrue(runCatching { f.verify() }.isFailure)
    }
    @Test fun rejectsDifferentAuthorityTargetOrEthTransfer() {
        val f=Fixture(); f.snapshot.getJSONObject("moduleState").put("policyHash",f.session); assertTrue(runCatching { f.verify() }.isFailure)
        f.snapshot.getJSONObject("moduleState").put("policyHash",f.policy); f.target=f.module; assertTrue(runCatching { f.verify() }.isFailure)
        f.target=f.owner; f.value=1; assertTrue(runCatching { f.verify() }.isFailure)
    }
}
