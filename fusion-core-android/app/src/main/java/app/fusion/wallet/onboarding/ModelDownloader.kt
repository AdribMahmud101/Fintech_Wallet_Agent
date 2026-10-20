package app.fusion.wallet.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

data class ModelFileSpec(
    val name: String,
    val size: Long,
    val sha256: String,
    val urls: List<String>,
    val fallbackContent: String? = null,
)

data class ModelManifest(
    val modelName: String,
    val format: String,
    val quantization: String,
    val totalSize: Long,
    val files: List<ModelFileSpec>,
) {
    companion object {
        fun parse(json: String): ModelManifest {
            val root = JSONObject(json)
            val filesArray = root.getJSONArray("files")
            val files = List(filesArray.length()) { i ->
                val obj = filesArray.getJSONObject(i)
                val urlsArray = obj.optJSONArray("urls")
                val urls = if (urlsArray != null) {
                    List(urlsArray.length()) { j -> urlsArray.getString(j) }
                } else emptyList()
                ModelFileSpec(
                    name = obj.getString("name"),
                    size = obj.getLong("size"),
                    sha256 = obj.getString("sha256").lowercase(),
                    urls = urls,
                    fallbackContent = obj.optString("fallbackContent").takeIf { it.isNotEmpty() }
                )
            }
            return ModelManifest(
                modelName = root.optString("modelName", "LFM2.5-230M"),
                format = root.optString("format", "MNN"),
                quantization = root.optString("quantization", "4-bit"),
                totalSize = root.optLong("totalSize", files.sumOf { it.size }),
                files = files,
            )
        }

        fun fromAssets(context: Context, assetPath: String = "model_manifest.json"): ModelManifest {
            val json = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            return parse(json)
        }
    }
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class CheckingStorage(val requiredBytes: Long, val availableBytes: Long) : DownloadState()
    data class Downloading(
        val currentFileName: String,
        val fileIndex: Int,
        val totalFiles: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Float,
        val isResuming: Boolean = false,
    ) : DownloadState()
    data class Verifying(val currentFileName: String, val fileIndex: Int, val totalFiles: Int) : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String, val isStorageError: Boolean = false, val isWifiError: Boolean = false) : DownloadState()
}

data class ModelStatus(
    val ready: Boolean,
    val verifiedFiles: List<String>,
    val totalFiles: Int,
    val totalSizeOnDisk: Long,
    val expectedTotalSize: Long,
    val hashes: Map<String, String>,
)

