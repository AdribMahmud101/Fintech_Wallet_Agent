package app.fusion.wallet.ui.activity

import app.fusion.wallet.chain.ChainReader
import app.fusion.wallet.chain.JsonRpcClient
import app.fusion.wallet.data.Deployment
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.math.BigInteger

/** Lazy, read-only receipt lookup for the selected sheet. Never sends or signs a transaction. */
data class ActivityReceipt(val gasUsed: BigInteger, val timestampSeconds: Long, val reverted: Boolean)

object ActivityReceiptReader {
    fun read(deployment: Deployment, item: ActivityItem): ActivityReceipt {
        val reader = ChainReader(JsonRpcClient(deployment.rpcUrl))
        require(reader.chainId() == deployment.chainId)
        val endpoint = URL(deployment.rpcUrl)
        require(endpoint.protocol == "https")
        val connection = endpoint.openConnection() as HttpURLConnection
        val receipt = try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val request = JSONObject().put("jsonrpc", "2.0").put("id", 1)
                .put("method", "eth_getTransactionReceipt").put("params", JSONArray().put(item.txHash))
            connection.outputStream.use { it.write(request.toString().toByteArray()) }
            check(connection.responseCode in 200..299)
            val bytes = connection.inputStream.use { it.readBytesLimited() }
            val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
            require(envelope.optString("jsonrpc") == "2.0" && envelope.getInt("id") == 1 && !envelope.has("error"))
            envelope.getJSONObject("result")
        } finally { connection.disconnect() }
        require(receipt.getString("transactionHash").equals(item.txHash, true))
        require(ChainReader.quantity(receipt.getString("blockNumber")) == item.blockNumber)
        val header = reader.block(ChainReader.tag(item.blockNumber))
        require(header.hash.equals(receipt.getString("blockHash"), true))
        val gasHex = receipt.getString("gasUsed")
        require(Regex("0x[0-9a-fA-F]+").matches(gasHex))
        val status = ChainReader.quantity(receipt.getString("status"))
        require(status in 0..1)
        return ActivityReceipt(BigInteger(gasHex.drop(2), 16), header.timestamp, status == 0L)
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= 1_048_576)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
