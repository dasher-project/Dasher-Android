package at.dasher.android

/**
 * RFC 0019 (the editor contract) — pure decision logic for synchronising the
 * editable output field with the engine, extracted so it is unit-testable
 * without an engine or Compose. The composable supplies the inputs; this
 * decides the action.
 *
 * The engine pushes its full buffer text EVERY FRAME (polled in doFrame), so:
 * - origin discrimination is "does the field still match what we last
 *   pushed" — an engine push landing changes nothing, a user edit does;
 * - seeding is immediate, never debounced: a debounced edit would be
 *   clobbered by the next frame's push before the timer fired (same trade
 *   as Dasher-Windows#56).
 */
sealed interface EditorSyncAction {
    /** A user edit: replace the buffer, anchored at the caret. */
    data class Seed(val text: String, val caretUtf16: Int) : EditorSyncAction

    /** A pure caret move with text already in sync: re-anchor the model. */
    data class Reanchor(val caretUtf16: Int) : EditorSyncAction

    /** Nothing to do (engine echo, composition in progress, or out of sync). */
    object None : EditorSyncAction
}

/**
 * @param textChanged the field's text differs from the last value we saw
 * @param selectionChanged only the selection/caret moved
 * @param fieldMatchesEngine the field text equals the engine buffer
 *   (CR-normalised: the engine emits CRLF, the field holds LF)
 * @param composing an IME composition is in progress — RFC 0019 clause 2:
 *   defer seeding until commit
 * @param engineTextEmpty the engine buffer is empty (nothing to re-anchor)
 */
fun editorSyncAction(
    textChanged: Boolean,
    selectionChanged: Boolean,
    fieldMatchesEngine: Boolean,
    composing: Boolean,
    engineTextEmpty: Boolean,
    text: String,
    caretUtf16: Int,
): EditorSyncAction = when {
    composing -> EditorSyncAction.None
    textChanged -> EditorSyncAction.Seed(text, caretUtf16)
    selectionChanged && fieldMatchesEngine && !engineTextEmpty -> EditorSyncAction.Reanchor(caretUtf16)
    else -> EditorSyncAction.None
}

/** CR-normalised comparison: the engine emits CRLF newlines, the field holds LF. */
fun editorTextEquals(pane: String, engine: String): Boolean {
    if (pane == engine) return true
    return pane.replace("\r", "") == engine.replace("\r", "")
}
