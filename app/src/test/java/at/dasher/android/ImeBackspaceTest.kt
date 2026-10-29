package at.dasher.android

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Heide's 2026-10-29 report: "if you select something, whether it's
 * everything or partial, and you use the delete button on the toolbar, it
 * doesn't do anything. It only works when you don't have anything selected."
 *
 * performImeBackspace must delete the SELECTION as a unit (empty commitText —
 * deleteSurroundingText ignores selections) and keep the caret backspace
 * behaviour (codepoint-correct, surrogate-aware) when nothing is selected.
 */
class ImeBackspaceTest {

    /** Records calls; controlled getSelectedText/getTextBeforeCursor. Pure JVM. */
    private class FakeIc(
        private val selected: CharSequence? = null,
        private val beforeCursor: CharSequence? = null,
        private val commitAccepts: Boolean = true,
        /** Bug-#5 editor class: commitText returns true AND the selection
         *  survives (the async-editor lie the review loop flagged). */
        private val selectionSurvivesCommit: Boolean = false,
    ) : InputConnection {
        val calls = mutableListOf<String>()
        private var committed = false
        override fun getSelectedText(flags: Int): CharSequence? =
            if (committed) { if (selectionSurvivesCommit) selected else null } else selected
        // Real windowing semantics (review M2): the last n UTF-16 units
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = beforeCursor?.takeLast(n)
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            calls += "commitText(${text.length})"
            committed = true
            return commitAccepts
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            calls += "deleteSurroundingText($beforeLength)"
            return true
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            // KeyEvent.getAction is not mocked on the JVM — record without
            // inspecting (performImeBackspace sends DOWN+UP; assert on count).
            calls += "sendKeyEvent"
            return true
        }
        // Unused in these paths
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean = false
        override fun setComposingRegion(start: Int, end: Int): Boolean = false
        override fun finishComposingText(): Boolean = true
        override fun setSelection(start: Int, end: Int): Boolean = false
        override fun performEditorAction(actionCode: Int): Boolean = false
        override fun performContextMenuAction(id: Int): Boolean = false
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun clearMetaKeyStates(states: Int): Boolean = true
        override fun reportFullscreenMode(enabled: Boolean): Boolean = true
        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = false
        override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? = null
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = false
        override fun getHandler(): android.os.Handler? = null
        override fun closeConnection() {}
        override fun commitContent(inputContent: android.view.inputmethod.InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean = false
        override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean = false
        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = false
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    }

    @Test
    fun selection_is_deleted_with_an_empty_commit() {
        val ic = FakeIc(selected = "everything or partial")
        val outcome = performImeBackspace(ic)
        assertEquals(ImeBackspaceOutcome.SelectionDeleted, outcome)
        assertEquals(listOf("commitText(0)"), ic.calls)
    }

    @Test
    fun selection_refusing_editor_gets_del_keyevent_fallback() {
        val ic = FakeIc(selected = "sel", commitAccepts = false)
        assertEquals(ImeBackspaceOutcome.SelectionDeleted, performImeBackspace(ic))
        assertEquals(listOf("commitText(0)", "sendKeyEvent", "sendKeyEvent"), ic.calls)
    }

    @Test
    fun lying_editor_true_commit_but_selection_survives_gets_del_fallback() {
        // Review loop 1 (I1): the bug-#5 editor class returns TRUE while
        // doing nothing. The re-verify must catch it and fire the fallback.
        val ic = FakeIc(selected = "sel", commitAccepts = true, selectionSurvivesCommit = true)
        assertEquals(ImeBackspaceOutcome.SelectionDeleted, performImeBackspace(ic))
        assertEquals(listOf("commitText(0)", "sendKeyEvent", "sendKeyEvent"), ic.calls)
    }

    @Test
    fun empty_selection_falls_through_to_caret_backspace() {
        // Some editors return "" rather than null with no selection
        val ic = FakeIc(selected = "", beforeCursor = "ab")
        performImeBackspace(ic)
        assertEquals(listOf("deleteSurroundingText(1)"), ic.calls)
    }

    @Test
    fun no_selection_deletes_one_unit() {
        val ic = FakeIc(selected = null, beforeCursor = "ab")
        performImeBackspace(ic)
        assertEquals(listOf("deleteSurroundingText(1)"), ic.calls)
    }

    @Test
    fun surrogate_pair_before_caret_deletes_two_units() {
        val ic = FakeIc(selected = null, beforeCursor = "a😀")
        performImeBackspace(ic)
        assertEquals(listOf("deleteSurroundingText(2)"), ic.calls)
    }

    @Test
    fun empty_field_is_a_noop() {
        val ic = FakeIc(selected = null, beforeCursor = "")
        performImeBackspace(ic)
        assertTrue(ic.calls.isEmpty())
    }
}
