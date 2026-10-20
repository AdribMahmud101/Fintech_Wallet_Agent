package app.fusion.wallet.agent

/** Reached stages only; elapsed time uses the monotonic clock, never a progress estimate. */
enum class DecisionStage(val title: String) {
    SNAPSHOT("Snapshot"), CANDIDATES("Candidates"), AI("On-device AI"), POLICY("Policy checks"), SIGNER("Signer"), CHAIN("On-chain")
}
data class PipelineStep(val stage: DecisionStage, val durationMs: Long? = null)
data class DecisionPipeline(
    val steps: List<PipelineStep> = emptyList(), val active: DecisionStage? = null,
    val dryRun: Boolean? = null, val error: String? = null,
    val loadMs: Double? = null, val inferenceMs: Double? = null,
) {
    fun enter(stage: DecisionStage, previousDurationMs: Long? = null): DecisionPipeline {
        if (dryRun == true && stage.ordinal > DecisionStage.POLICY.ordinal) return this
        if (steps.any { it.stage == stage }) return this
        return copy(steps = steps.map { if (it.stage == active) it.copy(durationMs = previousDurationMs) else it } + PipelineStep(stage), active = stage)
    }
    fun finish(durationMs: Long, failure: String? = null) = copy(
        steps = steps.map { if (it.stage == active) it.copy(durationMs = durationMs.coerceAtLeast(0)) else it }, active = null, error = failure)
}
