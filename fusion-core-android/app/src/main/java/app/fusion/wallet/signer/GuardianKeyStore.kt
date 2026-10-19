package app.fusion.wallet.signer

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No plaintext key is persisted. noBackupFilesDir also excludes device-to-device transfer. */
internal class GuardianKeyStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "signer/guardian.enc").apply { parentFile!!.mkdirs() })
    fun isImported(): Boolean = file.baseFile.exists()

    fun importOnce(key: ByteArray) {
        try {
            check(!isImported()) { "Guardian already imported; clear app data to replace it" }
            require(GuardianTransaction.address(key).equals(GuardianTransaction.GUARDIAN, true)) { "Key does not match the deployed testnet guardian" }
            val wrapped = KeyEnvelope.encrypt(wrappingKey(), key)
            val stream = file.startWrite()
            try { stream.write(wrapped); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        } finally { key.fill(0) }
    }

    fun <T> withKey(block: (ByteArray) -> T): T {
        check(isImported()) { "Import the Base Sepolia guardian using the debug import screen first" }
        val key = KeyEnvelope.decrypt(wrappingKey(), file.readFully())
        try { return block(key) } finally { key.fill(0) }
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(!isImported()) { "Guardian wrapping key unavailable; re-import after clearing app data" }
        fun generate(strongBox: Boolean): SecretKey {
            val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true)
            if (Build.VERSION.SDK_INT >= 28) builder.setIsStrongBoxBacked(strongBox)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(builder.build()); generateKey()
            }
        }
        return if (Build.VERSION.SDK_INT >= 28) {
            try { generate(true) } catch (_: StrongBoxUnavailableException) { generate(false) }
        } else generate(false)
    }
    private companion object { const val ALIAS = "fusion.guardian.wrap.v1" }
}

internal object KeyEnvelope {
    private val aad = "fusion.guardian:84532:v1".toByteArray()
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        check(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
    }
    fun decrypt(key: SecretKey, envelope: ByteArray): ByteArray {
        require(envelope.size == 61 && envelope[0] == 1.toByte()) { "Invalid guardian envelope" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        return cipher.doFinal(envelope.copyOfRange(13, envelope.size))
    }
}
