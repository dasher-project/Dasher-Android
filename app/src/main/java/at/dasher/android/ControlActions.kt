package at.dasher.android

/**
 * RFC 0021 — control-mode move/delete/select as *measured* pure functions.
 *
 * The engine passes the control-tree action through verbatim
 * (`forward` = "yes"|"no", `dist` = "char"|"word"|"sentence"|"paragraph"|
 * "all") and parses nothing; the frontend measures the affected range over
 * the text it owns (the IME's target field via `getExtractedText`, or the
 * in-app editor's `TextFieldValue`) and applies it through the platform's
 * native selection/edit APIs.
 *
 * Boundary rules mirror the engine-side `capi::getRange`
 * (DasherCore src/CAPI_edit.cpp): word separators are ` \t\v\f\r\n`,
 * sentence separators `.?!\r\n`, paragraph separators `\r\n` (each a
 * single-char match — `\r\n` is one separator UNIT only in the sense that
 * both its chars separate), and "all" runs to the text bounds. Mirroring
 * the engine keeps Android's measurements in step with what the
 * GTK/Windows frontends measure for the same node.
 *
 * Units are UTF-16 code units with codepoint STEPPING (a surrogate pair
 * moves as one, never splitting a pair) — the engine walks UTF-8 bytes as
 * whole codepoints, and Android text APIs count UTF-16 units.
 *
 * Pure JVM — no Android dependencies — so every distance × direction is
 * unit-testable ([ControlActionsTest]).
 */

/** Granularity carried by the action's `dist` attribute. */
enum class ControlDistance { CHAR, WORD, SENTENCE, PARAGRAPH, ALL }

/** Extension direction carried by the action's `forward` attribute. */
enum class ControlDirection { BACKWARD, FORWARD }

/** One parsed control action: the callback name plus its two attributes. */
data class ControlAction(
    val name: String,
    val dist: ControlDistance,
    val forward: ControlDirection,
) {
    /** RFC 0001 vocabulary for the control-node telemetry event. */
    val telemetryId: String
        get() = "${name}_${dist.name.lowercase()}_" +
            if (forward == ControlDirection.FORWARD) "forward" else "back"
}

/**
 * Parse the engine callback's name + attributes. The engine validates NO
 * attributes for custom actions (a typo'd `dist` fails silently at
 * runtime), so unknown values return null — the caller logs and no-ops
 * rather than crashing (the same contract Windows' `SelectActions.Parse`
 * pins).
 *
 * @param attrs the marshalled attribute pairs, `key=value` joined with
 *   `\n` (see jni_bridge.cpp: the value vocabulary is the fixed
 *   control.xml set `forward`/`dist`, so neither `=` nor `\n` can occur
 *   in a value)
 */
fun parseControlAction(name: String, attrs: String): ControlAction? {
    if (name != "move" && name != "delete" && name != "select") return null
    var dist: ControlDistance? = null
    var forward: ControlDirection? = null
    for (line in attrs.lineSequence()) {
        if (line.isEmpty()) continue
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        when (line.take(eq)) {
            "dist" -> dist = when (line.substring(eq + 1)) {
                "char" -> ControlDistance.CHAR
                "word" -> ControlDistance.WORD
                "sentence" -> ControlDistance.SENTENCE
                "paragraph" -> ControlDistance.PARAGRAPH
                "all" -> ControlDistance.ALL
                else -> return null
            }
            "forward" -> forward = when (line.substring(eq + 1)) {
                "yes" -> ControlDirection.FORWARD
                "no" -> ControlDirection.BACKWARD
                else -> return null
            }
        }
    }
    // Missing attributes cannot be defaulted (direction is load-bearing);
    // treat like a typo'd value: log + no-op at the call site.
    if (dist == null || forward == null) return null
    return ControlAction(name, dist, forward)
}

// ── Codepoint stepping (mirror of CAPI_edit.cpp nextChar/prevChar) ─────────

private fun nextCodePoint(text: String, pos: Int): Int {
    if (pos >= text.length) return text.length
    val step =
        if (Character.isHighSurrogate(text[pos]) && pos + 1 < text.length &&
            Character.isLowSurrogate(text[pos + 1])
        ) 2 else 1
    return minOf(pos + step, text.length)
}

private fun prevCodePoint(text: String, pos: Int): Int {
    if (pos <= 0) return 0
    val back =
        if (pos >= 2 && Character.isLowSurrogate(text[pos - 1]) &&
            Character.isHighSurrogate(text[pos - 2])
        ) 2 else 1
    return maxOf(pos - back, 0)
}

// ── Separator sets (verbatim from CAPI_edit.cpp) ────────────────────────────

// \u000B = vertical tab, \u000C = form feed (Kotlin has no \f escape).
private const val WORD_SEPS = " \t\u000B\u000C\r\n"
private const val SENTENCE_SEPS = ".?!\r\n"
private const val PARAGRAPH_SEPS = "\r\n"

private fun isSep(c: Char, seps: String): Boolean = seps.indexOf(c) >= 0

