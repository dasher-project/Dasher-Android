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

    /** Engine diagnostic log ring buffer (RFC 0009): appended to a crash report as engine_log_tail. */
    private val engineLog = ArrayDeque<String>()
    private const val ENGINE_LOG_MAX_LINES = 64
    private const val ENGINE_LOG_MAX_BYTES = 8 * 1024
    private const val CRASH_FILE = "pending_crash.txt"
    private const val CRASH_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    @Synchronized
    fun appendEngineLog(level: Int, message: String) {
        val line = "[${"DIWE".getOrElse(level) { 'X' }}] $message"
        engineLog.addLast(line)
        while (engineLog.size > ENGINE_LOG_MAX_LINES) engineLog.removeFirst()
        // Drop the oldest while over the byte cap.
        var bytes = engineLog.sumOf { it.length }
        while (bytes > ENGINE_LOG_MAX_BYTES && engineLog.size > 1) {
            bytes -= engineLog.removeFirst().length
        }
    }

    @Synchronized
    private fun snapshotEngineLog(): String = engineLog.joinToString("\n")

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

    // ── Crash reporting (RFC 0009) ─────────────────────────────────────────────
    // JVM-level capture only in v1: a native SIGSEGV inside libdasher.so is not seen
    // by Thread.setDefaultUncaughtExceptionHandler; a signal shim is a follow-up.

    /** Install the uncaught-exception handler. Call once from Application.onCreate. */
    fun installCrashHandler(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashFile(context, thread.name, throwable)
            } catch (_: Throwable) { /* never throw in a crash handler */ }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * On launch: if a pending crash file exists, send it (only if opted in — RFC 0009)
     * and delete it. Crash files older than 7 days are discarded regardless.
     */
    fun flushPendingCrash(context: Context) {
        val file = java.io.File(context.filesDir, CRASH_FILE)
        if (!file.exists()) return
        val age = System.currentTimeMillis() - file.lastModified()
        if (age > CRASH_MAX_AGE_MS) { file.delete(); return }
        if (optedIn(context)) {
            try {
                val text = file.readText()
                // Minimal envelope: lines "key=value", stack/engine tail after blank line.
                val props = mutableMapOf<String, Any>()
                val (header, body) = text.split("\n\n", limit = 2)
                    .let { it.first() to (it.getOrNull(1) ?: "") }
                header.split('\n').forEach { ln ->
                    val idx = ln.indexOf('=')
                    if (idx > 0) props[ln.substring(0, idx)] = ln.substring(idx + 1)
                }
                if (body.isNotBlank()) props["stack_trace"] = body
                capture("crash", props)
            } catch (_: Throwable) { }
        }
        file.delete()
    }

    private fun writeCrashFile(context: Context, threadName: String, t: Throwable) {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        val stack = scrub(sw.toString()).take(16 * 1024)
        val engineTail = scrub(snapshotEngineLog()).take(8 * 1024)
        val header = buildString {
            append("exception_type=").append(t::class.java.name).append('\n')
            append("thread=").append(threadName).append('\n')
            append("app_version=").append(appVersion()).append('\n')
            append("os_version=Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        }
        // stack_trace carries the JVM stack + the engine log tail (separated) so a
        // maintainer can reconstruct what DasherCore was doing when the process died.
        val body = if (engineTail.isNotBlank()) "$stack\n--- engine log ---\n$engineTail" else stack
        java.io.File(context.filesDir, CRASH_FILE).writeText("$header\n\n$body")
    }

    /** Scrub home-directory path segments and emails; respect RFC 0001's no-PII promise. */
    private fun scrub(s: String): String {
        var out = s
        out = Regex("""(/Users/|/home/)([^/\\]+)""").replace(out) { "${it.groupValues[1]}<user>" }
        out = Regex("""C:\\Users\\([^\\]+)""").replace(out, "C:\\Users\\<user>")
        out = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").replace(out, "<email>")
        return out
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
