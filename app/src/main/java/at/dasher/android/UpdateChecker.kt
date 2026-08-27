package at.dasher.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * RFC 0017: passive in-app update check for self-managed builds (GitHub
 * Releases APKs). Runs on a background coroutine after the Activity is up,
 * at most once per week. On a newer release, shows a notification with a
 * link — never a download, never a modal.
 *
 * The future Play Store flavour skips the check entirely (Play notifies);
 * the direct-APK build uses this class.
 */
object UpdateChecker {
    private const val PREFS = "dasher_update_check"
    private const val KEY_LAST_CHECK = "last_check_epoch"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SKIP_VERSION = "skip_version"
    private const val CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    private const val API_URL = "https://api.github.com/repos/dasher-project/Dasher-Android/releases?per_page=1"
    private const val RELEASES_PAGE = "https://github.com/dasher-project/Dasher-Android/releases/latest"

    data class UpdateInfo(
        val available: Boolean,
        val latestTag: String,
        val currentVersion: String,
        val releaseUrl: String,
        val releaseName: String,
    )

    fun shouldCheck(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, true)) return false
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
    }

    fun recordCheck(context: Context, skipVersion: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
        if (skipVersion != null) {
            prefs.edit().putString(KEY_SKIP_VERSION, skipVersion).apply()
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun getSkippedVersion(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIP_VERSION, null)
    }

    /**
     * Compare two version strings semantically (ignoring leading "v").
     * Pure logic — unit testable.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val l = latest.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    /**
     * Fetch the latest release from GitHub. Suspend function — call from a
     * coroutine scope. Returns UpdateInfo with available=false on any error.
     */
    suspend fun check(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Dasher-Android/$currentVersion")
            conn.setRequestProperty("Accept", "application/vnd.github+json")

            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext UpdateInfo(false, "", currentVersion, RELEASES_PAGE, "")
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val releases = org.json.JSONArray(body)
            if (releases.length() == 0) {
                return@withContext UpdateInfo(false, "", currentVersion, RELEASES_PAGE, "")
            }

            val release = releases.getJSONObject(0)
            val tag = release.optString("tag_name", "")
            val url = release.optString("html_url", RELEASES_PAGE)
            val name = release.optString("name", "")

            UpdateInfo(
                available = tag.isNotEmpty() && isNewer(tag, currentVersion),
                latestTag = tag,
                currentVersion = currentVersion,
                releaseUrl = url,
                releaseName = name,
            )
        } catch (e: Exception) {
            UpdateInfo(false, "", currentVersion, RELEASES_PAGE, "")
        }
    }
}
