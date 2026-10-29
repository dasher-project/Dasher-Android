package at.dasher.android

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.widget.ImageButton
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
    private val inputConnection: () -> InputConnection?,
    private val onBufferChanged: () -> Unit,
) : LinearLayout(context) {

    private val density = context.resources.displayMetrics.density
    private val nightMode = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    // Design-guide tokens via the shared palette (#78 review M4): see
    // ImeToolbarPalette — one definition, used by both IME toolbars.
    private val onSurface = ImeToolbarPalette.onSurface(nightMode)
    private val border = ImeToolbarPalette.border(nightMode)
    private val pressFill = ImeToolbarPalette.pressFill(nightMode)

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
        addTool(R.drawable.ic_lucide_delete, "Delete", large = true, mutates = true, logTelemetry = false) {
            val ic = inputConnection() ?: return@addTool
            // Telemetry distinguishes selection-delete from caret backspace
            // (review M4 — the distinction that motivated this fix).
            val state = when (performImeBackspace(ic)) {
                ImeBackspaceOutcome.SelectionDeleted -> "selection"
                ImeBackspaceOutcome.CaretDeleted -> "char"
                ImeBackspaceOutcome.Noop -> "none"
            }
            UiTelemetry.action("ime_toolbar", "backspace", state)
        }
        addSpacer(dp(8))
        addTool(R.drawable.ic_lucide_text_select, "Select all", mutates = false) { performAction(android.R.id.selectAll) }
        addTool(R.drawable.ic_lucide_copy, "Copy", mutates = false) { performAction(android.R.id.copy) }
        addTool(R.drawable.ic_lucide_clipboard_paste, "Paste", mutates = true) { performAction(android.R.id.paste) }
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun toolButton(iconRes: Int, tooltip: String, large: Boolean): ImageButton {
        val size = dp(if (large) 56 else 48)
        return ImageButton(context).apply {
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
                        large: Boolean = false, mutates: Boolean = true,
                        logTelemetry: Boolean = true, action: () -> Unit) {
        addView(toolButton(iconRes, tooltip, large).apply {
            setOnClickListener {
                if (logTelemetry) {
                    // RFC 0001 amendment: which IME editing tools are actually
                    // used (Heide's SwiftKey round-trips suggested these matter).
                    UiTelemetry.action("ime_toolbar", actionIdFor(tooltip))
                }
                action()
                if (mutates) onBufferChanged()
            }
        })
    }

    private fun actionIdFor(tooltip: String): String = when (tooltip) {
        "Move cursor left" -> "cursor_left"
        "Move cursor right" -> "cursor_right"
        "Delete" -> "backspace"
        "Select all" -> "select_all"
        "Copy" -> "copy"
        "Paste" -> "paste"
        else -> tooltip.lowercase().replace(' ', '_')
    }

    private fun addSpacer(widthPx: Int) {
        addView(android.view.View(context).apply {
            layoutParams = LayoutParams(widthPx, 1)
        })
    }

    // ── Actions ────────────────────────────────────────────────────────────

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

/**
 * The toolbar ⌫, extracted so its contract is unit-testable with a fake
 * [InputConnection] (the EditorSync.kt / ImeOutputCoalescer.kt pattern).
 *
 * Two cases (Heide, 2026-10-29: "if you select something, the delete button
 * doesn't do anything — it only works when you don't have anything
 * selected"):
 * - SELECTION: [InputConnection.deleteSurroundingText] cannot affect a
 *   selection (it deletes BEFORE the selection start — a precise no-op when
 *   the selection starts at the field beginning, i.e. Select All).
 *   commitText("") replaces the selection with nothing. Review loop 1 (I1):
 *   the editors documented hostile in bug #5 return TRUE while doing
 *   nothing, so a true commit is re-verified — if the selection survived,
 *   the DEL key-event fallback fires anyway (at worst a lying editor that
 *   DID delete gets one extra char — the lesser evil vs. a dead ⌫).
 * - CARET: one codepoint back — deleteSurroundingText with UTF-16 unit
 *   counts (a surrogate pair, e.g. any emoji, is 2).
 *
 * @return the outcome, for telemetry (selection vs caret distinction).
 */
sealed interface ImeBackspaceOutcome {
    /** The selection (if any) was deleted. */
    object SelectionDeleted : ImeBackspaceOutcome
    /** One codepoint before the caret was deleted. */
    object CaretDeleted : ImeBackspaceOutcome
    /** Nothing to delete (empty selection + empty before-cursor). */
    object Noop : ImeBackspaceOutcome
}

fun performImeBackspace(ic: InputConnection): ImeBackspaceOutcome {
    val selected = ic.getSelectedText(0)
    if (selected != null && selected.isNotEmpty()) {
        val committed = ic.commitText("", 1)
        // Bug #5's lesson: a `true` return does not mean it happened.
        val survived = ic.getSelectedText(0)
        if (!committed || (survived != null && survived.isNotEmpty())) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        return ImeBackspaceOutcome.SelectionDeleted
    }
    val before = ic.getTextBeforeCursor(2, 0)
    val units = when {
        before == null || before.isEmpty() -> return ImeBackspaceOutcome.Noop
        before.length >= 2 && Character.isHighSurrogate(before[before.length - 2]) -> 2
        else -> 1
    }
    ic.deleteSurroundingText(units, 0)
    return ImeBackspaceOutcome.CaretDeleted
}
