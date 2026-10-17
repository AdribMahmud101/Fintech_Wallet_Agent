package app.fusion.wallet.ui.i18n

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** In-app language choice (English / 中文), persisted locally; applies without restarting the Activity. */
object AppLocale {
    const val EN = "en"
    const val ZH = "zh"
    private const val PREFS = "fusion_prefs"
    private const val KEY = "lang"

    fun saved(context: Context): String {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (p == EN || p == ZH) return p
        return if (Locale.getDefault().language == "zh") ZH else EN
    }

    fun save(context: Context, lang: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply()
    }

    /** A context whose resources resolve in [lang]. Used to feed Compose's stringResource. */
    fun wrap(base: Context, lang: String): Context {
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(if (lang == ZH) "zh-CN" else "en"))
        return base.createConfigurationContext(cfg)
    }
}
