package at.dasher.android

import android.app.Application
import android.content.Context

/**
 * Process entry point. Installs the crash handler + flushes any pending crash
 * (RFC 0009) and initialises analytics (RFC 0001) as early as possible, before
 * any Activity runs. The uncaught-exception handler writes a crash file even
 * before the user has opted in; flushPendingCrash only transmits if they have.
 */
class DasherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AnalyticsService.installCrashHandler(this)
        AnalyticsService.flushPendingCrash(this)
        AnalyticsService.init(this)
    }

    // RFC 0003: wrap the base context so the Compose UI (strings.xml) follows the
    // user-chosen locale on every API level, independent of the engine's own locale.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }
}
