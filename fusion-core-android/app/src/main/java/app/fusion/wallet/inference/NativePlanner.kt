package app.fusion.wallet.inference

/** Loaded exclusively by PlannerService in :inference. No keys or RPC code. */
internal object NativePlanner {
    init { System.loadLibrary("fusion_planner_android") }
    @JvmStatic external fun call(operation: String, input: String): String
}
