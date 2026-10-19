package app.fusion.wallet.signer

import java.io.File
import java.math.BigInteger
import javax.crypto.KeyGenerator
import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

class GuardianTransactionTest {
    private fun testKey() = ByteArray(32).apply { this[31] = 1 }

    @Test fun `EIP1559 bytes match independent cast mktx vector`() {
        val signed = GuardianTransaction.sign(testKey(), BigInteger.valueOf(7), BigInteger.valueOf(1_000_000),
            BigInteger.valueOf(2_000_000_000), BigInteger.valueOf(150_000))
        // Foundry cast 1.8.5, offline (no RPC and no broadcast):
        // cast mktx MODULE 'pause(bytes32)' $(cast keccak APP_GUARDIAN_STOP)
        // --private-key <scalar 1, public test vector ONLY> --chain 84532 --nonce 7
        // --gas-limit 150000 --gas-price 2000000000 --priority-gas-price 1000000
        val expected = "0x02f89183014a3407830f42408477359400830249f094a560ec10259657e9badd297e68286d2838cd5a8f80a4ed56531a" +
            "29784e05ee52435c3d8aa5e9501ee7ba570ab9e3024af1bf377d8b0677387eefc080a0" +
            "b91a57eb0e4c2c7ba520d5c2818a5c02fae6ad962f1d74e4d8aee2a9cacef6c4a0" +
            "32c1a945f592f8f4e0fed2fb4598b32107c1da1e2bd64d61ed3ece4d44cac958"
        assertEquals(expected, GuardianTransaction.hex(signed))
        assertEquals("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf", GuardianTransaction.address(testKey()))
    }

    @Test fun `zero or oversized scalar and negative nonce cannot sign`() {
        for (key in listOf(ByteArray(32), ByteArray(32) { 255.toByte() }, ByteArray(31))) {
            assertThrows(IllegalArgumentException::class.java) { GuardianTransaction.address(key) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuardianTransaction.sign(testKey(), BigInteger.valueOf(-1), BigInteger.ONE, BigInteger.TEN, BigInteger.valueOf(100000))
        }
    }

    @Test fun `zero quantities and recovery parity one match second cast vector`() {
        // Same offline cast command, --nonce 0 --gas-limit 100000
        // --gas-price 1000000 --priority-gas-price 0; public scalar 1.
        val signed = GuardianTransaction.sign(testKey(), BigInteger.ZERO, BigInteger.ZERO,
            BigInteger.valueOf(1_000_000), BigInteger.valueOf(100_000))
        val expected = "0x02f88d83014a348080830f4240830186a094a560ec10259657e9badd297e68286d2838cd5a8f80a4ed56531a" +
            "29784e05ee52435c3d8aa5e9501ee7ba570ab9e3024af1bf377d8b0677387eefc001a0" +
            "0f0043ffbe5a87c3b483455caa5cd0371833d9a21e8217cf1db4ff0348acec40a0" +
            "3605aaf8e6abc3d31cf803b15ed6155bd17d90b2cf69ec901efbbcc84dd5d919"
        assertEquals(expected, GuardianTransaction.hex(signed))
    }

    @Test fun `AES GCM envelopes authenticate ciphertext and randomize nonce`() {
        val wrapping = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val first = KeyEnvelope.encrypt(wrapping, testKey())
        val second = KeyEnvelope.encrypt(wrapping, testKey())
        assertFalse(first.contentEquals(second))
        assertFalse(first.toList().windowed(32).any { it == testKey().toList() })
        assertArrayEquals(testKey(), KeyEnvelope.decrypt(wrapping, first))
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { KeyEnvelope.decrypt(wrapping, first) }
        assertThrows(IllegalArgumentException::class.java) { KeyEnvelope.decrypt(wrapping, byteArrayOf()) }
    }

    @Test fun `untrusted exception data cannot escape in signer errors`() {
        val secret = "0x" + "a1".repeat(32)
        val errors = listOf(Exception(secret), IllegalArgumentException("Bad key $secret"), Exception("RPC response: $secret"))
        errors.forEach { assertFalse(safeSignerError(it).contains(secret)) }
        assertEquals("Guardian needs Base Sepolia ETH for gas", safeSignerError(Exception("Guardian needs Base Sepolia ETH for gas")))
        val hash = "0x" + "0f".repeat(32)
        val publicError = "Guardian pause reverted: $hash"
        assertEquals(publicError, safeSignerError(Exception(publicError)))
        assertFalse(safeSignerError(Exception("$publicError key=$secret")).contains(secret))
    }

    @Test fun `signer production and import code contain no logging sinks or key extras`() {
        val roots = listOf(File("src/main/java/app/fusion/wallet/signer"), File("src/debug/java/app/fusion/wallet/signer"))
        val files = roots.flatMap { root -> check(root.isDirectory); root.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue(files.isNotEmpty())
        val logSink = Regex("(?:\\b(?:Log|Timber|System\\.(?:out|err))\\s*\\.|\\b(?:print|println|printf|printStackTrace)\\s*\\()")
        files.forEach { file -> assertFalse("Logging sink in ${file.name}", logSink.containsMatchIn(file.readText())) }
        val import = File("src/debug/java/app/fusion/wallet/signer/GuardianImportActivity.kt").readText()
        assertFalse(import.contains("getStringExtra"))
        assertFalse(import.contains("setResult("))
    }
}
