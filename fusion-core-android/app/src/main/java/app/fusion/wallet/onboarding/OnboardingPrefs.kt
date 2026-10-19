package app.fusion.wallet.onboarding

import android.content.Context
import android.content.SharedPreferences

object OnboardingPrefs {
    private const val PREFS_NAME = "fusion_onboarding_prefs"
    private const val KEY_COMPLETED = "onboarding_completed"
    private const val KEY_SELECTED_ROLE = "selected_role"
    private const val KEY_LAST_MODEL_LATENCY = "last_model_latency_ms"
    private const val KEY_WIFI_ONLY = "model_wifi_only"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMPLETED, false)

    fun setCompleted(context: Context, completed: Boolean = true) {
        prefs(context).edit().putBoolean(KEY_COMPLETED, completed).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_COMPLETED).apply()
    }

    fun getSelectedRole(context: Context): String =
        prefs(context).getString(KEY_SELECTED_ROLE, "AUDITOR") ?: "AUDITOR"

    fun setSelectedRole(context: Context, role: String) {
        prefs(context).edit().putString(KEY_SELECTED_ROLE, role).apply()
    }

    fun getLastModelLatency(context: Context): Long? {
        val latency = prefs(context).getLong(KEY_LAST_MODEL_LATENCY, -1L)
        return if (latency >= 0) latency else null
    }

    fun setLastModelLatency(context: Context, latencyMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_MODEL_LATENCY, latencyMs).apply()
    }

    fun isWifiOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIFI_ONLY, true)

    fun setWifiOnly(context: Context, wifiOnly: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).apply()
    }
}
