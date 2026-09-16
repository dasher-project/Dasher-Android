package at.dasher.android

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.LinearLayout

/**
 * RFC 0019 editing toolbar for the IME (#50): backspace, cursor movement,
 * and clipboard actions — the tools Heide needs so she doesn't "go back
 * and forth with SwiftKey" to fix mistakes.
 *
 * All actions go through [InputConnection] against the TARGET app's field.
 * After each action, [onBufferChanged] fires so the IME can re-anchor the
 * engine (re-read the target text and re-seed — RFC 0015 tier 2).
 */
class EditingToolbar(
    context: Context,
    private val inputConnection: () -> InputConnection?,
    private val onBufferChanged: () -> Unit,
) : LinearLayout(context) {

    private val density = context.resources.displayMetrics.density
    private val nightMode = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(if (nightMode) 0xFF2A353D.toInt() else 0xFFE0E6E8.toInt())
        setPadding(dp(4), dp(2), dp(4), dp(2))

        // Buffer-mutating actions re-anchor the engine (buffer changed);
        // non-mutating (Copy, Select All) don't — replacing the edit buffer
        // on every action would reset the model mid-sentence (review 2).
        addTool("←", "Move cursor left", mutates = true) { moveCursor(-1) }
        addTool("→", "Move cursor right", mutates = true) { moveCursor(1) }
        addSpacer(dp(8))
        addTool("⌫", "Delete", large = true, mutates = true) { backspace() }
        addSpacer(dp(8))
        addTool("Sel", "Select all", mutates = false) { performAction(android.R.id.selectAll) }
        addTool("Cp", "Copy", mutates = false) { performAction(android.R.id.copy) }
        addTool("Ps", "Paste", mutates = true) { performAction(android.R.id.paste) }
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun toolButton(label: String, tooltip: String, large: Boolean): Button {
        val width = if (large) dp(56) else dp(48)
        return Button(context).apply {
            text = label
            textSize = if (large) 20f else 14f
            isAllCaps = false
            minimumWidth = width // exact width; LayoutParams below uses this
            minimumHeight = dp(48) // #48: 48dp minimum touch target for motor-impaired users
            setPadding(dp(4), dp(2), dp(4), dp(2))
            contentDescription = tooltip
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0)
                setStroke(1, if (nightMode) 0xFF3A4550.toInt() else 0xFFB8C4C8.toInt())
            }
            layoutParams = LayoutParams(width, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(2)
            }
        }
    }

    private fun addTool(label: String, tooltip: String, large: Boolean = false,
                        mutates: Boolean = true, action: () -> Unit) {
        addView(toolButton(label, tooltip, large).apply {
            setOnClickListener {
                action()
                if (mutates) onBufferChanged()
            }
        })
    }

    private fun addSpacer(widthPx: Int) {
        addView(View(context).apply {
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
        val keyCode = if (direction < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /** Clipboard bridge: Select All / Copy / Paste via the target's context menu (#50). */
    private fun performAction(actionId: Int) {
        inputConnection()?.performContextMenuAction(actionId)
    }
}
