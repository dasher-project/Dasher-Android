package at.dasher.android

import android.view.KeyEvent
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * RFC 0021 — executes one parsed control action against an
 * [InputConnection] (the IME's target field). Extracted from
 * DasherImeService so the InputConnection choreography is unit-testable
 * with a fake (the `performImeBackspace` / ImeBackspaceTest pattern);
 * the service owns queueing, threading and telemetry.
 *
 * Edit discipline (identical to [performImeBackspace] /
 * DasherImeService.flushOutputToTarget):
 *  - the caller wraps each action in beginBatchEdit/endBatchEdit;
 *  - deletions go through deleteSurroundingText first, with a
 *    DEL/FORWARD_DEL key-event fallback for the editors that refuse (or
 *    lie about) delta deletions — the bug #5 class — capped so a bulk
 *    delete never floods the target with key events;
 *  - deleteSurroundingText's contract is undefined over an ACTIVE
 *    SELECTION (Heide's #50 ⌫ bug), so the executor collapses any
 *    selection to its start before deleting — safe because the executor
 *    holds absolute offsets from getExtractedText.
 *
 * The IME's operative caret is the selection START — the anchor Android's
 * cursor-relative APIs (getTextBeforeCursor…) measure from.
 *
 * @param onBufferChanged invoked after mutations the engine must follow
 *   (delete changed the field text; move changed the caret) — the
 *   service re-anchors from the field (RFC 0015 tier 2). Selections
 *   don't re-anchor (replacing the buffer on every select would reset
 *   the model mid-sentence).
 * @return the telemetry state string ("applied", "noop", "refused",
 *   "no_text", "no_connection" is the caller's, "applied_keys",
 *   "applied_menu", "skipped_cap", "unparseable" is the queue's).
 */
fun executeControlAction(
    ic: InputConnection,
    action: ControlAction,
    onBufferChanged: () -> Unit,
): String = when (action.name) {
    "select" -> executeSelect(ic, action)
    "move" -> executeMove(ic, action, onBufferChanged)
    else -> executeDelete(ic, action, onBufferChanged)
}

/**
 * The target field's text as reported by getExtractedText: the (usually
 * whole) text, its base offset within the field, and the ABSOLUTE
 * selection. Null when the target refuses reads (password fields, some
 * WebViews) — callers take the honest cursor-relative fallback instead
 * of guessing absolute offsets (RFC 0021's platform honesty table).
 */
private class TargetText(val text: String, val base: Int, val selStart: Int, val selEnd: Int)

private fun readTargetText(ic: InputConnection): TargetText? {
    val et = try {
        ic.getExtractedText(ExtractedTextRequest(), 0)
    } catch (e: Exception) {
        null
    } ?: return null
    val text = et.text?.toString() ?: return null
    if (et.selectionStart < 0 || et.selectionEnd < 0) return null
    val base = et.startOffset.coerceAtLeast(0)
    val lo = et.selectionStart.coerceAtMost(et.selectionEnd)
    val hi = et.selectionStart.coerceAtLeast(et.selectionEnd)
    // The reported selection must lie inside the reported window.
    if (lo < 0 || hi > text.length) return null
    return TargetText(text, base, base + lo, base + hi)
}

/** Select: extend the OS selection over the measured range. */
private fun executeSelect(ic: InputConnection, action: ControlAction): String {
    val t = readTargetText(ic)
    if (t == null) {
        // No absolute offsets → only Select All is honestly available (the
        // target's own context-menu action). Measured granularities need
        // the full-text read; anything else no-ops rather than guessing.
        if (action.dist == ControlDistance.ALL &&
            ic.performContextMenuAction(android.R.id.selectAll)
        ) return "applied_menu"
        return "no_text"
    }
    val range = controlSelectBounds(
        t.text,
        t.selStart - t.base,
        t.selEnd - t.base,
        action.dist,
        action.forward,
        hadSelection = t.selEnd > t.selStart,
    )
    return if (ic.setSelection(t.base + range.first, t.base + range.last + 1)) "applied"
    else "refused"
}

/** Move: collapse the selection to the measured caret. */
private fun executeMove(ic: InputConnection, action: ControlAction, onBufferChanged: () -> Unit): String {
    val t = readTargetText(ic)
    if (t == null) {
        // Cursor-relative fallback: one DPAD hop is exact for char; larger
        // granularities have no honest mapping without text reads.
        return if (action.dist == ControlDistance.CHAR) {
            val key = if (action.forward == ControlDirection.FORWARD)
                KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            onBufferChanged()
            "applied_keys"
        } else {
            "no_text"
        }
    }
    // Move OFF the selection in the direction of travel (forward from the
    // selection end, backward from its start — the native arrow-collapse
    // expectation); with a collapsed caret both ends coincide.
    val from = if (action.forward == ControlDirection.FORWARD) t.selEnd else t.selStart
    val target = controlMoveOffset(t.text, from - t.base, action.dist, action.forward)
    if (target == from - t.base) return "noop"
    val ok = ic.setSelection(t.base + target, t.base + target)
    if (ok) onBufferChanged()
    return if (ok) "applied" else "refused"
}

/** Delete: measure from the caret, apply as a surrounding delete. */
private fun executeDelete(ic: InputConnection, action: ControlAction, onBufferChanged: () -> Unit): String {
    val t = readTargetText(ic)
    if (t == null) return deleteCursorRelative(ic, action, onBufferChanged)
    // Direction-facing anchor over a selection: backward deletes BEFORE
    // the selection start, forward AFTER its end (the selection itself is
    // the plain Delete key's job — the control-tree delete is a
    // measured-distance operation by design). deleteSurroundingText is
    // undefined over an ACTIVE selection (Heide's #50 ⌫ bug), so collapse
    // to that anchor first — safe, we hold absolute offsets.
    val caret = (if (action.forward == ControlDirection.FORWARD) t.selEnd else t.selStart) - t.base
    if (t.selEnd > t.selStart) ic.setSelection(t.base + caret, t.base + caret)
    val range = controlDeleteRange(t.text, caret, action.dist, action.forward)
    if (range.isEmpty()) return "noop"
    val units = range.last + 1 - range.first
    val backward = action.forward == ControlDirection.BACKWARD
    // One-sided by construction (controlDeleteRange measures from the
    // caret), so plain surrounding deletes cover both directions.
    return if (ic.deleteSurroundingText(if (backward) units else 0, if (backward) 0 else units)) {
        onBufferChanged()
        "applied"
    } else {
        // The OneNote/Word class refuses (or lies about — bug #5) delta
        // deletions; fall back to key events through the target's own
        // pipeline, flood-capped.
        delEventFallback(
            ic, action, backward,
            t.text.substring(range.first, range.last + 1),
            onBufferChanged,
        )
    }
}

/**
 * Fallback delete without extracted text: measure over a bounded
 * cursor-relative window (getTextBefore/AfterCursor) and apply as one
 * surrounding delete — no absolute offsets needed. The window bounds the
 * measurement (a paragraph longer than the window truncates at its edge)
 * AND the read itself (a hostile editor can't make the IME copy the
 * whole field).
 */
private fun deleteCursorRelative(
    ic: InputConnection,
    action: ControlAction,
    onBufferChanged: () -> Unit,
): String {
    val before = ic.getTextBeforeCursor(DELETE_FALLBACK_WINDOW, 0)?.toString() ?: return "no_text"
    val after = ic.getTextAfterCursor(DELETE_FALLBACK_WINDOW, 0)?.toString() ?: return "no_text"
    val window = before + after
    val caret = before.length
    val range = controlDeleteRange(window, caret, action.dist, action.forward)
    if (range.isEmpty()) return "noop"
    val units = range.last + 1 - range.first
    val backward = action.forward == ControlDirection.BACKWARD
    return if (ic.deleteSurroundingText(if (backward) units else 0, if (backward) 0 else units)) {
        onBufferChanged()
        "applied"
    } else {
        delEventFallback(ic, action, backward, window.substring(range.first, range.last + 1), onBufferChanged)
    }
}

/**
 * DEL / FORWARD_DEL key-event fallback. One event deletes one CODEPOINT,
 * so the count comes from the measured range's text (never UTF-16 units
 * — a surrogate pair is one DEL). Over the cap, skip and re-anchor: the
 * engine/field desync is recoverable, an ANR is not (the same decision
 * as the output path's DelFallback.SkipOverCap).
 */
private fun delEventFallback(
    ic: InputConnection,
    action: ControlAction,
    backward: Boolean,
    rangeText: String,
    onBufferChanged: () -> Unit,
): String {
    val dels = Character.codePointCount(rangeText, 0, rangeText.length)
    when (delFallbackPlan(dels, MAX_CONTROL_DEL_KEY_EVENTS)) {
        is DelFallback.Send -> {
            val key = if (backward) KeyEvent.KEYCODE_DEL else KeyEvent.KEYCODE_FORWARD_DEL
            repeat(dels) {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }
            onBufferChanged()
            return "applied_keys"
        }
        is DelFallback.SkipOverCap -> return "skipped_cap"
        DelFallback.SkipNoText -> return "no_text"
    }
}

/** Same caps as the IME's output path (DasherImeService.MAX_DEL_KEY_EVENTS). */
internal const val MAX_CONTROL_DEL_KEY_EVENTS = 64

/**
 * Cursor-relative window (UTF-16 units) for the no-extracted-text delete
 * fallback: large enough that word/sentence/paragraph boundaries near the
 * caret are inside it, bounded so a hostile editor can't make the IME copy
 * unbounded field text.
 */
internal const val DELETE_FALLBACK_WINDOW = 4000
