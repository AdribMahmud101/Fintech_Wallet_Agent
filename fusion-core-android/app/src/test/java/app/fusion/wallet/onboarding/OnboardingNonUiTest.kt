package app.fusion.wallet.onboarding

import app.fusion.wallet.chain.Abi
import app.fusion.wallet.chain.RpcTransport
import app.fusion.wallet.data.Deployment
import app.fusion.wallet.data.Money
import app.fusion.wallet.data.ModuleState
import app.fusion.wallet.data.Payee
import app.fusion.wallet.data.PayeeStatus
import app.fusion.wallet.data.PermissionTier
import app.fusion.wallet.data.Policy
import app.fusion.wallet.data.WalletState
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OnboardingNonUiTest {

    private lateinit var tempDir: File
    private var serverSocket: ServerSocket? = null
    private var serverRunning = false

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("fusion_test_").toFile()
    }

    @After
    fun tearDown() {
        serverRunning = false
        serverSocket?.close()
        tempDir.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    // --- ModelDownloader Tests ---

    @Test
    fun testSha256Verification_validFile() {
        val testContent = "Test content for sha256 verification".toByteArray()
        val file = File(tempDir, "sample.txt")
        file.writeBytes(testContent)

        val expectedHash = sha256Hex(testContent)
        val computedHash = ModelDownloader.sha256(file)

        assertEquals(expectedHash, computedHash)
        assertTrue(computedHash.isNotEmpty())
    }

    @Test
    fun testSha256Verification_corruptedFileDetected() {
        val testContent = "Original test content".toByteArray()
        val file = File(tempDir, "sample.txt")
        file.writeBytes(testContent)

        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"
        val actualHash = ModelDownloader.sha256(file)

        assertNotEquals(wrongHash, actualHash)
    }

    @Test
    fun testResumeDownload_withPartialContent206() = runBlocking {
        val fullData = "ABCDEFGHIJ0123456789KLMNOPQRSTUVWXYZ".toByteArray(Charsets.UTF_8)
        val expectedHash = sha256Hex(fullData)
        val totalSize = fullData.size.toLong()

        // Lightweight ServerSocket supporting Range: bytes=...
        val s = ServerSocket(0)
        serverSocket = s
        val port = s.localPort
        serverRunning = true

        thread(isDaemon = true) {
            while (serverRunning && !s.isClosed) {
                val client = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    client.use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        var line: String?
                        var rangeHeader: String? = null
                        while (reader.readLine().also { line = it } != null) {
                            if (line!!.isEmpty()) break
                            if (line!!.startsWith("Range:", ignoreCase = true)) {
                                rangeHeader = line!!.substringAfter(":").trim()
                            }
                        }

                        val out = socket.getOutputStream()
                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                            val parts = rangeSpec.split("-")
                            val start = parts[0].toInt()
                            val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toInt() else fullData.size - 1
                            val chunk = fullData.copyOfRange(start, end + 1)
                            val headers = "HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Type: application/octet-stream\r\n" +
                                "Content-Range: bytes $start-$end/${fullData.size}\r\n" +
                                "Content-Length: ${chunk.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(headers.toByteArray(Charsets.UTF_8))
                            out.write(chunk)
                            out.flush()
                        } else {
                            val headers = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/octet-stream\r\n" +
                                "Content-Length: ${fullData.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            out.write(headers.toByteArray(Charsets.UTF_8))
                            out.write(fullData)
                            out.flush()
                        }
                    }
                }
            }
        }

        // Pre-create .part file with first 10 bytes to simulate partial download
        val modelDir = File(tempDir, "models")
        modelDir.mkdirs()
        val partFile = File(modelDir, "model_test.bin.part")
        partFile.writeBytes(fullData.copyOfRange(0, 10))
        assertEquals(10L, partFile.length())

        val spec = ModelFileSpec(
            name = "model_test.bin",
            size = totalSize,
            sha256 = expectedHash,
            urls = listOf("http://127.0.0.1:$port/model_test.bin")
        )
        val manifest = ModelManifest("TestModel", "MNN", "4-bit", totalSize, listOf(spec))

        val downloader = ModelDownloader(modelDir) { true }
        val states = downloader.download(manifest).toList()

        val destFile = File(modelDir, "model_test.bin")
        assertTrue("Destination file should exist after completed download", destFile.exists())
        assertEquals(totalSize, destFile.length())
        assertEquals(expectedHash, ModelDownloader.sha256(destFile))
        assertFalse(".part file should be renamed/removed", partFile.exists())

        // Verify progress emissions
        val downloadStates = states.filterIsInstance<DownloadState.Downloading>()
        assertTrue("Expected progress emissions", downloadStates.isNotEmpty())
        assertTrue("At least one progress emission should indicate resuming", downloadStates.any { it.isResuming })
        assertTrue("Final state should be Completed", states.last() is DownloadState.Completed)
    }

    @Test
    fun testResumeDownload_serverReturns200Fallback() = runBlocking {
        val fullData = "FULL_CONTENT_WHEN_SERVER_IGNORES_RANGE".toByteArray(Charsets.UTF_8)
        val expectedHash = sha256Hex(fullData)
        val totalSize = fullData.size.toLong()

        val s = ServerSocket(0)
        serverSocket = s
        val port = s.localPort
        serverRunning = true

        thread(isDaemon = true) {
            while (serverRunning && !s.isClosed) {
                val client = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    client.use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (line!!.isEmpty()) break
                        }
                        val out = socket.getOutputStream()
                        val headers = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "Content-Length: ${fullData.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(headers.toByteArray(Charsets.UTF_8))
                        out.write(fullData)
                        out.flush()
                    }
                }
            }
        }

        val modelDir = File(tempDir, "models2")
        modelDir.mkdirs()
        val partFile = File(modelDir, "model_test2.bin.part")
        // Corrupted 5 bytes in part file
        partFile.writeBytes("CORRU".toByteArray(Charsets.UTF_8))

        val spec = ModelFileSpec(
            name = "model_test2.bin",
            size = totalSize,
            sha256 = expectedHash,
            urls = listOf("http://127.0.0.1:$port/model_test2.bin")
        )
        val manifest = ModelManifest("TestModel", "MNN", "4-bit", totalSize, listOf(spec))

        val downloader = ModelDownloader(modelDir) { true }
        val states = downloader.download(manifest).toList()

        val destFile = File(modelDir, "model_test2.bin")
        assertTrue(destFile.exists())
        assertEquals(totalSize, destFile.length())
        assertEquals(expectedHash, ModelDownloader.sha256(destFile))
        assertTrue(states.last() is DownloadState.Completed)
    }

    @Test
    fun testWifiOnly_blockedWhenNotWifi() = runBlocking {
        val modelDir = File(tempDir, "models_wifi")
        modelDir.mkdirs()

        val spec = ModelFileSpec(
            name = "file.bin",
            size = 100,
            sha256 = "abc",
            urls = listOf("http://example.com/file.bin")
        )
        val manifest = ModelManifest("TestModel", "MNN", "4-bit", 100, listOf(spec))

        val downloader = ModelDownloader(modelDir, isWifiProvider = { false })
        val states = downloader.download(manifest, wifiOnly = true).toList()

        assertTrue("Expected error state when wifiOnly is true and not on Wi-Fi", states.last() is DownloadState.Error)
        val err = states.last() as DownloadState.Error
        assertTrue(err.isWifiError)
    }

    @Test
    fun testModelManifest_parse() {
        val json = """
        {
            "modelName": "LFM2.5-230M",
            "format": "MNN",
            "quantization": "4-bit",
            "totalSize": 1000,
            "files": [
                {
                    "name": "config.json",
                    "size": 873,
                    "sha256": "9cd1d048291fbbbf0b78977c78978d4ce0aa03bc26652ecf163b8e3395bcba20",
                    "urls": ["http://localhost/config.json"]
                }
            ]
        }
        """.trimIndent()
        val manifest = ModelManifest.parse(json)
        assertEquals("LFM2.5-230M", manifest.modelName)
        assertEquals(1, manifest.files.size)
        assertEquals("config.json", manifest.files[0].name)
        assertEquals(873L, manifest.files[0].size)
    }

    // --- TreasuryChecks Tests ---

    @Test
    fun testTreasuryChecks_withMockRpcTransport() = runBlocking {
        val safeAddress = "0x029869910714D9e09b854a2688311AFd42fBb44E".lowercase()
        val moduleAddress = "0xA560ec10259657E9bADd297E68286d2838Cd5a8f".lowercase()

        val mockTransport = object : RpcTransport {
            override fun request(method: String, params: JSONArray): Any {
                return when (method) {
                    "eth_getBlockByNumber" -> JSONObject().put("number", "0x2e15bc")
                    "eth_getCode" -> "0x608060405234801561001057600080fd5b50"
                    "eth_call" -> {
                        val callObj = params.getJSONObject(0)
                        val data = callObj.getString("data")
                        if (data == "0xe75235b8") {
                            // getThreshold() -> 2
                            "0x" + Abi.uintWord(BigInteger.TWO)
                        } else if (data.startsWith("0x2d9ad53d")) {
                            // isModuleEnabled -> true
                            "0x" + Abi.uintWord(BigInteger.ONE)
                        } else {
                            "0x" + Abi.uintWord(BigInteger.ZERO)
                        }
                    }
                    else -> throw IllegalArgumentException("Unknown method $method")
                }
            }

            override fun batch(calls: List<Pair<String, JSONArray>>): List<Any> {
                return calls.map { request(it.first, it.second) }
            }
        }

        val manifestJson = """
        {
            "chainId": 84532,
            "rpcUrl": "https://sepolia.base.org",
            "safe": "$safeAddress",
            "module": "$moduleAddress",
            "asset": "0xba50Cd2A20f6DA35D788639E581bca8d0B5d4D5f",
            "vaultA": "0xf430cb6E2b85f99222fBFA6dFEa18Ff60FA6B32a",
            "vaultB": "0xf052C0ED8404adEaAEA773A5F8Ae4628134aff6D",
            "sanctionsList": "0xd2716e08BCe6eb5B1f01329FF5E1a2dd0468B678",
            "deploymentBlock": 48317685,
            "explorerBaseUrl": "https://sepolia.basescan.org",
            "contracts": {
                "safe": {
                    "verification": {
                        "url": "https://repo.sourcify.dev/contracts/full_match/84532/$safeAddress/"
                    }
                },
                "module": {
                    "verification": {
                        "url": "https://repo.sourcify.dev/contracts/full_match/84532/$moduleAddress/"
                    }
                }
            }
        }
        """.trimIndent()

        val checker = TreasuryChecks(transport = mockTransport)
        val result = checker.runChecks(manifestJson)

        assertTrue("All checks should pass", result.allPassed)
        assertEquals(4, result.checks.size)
        assertTrue("Safe code check should pass", result.checks.first { it.id == "safe_code" }.passed)
        assertTrue("Threshold check should pass", result.checks.first { it.id == "threshold" }.passed)
        assertTrue("Module check should pass", result.checks.first { it.id == "module_enabled" }.passed)
        assertTrue("Sourcify check should pass", result.checks.first { it.id == "sourcify" }.passed)
        assertEquals(0x2e15bcL, result.blockNumber)
        result.checks.forEach { assertEquals(0x2e15bcL, it.blockNumber) }
    }

    // --- PolicyReader Tests ---

    @Test
    fun testPolicyReader_fromWalletState() {
        val policy = Policy(
            permissionTier = PermissionTier.AUTONOMOUS,
            operatingBuffer = Money.fromRaw("500000000"),
            microMax = Money.fromRaw("100000000"),
            delayedMax = Money.fromRaw("500000000"),
            hardMax = Money.fromRaw("2000000000"),
            dailyLimit = Money.fromRaw("4000000000"),
            delaySeconds = 3600L,
            maxShareSlippageBps = 100,
            guardian = "0x46B8E40EB9767BD46E03C5eCF398C10E1372251E",
            payeeDelaySeconds = 3600L,
            payEarlyWindowSeconds = 86400L,
        )

        val moduleState = ModuleState(
            address = "0xA560ec10259657E9bADd297E68286d2838Cd5a8f",
            configured = true,
            paused = false,
            sessionActive = true,
            policy = policy,
            policyEpoch = BigInteger.valueOf(6),
            policyHash = "0xhash",
            mandateHash = "0xmandate",
            obligationsRoot = "0xroot",
            reserved = Money.fromRaw("50000000"),
            obligationCount = BigInteger.valueOf(5),
            actionNonce = BigInteger.valueOf(6),
            dailyUsed = Money.fromRaw("2093000000"),
            dailyRemaining = Money.fromRaw("1907000000"),
            sessionId = "0xsession",
            sessionKey = "0xB52Cb48eC1cc5Ad1171C83F162228e31752F87fA",
            sessionExpiresAt = 1792600000L,
            sessionLimit = Money.fromRaw("1000000000"),
            sessionUsed = Money.fromRaw("200000000"),
        )

        val payee = Payee(
            id = "0x01",
            address = "0xvendor",
            label = "Verified Vendor 1",
            labelHash = "0xdetails",
            status = PayeeStatus.ACTIVE,
            activeAt = 1000L,
            sanctioned = false,
        )

        val walletState = WalletState(
            blockNumber = 48319954L,
            blockTime = 1792408196L,
            module = moduleState,
            payees = listOf(payee),
        )

        val deployment = Deployment(
            chainId = 84532L,
            rpcUrl = "https://sepolia.base.org",
            safe = "0xsafe",
            module = "0xmodule",
            asset = "0xasset",
            vaultA = "0xvaulta",
            vaultB = "0xvaultb",
            sanctionsList = "0xsanctions",
            deploymentBlock = 48317685L,
            explorerBaseUrl = "https://sepolia.basescan.org",
        )

        val summary = PolicyReader.fromWalletState(walletState, deployment)
        assertNotNull(summary)
        assertEquals(48319954L, summary!!.blockNumber)
        assertEquals(PermissionTier.AUTONOMOUS, summary.tiers.tier)
        assertEquals("4000.000000", summary.limits.dailyLimit.display)
        assertEquals("1907.000000", summary.limits.dailyRemaining.display)
        assertEquals("1000.000000", summary.limits.sessionLimit.display)
        assertEquals("800.000000", summary.limits.sessionRemaining.display)
        assertTrue(summary.limits.sessionActive)
        assertEquals("500.000000", summary.reserveBuffer.operatingBuffer.display)
        assertEquals("50.000000", summary.reserveBuffer.reserved.display)
        assertEquals("550.000000", summary.reserveBuffer.totalRequired.display)
        assertEquals(1, summary.verifiedVendors.size)
        assertEquals("Verified Vendor 1", summary.verifiedVendors[0].name)
        assertEquals("Changes need both owners (Safe 2-of-2)", summary.ruleNotice)
    }
}
