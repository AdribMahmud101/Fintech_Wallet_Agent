package app.fusion.wallet.onboarding

import app.fusion.wallet.chain.Abi
import app.fusion.wallet.chain.RpcTransport
import app.fusion.wallet.data.Deployment
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CheckItem(
    val id: String,
    val title: String,
    val passed: Boolean,
    val blockNumber: Long,
    val detail: String,
    val link: String? = null,
)

data class TreasuryCheckResult(
    val checks: List<CheckItem>,
    val allPassed: Boolean,
    val blockNumber: Long,
    val safeAddress: String,
    val moduleAddress: String,
    val error: String? = null,
)

class TreasuryChecks(
    private val rpcUrl: String = "https://sepolia.base.org",
    private val transport: RpcTransport? = null,
) {
    suspend fun runChecks(manifestJson: String): TreasuryCheckResult = withContext(Dispatchers.IO) {
        try {
            val manifest = JSONObject(manifestJson)
            val deployment = Deployment.parse(manifestJson)
            val safe = deployment.safe
            val module = deployment.module
            val effectiveRpc = if (deployment.rpcUrl.isNotEmpty()) deployment.rpcUrl else rpcUrl

            // 1. Pinned block: fetch latest block
            val blockObj = rpcCall(effectiveRpc, "eth_getBlockByNumber", JSONArray().put("latest").put(false)) as JSONObject
            val blockNumber = java.lang.Long.decode(blockObj.getString("number"))
            val blockHex = "0x" + java.lang.Long.toHexString(blockNumber)

            // 2. Safe code exists at that block
            val safeCode = rpcCall(effectiveRpc, "eth_getCode", JSONArray().put(safe).put(blockHex)) as String
            val codeExists = safeCode.length > 2 && safeCode != "0x" && safeCode != "0x0"
            val safeCodeBytes = if (codeExists) (safeCode.length - 2) / 2 else 0
            val checkSafeCode = CheckItem(
                id = "safe_code",
                title = "Safe has code",
                passed = codeExists,
                blockNumber = blockNumber,
                detail = if (codeExists) "Safe contract deployed ($safeCodeBytes bytes code)" else "Safe address has no runtime bytecode",
                link = "${deployment.explorerBaseUrl}/address/$safe",
            )

            // 3. Threshold == 2 at that block (getThreshold() -> 0xe75235b8)
            val getThresholdCall = JSONObject().put("to", safe).put("data", "0xe75235b8")
            val threshHex = rpcCall(effectiveRpc, "eth_call", JSONArray().put(getThresholdCall).put(blockHex)) as String
            val threshold = try {
                Abi(threshHex).requireSize(1).uint(0).toLong()
            } catch (e: Exception) {
                -1L
            }
            val thresholdPass = threshold == 2L
            val checkThreshold = CheckItem(
                id = "threshold",
                title = "Threshold 2 of 2",
                passed = thresholdPass,
                blockNumber = blockNumber,
                detail = if (thresholdPass) "Safe threshold is exactly 2 of 2" else "Safe threshold is $threshold (expected 2)",
            )

            // 4. Module enabled at that block (isModuleEnabled(address) -> 0x2d9ad53d + addressWord)
            val isModuleEnabledCall = JSONObject().put("to", safe).put("data", "0x2d9ad53d" + Abi.addressWord(module))
            val modHex = rpcCall(effectiveRpc, "eth_call", JSONArray().put(isModuleEnabledCall).put(blockHex)) as String
            val moduleEnabled = try {
                Abi(modHex).requireSize(1).bool(0)
            } catch (e: Exception) {
                false
            }
            val checkModule = CheckItem(
                id = "module_enabled",
                title = "KavachiModule enabled",
                passed = moduleEnabled,
                blockNumber = blockNumber,
                detail = if (moduleEnabled) "Module enabled on Safe" else "Module not enabled on Safe",
                link = "${deployment.explorerBaseUrl}/address/$module",
            )

            // 5. Contracts source-verified (Sourcify link)
            val contracts = manifest.optJSONObject("contracts")
            val safeObj = contracts?.optJSONObject("safe")
            val moduleObj = contracts?.optJSONObject("module")
            val safeVerificationUrl = safeObj?.optJSONObject("verification")?.optString("url").orEmpty()
            val moduleVerificationUrl = moduleObj?.optJSONObject("verification")?.optString("url").orEmpty()
            val sourcifyPresent = safeVerificationUrl.contains("sourcify", ignoreCase = true) ||
                moduleVerificationUrl.contains("sourcify", ignoreCase = true)
            val primarySourcifyUrl = if (moduleVerificationUrl.isNotEmpty()) moduleVerificationUrl else safeVerificationUrl
            val checkSourcify = CheckItem(
                id = "sourcify",
                title = "Contracts source-verified",
                passed = sourcifyPresent,
                blockNumber = blockNumber,
                detail = if (sourcifyPresent) "Sourcify source verification recorded" else "No Sourcify verification recorded",
                link = primarySourcifyUrl.ifEmpty { null },
            )

            val checks = listOf(checkSafeCode, checkThreshold, checkModule, checkSourcify)
            TreasuryCheckResult(
                checks = checks,
                allPassed = checks.all { it.passed },
                blockNumber = blockNumber,
                safeAddress = safe,
                moduleAddress = module,
            )
        } catch (e: Exception) {
            TreasuryCheckResult(
                checks = emptyList(),
                allPassed = false,
                blockNumber = 0L,
                safeAddress = "",
                moduleAddress = "",
                error = e.message ?: "Failed to perform treasury checks",
            )
        }
    }

    private fun rpcCall(urlStr: String, method: String, params: JSONArray): Any {
        if (transport != null) {
            return transport.request(method, params)
        }
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "fusion-wallet-android/1.0")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(512) }.orEmpty()
                throw IOException("$method HTTP ${connection.responseCode}: $detail")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val response = JSONObject(body)
            if (response.has("error")) {
                val error = response.getJSONObject("error")
                throw IOException("$method: ${error.optString("message")}")
            }
            return response.opt("result")?.takeUnless { it == JSONObject.NULL }
                ?: throw IOException("$method returned null result")
        } finally {
            connection.disconnect()
        }
    }
}
