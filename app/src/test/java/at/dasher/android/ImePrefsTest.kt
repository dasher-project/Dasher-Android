package at.dasher.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mode-restore gate (#75 review I-4): attempt #3 at the float-mode bug —
 * a silent reorder of these clauses (e.g. saved-mode checked before the
 * always-docked pin) would break the escape hatch again. Truth table pinned.
 */
class ImePrefsTest {

    @Test
    fun default_state_stays_docked() {
        assertFalse(shouldRestoreFloating(savedMode = null, alwaysDocked = false, overlayPermissionGranted = true))
    }

    @Test
    fun docked_pref_stays_docked() {
        assertFalse(shouldRestoreFloating(ImePrefs.START_MODE_DOCKED, alwaysDocked = false, overlayPermissionGranted = true))
    }

    @Test
    fun floating_pref_restores_float() {
        assertTrue(shouldRestoreFloating(ImePrefs.START_MODE_FLOATING, alwaysDocked = false, overlayPermissionGranted = true))
    }

    @Test
    fun always_docked_pin_overrides_a_floating_pref() {
        // Heide's escape hatch: three reports of unwanted float — the pin
        // wins regardless of what was saved.
        assertFalse(shouldRestoreFloating(ImePrefs.START_MODE_FLOATING, alwaysDocked = true, overlayPermissionGranted = true))
    }

    @Test
    fun no_overlay_permission_never_restores_float() {
        // Without SYSTEM_ALERT_WINDOW the restore would bounce the user to
        // system settings — never auto-enter floating.
        assertFalse(shouldRestoreFloating(ImePrefs.START_MODE_FLOATING, alwaysDocked = false, overlayPermissionGranted = false))
    }

    @Test
    fun unset_saved_mode_never_restores_even_with_the_pin_redundant() {
        // Same inputs as default_state_stays_docked but WITH the pin set:
        // the pin must not somehow force floating (name true to inputs).
        assertFalse(shouldRestoreFloating(null, alwaysDocked = true, overlayPermissionGranted = true))
    }
}
