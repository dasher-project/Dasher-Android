package at.dasher.android

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * RFC 0003: per-app locale for the frontend chrome. The engine's own labels are
 * localised via `dasher_set_locale`; this wraps the Application's base context so
 * the Compose UI's `strings.xml` resources follow the same user-chosen locale on
 * all API levels (no AppCompat dependency needed).
 *
 * Flow: [setLocale] persists the code and the top Activity is recreated;
 * [DasherApp.attachBaseContext] re-wraps on the next process start.
 */
object LocaleHelper {
    private const val PREFS = "dasher_locale"
    private const val KEY = "app_locale"

    fun wrap(base: Context): Context {
        val code = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return base
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    fun setLocale(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    fun currentLanguageTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
}
