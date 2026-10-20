package app.fusion.wallet.agent

import kotlinx.coroutines.delay
import org.json.JSONObject

/** Retry only confirmation of the exact durable attempt. The engine's
 * reconcileOnly gate forbids verification/signing/broadcast if no journal exists. */
internal suspend fun recoverSessionReceipt(
    initial: JSONObject,
    report: JSONObject,
    hasPending: () -> Boolean,
    execute: suspend (JSONObject) -> JSONObject,
    pause: suspend () -> Unit = { delay(2000) },
): JSONObject {
    var result=initial
    repeat(3) {
        if (!result.has("error") || !hasPending()) return result
        pause()
        report.put("reconcileOnly",true)
        result=execute(report)
        if (!result.has("error")) report.put("reconciledAfterReceiptReadFailure",true)
    }
    return result
}
