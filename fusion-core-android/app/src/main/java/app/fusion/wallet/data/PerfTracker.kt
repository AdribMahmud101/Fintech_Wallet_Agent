package app.fusion.wallet.data

import android.util.Log

/**
 * Tracks cold-start first-paint and live-snapshot latency for performance metrics and judge verification.
 */
object PerfTracker {
    var appStartTime: Long = System.currentTimeMillis()
    var firstPaintMs: Long? = null
    var liveSnapshotMs: Long? = null
    private var firstPaintLogged = false

    fun logFirstPaint(isCached: Boolean) {
        if (!firstPaintLogged) {
            firstPaintLogged = true
            val ms = System.currentTimeMillis() - appStartTime
            firstPaintMs = ms
            try {
                Log.i("FusionPerf", "firstPaintMs=$ms (cached=$isCached)")
            } catch (_: Exception) {}
        }
    }

    fun logLiveSnapshot(durationMs: Long) {
        liveSnapshotMs = durationMs
        try {
            Log.i("FusionPerf", "liveSnapshotMs=$durationMs")
        } catch (_: Exception) {}
    }
}
