package app.fusion.wallet.accountant.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the DeepInfra API key AES-GCM encrypted under a non-exportable Android Keystore key.
 * The key never appears in Room, logs, the egress payload, or any file in plaintext.
 */
class KeystoreApiKeyStore(context: Context) : ApiKeyProvider {
    private val prefs = context.applicationContext.getSharedPreferences("accountant_secrets", Context.MODE_PRIVATE)

    fun save(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ct = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(K_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(K_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun hasKey(): Boolean = prefs.contains(K_CT)

    override fun apiKey(): String? {
        val iv = prefs.getString(K_IV, null) ?: return null
        val ct = prefs.getString(K_CT, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null // key invalidated or data corrupt: behave as "no key", never crash the app
        }
    }

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

    private companion object {
        const val ALIAS = "fusion_accountant_deepinfra_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val K_IV = "iv"
        const val K_CT = "ct"
    }
}

/** Keystore store first; a DEBUG-only BuildConfig key (from gitignored local.properties) as fallback. */
class ChainedKeyProvider(private val primary: ApiKeyProvider, private val debugFallback: String) : ApiKeyProvider {
    override fun apiKey(): String? = primary.apiKey()?.takeIf { it.isNotBlank() } ?: debugFallback.takeIf { it.isNotBlank() }
}