/**
 * Forward search: skip non-separators, then separators (mirror of
 * `findAfter` — the boundary AFTER the run the position sits in).
 */
internal fun findAfter(text: String, pos: Int, seps: String): Int {
    var p = pos.coerceIn(0, text.length)
    while (p < text.length && !isSep(text[p], seps)) p = nextCodePoint(text, p)
    while (p < text.length && isSep(text[p], seps)) p = nextCodePoint(text, p)
    return p
}

/**
 * Backward search: skip separators, then non-separators, and step off any
 * separator the walk lands on (mirror of `findBefore`, including its
 * inclusive `pos` — the caller passes `caret - 1` exactly as getRange
 * does for word-class distances).
 */
internal fun findBefore(text: String, pos: Int, seps: String): Int {
    if (text.isEmpty()) return 0
    var p = if (pos >= text.length) text.length - 1 else pos
    while (p > 0 && isSep(text[p], seps)) p = prevCodePoint(text, p)
    while (p > 0 && !isSep(text[p], seps)) p = prevCodePoint(text, p)
    if (p < text.length && isSep(text[p], seps)) p = nextCodePoint(text, p)
    return p
}

/**
 * The one-sided boundary getRange would move an offset to — the shared
 * measurement under move (caret), delete (range edge) and select
 * (active end).
 */
fun controlBoundary(text: String, from: Int, dist: ControlDistance, forward: ControlDirection): Int {
    val caret = from.coerceIn(0, text.length)
    val seps = when (dist) {
        ControlDistance.WORD -> WORD_SEPS
        ControlDistance.SENTENCE -> SENTENCE_SEPS
        else -> PARAGRAPH_SEPS
    }
    return when (dist) {
        ControlDistance.CHAR ->
            if (forward == ControlDirection.FORWARD) nextCodePoint(text, caret)
            else prevCodePoint(text, caret)
        ControlDistance.WORD, ControlDistance.SENTENCE, ControlDistance.PARAGRAPH ->
            if (forward == ControlDirection.FORWARD) findAfter(text, caret, seps)
            else findBefore(text, if (caret > 0) caret - 1 else 0, seps)
        ControlDistance.ALL ->
            if (forward == ControlDirection.FORWARD) text.length else 0
    }
}

/**
 * Move: the new caret offset for a move action (getRange's moved edge).
 */
fun controlMoveOffset(text: String, caret: Int, dist: ControlDistance, forward: ControlDirection): Int =
    controlBoundary(text, caret, dist, forward)

/**
 * Delete: the span a delete action removes, measured FROM THE CARET
 * (backward deletes end at the caret, forward deletes start at it —
 * nothing beyond the measured distance is touched). Deliberately not
 * selection-relative: replacing an existing selection is the plain
 * Delete editing key's job; the control-tree delete is a
 * measured-distance operation (mirrors Dasher-Windows `DeleteBounds`).
 * Returns an ordered half-open range (`[first, last + 1)` — the standard
 * Kotlin `until` pair); empty when there is nothing to delete in that
 * direction. ALL follows getRange's one-sided rule: backward erases to the
 * text start, forward to the text end (never the whole buffer from a
 * mid-text caret).
 */
fun controlDeleteRange(text: String, caret: Int, dist: ControlDistance, forward: ControlDirection): IntRange {
    val c = caret.coerceIn(0, text.length)
    val target = controlBoundary(text, c, dist, forward)
    return if (forward == ControlDirection.FORWARD) c until target else target until c
}

/**
 * Select: the new selection bounds, implementing RFC 0021's active-end
 * state machine as the same STATELESS grow-only model Dasher-Windows
 * ships (`SelectActions.EditorBounds`): the anchor is the end OPPOSITE
 * the extension direction and stays put; the active end extends to the
 * measured boundary. A forward-then-backward sequence therefore GROWS the
 * selection rather than collapsing it (unlike native Shift+Arrow) — a
 * deliberate AAC choice: monotonic selection is safer for eye-gaze users
 * who cannot easily undo an accidental shrink. Cold start (no prior
 * selection) anchors at the caret.
 *
 * @param hadSelection the field had a non-empty selection before this
 *   action (an empty selection means the caret is the anchor)
 * @return the ordered half-open range (`[first, last + 1)`)
 */
fun controlSelectBounds(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    dist: ControlDistance,
    forward: ControlDirection,
    hadSelection: Boolean,
): IntRange {
    if (dist == ControlDistance.ALL) return 0 until text.length
    val start = selectionStart.coerceIn(0, text.length)
    val end = selectionEnd.coerceIn(start, text.length)
    val anchor: Int
    val active: Int
    if (hadSelection) {
        anchor = if (forward == ControlDirection.BACKWARD) end else start
        active = if (forward == ControlDirection.BACKWARD) start else end
    } else {
        anchor = end
        active = end
    }
    val target = controlBoundary(text, active, dist, forward)
    return minOf(anchor, target) until maxOf(anchor, target)
}
