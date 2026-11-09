package at.dasher.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RFC 0021 control-action semantics (pure functions — no engine, no
 * Android). The boundary cases pin parity with the engine-side
 * `capi::getRange` (DasherCore src/CAPI_edit.cpp): a distance measured
 * here must move/select/delete exactly what the same node does on the
 * GTK/Windows frontends.
 */
class ControlActionsTest {

    // ── parseControlAction ────────────────────────────────────────────────

    @Test
    fun parse_accepts_every_name_distance_and_direction() {
        for (name in listOf("move", "delete", "select")) {
            for ((attr, dist) in mapOf(
                "char" to ControlDistance.CHAR,
                "word" to ControlDistance.WORD,
                "sentence" to ControlDistance.SENTENCE,
                "paragraph" to ControlDistance.PARAGRAPH,
                "all" to ControlDistance.ALL,
            )) {
                assertEquals(
                    ControlAction(name, dist, ControlDirection.FORWARD),
                    parseControlAction(name, "dist=$attr\nforward=yes"),
                )
                assertEquals(
                    ControlAction(name, dist, ControlDirection.BACKWARD),
                    parseControlAction(name, "dist=$attr\nforward=no"),
                )
            }
        }
    }

    @Test
    fun parse_accepts_either_attribute_order() {
        // The engine marshals a std::map (sorted: dist before forward), but
        // the contract is key=value pairs — pin that order is irrelevant.
        assertEquals(
            parseControlAction("select", "dist=word\nforward=yes"),
            parseControlAction("select", "forward=yes\ndist=word"),
        )
    }

    @Test
    fun parse_rejects_unknown_values_and_names() {
        // The engine validates NO attributes: a typo'd dist must no-op the
        // frontend, never crash it.
        assertNull(parseControlAction("select", "dist=sentance\nforward=yes"))
        assertNull(parseControlAction("move", "dist=char\nforward=maybe"))
        assertNull(parseControlAction("copy", "dist=char\nforward=yes"))
        assertNull(parseControlAction("select", "")) // missing both attrs
        assertNull(parseControlAction("select", "dist=char")) // missing forward
        assertNull(parseControlAction("select", "forward=yes")) // missing dist
        assertNull(parseControlAction("select", "dist")) // malformed line
    }

    @Test
    fun telemetry_id_uses_the_rfc_0001_vocabulary() {
        assertEquals(
            "select_word_forward",
            ControlAction("select", ControlDistance.WORD, ControlDirection.FORWARD).telemetryId,
        )
        assertEquals(
            "delete_paragraph_back",
            ControlAction("delete", ControlDistance.PARAGRAPH, ControlDirection.BACKWARD).telemetryId,
        )
    }

    // ── char distance: codepoint stepping, never splitting a pair ─────────

    @Test
    fun char_forward_steps_one_codepoint() {
        assertEquals(1, controlMoveOffset("ab", 0, ControlDistance.CHAR, ControlDirection.FORWARD))
        assertEquals(2, controlMoveOffset("ab", 1, ControlDistance.CHAR, ControlDirection.FORWARD))
        // At the end: stays.
        assertEquals(2, controlMoveOffset("ab", 2, ControlDistance.CHAR, ControlDirection.FORWARD))
    }

    @Test
    fun char_backward_steps_one_codepoint() {
        assertEquals(1, controlMoveOffset("ab", 2, ControlDistance.CHAR, ControlDirection.BACKWARD))
        assertEquals(0, controlMoveOffset("ab", 1, ControlDistance.CHAR, ControlDirection.BACKWARD))
        // At the start: stays.
        assertEquals(0, controlMoveOffset("ab", 0, ControlDistance.CHAR, ControlDirection.BACKWARD))
    }

    @Test
    fun char_moves_a_supplementary_codepoint_as_one() {
        // 😀 is one codepoint, two UTF-16 units (a surrogate pair).
        val text = "a😀b"
        assertEquals(3, controlMoveOffset(text, 1, ControlDistance.CHAR, ControlDirection.FORWARD))
        assertEquals(1, controlMoveOffset(text, 3, ControlDistance.CHAR, ControlDirection.BACKWARD))
    }

    @Test
    fun char_ignores_a_caret_landed_mid_pair() {
        // Only possible when engine and field are already desynced; the
        // step must still land on a codepoint boundary.
        val text = "😀"
        assertEquals(2, controlMoveOffset(text, 1, ControlDistance.CHAR, ControlDirection.FORWARD))
        assertEquals(0, controlMoveOffset(text, 1, ControlDistance.CHAR, ControlDirection.BACKWARD))
    }

