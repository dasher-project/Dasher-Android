package at.dasher.android

/**
 * Coalesces engine output deltas into ONE atomic flush per frame (user bug
 * report #5: "typed letters get completely jumbled up in applications like
 * OneNote and Word, though it works perfectly fine inside AquaMail").
 *
 * Dasher emits text as a stream of small deltas — one commit per node entry,
 * one delete per backspace-class control node. Applied delta-by-delta, each
 * op is a separate binder call that the TARGET app applies on its own
 * pipeline. Editors with synchronous InputConnections (AquaMail's EditText)
 * apply them in order and everything works. OneNote and Word route edits
 * through their own async editing engines (undo coalescing, autosave),
 * where rapid unbatched deltas can land out of order or partially — the
 * field ends up jumbled. (Android's version of the problem Dasher-Windows
 * #17 fixed by forwarding deletions through the target's own input path.)
 *
 * The IME feeds every engine output event here as it fires during
 * dasher_frame(), then flushes ONCE after the frame inside a
 * beginBatchEdit/endBatchEdit pair: the editor sees a single atomic
 * "delete N, insert S" step instead of a burst of unrelated calls.
 *
 * Pure logic — no InputConnection, so the delta arithmetic (including the
 * surrogate-pair and boundary-spanning cases) is unit-testable, following
 * the EditorSync.kt pattern.
 */
class ImeOutputCoalescer {

    /**
     * One atomic edit: delete [deleteUnits] UTF-16 code units before the
     * caret, then insert [insert]. Either half may be empty.
     */
    data class Flush(val deleteUnits: Int, val insert: String)

    private var pendingDeleteUnits = 0
    private val pendingInsert = StringBuilder()

    /**
     * Engine output event (dasher_output_callback semantics from dasher.h):
     * 0 = commit `text`; 1 = delete `text` (a backspace — always removes the
     * buffer TAIL); 2 = buffer clear (dasher_reset / alphabet change — no
     * delta can express it; the IME re-anchors the engine separately, and
     * committed text already belongs to the target field).
     */
    fun onEngineOutput(type: Int, text: String) {
        when (type) {
            0 -> pendingInsert.append(text)
            1 -> {
                if (text.isEmpty()) return
                // The delete removes the engine buffer's tail. That tail is
                // [text already flushed to the field][pending insert]. Split
                // the deleted length across that boundary:
                if (text.length <= pendingInsert.length) {
                    // Entirely inside this frame's pending insert — those
                    // chars never reached the field; just cancel them.
                    pendingInsert.setLength(pendingInsert.length - text.length)
                } else {
                    // Spans past the pending insert into text the field
                    // already holds — cancel all of pending, and delete only
                    // the remainder from the field.
                    pendingDeleteUnits += text.length - pendingInsert.length
                    pendingInsert.setLength(0)
                }
            }
            else -> {} // type 2 (buffer clear) — nothing to apply
        }
    }

    /**
     * The pending edit, or null when nothing accumulated. Resets the state —
     * callers own the returned edit. (UTF-16 units throughout:
     * deleteSurroundingText and String.length agree; a surrogate pair is 2.)
     */
    fun takeFlush(): Flush? {
        if (pendingDeleteUnits == 0 && pendingInsert.isEmpty()) return null
        val flush = Flush(pendingDeleteUnits, pendingInsert.toString())
        pendingDeleteUnits = 0
        pendingInsert.setLength(0)
        return flush
    }
}
