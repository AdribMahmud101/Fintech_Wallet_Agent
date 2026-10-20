package app.fusion.wallet.ui.agent

import app.fusion.wallet.agent.*
import org.json.JSONObject

/** Never infer signing from a live-mode request: only recorded execution proves it was reached. */
fun pipelineFromReport(raw: String?): DecisionPipeline = runCatching {
    val report = JSONObject(requireNotNull(raw))
    val dryRun = if (report.has("dryRun")) report.getBoolean("dryRun") else null
    val recorded = report.optJSONArray("pipeline")
    val steps = if (recorded != null) (0 until recorded.length()).map { i ->
        val row = recorded.getJSONObject(i)
        PipelineStep(DecisionStage.valueOf(row.getString("stage")), if (row.isNull("durationMs")) null else row.getLong("durationMs").takeIf { it >= 0 })
    } else buildList {
        if (report.has("snapshot")) add(PipelineStep(DecisionStage.SNAPSHOT))
        if (report.has("candidateSet")) add(PipelineStep(DecisionStage.CANDIDATES))
        report.optJSONObject("model")?.let { model ->
            add(PipelineStep(DecisionStage.AI, model.optJSONObject("timings")?.optString("totalMs")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.toLong()))
        }
        if (report.has("preview")) add(PipelineStep(DecisionStage.POLICY))
        if (dryRun != true && report.has("execution")) {
            add(PipelineStep(DecisionStage.SIGNER))
            val execution = report.getJSONObject("execution")
            if (execution.optInt("status") == 1 && execution.has("receipt")) add(PipelineStep(DecisionStage.CHAIN))
        }
    }
    DecisionPipeline(steps.filter { dryRun != true || it.stage.ordinal <= DecisionStage.POLICY.ordinal }, dryRun = dryRun,
        loadMs = report.optJSONObject("model")?.optJSONObject("load")?.optString("coldLoadMs")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
        inferenceMs = report.optJSONObject("model")?.optJSONObject("timings")?.optString("totalMs")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 })
}.getOrDefault(DecisionPipeline())
