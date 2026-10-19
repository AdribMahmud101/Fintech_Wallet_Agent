package app.fusion.wallet.onboarding

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real on-device provisioning run for the LFM2.5-230M model (UX plan §3 step 4).
 *
 * Gated behind FUSION_DEVICE_IT=1 so the normal DoD unit-test run skips it (JUnit Assume →
 * ignored, never failed). When enabled it drives the *production* [ModelDownloader] against a
 * local HTTP mirror (the first URL in model_manifest.json is 127.0.0.1:8888), downloading the
 * real ~128 MB model and verifying every file's SHA-256 against the pinned manifest hash. If a
 * partial `.part` exists it is resumed via HTTP Range. Run on this arm64 Android host it is a
 * genuine device run of the download + verify path.
 *
 * Env:
 *   FUSION_DEVICE_IT=1          enable
 *   FUSION_MANIFEST=<path>      model_manifest.json (default src/main/assets/model_manifest.json)
 *   FUSION_MODEL_DIR=<path>     source model dir, used only to pre-seed a resume .part (optional)
 */
class ModelDownloaderDeviceIT {

    @Test
    fun downloadsAndVerifiesRealModelFromLocalMirror() = runBlocking {
        assumeTrue("set FUSION_DEVICE_IT=1 to run the on-device model provisioning IT", System.getenv("FUSION_DEVICE_IT") == "1")

        val manifestPath = System.getenv("FUSION_MANIFEST") ?: "src/main/assets/model_manifest.json"
        val manifest = ModelManifest.parse(File(manifestPath).readText())
        val outDir = Files.createTempDirectory("fusion_model_it_").toFile()

        // Optionally pre-seed the largest file's .part with its first 40 MB so the run must
        // resume it over HTTP Range (proves resume on real data, not just the synthetic test).
        val modelSrc = System.getenv("FUSION_MODEL_DIR")
        val largest = manifest.files.maxByOrNull { it.size }
        if (modelSrc != null && largest != null) {
            val src = File(modelSrc, largest.name)
            if (src.isFile) {
                val seed = minOf(40L * 1_000_000L, largest.size - 1)
                val part = File(outDir, "${largest.name}.part")
                src.inputStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16); var left = seed
                        while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n }
                    }
                }
                println("DEVICE_IT: pre-seeded ${part.name} with ${part.length()} bytes to force resume")
            }
        }

        val downloader = ModelDownloader(outDir) { true }
        val t0 = System.currentTimeMillis()
        val states = downloader.download(manifest, wifiOnly = false).toList()
        val elapsed = System.currentTimeMillis() - t0

        val last = states.last()
        assertTrue("download ended in ${last}", last is DownloadState.Completed)

        val status = downloader.getStatus(manifest)
        assertTrue("every file verified against pinned SHA-256", status.ready)
        assertEquals(manifest.files.size, status.verifiedFiles.size)
        manifest.files.forEach { spec ->
            val got = ModelDownloader.sha256(File(outDir, spec.name))
            assertEquals("SHA-256 for ${spec.name}", spec.sha256.lowercase(), got.lowercase())
        }

        println("DEVICE_IT: verified ${status.verifiedFiles.size}/${status.totalFiles} files, ${status.totalSizeOnDisk} bytes in ${elapsed} ms")
        status.hashes.forEach { (name, hash) -> println("DEVICE_IT: $name $hash") }
        outDir.deleteRecursively()
        Unit
    }
}
