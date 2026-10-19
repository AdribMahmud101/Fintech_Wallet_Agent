package app.fusion.wallet.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ObligationsResolverTest {

    private fun sampleObligationsJson(root: String, reserved: String = "50000000"): String {
        return JSONObject()
            .put("root", root)
            .put("bills", JSONArray().put(
                JSONObject()
                    .put("obligationIdHash", "0x" + "1".repeat(64))
                    .put("payeeIdHash", "0x" + "2".repeat(64))
                    .put("assets", reserved)
                    .put("dueAt", "1792412900")
            ))
            .toString()
    }

    @Test
    fun bundledMatchesRoot_returnsBundledWithoutRemote() {
        val root = "0x" + "a".repeat(64)
        val bundled = sampleObligationsJson(root)
        var remoteCalled = false
        val source = object : ObligationsCandidateSource {
            override fun fetchCandidate(url: String): String? {
                remoteCalled = true
                return null
            }
            override fun listAgentEvidenceUrls(): List<String> {
                remoteCalled = true
                return emptyList()
            }
        }
        val resolver = ObligationsResolver(source)
        val resolved = resolver.resolve(root, bundled)
        assertEquals(bundled, resolved)
        assertFalse("Should not call remote source when bundled matches", remoteCalled)
    }

    @Test
    fun bundledDiffers_fetchesMatchingFromCandidateSource() {
        val bundledRoot = "0x" + "a".repeat(64)
        val onChainRoot = "0x" + "b".repeat(64)
        val bundled = sampleObligationsJson(bundledRoot)
        val matchingRemote = sampleObligationsJson(onChainRoot)

        val fetchedUrls = mutableListOf<String>()
        val source = object : ObligationsCandidateSource {
            override fun fetchCandidate(url: String): String? {
                fetchedUrls.add(url)
                return if (url.contains("obligations-84532.json")) matchingRemote else null
            }
            override fun listAgentEvidenceUrls(): List<String> = emptyList()
        }
        val resolver = ObligationsResolver(source)
        val resolved = resolver.resolve(onChainRoot, bundled)
        assertEquals(matchingRemote, resolved)
        assertTrue(fetchedUrls.any { it.contains("obligations-84532.json") })
    }

    @Test
    fun agentListing_discoversAndFetchesMatchingCandidate() {
        val bundledRoot = "0x" + "a".repeat(64)
        val onChainRoot = "0x" + "c".repeat(64)
        val bundled = sampleObligationsJson(bundledRoot)
        val agentCandidate = sampleObligationsJson(onChainRoot)

        val source = object : ObligationsCandidateSource {
            override fun fetchCandidate(url: String): String? {
                return if (url.contains("agent/obligations-new.json")) agentCandidate else null
            }
            override fun listAgentEvidenceUrls(): List<String> {
                return listOf("https://raw.githubusercontent.com/AdribMahmud101/fusion_wallet/main/evidence/agent/obligations-new.json")
            }
        }
        val resolver = ObligationsResolver(source)
        val resolved = resolver.resolve(onChainRoot, bundled)
        assertEquals(agentCandidate, resolved)
    }

    @Test
    fun secondBundledCandidateMatches_returnsItWithoutRemote() {
        val onChainRoot = "0x" + "e".repeat(64)
        val primary = sampleObligationsJson("0x" + "a".repeat(64))
        val agentDemo = sampleObligationsJson(onChainRoot)
        var remoteCalled = false
        val source = object : ObligationsCandidateSource {
            override fun fetchCandidate(url: String): String? { remoteCalled = true; return null }
            override fun listAgentEvidenceUrls(): List<String> { remoteCalled = true; return emptyList() }
        }
        val resolver = ObligationsResolver(source)
        val resolved = resolver.resolve(onChainRoot, listOf(primary, agentDemo))
        assertEquals(agentDemo, resolved)
        assertFalse("A matching bundled candidate must not trigger a network fetch", remoteCalled)
    }

    @Test
    fun demoCandidateInBundledMatches_returnsItWithoutRemote() {
        val onChainRoot = "0x" + "f".repeat(64)
        val primary = sampleObligationsJson("0x" + "a".repeat(64))
        val agent = sampleObligationsJson("0x" + "b".repeat(64))
        val demo = sampleObligationsJson(onChainRoot)
        val resolver = ObligationsResolver(object : ObligationsCandidateSource {
            override fun fetchCandidate(url: String): String? = error("must not call remote")
            override fun listAgentEvidenceUrls(): List<String> = emptyList()
        })
        val resolved = resolver.resolve(onChainRoot, listOf(primary, agent, demo))
        assertEquals(demo, resolved)
    }

    @Test
    fun noCandidateMatchesRoot_returnsNull() {
        val onChainRoot = "0x" + "d".repeat(64)
        val bundled = sampleObligationsJson("0x" + "1".repeat(64))

        val source = object : ObligationsCandidateSource {
            override fun fetchCandidate(url: String): String? {
                return sampleObligationsJson("0x" + "2".repeat(64))
            }
            override fun listAgentEvidenceUrls(): List<String> = emptyList()
        }
        val resolver = ObligationsResolver(source)
        val resolved = resolver.resolve(onChainRoot, bundled)
        assertNull(resolved)
    }
}
