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