class ModelDownloader(
    val targetDir: File,
    private val isWifiProvider: () -> Boolean = { true },
) {
    companion object {
        fun defaultTargetDir(context: Context): File = File(context.filesDir, "models/lfm_230m")

        fun create(context: Context, targetDir: File = defaultTargetDir(context)): ModelDownloader {
            return ModelDownloader(targetDir) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                // A missing permission or an OEM quirk must never crash setup:
                // if the network type cannot be read, do not block the download.
                if (cm != null) {
                    runCatching {
                        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                    }.getOrDefault(true)
                } else true
            }
        }

        fun sha256(file: File): String {
            if (!file.exists() || !file.isFile) return ""
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(65536)
                var read = 0
                while (input.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    fun isModelReady(manifest: ModelManifest): Boolean {
        if (!targetDir.exists() || !targetDir.isDirectory) return false
        for (spec in manifest.files) {
            val file = File(targetDir, spec.name)
            if (!file.exists() || file.length() != spec.size) return false
            if (sha256(file).lowercase() != spec.sha256.lowercase()) return false
        }
        return true
    }

    fun getStatus(manifest: ModelManifest): ModelStatus {
        val verified = mutableListOf<String>()
        val hashes = mutableMapOf<String, String>()
        var sizeOnDisk = 0L
        for (spec in manifest.files) {
            val file = File(targetDir, spec.name)
            if (file.exists()) {
                sizeOnDisk += file.length()
                val hash = sha256(file)
                hashes[spec.name] = hash
                if (file.length() == spec.size && hash.equals(spec.sha256, ignoreCase = true)) {
                    verified.add(spec.name)
                }
            }
        }
        return ModelStatus(
            ready = verified.size == manifest.files.size,
            verifiedFiles = verified,
            totalFiles = manifest.files.size,
            totalSizeOnDisk = sizeOnDisk,
            expectedTotalSize = manifest.totalSize,
            hashes = hashes,
        )
    }

    fun download(manifest: ModelManifest, wifiOnly: Boolean = false): Flow<DownloadState> = flow {
        emit(DownloadState.Idle)

        // 1. Wi-Fi check
        if (wifiOnly && !isWifiProvider()) {
            emit(DownloadState.Error("Wi-Fi only enabled, but Wi-Fi is not connected.", isWifiError = true))
            return@flow
        }

        // 2. Storage check
        if (!targetDir.exists()) targetDir.mkdirs()
        val availableBytes = targetDir.usableSpace
        val requiredBytes = manifest.totalSize
        emit(DownloadState.CheckingStorage(requiredBytes, availableBytes))
        if (availableBytes < requiredBytes) {
            val reqMb = requiredBytes / 1_000_000
            val availMb = availableBytes / 1_000_000
            emit(DownloadState.Error("Insufficient storage: requires ${reqMb}MB, but only ${availMb}MB available.", isStorageError = true))
            return@flow
        }

        // Calculate initial already downloaded bytes from existing valid files
        val totalBytes = manifest.totalSize
        var completedBytes = 0L
        for (spec in manifest.files) {
            val destFile = File(targetDir, spec.name)
            if (destFile.exists() && destFile.length() == spec.size && sha256(destFile).equals(spec.sha256, ignoreCase = true)) {
                completedBytes += spec.size
            }
        }

        if (completedBytes == totalBytes) {
            emit(DownloadState.Completed)
            return@flow
        }

        // 3. Download files sequentially
        val totalFiles = manifest.files.size
        for ((index, spec) in manifest.files.withIndex()) {
            val destFile = File(targetDir, spec.name)
            if (destFile.exists() && destFile.length() == spec.size && sha256(destFile).equals(spec.sha256, ignoreCase = true)) {
                continue
            }

            val partFile = File(targetDir, "${spec.name}.part")
            var currentPartLength = if (partFile.exists()) partFile.length() else 0L

            if (currentPartLength > spec.size) {
                partFile.delete()
                currentPartLength = 0L
            } else if (currentPartLength == spec.size) {
                if (sha256(partFile).equals(spec.sha256, ignoreCase = true)) {
                    partFile.renameTo(destFile)
                    completedBytes += spec.size
                    continue
                } else {
                    partFile.delete()
                    currentPartLength = 0L
                }
            }

            var downloadSuccess = false
            var lastError: Exception? = null

            // Try configured URLs
            for (urlString in spec.urls) {
                if (downloadSuccess) break
                try {
                    val url = URL(urlString)
                    downloadFileUrl(url, spec, partFile, currentPartLength) { bytesDownloadedNow, isResuming ->
                        val currentTotal = completedBytes + bytesDownloadedNow
                        val percent = (currentTotal.toDouble() / totalBytes * 100.0).toFloat().coerceIn(0f, 100f)
                        emit(DownloadState.Downloading(
                            currentFileName = spec.name,
                            fileIndex = index + 1,
                            totalFiles = totalFiles,
                            downloadedBytes = currentTotal,
                            totalBytes = totalBytes,
                            percent = percent,
                            isResuming = isResuming,
                        ))
                    }

                    // Verify SHA-256
                    emit(DownloadState.Verifying(spec.name, index + 1, totalFiles))
                    val actualHash = sha256(partFile)
                    if (actualHash.equals(spec.sha256, ignoreCase = true)) {
                        partFile.renameTo(destFile)
                        completedBytes += spec.size
                        downloadSuccess = true
                    } else {
                        partFile.delete()
                        currentPartLength = 0L
                        throw IOException("Checksum mismatch for ${spec.name}: expected ${spec.sha256}, got $actualHash")
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }

            // Fallback content if remote URLs failed and fallback content is present
            if (!downloadSuccess && spec.fallbackContent != null) {
                val bytes = spec.fallbackContent.toByteArray(Charsets.UTF_8)
                FileOutputStream(partFile).use { it.write(bytes) }
                emit(DownloadState.Verifying(spec.name, index + 1, totalFiles))
                val actualHash = sha256(partFile)
                if (actualHash.equals(spec.sha256, ignoreCase = true)) {
                    partFile.renameTo(destFile)
                    completedBytes += spec.size
                    downloadSuccess = true
                } else {
                    partFile.delete()
                }
            }

            if (!downloadSuccess) {
                val errorMsg = lastError?.message ?: "Failed to download ${spec.name} from all sources"
                emit(DownloadState.Error(errorMsg))
                return@flow
            }
        }

        emit(DownloadState.Completed)
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadFileUrl(
        url: URL,
        spec: ModelFileSpec,
        partFile: File,
        startOffset: Long,
        onProgress: suspend (bytesDownloadedForFile: Long, isResuming: Boolean) -> Unit,
    ) {
        var currentUrl = url
        var connection: HttpURLConnection? = null
        var redirects = 0
        val maxRedirects = 5
        var isResuming = startOffset > 0

        while (redirects < maxRedirects) {
            connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "fusion-wallet-android/1.0")
                if (startOffset > 0) {
                    setRequestProperty("Range", "bytes=$startOffset-${spec.size - 1}")
                }
            }

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without Location header")
                connection.disconnect()
                currentUrl = URL(currentUrl, location)
                redirects++
                continue
            }
            break
        }

        val conn = connection ?: throw IOException("Connection failed for $url")
        try {
            val responseCode = conn.responseCode
            val append: Boolean
            var currentBytes = startOffset

            if (startOffset > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL) {
                append = true
            } else if (responseCode == HttpURLConnection.HTTP_OK) {
                append = false
                currentBytes = 0L
                isResuming = false
            } else {
                throw IOException("HTTP $responseCode from $url")
            }

            var lastEmitTime = 0L
            conn.inputStream.use { input ->
                FileOutputStream(partFile, append).use { output ->
                    val buffer = ByteArray(16384)
                    var read = 0
                    while (coroutineContext.isActive && input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        currentBytes += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= 100 || currentBytes >= spec.size) {
                            lastEmitTime = now
                            onProgress(currentBytes, isResuming)
                        }
                    }
                }
            }

            if (currentBytes != spec.size) {
                throw IOException("Download incomplete for ${spec.name}: expected ${spec.size} bytes, got $currentBytes")
            }
        } finally {
            conn.disconnect()
        }
    }
}
