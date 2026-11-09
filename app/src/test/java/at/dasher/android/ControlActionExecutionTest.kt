package at.dasher.android

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC 0021 — the InputConnection choreography for control-node actions
 * (executeControlAction; pure JVM with a fake, the ImeBackspaceTest
 * pattern). The service owns queueing/threading; this pins the edit
 * discipline: measured ranges → absolute setSelection / surrounding
 * deletes, the honest fallbacks for targets that refuse text reads, and
 * the DEL-event fallback + flood cap.
 */
class ControlActionExecutionTest {

    /** Records every mutating call; programmable reads. */
    private class FakeIc(
        private val extracted: ExtractedText? = null,
        private val beforeCursor: CharSequence? = null,
        private val afterCursor: CharSequence? = null,
        private val deleteAccepts: Boolean = true,
        private val setSelectionAccepts: Boolean = true,
    ) : InputConnection {
        val calls = mutableListOf<String>()
        var bufferChanged = 0
        var selection: Pair<Int, Int>? = null

        override fun getExtractedText(
            request: android.view.inputmethod.ExtractedTextRequest?,
            flags: Int,
        ): ExtractedText? = extracted

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            beforeCursor?.takeLast(n)

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
            afterCursor?.take(n)

        override fun setSelection(start: Int, end: Int): Boolean {
            calls += "setSelection($start,$end)"
            selection = start to end
            return setSelectionAccepts
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            calls += "deleteSurroundingText($beforeLength,$afterLength)"
            return deleteAccepts
        }

        override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
            // KeyEvent.getAction/getKeyCode are not mocked on the JVM —
            // count without inspecting (DOWN+UP pairs per key event).
            calls += "sendKeyEvent"
            return true
        }

        override fun performContextMenuAction(id: Int): Boolean {
            calls += "performContextMenuAction($id)"
            return true
        }

