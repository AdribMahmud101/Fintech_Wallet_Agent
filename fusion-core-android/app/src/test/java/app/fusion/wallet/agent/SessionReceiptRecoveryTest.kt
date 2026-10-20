package app.fusion.wallet.agent

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionReceiptRecoveryTest {
    private fun resource(name: String)=JSONObject(javaClass.getResource("/agent/$name")!!.readText())
    @Test fun automaticRecoveryOnlyRequestsReceiptModeAndPreservesPassport() = runTest {
        val report=JSONObject().put("passportJson","original canonical bytes").put("decisionHash","original hash")
        var calls=0
        val result=recoverSessionReceipt(JSONObject().put("error","SESSION_SIGNER_FAILED"),report,{ true },{ original ->
            calls++; assertTrue(original.getBoolean("reconcileOnly"))
            assertEquals("original canonical bytes",original.getString("passportJson"))
            assertEquals("original hash",original.getString("decisionHash"))
            if(calls==1) JSONObject().put("error","PENDING") else JSONObject().put("status",1)
        },{})
        assertEquals(2,calls); assertEquals(1,result.getInt("status"))
        assertTrue(report.getBoolean("reconciledAfterReceiptReadFailure"))
    }
    @Test fun missingJournalNeverRequestsARecoveryOrNewSignature() = runTest {
        val result=recoverSessionReceipt(JSONObject().put("error","SESSION_SIGNER_FAILED"),JSONObject(),{ false },
            { error("Must not request execution without a durable attempt") },{})
        assertTrue(result.has("error"))
    }
    @Test fun realAaveReceiptReconcilesWithoutAnotherSignatureOrBroadcast() = runTest {
        val pending=resource("K38_deposit_confirmation.json")
        val receipt=resource("K38_deposit_receipt.json")
        val tx=resource("K38_deposit_transaction.json")
        var active: JSONObject?=pending; var confirmed=0
        val journal=object: SessionJournal {
            override fun load()=active
            override fun save(pending: JSONObject) { error("Must not create another transaction") }
            override fun clear() { active=null }
            override fun recordConfirmed(result: JSONObject) { confirmed++ }
        }
        val engine=SessionSignerEngine(pending.getString("to"),pending.getString("from"),
            rpc={ method,_ -> when(method) {
                "eth_chainId" -> "0x14a34"
                "eth_getTransactionReceipt" -> receipt
                "eth_getTransactionByHash" -> tx
                "eth_getBlockByNumber" -> JSONObject().put("hash",receipt.getString("blockHash"))
                else -> error("Unexpected RPC $method")
            } }, lease={ "unit" }, verify={ error("Must not verify/sign a new action") },
            sign={ _,_ -> error("Must not access the key") }, journal=journal)
        val report=JSONObject().put("dryRun",false).put("reconcileOnly",true).put("requestId","unit").put("decisionHash",pending.getString("decisionHash"))
        assertEquals(pending.getString("txHash"),engine.execute(report).getString("txHash"))
        assertEquals(1,confirmed); assertNull(active)
        val error=runCatching { engine.execute(report) }.exceptionOrNull()
        assertEquals("NO_PENDING_SESSION_ATTEMPT",error?.message)
    }
}
