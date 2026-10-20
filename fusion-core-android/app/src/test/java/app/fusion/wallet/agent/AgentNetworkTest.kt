package app.fusion.wallet.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentNetworkTest {
    @Test fun preservesBatchesAndPinnedBlockInsteadOfSerializingViews() {
        var singles=0; var batches=0
        val transport=AgentNetwork.readOnlyRelay({ _, _ -> singles++; "single" }, { calls ->
            batches++
            calls.forEach { assertEquals("eth_call",it.first); assertEquals("0x42",it.second.getString(1)) }
            calls.indices.map { it.toString() }
        })
        val calls=List(12) { "eth_call" to JSONArray().put(JSONObject().put("data","0x1865c57d")).put("0x42") }
        assertEquals((0..11).map(Int::toString),transport.batch(calls))
        assertEquals(0,singles); assertEquals(1,batches)
    }
    @Test fun refusesWritesBeforeEitherTransportAndChunksReadBatches() {
        var singles=0; val sizes=mutableListOf<Int>()
        val transport=AgentNetwork.readOnlyRelay({ _, _ -> singles++; "read" }, { calls -> sizes.add(calls.size); calls.map { "read" } })
        assertTrue(runCatching { transport.request("eth_sendRawTransaction",JSONArray()) }.isFailure)
        assertTrue(runCatching { transport.batch(listOf("eth_sendRawTransaction" to JSONArray())) }.isFailure)
        assertEquals(0,singles); assertTrue(sizes.isEmpty())
        assertEquals(51,transport.batch(List(51) { "eth_chainId" to JSONArray() }).size)
        assertEquals(listOf(50,1),sizes)
    }
}
