package at.dasher.android

/**
 * Design-guide tokens (dasher-design-guide DESIGN.md) for the IME's classic-
 * View toolbars, as one definition shared by DasherImeService and
 * EditingToolbar (#78 review M4 — the hexes previously lived twice and a
 * third copy would have drifted).
 *
 * The border stroke is deliberately a DARKENED variant of the border token
 * (#E0E6E8/#2A353D): the token itself doubles as the toolbar strip
 * background, and a same-colour stroke on it is invisible.
 */
object ImeToolbarPalette {
    fun onSurface(nightMode: Boolean): Int =
        if (nightMode) 0xFFE6EDF3.toInt() else 0xFF0F4B75.toInt() // text token

    fun border(nightMode: Boolean): Int =
        if (nightMode) 0xFF3A4550.toInt() else 0xFFB8C4C8.toInt() // darkened border

    fun pressFill(nightMode: Boolean): Int =
        if (nightMode) 0xFF1D2D35.toInt() else 0xFFE9F2F1.toInt() // highlight tokens
}
