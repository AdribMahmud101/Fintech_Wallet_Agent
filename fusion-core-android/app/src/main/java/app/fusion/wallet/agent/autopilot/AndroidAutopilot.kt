package app.fusion.wallet.agent.autopilot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.fusion.wallet.agent.AgentNetwork
import app.fusion.wallet.agent.AgentRuntime
import app.fusion.wallet.agent.SessionKeys
import app.fusion.wallet.data.WalletRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Foreground-only by design: the root Activity lifecycle starts/stops the controller.
 * No background service, alarm, wake lock or foreground-service policy exception is needed. */
fun createAutopilot(context: Context, repository: WalletRepository, scope: CoroutineScope): AutopilotController {
    val app = context.applicationContext
    fun hasKey() = SessionKeys(app).imported()
    return AutopilotController(scope, FileAutopilotStore(File(app.noBackupFilesDir, "autopilot/state.json")),
        now = System::currentTimeMillis,
        online = {
            val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        },
        snapshot = { withContext(Dispatchers.IO) { repository.refresh(); repository.state.value } },
        executionMode = {
            withContext(Dispatchers.IO) {
                when {
                    !hasKey() -> ExecutionMode(true, "Preview only on this device: no session key")
                    runCatching { AgentNetwork.requireLease(app) }.isFailure -> ExecutionMode(true, "Preview only on this device: broadcast lease unavailable")
                    else -> ExecutionMode(false, "Live execution · session key and broadcast lease available")
                }
            }
        },
        run = { dryRun -> AgentRuntime.run(app, repository, dryRun) },
        initialMode = if (runCatching { hasKey() }.getOrDefault(false)) "Checking broadcast lease at next tick" else "Preview only on this device: no session key",
    )
}
