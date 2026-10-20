package app.fusion.wallet.chain

import app.fusion.wallet.data.AgentGasReader
import app.fusion.wallet.data.TxIndexCache
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with FUSION_AGENT_GAS_LIVE=1; runs the production HTTP client and index loader. */
class AgentGasLiveTest {
    @Test fun productionGasPathAgainstBaseSepolia() {
        assumeTrue(System.getenv("FUSION_AGENT_GAS_LIVE") == "1")
        val directory = Files.createTempDirectory("agent-gas-live").toFile()
        try {
            val rpc = JsonRpcClient()
            val reader = ChainReader(rpc)
            val block = reader.block("latest")
            val session = reader.call("0xA560ec10259657E9bADd297E68286d2838Cd5a8f", Views.STATE, block.number)
                .requireSize(17).address(13)
            val index = TxIndexCache(directory.resolve("evidence/tx_index.json")).refresh()
            assertTrue("GitHub index unavailable", index.all().isNotEmpty())
            val gas = AgentGasReader(rpc).read(session, block, index)
            println("LIVE_AGENT_GAS " + JSONObject().put("block", ChainReader.tag(block.number)).put("blockHash", block.hash)
                .put("session", session).put("indexEntries", index.all().size).put("balanceWei", gas.balanceWei.toString())
                .put("medianCostWei", gas.medianCostWei?.toPlainString()).put("estimatedMoves", gas.estimatedMoves?.toString()))
            assertNotNull("No live fee samples survived; inspect the pinned snapshot", gas.estimatedMoves)
        } finally { directory.deleteRecursively() }
    }
}
