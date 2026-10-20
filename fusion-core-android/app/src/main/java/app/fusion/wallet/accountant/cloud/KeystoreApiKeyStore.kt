package app.fusion.wallet.accountant.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.fusion.wallet.advisor.ApiKeyFormat
import app.fusion.wallet.advisor.ByteStore
import app.fusion.wallet.advisor.EncryptedSecretStore
import app.fusion.wallet.advisor.SecretBox
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the DeepInfra API key AES-GCM encrypted under a non-exportable Android Keystore key.
 * The key never appears in Room, logs, the egress payload, the APK or any file in plaintext.
 * Shared by the Accountant and the K40 cloud advisor; the user enters it in Settings.
 */
class KeystoreApiKeyStore(context: Context) : ApiKeyProvider {
    private val prefs = context.applicationContext.getSharedPreferences("accountant_secrets", Context.MODE_PRIVATE)
    private val store = EncryptedSecretStore(PrefsByteStore(prefs), KeystoreBox)

    /** False when [apiKey] does not look like a DeepInfra key; nothing is stored then. */
    fun save(apiKey: String): Boolean {
        val key = ApiKeyFormat.normalize(apiKey) ?: return false
        store.save(key)
        return true
    }

    fun clear() = store.clear()

    fun hasKey(): Boolean = store.has()

    override fun apiKey(): String? = store.read()

    /** Same layout as before the K40 refactor (Base64 "iv"/"ct"), so stored keys survive the update. */
    private class PrefsByteStore(private val prefs: android.content.SharedPreferences) : ByteStore {
        override fun get(key: String): ByteArray? = prefs.getString(key, null)?.let {
            try { Base64.decode(it, Base64.NO_WRAP) } catch (e: IllegalArgumentException) { null }
        }
        override fun put(values: Map<String, ByteArray>) {
            val e = prefs.edit()
            values.forEach { (k, v) -> e.putString(k, Base64.encodeToString(v, Base64.NO_WRAP)) }
            e.apply()
        }
        override fun clear() { prefs.edit().clear().apply() }
        override fun contains(key: String) = prefs.contains(key)
    }

    private object KeystoreBox : SecretBox {
        override fun seal(plain: ByteArray): Pair<ByteArray, ByteArray> {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            return cipher.iv to cipher.doFinal(plain)
        }

        override fun open(iv: ByteArray, ciphertext: ByteArray): ByteArray =
            Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv)) }.doFinal(ciphertext)

        private fun secretKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }
    }

    private companion object {
        const val ALIAS = "fusion_accountant_deepinfra_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
