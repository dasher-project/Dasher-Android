package at.dasher.android

/**
 * SharedPreferences contract shared by the IME service, MainActivity and the
 * Settings screen (#75). One definition — a typo'd literal in any one of the
 * three call sites silently broke the always-docked gate, which is the
 * escape hatch for a user who has reported unwanted float mode three times.
 */
object ImePrefs {
    const val NAME = "dasher_ime"

    /** Last dock/float choice: "docked" (default) or "floating". */
    const val START_MODE = "start_mode"
    const val START_MODE_DOCKED = "docked"
    const val START_MODE_FLOATING = "floating"

    /**
     * #75 escape hatch: the keyboard reopens docked on every show,
     * regardless of [START_MODE]. Set from Settings → Input.
     */
    const val ALWAYS_DOCKED = "always_docked"

    /** Emoji mode (#61 option 2): the alphabet to return to. */
    const val EMOJI_RETURN_ALPHABET = "emoji_return_alphabet"
}

/**
 * The mode-restore decision, extracted so the truth table is unit-testable
 * (#75 review I-4): this is attempt #3 at the float-mode bug — a reorder of
 * these clauses (e.g. start_mode checked before always_docked) would
 * silently break the escape hatch again.
 *
 * Pure — no Context, follows the EditorSync.kt / ImeOutputCoalescer.kt pattern.
 */
fun shouldRestoreFloating(
    savedMode: String?,
    alwaysDocked: Boolean,
    overlayPermissionGranted: Boolean,
): Boolean = !alwaysDocked && savedMode == ImePrefs.START_MODE_FLOATING && overlayPermissionGranted