    @Test
    fun delete_char_over_a_surrogate_pair_never_splits_it() {
        val text = "a😀b"
        assertEquals(1 until 3, controlDeleteRange(text, 3, ControlDistance.CHAR, ControlDirection.BACKWARD))
        assertEquals(3 until 4, controlDeleteRange(text, 3, ControlDistance.CHAR, ControlDirection.FORWARD))
        assertEquals(0 until 1, controlDeleteRange(text, 1, ControlDistance.CHAR, ControlDirection.BACKWARD))
        assertEquals(1 until 3, controlDeleteRange(text, 1, ControlDistance.CHAR, ControlDirection.FORWARD))
    }

    // ── word distance ─────────────────────────────────────────────────────

    @Test
    fun word_forward_lands_on_the_next_word_start() {
        val text = "hello  world" // two spaces: 0-4 hello, 5-6 spaces, 7-11 world
        assertEquals(7, controlMoveOffset(text, 0, ControlDistance.WORD, ControlDirection.FORWARD))
        assertEquals(7, controlMoveOffset(text, 3, ControlDistance.WORD, ControlDirection.FORWARD))
        // Sitting ON the separator run: skips it.
        assertEquals(7, controlMoveOffset(text, 5, ControlDistance.WORD, ControlDirection.FORWARD))
        // Inside/after the last word: end of text.
        assertEquals(12, controlMoveOffset(text, 7, ControlDistance.WORD, ControlDirection.FORWARD))
        assertEquals(12, controlMoveOffset(text, 12, ControlDistance.WORD, ControlDirection.FORWARD))
    }

    @Test
    fun word_backward_lands_on_a_word_start() {
        val text = "hello  world"
        assertEquals(7, controlMoveOffset(text, 11, ControlDistance.WORD, ControlDirection.BACKWARD))
        // From the START of "world": the walk crosses the separator run and
        // lands on the start of "hello" (getRange's chained-hop semantics).
        assertEquals(0, controlMoveOffset(text, 7, ControlDistance.WORD, ControlDirection.BACKWARD))
        assertEquals(0, controlMoveOffset(text, 4, ControlDistance.WORD, ControlDirection.BACKWARD))
        // Caret right after the separator run (on the first space).
        assertEquals(0, controlMoveOffset(text, 5, ControlDistance.WORD, ControlDirection.BACKWARD))
        assertEquals(0, controlMoveOffset(text, 0, ControlDistance.WORD, ControlDirection.BACKWARD))
    }

    @Test
    fun word_boundaries_over_tabs_and_newlines() {
        // kWordSeps = " \t\v\f\r\n" — all count.
        val text = "a\tb\nc"
        assertEquals(2, controlMoveOffset(text, 0, ControlDistance.WORD, ControlDirection.FORWARD))
        assertEquals(4, controlMoveOffset(text, 2, ControlDistance.WORD, ControlDirection.FORWARD))
        assertEquals(2, controlMoveOffset(text, 4, ControlDistance.WORD, ControlDirection.BACKWARD))
        assertEquals(0, controlMoveOffset(text, 2, ControlDistance.WORD, ControlDirection.BACKWARD))
    }

    // ── sentence distance ─────────────────────────────────────────────────

    @Test
    fun sentence_stops_at_terminators_and_newlines() {
        // kSentenceSeps = ".?!\r\n" — SPACES ARE NOT sentence separators:
        // forward stops right AFTER the terminator ('.' belongs to the
        // sentence, the following space to the next one); the backward
        // walk crosses spaces to the previous . ? ! or newline.
        val text = "One. Two? Three!"
        assertEquals(4, controlMoveOffset(text, 0, ControlDistance.SENTENCE, ControlDirection.FORWARD))
        assertEquals(9, controlMoveOffset(text, 4, ControlDistance.SENTENCE, ControlDirection.FORWARD))
        assertEquals(16, controlMoveOffset(text, 9, ControlDistance.SENTENCE, ControlDirection.FORWARD))
        // Backward hops one sentence at a time: from inside "Three!" back
        // to just after "? ", then (chained) to just after ". ", then 0.
        assertEquals(9, controlMoveOffset(text, 15, ControlDistance.SENTENCE, ControlDirection.BACKWARD))
        assertEquals(4, controlMoveOffset(text, 6, ControlDistance.SENTENCE, ControlDirection.BACKWARD))
        assertEquals(4, controlMoveOffset(text, 5, ControlDistance.SENTENCE, ControlDirection.BACKWARD))
        assertEquals(0, controlMoveOffset(text, 4, ControlDistance.SENTENCE, ControlDirection.BACKWARD))
    }

