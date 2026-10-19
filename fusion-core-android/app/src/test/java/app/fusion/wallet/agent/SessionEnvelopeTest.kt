package app.fusion.wallet.agent

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.AEADBadTagException

class SessionEnvelopeTest {
    private fun aes() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun roundTripUsesRandomIvAndFixedEnvelope() {
        val key=aes(); val secret=ByteArray(32).also { SecureRandom().nextBytes(it) }
        val first=SessionEnvelope.encrypt(key,secret); val second=SessionEnvelope.encrypt(key,secret)
        assertEquals(61,first.size); assertFalse(first.contentEquals(second)); assertArrayEquals(secret,SessionEnvelope.decrypt(key,first))
        secret.fill(0)
    }
    @Test fun tamperingOrWrongWrappingKeyIsRejected() {
        val key=aes(); val secret=ByteArray(32).also { SecureRandom().nextBytes(it) }; val encrypted=SessionEnvelope.encrypt(key,secret)
        val tampered=encrypted.clone().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { SessionEnvelope.decrypt(key,tampered) }
        assertThrows(AEADBadTagException::class.java) { SessionEnvelope.decrypt(aes(),encrypted) }; secret.fill(0)
    }
    @Test fun signerErrorsNeverEchoUntrustedText() {
        val secretLike="private input must not be returned"
        assertEquals("SESSION_SIGNER_FAILED",safeSessionError(IllegalStateException(secretLike)))
        assertEquals("SESSION_LOCK_REQUIRED",safeSessionError(IllegalStateException("SESSION_LOCK_REQUIRED")))
    }
}
