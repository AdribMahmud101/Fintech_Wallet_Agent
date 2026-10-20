package app.fusion.wallet.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionSigningPolicyTest {
    private fun golden() = JSONObject(javaClass.getResource("/golden_app_transaction.json")!!.readText()).let {
        it.put("data", it.getString("input"))
    }
    private fun policy() = SessionSigningPolicy(golden().getString("to"), 84532)
    private fun refused(reason: SignerRefusal, change: (JSONObject) -> Unit) {
        val tx = golden().also(change)
        val error = runCatching { policy().validate(tx) }.exceptionOrNull()
        assertTrue(error is SignerRefused)
        assertEquals(reason, (error as SignerRefused).reason)
        assertEquals("SignerRefused::$reason", safeSessionError(error))
    }
    @Test fun goldenAppAndQueueRemainAllowed() {
        val tx = golden()
        policy().validate(tx)
        SessionEntryPoints.selectors.forEach { selector ->
            policy().validate(tx.put("data", selector + tx.getString("input").drop(10)))
        }
    }
    @Test fun a12PreparedTransferIsRefused() = refused(SignerRefusal.NotModuleCall) {
        it.put("chainId", 31337).put("to", "0x" + "ee".repeat(20))
            .put("data", "0xa9059cbb" + "0".repeat(24) + "ee".repeat(20) + "0".repeat(56) + "05e69ec0")
    }
    @Test fun wrongModule() = refused(SignerRefusal.NotModuleCall) { it.put("to", "0x" + "ee".repeat(20)) }
    @Test fun nonzeroValue() = refused(SignerRefusal.NonzeroValue) { it.put("value", "0x1") }
    @Test fun wrongChain() = refused(SignerRefusal.WrongChain) { it.put("chainId", 1) }
    @Test fun erc20AtModule() = refused(SignerRefusal.UnsupportedSelector) { it.put("data", "0xa9059cbb") }
    @Test fun ownerAdminSelector() = refused(SignerRefusal.UnsupportedSelector) { it.put("data", "0x" + AgentCrypto.hashHex("resume(uint256)".toByteArray()).drop(2).take(8) + "0".repeat(64)) }
    @Test fun emptyData() = refused(SignerRefusal.MalformedCalldata) { it.put("data", "0x") }
    @Test fun invalidHex() = refused(SignerRefusal.MalformedCalldata) { it.put("data", "0xzzzzzzzz") }
}
