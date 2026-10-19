package app.fusion.wallet.data

import android.content.Context
import java.io.File

/** Durable store for the last good [WalletState], so a cold start paints cached numbers at once. */
interface SnapshotCache {
    fun read(): String?
    fun write(json: String)
}

/** Writes the cached snapshot to a single private file in the app's no-backup storage. */
class FileSnapshotCache(private val file: File) : SnapshotCache {
    override fun read(): String? = try {
        if (file.exists()) file.readText() else null
    } catch (_: Exception) {
        null
    }

    override fun write(json: String) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun forContext(context: Context): FileSnapshotCache =
            FileSnapshotCache(File(context.noBackupFilesDir, "wallet_snapshot.json"))
    }
}