    @Test
    fun sentence_treats_newline_as_a_separator() {
        val text = "one\ntwo"
        assertEquals(4, controlMoveOffset(text, 0, ControlDistance.SENTENCE, ControlDirection.FORWARD))
        assertEquals(0, controlMoveOffset(text, 4, ControlDistance.SENTENCE, ControlDirection.BACKWARD))
    }

    // ── paragraph distance ────────────────────────────────────────────────

    @Test
    fun paragraph_stops_at_newlines() {
        val text = "para one\npara two\npara three"
        assertEquals(9, controlMoveOffset(text, 0, ControlDistance.PARAGRAPH, ControlDirection.FORWARD))
        assertEquals(18, controlMoveOffset(text, 9, ControlDistance.PARAGRAPH, ControlDirection.FORWARD))
        assertEquals(9, controlMoveOffset(text, 17, ControlDistance.PARAGRAPH, ControlDirection.BACKWARD))
        assertEquals(0, controlMoveOffset(text, 8, ControlDistance.PARAGRAPH, ControlDirection.BACKWARD))
    }

    @Test
    fun paragraph_treats_cr_and_lf_each_as_separators() {
        // kParagraphSeps is "\r\n" — a single-char strchr set: CR and LF
        // each separate (an editor's CRLF is a two-separator run, matching
        // the engine's byte view). Forward skips the whole run.
        val text = "a\r\nb"
        assertEquals(3, controlMoveOffset(text, 0, ControlDistance.PARAGRAPH, ControlDirection.FORWARD))
        assertEquals(0, controlMoveOffset(text, 3, ControlDistance.PARAGRAPH, ControlDirection.BACKWARD))
    }

    // ── all distance ──────────────────────────────────────────────────────

    @Test
    fun all_runs_to_the_text_bounds() {
        val text = "some text"
        assertEquals(9, controlMoveOffset(text, 4, ControlDistance.ALL, ControlDirection.FORWARD))
        assertEquals(0, controlMoveOffset(text, 4, ControlDistance.ALL, ControlDirection.BACKWARD))
        // Delete-all follows getRange's one-sided rule too: backward erases
        // to the text start, forward to the end (not the whole buffer from
        // a mid-text caret).
        assertEquals(0 until 4, controlDeleteRange(text, 4, ControlDistance.ALL, ControlDirection.BACKWARD))
        assertEquals(4 until 9, controlDeleteRange(text, 4, ControlDistance.ALL, ControlDirection.FORWARD))
        assertEquals(0 until 9, controlSelectBounds(text, 4, 4, ControlDistance.ALL, ControlDirection.FORWARD, false))
    }

    // ── empty text and boundary carets ────────────────────────────────────

    @Test
    fun empty_text_degenerates_everywhere() {
        for (dist in ControlDistance.values()) {
            for (dir in ControlDirection.values()) {
                assertEquals(0, controlMoveOffset("", 0, dist, dir))
                assertEquals(0 until 0, controlDeleteRange("", 0, dist, dir))
                assertEquals(0 until 0, controlSelectBounds("", 0, 0, dist, dir, false))
            }
        }
    }

    @Test
    fun caret_at_the_ends_never_moves_past_the_text() {
        val text = "abc"
        for (dist in ControlDistance.values()) {
            assertEquals(0, controlMoveOffset(text, 0, dist, ControlDirection.BACKWARD))
            assertEquals(3, controlMoveOffset(text, 3, dist, ControlDirection.FORWARD))
        }
        // Out-of-range carets are clamped, not crashed on.
        assertEquals(3, controlMoveOffset(text, 99, ControlDistance.CHAR, ControlDirection.FORWARD))
        assertEquals(0, controlMoveOffset(text, -1, ControlDistance.CHAR, ControlDirection.BACKWARD))
    }

    @Test
    fun supplementary_chars_inside_words_do_not_split_on_boundaries() {
        // The word walk steps codepoints: the pair moves as a unit, and a
        // boundary can never land between the surrogates.
        val text = "a😀b c"
        assertEquals(5, controlMoveOffset(text, 0, ControlDistance.WORD, ControlDirection.FORWARD))
        assertEquals(0, controlMoveOffset(text, 4, ControlDistance.WORD, ControlDirection.BACKWARD))
    }

    // ── delete ranges ─────────────────────────────────────────────────────

