package at.dasher.android

import android.content.Context
import android.os.Build
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import java.util.UUID

/**
 * Privacy-preserving analytics (RFC 0001). Shares the cross-frontend PostHog
 * project + event schema with DasherApple / Dasher-Windows (see `analytics-events.json`).
 *
 * - **Opt-in only.** The PostHog SDK is not set up until the user consents, so zero
 *   data leaves the device before that.
 * - Every event carries the contract defaults: `platform="android"`,
 *   `app_variant="dasher-android"`, `app_version`, `os_version`.
 * - Resettable anonymous ID (a UUID); [resetId] rotates it and re-identifies.
 * - Never sends typed text, clipboard, canvas contents, or PII (per the schema's
 *   `explicitly_not_collected` list) — only the documented event names/properties.
 */
object AnalyticsService {

    private const val TOKEN = "phc_ubtNRuCT7Zqo4dVrVWRnJRYE9m9WqGeTyK7zVDKQ968J"
    private const val HOST = "https://eu.i.posthog.com"
    private const val PREFS = "dasher_analytics"
    private const val KEY_OPTED_IN = "opted_in"
    private const val KEY_PROMPTED = "prompted"
    private const val KEY_ANON_ID = "anon_id"

    private var appContext: Context? = null
    private var initialized = false

    /** Call from Application/Activity startup. Sets up the SDK only if already opted in. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (optedIn(context)) setup()
    }

    private fun setup() {
        if (initialized) return
        val ctx = appContext ?: return
        val config = PostHogAndroidConfig(apiKey = TOKEN, host = HOST).apply {
            // We send only explicit manual events; disable PostHog's auto features.
            captureApplicationLifecycleEvents = false
            captureScreenViews = false
            captureDeepLinks = false
        }
        PostHogAndroid.setup(ctx, config)
        PostHog.identify(anonId(ctx))
        initialized = true
    }

    /** Capture an event with the contract default properties (no-op until opted in). */
    fun capture(event: String, properties: Map<String, Any> = emptyMap()) {
        if (!initialized) return
        val defaults: Map<String, Any> = mapOf(
            "platform" to "android",
            "app_variant" to "dasher-android",
            "app_version" to appVersion(),
            "os_version" to "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        )
        PostHog.capture(event, properties = defaults + properties)
    }

    fun optedIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OPTED_IN, false)

    fun hasPrompted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROMPTED, false)

    fun setOptedIn(context: Context, optedIn: Boolean) {
        prefs(context).edit().putBoolean(KEY_OPTED_IN, optedIn).putBoolean(KEY_PROMPTED, true).apply()
        if (optedIn) {
            setup()
            capture("analytics_opted_in")
        }
    }

    /** Rotates the anonymous ID (the user's "reset ID" control). */
    fun resetId(context: Context) {
        val fresh = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_ANON_ID, fresh).apply()
        if (initialized) {
            PostHog.reset()
            PostHog.identify(fresh)
            capture("analytics_id_reset")
        }
    }

    private fun anonId(context: Context): String =
        prefs(context).getString(KEY_ANON_ID, null) ?: UUID.randomUUID().toString().also {
            prefs(context).edit().putString(KEY_ANON_ID, it).apply()
        }

    private fun appVersion(): String =
        try { BuildConfig.VERSION_NAME } catch (_: Throwable) { "unknown" }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
