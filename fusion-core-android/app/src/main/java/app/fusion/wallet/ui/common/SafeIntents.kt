package app.fusion.wallet.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * Start an activity from any Compose context. The app wraps its context for
 * in-app language switching, so LocalContext is not an Activity and a plain
 * startActivity() threw "requires the FLAG_ACTIVITY_NEW_TASK flag" — every
 * "View on BaseScan" tap crashed the app.
 */
fun Context.startActivitySafe(intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/**
 * The data layer keeps a machine-readable code ("ACCOUNTING_MISMATCH: …") so tests
 * and the agent can classify the error; people see a plain sentence instead.
 */
fun accountingMessageForPeople(error: String?): String? = when {
    error == null -> null
    error.contains("differs from bundled bills") ->
        "Bill details for the current on-chain commitment are not published yet. Totals come straight from the chain."
    error.startsWith("ACCOUNTING_MISMATCH") ->
        "The bill list does not add up to the unpaid total on-chain. Showing on-chain totals."
    else -> error
}
