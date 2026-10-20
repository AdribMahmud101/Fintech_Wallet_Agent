package app.fusion.wallet.advisor

/** Authenticated encryption under a key the app cannot export (Android Keystore on device). */
interface SecretBox {
    /** Returns (iv, ciphertext). */
    fun seal(plain: ByteArray): Pair<ByteArray, ByteArray>
    fun open(iv: ByteArray, ciphertext: ByteArray): ByteArray
}

/** Private, app-only byte storage (SharedPreferences on device). */
interface ByteStore {
    fun get(key: String): ByteArray?
    fun put(values: Map<String, ByteArray>)
    fun clear()
    fun contains(key: String): Boolean
}

/**
 * Holds one secret encrypted at rest. Plaintext exists only in memory while it is used; it is never
 * written to a file, log, intent or the egress payload. Undecryptable data reads as "no key".
 */
class EncryptedSecretStore(private val store: ByteStore, private val box: SecretBox) {
    fun save(secret: String) {
        val plain = secret.toByteArray(Charsets.UTF_8)
        try {
            val (iv, ct) = box.seal(plain)
            store.put(mapOf(K_IV to iv, K_CT to ct))
        } finally {
            plain.fill(0)
        }
    }

    fun clear() = store.clear()

    fun has(): Boolean = store.contains(K_CT)

    fun read(): String? {
        val iv = store.get(K_IV) ?: return null
        val ct = store.get(K_CT) ?: return null
        return try {
            String(box.open(iv, ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null // Keystore key invalidated or data corrupt: behave as "no key", never crash
        }
    }

    companion object {
        const val K_IV = "iv"
        const val K_CT = "ct"
    }
}

/** What a pasted DeepInfra key may look like. Accepts a pasted `DEEPINFRA_API_KEY=...` env line. */
object ApiKeyFormat {
    private val KEY = Regex("^[A-Za-z0-9._-]{16,256}$")

    fun normalize(raw: String): String? {
        val line = raw.trim().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val value = line.removePrefix("export ").substringAfter("DEEPINFRA_API_KEY=", line).trim().trim('"', '\'')
        return value.takeIf { KEY.matches(it) }
    }
}
