package app.fusion.wallet.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug shell control only; financial execution still requires the edge flock lease. */
class AgentControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = intent.getStringExtra("mode") ?: "dry-run"
        require(mode in setOf("m1", "dry-run", "broadcast", "probe-suite"))
        context.startActivity(Intent(context, AgentControlActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("mode", mode).putExtra("requestId", intent.getStringExtra("requestId")))
    }
}
