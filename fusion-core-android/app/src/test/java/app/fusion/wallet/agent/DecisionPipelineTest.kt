package app.fusion.wallet.agent

import app.fusion.wallet.ui.agent.pipelineFromReport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DecisionPipelineTest {
    @Test fun realStageTransitionsRetainMeasuredDurations() {
        var pipeline = DecisionPipeline(dryRun = false)
        DecisionStage.entries.forEachIndexed { index, stage -> pipeline = pipeline.enter(stage, index * 37L) }
        pipeline = pipeline.finish(481)
        assertEquals(DecisionStage.entries, pipeline.steps.map { it.stage })
        assertEquals(listOf(37L, 74L, 111L, 148L, 185L, 481L), pipeline.steps.map { it.durationMs })
        assertNull(pipeline.active)
    }

    @Test fun previewCannotLightSignerOrChainEvenWithBadEvents() {
        var pipeline = DecisionPipeline(dryRun = true)
        DecisionStage.entries.forEach { pipeline = pipeline.enter(it, 100) }
        pipeline = pipeline.finish(60)
        assertEquals(DecisionStage.POLICY, pipeline.steps.last().stage)
        assertEquals(4, pipeline.steps.size)
        assertNull(pipeline.active)
    }

    @Test fun failedInferenceDoesNotInventPolicyOrExecution() {
        val pipeline = DecisionPipeline(dryRun = false).enter(DecisionStage.SNAPSHOT)
            .enter(DecisionStage.CANDIDATES, 180).enter(DecisionStage.AI, 5).finish(420, "Planner unavailable")
        assertEquals(3, pipeline.steps.size)
        assertEquals("Planner unavailable", pipeline.error)
        assertEquals(420L, pipeline.steps.last().durationMs)
    }

    @Test fun duplicateCallbackDoesNotAddASecondChainStage() {
        val pipeline = DecisionPipeline().enter(DecisionStage.CHAIN)
        assertEquals(pipeline, pipeline.enter(DecisionStage.CHAIN, 99))
    }

    @Test fun oldLiveReportDoesNotImplySigningOrMadeUpDurations() {
        val report = JSONObject().put("dryRun", false).put("snapshot", JSONObject()).put("candidateSet", JSONObject())
            .put("preview", JSONObject()).put("model", JSONObject().put("timings", JSONObject().put("totalMs", 734)))
        val pipeline = pipelineFromReport(report.toString())
        assertEquals(DecisionStage.POLICY, pipeline.steps.last().stage)
        assertEquals(734L, pipeline.steps.single { it.stage == DecisionStage.AI }.durationMs)
        assertNull(pipeline.steps.first().durationMs)
    }

    @Test fun recordedReceiptAndTimingsMapWithoutEstimates() {
        val measured = JSONArray().put(JSONObject().put("stage", "SIGNER").put("durationMs", 240))
            .put(JSONObject().put("stage", "CHAIN").put("durationMs", 1824))
        val pipeline = pipelineFromReport(JSONObject().put("dryRun", false).put("pipeline", measured).toString())
        assertEquals(listOf(240L, 1824L), pipeline.steps.map { it.durationMs })
        val dry = pipelineFromReport(JSONObject().put("dryRun", true).put("pipeline", measured).toString())
        assertTrue(dry.steps.isEmpty())
    }

    @Test fun unknownAndCorruptReportsStayEmpty() {
        assertTrue(pipelineFromReport(null).steps.isEmpty())
        assertTrue(pipelineFromReport("invalid").steps.isEmpty())
        assertTrue(pipelineFromReport("{}").steps.isEmpty())
    }
}
