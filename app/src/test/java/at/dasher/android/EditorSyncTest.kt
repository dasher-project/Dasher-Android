package at.dasher.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC 0019 editor-sync decision logic (pure function — no engine/Compose).
 */
class EditorSyncTest {

    private fun action(
        textChanged: Boolean = false,
        selectionChanged: Boolean = false,
        inSync: Boolean = true,
        composing: Boolean = false,
        engineEmpty: Boolean = false,
        text: String = "hello",
        caret: Int = 3,
    ) = editorSyncAction(textChanged, selectionChanged, inSync, composing, engineEmpty, text, caret)

    @Test
    fun user_edit_seeds_with_caret() {
        assertEquals(EditorSyncAction.Seed("héllo", 2), action(textChanged = true, text = "héllo", caret = 2))
    }

    @Test
    fun pure_caret_move_in_sync_reanchors() {
        assertEquals(EditorSyncAction.Reanchor(4), action(selectionChanged = true, caret = 4))
    }

    @Test
    fun caret_move_out_of_sync_does_nothing() {
        // Text differs from the engine (a seed is pending/failed) — re-anchoring
        // against a stale buffer would corrupt the position.
        assertEquals(EditorSyncAction.None, action(selectionChanged = true, inSync = false))
    }

    @Test
    fun caret_move_with_empty_engine_does_nothing() {
        assertEquals(EditorSyncAction.None, action(selectionChanged = true, engineEmpty = true))
    }

    @Test
    fun composition_defers_everything() {
        // RFC 0019 clause 2: defer seeding until composition commit — even a
        // text change during composition must not seed.
        assertEquals(EditorSyncAction.None, action(textChanged = true, composing = true))
        assertEquals(EditorSyncAction.None, action(selectionChanged = true, composing = true))
    }

    @Test
    fun nothing_happens_when_nothing_changed() {
        assertEquals(EditorSyncAction.None, action())
    }

    @Test
    fun cr_normalised_equality() {
        // The engine emits CRLF; the field holds LF — equality must hold or the
        // loop-guard would misclassify every push as a user edit.
        assertTrue(editorTextEquals("a\nb", "a\r\nb"))
        assertTrue(editorTextEquals("a\r\nb", "a\nb"))
        assertFalse(editorTextEquals("a\nb", "a b"))
    }
}
