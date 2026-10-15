package at.dasher.android

/**
 * Coalesces engine output deltas into ONE atomic flush per frame (user bug
 * report #5: "typed letters get completely jumbled up in applications like
 * OneNote and Word, though it works perfectly fine inside AquaMail").
 *
 * Dasher emits text as a stream of small deltas — one commit per node entry,
 * one delete per deletion. Applied delta-by-delta, each op is a separate
 * binder call that the TARGET app applies on its own pipeline. Editors with
 * synchronous InputConnections (AquaMail's EditText) apply them in order and
 * everything works. OneNote and Word route edits through their own async
 * editing engines (undo coalescing, autosave), where rapid unbatched deltas
 * can land out of order or partially — the field ends up jumbled. (Android's
 * version of the problem Dasher-Windows #17 fixed by forwarding deletions
 * through the target's own input path.)
 *
 * The IME feeds every engine output event here as it fires during
 * dasher_frame(), then flushes ONCE after the frame inside a
 * beginBatchEdit/endBatchEdit pair: the editor sees a single atomic
 * "delete N, insert S" step instead of a burst of unrelated calls.
 *
 * Delete-event contract (see DasherCore src/CAPI.cpp) and its limits:
 * type-1 events come from two engine paths. `editDelete` is a
 * caret-relative backspace — it erases N units immediately BEFORE
 * cursorPos, which is exactly `deleteSurroundingText(N, 0)` semantics
 * (mid-buffer only after a control-mode ctrlMove moved the cursor — see
 * below). `ctrlDelete` (control-mode delete nodes, if the user enables
 * them) removes a range AT the cursor — possibly spanning it, possibly
 * forwards — but the event carries only the removed string, with no
 * position or direction, so no frontend can express it faithfully. Both
 * non-caret-relative cases are approximated as caret deletes — exactly
 * what the pre-batching code did per-event, so no regression; the proper
 * fix needs the event contract to grow position/direction (DasherCore
 * #100; #101 covers editDelete firing the event even when its guard
 * rejected the erase).
 *
 * Type-2 (buffer clear) DROPS pending deltas — dasher.h says subscribers
 * "must treat this as 'clear your copy'", and the pending state is exactly a
 * shadow copy of the engine buffer. This matters because the flush can strand
 * deltas when the connection is momentarily null (WebView focus churn); a
 * later reset (dasher_seed_buffer fires type-2 on every toolbar reanchor —
 * CAPI_edit.cpp) must not let those stale deltas apply to post-reset context.
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
     * 0 = commit `text`; 1 = delete `text` (see the class doc for the
     * tail-delete assumption and its control-mode limits); 2 = buffer clear
     * (dasher_reset / alphabet change — no delta can express it; the IME
     * re-anchors the engine separately, and committed text already belongs
     * to the target field).
     */
    fun onEngineOutput(type: Int, text: String) {
        when (type) {
            0 -> pendingInsert.append(text)
            1 -> {
                if (text.isEmpty()) return
                // A tail delete removes the engine buffer's tail. That tail
                // is [text already flushed to the field][pending insert].
                // Split the deleted length across that boundary:
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
            else -> clear() // type 2 (buffer clear) — drop the shadow copy; see class doc
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

    /** Drop any pending deltas (e.g. on a field switch — see onStartInput). */
    fun clear() {
        pendingDeleteUnits = 0
        pendingInsert.setLength(0)
    }
}

/**
 * How many KEYCODE_DEL events delete the same text as
 * `deleteSurroundingText(units, 0)` — the fallback path for editors that
 * refuse delta deletions (bug #5 review: OneNote/Word class).
 *
 * One DEL deletes one CHARACTER (a codepoint), not one UTF-16 unit: a
 * surrogate pair (e.g. any emoji — this IME ships an Emoji alphabet) is two
 * units but ONE DEL. Sending `units` DELs would over-delete into the
 * preceding character, so the count is derived from the actual text before
 * the caret.
 *
 * Boundary note (decided behavior, review M-3): if the range start lands
 * INSIDE a surrogate pair (only possible when engine and field are already
 * desynced — this is a fallback of a fallback), the orphaned low surrogate
 * counts as one codepoint. Pinned by test.
 *
 * @param units UTF-16 units the engine wants deleted
 * @param beforeCursor the field text actually before the caret, at least
 *   `units` long from a cooperative editor — shorter text is clamped (the
 *   editor truncated the read; deleting what it reported is the best move)
 * @return the DEL count, or null when no safe count exists (editor returned
 *   no text — connection effectively dead)
 */
fun delKeyEventCount(units: Int, beforeCursor: String?): Int? {
    if (units <= 0) return 0
    if (beforeCursor == null) return null
    val from = (beforeCursor.length - units).coerceAtLeast(0)
    return Character.codePointCount(beforeCursor, from, beforeCursor.length)
}

/**
 * The DEL-fallback decision for one flush (review M-4 — extracted so the
 * cap boundary is unit-testable, not buried in the service).
 * [DelFallback.Send] means `keyEvents` DOWN/UP pairs (i.e. 2×keyEvents
 * `sendKeyEvent` calls) inside the batch edit.
 */
sealed interface DelFallback {
    data class Send(val keyEvents: Int) : DelFallback
    /** Editor returned no text before the caret — no safe count exists. */
    object SkipNoText : DelFallback
    /** Bulk delete over the flood cap — skipped to avoid jank/ANR. */
    data class SkipOverCap(val requested: Int, val cap: Int) : DelFallback
}

fun delFallbackPlan(dels: Int?, cap: Int): DelFallback = when {
    dels == null -> DelFallback.SkipNoText
    dels > cap -> DelFallback.SkipOverCap(dels, cap)
    else -> DelFallback.Send(dels)
}
