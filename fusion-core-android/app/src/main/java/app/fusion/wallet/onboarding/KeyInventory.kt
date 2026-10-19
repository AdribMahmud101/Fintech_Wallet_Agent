package app.fusion.wallet.onboarding

import android.content.Context
import android.util.AtomicFile
import app.fusion.wallet.agent.SessionKeys
import app.fusion.wallet.signer.GuardianKeyStore
import java.io.File
import org.json.JSONObject

/**
 * Keyless view over what is (or is not) imported into the Android Keystore-backed signer,
 * plus a supported removal. We never read, log or format key material here — only presence.
 *
 * Presence delegates to the real stores ([GuardianKeyStore], [SessionKeys]); the stores own
 * the import path (launched from the debug import screens). Removal deletes the encrypted
 * envelope through [AtomicFile], which clears both the base file and its backup exactly as
 * the stores wrote them. The Keystore wrapping key is intentionally left in place so a later
 * re-import of the same operator key succeeds on the existing hardware key.
 */
object KeyInventory {
    data class Keys(
        val guardianImported: Boolean,
        val sessionImported: Boolean,
        val guardianAddress: String,
        val sessionAddress: String,
    )

    fun read(context: Context): Keys {
        val roles = runCatching {
            JSONObject(
                context.assets.open("deployments/84532.json").bufferedReader().use { it.readText() }
            ).getJSONObject("roles")
        }.getOrNull()
        return Keys(
            guardianImported = guardianImported(context),
            sessionImported = sessionImported(context),
            guardianAddress = roles?.optString("guardian").orEmpty(),
            sessionAddress = roles?.optString("session").orEmpty(),
        )
    }

    fun guardianImported(context: Context): Boolean = GuardianKeyStore(context).isImported()

    fun sessionImported(context: Context): Boolean = SessionKeys(context).imported()

    fun removeGuardian(context: Context) {
        AtomicFile(File(context.noBackupFilesDir, "signer/guardian.enc")).delete()
    }

    fun removeSession(context: Context) {
        AtomicFile(File(context.noBackupFilesDir, "agent/session.enc")).delete()
    }
}