        // Unused in these paths
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean = false
        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean = false
        override fun setComposingRegion(start: Int, end: Int): Boolean = false
        override fun finishComposingText(): Boolean = true
        override fun performEditorAction(actionCode: Int): Boolean = false
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun clearMetaKeyStates(states: Int): Boolean = true
        override fun reportFullscreenMode(enabled: Boolean): Boolean = true
        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = false
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = false
        override fun getHandler(): android.os.Handler? = null
        override fun closeConnection() {}
        override fun commitContent(
            inputContent: android.view.inputmethod.InputContentInfo,
            flags: Int,
            opts: android.os.Bundle?,
        ): Boolean = false
        override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean = false
        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = false
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    }

    private fun extracted(text: String, selStart: Int, selEnd: Int = selStart, base: Int = 0) =
        ExtractedText().apply {
            this.text = text
            startOffset = base
            selectionStart = selStart
            selectionEnd = selEnd
        }

    private fun run(ic: FakeIc, action: ControlAction): String =
        executeControlAction(ic, action) { ic.bufferChanged++ }

    private fun act(name: String, dist: ControlDistance, forward: Boolean) = ControlAction(
        name, dist, if (forward) ControlDirection.FORWARD else ControlDirection.BACKWARD,
    )

    // ── select ────────────────────────────────────────────────────────────

    @Test
    fun select_word_forward_sets_the_measured_absolute_selection() {
        val ic = FakeIc(extracted = extracted("hello world", 2))
        assertEquals("applied", run(ic, act("select", ControlDistance.WORD, true)))
        assertEquals(listOf("setSelection(2,6)"), ic.calls)
        assertEquals(0, ic.bufferChanged) // selections never re-anchor
    }

    @Test
    fun select_all_uses_the_absolute_selection() {
        val ic = FakeIc(extracted = extracted("hello world", 3))
        assertEquals("applied", run(ic, act("select", ControlDistance.ALL, false)))
        assertEquals(listOf("setSelection(0,11)"), ic.calls)
    }

    @Test
    fun select_accounts_for_a_windowed_extraction_base_offset() {
        // A huge field may report only a window: offsets are
        // window-relative; setSelection takes ABSOLUTE field offsets.
        val ic = FakeIc(extracted = extracted("hello world", 2, 2, base = 100))
        assertEquals("applied", run(ic, act("select", ControlDistance.WORD, true)))
        assertEquals(listOf("setSelection(102,106)"), ic.calls)
    }

    @Test
    fun select_over_an_existing_selection_extends_the_active_end() {
        val ic = FakeIc(extracted = extracted("hello world", 2, 6))
        assertEquals("applied", run(ic, act("select", ControlDistance.WORD, true)))
        assertEquals(listOf("setSelection(2,11)"), ic.calls)
    }

    @Test
    fun select_without_text_reads_falls_back_to_the_context_menu_for_all() {
        val ic = FakeIc()
        assertEquals("applied_menu", run(ic, act("select", ControlDistance.ALL, true)))
        assertEquals(listOf("performContextMenuAction(${android.R.id.selectAll})"), ic.calls)
    }

    @Test
    fun select_without_text_reads_no_ops_rather_than_guessing_offsets() {
        val ic = FakeIc()
        assertEquals("no_text", run(ic, act("select", ControlDistance.WORD, true)))
        assertTrue(ic.calls.isEmpty())
    }

    @Test
    fun select_refusing_editor_reports_refused() {
        val ic = FakeIc(extracted = extracted("hello world", 0), setSelectionAccepts = false)
        assertEquals("refused", run(ic, act("select", ControlDistance.ALL, true)))
    }

    // ── move ──────────────────────────────────────────────────────────────

    @Test
    fun move_word_backward_collapses_to_the_measured_caret() {
        val ic = FakeIc(extracted = extracted("hello world", 8))
        assertEquals("applied", run(ic, act("move", ControlDistance.WORD, false)))
        assertEquals(listOf("setSelection(6,6)"), ic.calls)
        assertEquals(1, ic.bufferChanged) // caret moved → re-anchor
    }

    @Test
    fun move_collapses_off_the_selection_in_the_direction_of_travel() {
        // Forward moves from the selection END (native arrow-collapse
        // expectation); backward from its start.
        val fwd = FakeIc(extracted = extracted("hello world", 2, 6))
        assertEquals("applied", run(fwd, act("move", ControlDistance.CHAR, true)))
        assertEquals(listOf("setSelection(7,7)"), fwd.calls)
        val back = FakeIc(extracted = extracted("hello world", 6, 11))
        assertEquals("applied", run(back, act("move", ControlDistance.WORD, false)))
        // Word-backward from the selection start crosses the separator run
        // to the previous word's start (getRange's chained-hop semantics).
        assertEquals(listOf("setSelection(0,0)"), back.calls)
    }

    @Test
    fun move_at_a_boundary_reports_noop_without_touching_the_field() {
        val ic = FakeIc(extracted = extracted("abc", 0))
        assertEquals("noop", run(ic, act("move", ControlDistance.WORD, false)))
        assertTrue(ic.calls.isEmpty())
        assertEquals(0, ic.bufferChanged)
    }

    @Test
    fun move_char_without_text_reads_falls_back_to_dpad_keys() {
        val ic = FakeIc()
        assertEquals("applied_keys", run(ic, act("move", ControlDistance.CHAR, true)))
        assertEquals(listOf("sendKeyEvent", "sendKeyEvent"), ic.calls) // DOWN + UP
        assertEquals(1, ic.bufferChanged)
    }

    @Test
    fun move_word_without_text_reads_is_honestly_unavailable() {
        val ic = FakeIc()
        assertEquals("no_text", run(ic, act("move", ControlDistance.WORD, true)))
        assertTrue(ic.calls.isEmpty())
    }

    // ── delete ────────────────────────────────────────────────────────────

    @Test
    fun delete_word_backward_applies_one_surrounding_delete() {
        val ic = FakeIc(extracted = extracted("hello world", 11))
        assertEquals("applied", run(ic, act("delete", ControlDistance.WORD, false)))
        assertEquals(listOf("deleteSurroundingText(5,0)"), ic.calls)
        assertEquals(1, ic.bufferChanged)
    }

    @Test
    fun delete_word_forward_applies_one_surrounding_delete_after_the_caret() {
        val ic = FakeIc(extracted = extracted("hello world", 6))
        assertEquals("applied", run(ic, act("delete", ControlDistance.WORD, true)))
        assertEquals(listOf("deleteSurroundingText(0,5)"), ic.calls)
    }

    @Test
    fun delete_collapses_an_active_selection_first() {
        // Heide's #50 ⌫ rule: deleteSurroundingText is undefined over a
        // selection — collapse to the direction-facing anchor. Backward
        // deletes BEFORE the selection start (selection [6,11), word
        // backward → delete [0,6)).
        val ic = FakeIc(extracted = extracted("hello world", 6, 11))
        assertEquals("applied", run(ic, act("delete", ControlDistance.WORD, false)))
        assertEquals(listOf("setSelection(6,6)", "deleteSurroundingText(6,0)"), ic.calls)
    }

    @Test
    fun delete_forward_over_a_selection_starts_at_its_end() {
        // Forward deletes AFTER the selection end: selection [0,6),
        // word forward → delete [6,11).
        val ic = FakeIc(extracted = extracted("hello world", 0, 6))
        assertEquals("applied", run(ic, act("delete", ControlDistance.WORD, true)))
        assertEquals(listOf("setSelection(6,6)", "deleteSurroundingText(0,5)"), ic.calls)
    }

    @Test
    fun delete_at_a_dead_end_is_a_noop() {
        // ALL backward from caret 0 deletes [0,0): nothing before it.
        val ic = FakeIc(extracted = extracted("abc", 0))
        assertEquals("noop", run(ic, act("delete", ControlDistance.ALL, false)))
        assertTrue(ic.calls.isEmpty())
    }

    @Test
    fun delete_all_backward_erases_to_the_text_start() {
        // getRange's one-sided ALL rule (pinned in ControlActionsTest).
        val ic = FakeIc(extracted = extracted("hello world", 6))
        assertEquals("applied", run(ic, act("delete", ControlDistance.ALL, false)))
        assertEquals(listOf("deleteSurroundingText(6,0)"), ic.calls)
    }

    @Test
    fun delete_refusing_editor_gets_capped_del_key_events() {
        val ic = FakeIc(extracted = extracted("hello world", 11), deleteAccepts = false)
        assertEquals("applied_keys", run(ic, act("delete", ControlDistance.WORD, false)))
        // 5 codepoints × DOWN+UP
        assertEquals(
            List(10) { "sendKeyEvent" },
            ic.calls.filter { it == "sendKeyEvent" },
        )
        assertEquals(1, ic.bufferChanged)
    }

    @Test
    fun delete_over_the_flood_cap_is_skipped() {
        // 200 a-chars before the caret: ALL-backward wants 200 DELs > cap.
        val ic = FakeIc(extracted = extracted("a".repeat(200), 200), deleteAccepts = false)
        assertEquals("skipped_cap", run(ic, act("delete", ControlDistance.ALL, false)))
        // The delta was attempted (and refused) — but no key-event flood.
        assertEquals(listOf("deleteSurroundingText(200,0)"), ic.calls)
    }

    @Test
    fun delete_surrogate_pairs_count_del_events_in_codepoints() {
        // "😀😀" before the caret: 4 UTF-16 units but 2 codepoints → 2 DELs.
        val ic = FakeIc(extracted = extracted("😀😀", 4), deleteAccepts = false)
        assertEquals("applied_keys", run(ic, act("delete", ControlDistance.ALL, false)))
        assertEquals(4, ic.calls.count { it == "sendKeyEvent" }) // 2 × (DOWN+UP)
        // The delta path would have counted UTF-16 units:
        val ic2 = FakeIc(extracted = extracted("😀😀", 4))
        assertEquals("applied", run(ic2, act("delete", ControlDistance.ALL, false)))
        assertEquals(listOf("deleteSurroundingText(4,0)"), ic2.calls)
    }

    // ── delete: cursor-relative fallback (no extracted text) ──────────────

    @Test
    fun delete_without_text_reads_measures_the_cursor_relative_window() {
        val ic = FakeIc(beforeCursor = "hello ", afterCursor = "world")
        assertEquals("applied", run(ic, act("delete", ControlDistance.WORD, false)))
        assertEquals(listOf("deleteSurroundingText(6,0)"), ic.calls)
        assertEquals(1, ic.bufferChanged)
    }

    @Test
    fun delete_without_text_reads_forward_uses_the_after_window() {
        val ic = FakeIc(beforeCursor = "hello ", afterCursor = "world")
        assertEquals("applied", run(ic, act("delete", ControlDistance.WORD, true)))
        assertEquals(listOf("deleteSurroundingText(0,5)"), ic.calls)
    }

    @Test
    fun delete_without_any_reads_reports_no_text() {
        val ic = FakeIc(beforeCursor = null, afterCursor = "")
        assertEquals("no_text", run(ic, act("delete", ControlDistance.WORD, false)))
        assertTrue(ic.calls.isEmpty())
    }
}
