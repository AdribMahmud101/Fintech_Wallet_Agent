package app.fusion.wallet.agent

import android.content.Context
import android.os.Build
import android.security.keystore.*
import android.util.AtomicFile
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import java.io.File
import java.math.BigInteger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal object AgentCrypto {
    fun bytes(hex: String): ByteArray {
        val s = hex.removePrefix("0x"); require(s.length % 2 == 0 && s.all { it.digitToIntOrNull(16) != null })
        return ByteArray(s.length / 2) { s.substring(it*2, it*2+2).toInt(16).toByte() }
    }
    fun hex(bytes: ByteArray): String = "0x" + bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun hash(bytes: ByteArray): ByteArray = ByteArray(32).also { KeccakDigest(256).apply { update(bytes, 0, bytes.size); doFinal(it, 0) } }
    fun hashHex(bytes: ByteArray) = hex(hash(bytes))
    fun address(key: ByteArray): String {
        val curve = CustomNamedCurves.getByName("secp256k1")
        val d = BigInteger(1, key); require(key.size == 32 && d.signum() > 0 && d < curve.n)
        val pub = curve.g.multiply(d).normalize().getEncoded(false)
        return hex(hash(pub.copyOfRange(1, pub.size)).takeLast(20).toByteArray())
    }
}

/** Separate session envelope and alias; never reads or replaces guardian material. */
internal class SessionKeys(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "agent/session.enc").apply { parentFile!!.mkdirs() })
    private val expected = JSONObject(context.assets.open("deployments/84532.json").bufferedReader().use { it.readText() }).getJSONObject("roles").getString("session")
    fun imported(): Boolean = file.baseFile.isFile
    @Synchronized fun importOnce(key: ByteArray) {
        try {
            check(!imported()) { "SESSION_ALREADY_IMPORTED" }
            check(AgentCrypto.address(key).equals(expected, true)) { "SESSION_KEY_MISMATCH" }
            val encrypted = SessionEnvelope.encrypt(wrappingKey(), key)
            val stream = file.startWrite()
            try { stream.write(encrypted); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        } finally { key.fill(0) }
    }
    fun <T> withKey(activeAddress: String, block: (ByteArray) -> T): T {
        check(imported()) { "SESSION_KEY_NOT_IMPORTED" }
        check(activeAddress.equals(expected, true)) { "SESSION_KEY_MISMATCH" }
        val key = SessionEnvelope.decrypt(wrappingKey(), file.readFully())
        try {
            check(AgentCrypto.address(key).equals(activeAddress, true)) { "SESSION_KEY_MISMATCH" }
            return block(key)
        } finally { key.fill(0) }
    }
    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(!imported()) { "SESSION_WRAPPING_KEY_UNAVAILABLE" }
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true)
            if (Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(strongBox)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec.build()); generateKey() }
        }
        return if (Build.VERSION.SDK_INT >= 28) try { generate(true) } catch (_: StrongBoxUnavailableException) { generate(false) } else generate(false)
    }
    private companion object { const val ALIAS = "fusion.session.wrap.v1" }
}
internal object SessionEnvelope {
    private val aad = "fusion.session:84532:v1".toByteArray()
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key); c.updateAAD(aad)
        check(c.iv.size == 12)
        return byteArrayOf(1) + c.iv + c.doFinal(plaintext)
    }
    fun decrypt(key: SecretKey, envelope: ByteArray): ByteArray {
        require(envelope.size == 61 && envelope[0] == 1.toByte())
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(1, 13))); c.updateAAD(aad)
        return c.doFinal(envelope.copyOfRange(13, envelope.size))
    }
}
