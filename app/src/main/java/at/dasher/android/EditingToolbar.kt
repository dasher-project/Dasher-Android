package at.dasher.android

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout

/**
 * RFC 0019 editing toolbar for the IME (#50): backspace, cursor movement,
 * and clipboard actions — the tools Heide needs so she doesn't "go back
 * and forth with SwiftKey" to fix mistakes.
 *
 * All actions go through InputConnection against the TARGET app's field.
 * After each action, [onBufferChanged] fires so the IME can re-anchor the
 * engine (re-read the target text and re-seed — RFC 0015 tier 2).
 *
 * Edit actions ONLY: mode toggles (emoji, float/dock) live in the IME's
 * top bar so this strip stays a predictable editing cluster.
 *
 * Icons: Lucide (RFC 0002), rasterised for these classic Views via
 * [LucideIcons] — text labels ("⌫", "Sel", "Cp", "Ps") read as clutter and
 * depended on glyph coverage; the icon set matches the main app and the
 * cross-platform design guide. Touch targets stay ≥48dp (#48).
 */
class EditingToolbar(
    context: Context,
    private val inputConnection: () -> android.view.inputmethod.InputConnection?,
    private val onBufferChanged: () -> Unit,
) : LinearLayout(context) {

    private val density = context.resources.displayMetrics.density
    private val nightMode = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    // Design-guide tokens (dasher-design-guide DESIGN.md): border-light/dark,
    // rounded md = 8, highlight as press fill, text/onSurface navy. OnSurface
    // ints live here because the View layer has no MaterialTheme to read from.
    private val onSurface = if (nightMode) 0xFFE6EDF3.toInt() else 0xFF0F4B75.toInt()
    private val border = if (nightMode) 0xFF3A4550.toInt() else 0xFFB8C4C8.toInt()
    private val pressFill = if (nightMode) 0xFF1D2D35.toInt() else 0xFFE9F2F1.toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(if (nightMode) 0xFF2A353D.toInt() else 0xFFE0E6E8.toInt())
        setPadding(dp(4), dp(2), dp(4), dp(2))

        // Buffer-mutating actions re-anchor the engine (buffer changed);
        // non-mutating (Copy, Select All) don't — replacing the edit buffer
        // on every action would reset the model mid-sentence (review 2).
        addTool(R.drawable.ic_lucide_arrow_left, "Move cursor left", mutates = true) { moveCursor(-1) }
        addTool(R.drawable.ic_lucide_arrow_right, "Move cursor right", mutates = true) { moveCursor(1) }
        addSpacer(dp(8))
        addTool(R.drawable.ic_lucide_delete, "Delete", large = true, mutates = true) { backspace() }
        addSpacer(dp(8))
        addTool(R.drawable.ic_lucide_text_select, "Select all", mutates = false) { performAction(android.R.id.selectAll) }
        addTool(R.drawable.ic_lucide_copy, "Copy", mutates = false) { performAction(android.R.id.copy) }
        addTool(R.drawable.ic_lucide_clipboard_paste, "Paste", mutates = true) { performAction(android.R.id.paste) }
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun toolButton(@Suppress("SameParameterValue") iconRes: Int, tooltip: String, large: Boolean): android.widget.ImageButton {
        val size = dp(if (large) 56 else 48)
        return android.widget.ImageButton(context).apply {
            layoutParams = LayoutParams(size, size).apply { marginEnd = dp(2) }
            contentDescription = tooltip
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(LucideIcons.tinted(context, iconRes, onSurface))
            // Bordered pill with a real pressed state — the flat
            // GradientDrawable gave no touch feedback ("can't tell what
            // the icons mean / no hover state").
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(pressFill) }
                )
                addState(
                    intArrayOf(),
                    GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0); setStroke(1, border) }
                )
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = tooltip
        }
    }

    private fun addTool(iconRes: Int, tooltip: String,
                        large: Boolean = false, mutates: Boolean = true, action: () -> Unit) {
        addView(toolButton(iconRes, tooltip, large).apply {
            setOnClickListener {
                action()
                if (mutates) onBufferChanged()
            }
        })
    }

    private fun addSpacer(widthPx: Int) {
        addView(android.view.View(context).apply {
            layoutParams = LayoutParams(widthPx, 1)
        })
    }

    // ── Actions ────────────────────────────────────────────────────────────

    /**
     * Delete one codepoint before the cursor (#50: Heide's #1 request).
     * [InputConnection.deleteSurroundingText] expects UTF-16 code-unit
     * counts; a surrogate pair (emoji, CJK beyond BMP) is 2 units.
     */
    private fun backspace() {
        val ic = inputConnection() ?: return
        val before = ic.getTextBeforeCursor(2, 0)
        val units = when {
            before == null || before.isEmpty() -> return
            before.length >= 2 && Character.isHighSurrogate(before[before.length - 2]) -> 2
            else -> 1
        }
        ic.deleteSurroundingText(units, 0)
    }

    /** Move the cursor one position left/right via the target's key handler (#50). */
    private fun moveCursor(direction: Int) {
        val ic = inputConnection() ?: return
        val keyCode = if (direction < 0) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

    /** Clipboard bridge: Select All / Copy / Paste via the target's context menu (#50). */
    private fun performAction(actionId: Int) {
        inputConnection()?.performContextMenuAction(actionId)
    }
}
