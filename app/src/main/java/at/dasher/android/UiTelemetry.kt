package at.dasher.android

/**
 * RFC 0001 2026-10-28 amendment: UI-interaction telemetry. Two events, both
 * opt-in gated by [AnalyticsService.capture]'s no-op-before-consent, both in
 * the published schema:
 *
 * - `ui_action` — a main-UI control was activated (fixed action vocabulary)
 * - `setting_changed` — a settings change was committed at its widget site
 *   (never for programmatic engine changes). String settings record the
 *   change but never the free-text value.
 *
 * Pure formatting helpers over AnalyticsService — no state.
 */
object UiTelemetry {

    fun action(surface: String, action: String, state: String? = null) {
        val props = buildMap<String, Any> {
            put("surface", surface)
            put("action", action)
            if (state != null) put("state", state)
        }
        AnalyticsService.capture("ui_action", props)
    }

    /** Bool setting committed by the user. */
    fun setting(settingId: String, value: Boolean, surface: String) {
        AnalyticsService.capture("setting_changed", mapOf(
            "setting_id" to settingId,
            "value_type" to "bool",
            "value" to value,
            "surface" to surface,
        ))
    }

    /** Numeric setting committed by the user (speeds, sizes — not PII). */
    fun setting(settingId: String, value: Long, surface: String) {
        AnalyticsService.capture("setting_changed", mapOf(
            "setting_id" to settingId,
            "value_type" to "number",
            "value" to value,
            "surface" to surface,
        ))
    }

    /** Enum-valued setting committed by the user (fixed vocabularies: alphabet ids, palettes, fonts). */
    fun setting(settingId: String, value: String, surface: String) {
        AnalyticsService.capture("setting_changed", mapOf(
            "setting_id" to settingId,
            "value_type" to "enum",
            "value" to value,
            "surface" to surface,
        ))
    }

    /**
     * Free-text/string setting committed by the user — the VALUE is never
     * collected (RFC 0001 amendment: file paths and custom text excluded);
     * only the fact of the change and its surface.
     */
    fun stringSettingChanged(settingId: String, surface: String) {
        AnalyticsService.capture("setting_changed", mapOf(
            "setting_id" to settingId,
            "value_type" to "string",
            "surface" to surface,
        ))
    }
}