    @Test
    fun delete_backward_range_ends_at_the_caret() {
        val text = "hello world"
        assertEquals(0 until 5, controlDeleteRange(text, 5, ControlDistance.WORD, ControlDirection.BACKWARD))
        assertEquals(6 until 11, controlDeleteRange(text, 11, ControlDistance.WORD, ControlDirection.BACKWARD))
        assertEquals(10 until 11, controlDeleteRange(text, 11, ControlDistance.CHAR, ControlDirection.BACKWARD))
    }

    @Test
    fun delete_forward_range_starts_at_the_caret() {
        val text = "hello world"
        // From the space between the words: forward crosses only the
        // separator run (findAfter from a separator skips just it).
        assertEquals(5 until 6, controlDeleteRange(text, 5, ControlDistance.WORD, ControlDirection.FORWARD))
        assertEquals(6 until 11, controlDeleteRange(text, 6, ControlDistance.WORD, ControlDirection.FORWARD))
        assertEquals(0 until 6, controlDeleteRange(text, 0, ControlDistance.WORD, ControlDirection.FORWARD))
        assertEquals(0 until 1, controlDeleteRange(text, 0, ControlDistance.CHAR, ControlDirection.FORWARD))
    }

    @Test
    fun delete_at_a_dead_end_is_empty() {
        assertEquals(0 until 0, controlDeleteRange("abc", 0, ControlDistance.WORD, ControlDirection.BACKWARD))
        assertEquals(3 until 3, controlDeleteRange("abc", 3, ControlDistance.WORD, ControlDirection.FORWARD))
    }

    // ── select bounds: the grow-only active-end model ─────────────────────

    @Test
    fun select_cold_start_extends_from_the_caret() {
        val text = "hello world"
        // Forward word from caret 2: boundary 6 → selection [2,6).
        assertEquals(2 until 6, controlSelectBounds(text, 2, 2, ControlDistance.WORD, ControlDirection.FORWARD, false))
        // Backward word from caret 8: boundary 6 → selection [6,8).
        assertEquals(6 until 8, controlSelectBounds(text, 8, 8, ControlDistance.WORD, ControlDirection.BACKWARD, false))
    }

    @Test
    fun select_forward_extends_the_forward_end_keeping_the_anchor() {
        val text = "hello world"
        // Existing [2,6): forward word extends the active end 6 → 11.
        assertEquals(2 until 11, controlSelectBounds(text, 2, 6, ControlDistance.WORD, ControlDirection.FORWARD, true))
    }

    @Test
    fun select_backward_extends_the_backward_end_keeping_the_anchor() {
        val text = "hello world"
        // Existing [6,11): backward word extends the active end 6 → 0.
        assertEquals(0 until 11, controlSelectBounds(text, 6, 11, ControlDistance.WORD, ControlDirection.BACKWARD, true))
    }

    @Test
    fun select_direction_reversal_grows_rather_than_shrinks() {
        // The deliberate divergence from Shift+Arrow (RFC 0021's AAC
        // choice, mirroring Dasher-Windows): forward then backward grows —
        // the anchor becomes the old forward end, the active end retracts
        // to the word boundary before it.
        val text = "hello world"
        val first = controlSelectBounds(text, 2, 2, ControlDistance.WORD, ControlDirection.FORWARD, false)
        assertEquals(2 until 6, first)
        // IntRange carries the half-open pair as [first, last+1).
        val second = controlSelectBounds(
            text, first.first, first.last + 1,
            ControlDistance.WORD, ControlDirection.BACKWARD, true,
        )
        assertEquals(0 until 6, second)
    }

    @Test
    fun select_char_over_a_surrogate_pair_takes_the_whole_pair() {
        val text = "a😀b"
        assertEquals(1 until 3, controlSelectBounds(text, 1, 1, ControlDistance.CHAR, ControlDirection.FORWARD, false))
        // Backward from the pair's start selects the 'a' before it.
        assertEquals(0 until 1, controlSelectBounds(text, 1, 1, ControlDistance.CHAR, ControlDirection.BACKWARD, false))
    }

    @Test
    fun select_all_covers_the_whole_text_regardless_of_direction() {
        val text = "a\nb"
        assertEquals(0 until 3, controlSelectBounds(text, 1, 1, ControlDistance.ALL, ControlDirection.BACKWARD, false))
        assertEquals(0 until 3, controlSelectBounds(text, 0, 3, ControlDistance.ALL, ControlDirection.BACKWARD, true))
    }
}
