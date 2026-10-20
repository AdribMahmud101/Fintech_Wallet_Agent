package app.fusion.wallet.ui.owner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

class OwnerTransactionTest {
    private val recipient = "0x52908400098527886E0F7030069857D2E4169EE7"
    private val token = "0x0000000000000000000000000000000000001234"

    @Test fun encodesExactErc20TransferCalldata() {
        val tx = OwnerTransactionEncoder.transfer(token, recipient, BigInteger("1000000"))
        assertEquals(token, tx.to)
        assertEquals("0", tx.value)
        assertEquals("CALL", tx.operation)
        assertEquals(
            "0xa9059cbb00000000000000000000000052908400098527886e0f7030069857d2e4169ee7" +
                "00000000000000000000000000000000000000000000000000000000000f4240",
            tx.data,
        )
        assertEquals("{\"to\":\"$token\",\"value\":\"0\",\"data\":\"${tx.data}\",\"operation\":\"CALL\"}", tx.json())
    }

    @Test fun requiresChecksummedAddressAndPositiveSixDecimalAmount() {
        assertEquals(recipient, OwnerTransactionEncoder.checksummedAddress(recipient))
        assertNull(OwnerTransactionEncoder.checksummedAddress(recipient.lowercase()))
        assertNull(parseUsdcAmount("0"))
        assertNull(parseUsdcAmount("1.0000001"))
        assertEquals(BigInteger("1234500"), parseUsdcAmount("1.2345"))
        assertNotNull(parseUsdcAmount("1"))
    }

    @Test fun ownerActionSourcesDoNotDependOnSigningOrAgentCode() {
        val paths = listOf(
            "src/main/java/app/fusion/wallet/ui/owner/OwnerActionSheets.kt",
            "src/main/java/app/fusion/wallet/ui/owner/OwnerTransaction.kt",
        )
        val source = paths.joinToString("\n") { File(it).readText() }
        listOf("app.fusion.wallet.signer", "app.fusion.wallet.agent", "AgentRuntime", "SessionSigner", "Planner", "SessionKey")
            .forEach { forbidden -> assertFalse("Owner action UI references $forbidden", source.contains(forbidden, ignoreCase = true)) }
        assertTrue(source.contains("OwnerTransactionEncoder.transfer"))
    }
}
