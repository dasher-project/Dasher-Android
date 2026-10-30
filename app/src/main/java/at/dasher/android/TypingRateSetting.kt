package at.dasher.android

import android.content.Context

/**
 * RFC 0012 typing-rate display toggle. Frontend setting (DasherCore has no
 * BP_SHOW_TYPING_RATE — Windows/Apple also implement it locally). Default
 * OFF per the RFC ("doesn't clutter the main writing view").
 *
 * Governs both surfaces that show a rate:
 * - the main app's status-bar readout ("X.X cps · N wpm")
 * - the IME top bar's compact live-WPM (RFC 0012 direct-entry clause, added
 *   2026-10-30 amendment — the "no persistent strip" exemption predates the
 *   IME's stable top bar)
 *
 * Stored in the app's default SharedPreferences so the main activity and the
 * IME service (same package, same process) share one value.
 */
object TypingRateSetting {
    private const val PREFS = "at.dasher.android_preferences"
    private const val KEY = "show_typing_rate"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, value).apply()
    }
}
