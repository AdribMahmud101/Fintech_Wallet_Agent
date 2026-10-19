package app.fusion.wallet.data

import app.fusion.wallet.chain.Abi
import org.json.JSONObject

/** The bundled manifest supplies addresses only. Its handoffSnapshot is never used as state. */
data class Deployment(
    val chainId: Long,
    val rpcUrl: String,
    val safe: String,
    val module: String,
    val asset: String,
    val vaultA: String,
    val vaultB: String,
    val sanctionsList: String,
    val deploymentBlock: Long,
    val explorerBaseUrl: String,
) {
    companion object {
        fun parse(json: String): Deployment {
            val value = JSONObject(json)
            fun address(key: String): String = value.getString(key).lowercase().also {
                Abi.addressWord(it)
                require(it != "0x0000000000000000000000000000000000000000") { "Zero $key address" }
            }
            require(value.getLong("chainId") == 84532L) { "Expected Base Sepolia" }
            val deploymentBlock = value.getLong("deploymentBlock")
            require(deploymentBlock >= 0)
            return Deployment(84532, value.getString("rpcUrl"), address("safe"), address("module"), address("asset"),
                address("vaultA"), address("vaultB"), address("sanctionsList"), deploymentBlock,
                value.getString("explorerBaseUrl").trimEnd('/'))
        }
    }
}
