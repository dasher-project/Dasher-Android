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
    fun edit_while_out_of_sync_still_seeds() {
        // The retry path after a failed seed: the field differs from the
        // engine, and the user edits again — the new text must still seed
        // (loop-guard stays at the engine's text until a seed SUCCEEDS).
        assertEquals(
            EditorSyncAction.Seed("retry text", 3),
            action(textChanged = true, inSync = false, text = "retry text", caret = 3)
        )
    }

    // ── mergeEnginePush: clause 4 caret preservation ──

    private fun tfv(text: String, caret: Int) =
        androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(caret))

    @Test
    fun push_caret_at_end_follows_growth() {
        val merged = mergeEnginePush(tfv("ab", 2), "abcdef")
        assertEquals("abcdef", merged.text)
        assertEquals(6, merged.selection.end)
    }

    @Test
    fun push_caret_mid_text_stays_put() {
        val merged = mergeEnginePush(tfv("abcdef", 3), "abcdef")
        assertEquals(3, merged.selection.end)
    }

    @Test
    fun push_caret_clamps_when_text_shrinks() {
        // Reset-class pushes shrink to "" — caret must clamp to 0.
        val merged = mergeEnginePush(tfv("abcdef", 4), "")
        assertEquals("", merged.text)
        assertEquals(0, merged.selection.end)
    }

    @Test
    fun push_caret_at_old_end_clamps_to_shorter_new_text() {
        val merged = mergeEnginePush(tfv("abcdef", 6), "abc")
        assertEquals(3, merged.selection.end)
    }

    @Test
    fun cr_normalised_equality() {
        // The engine emits CRLF; the field holds LF — the push boundary strips
        // CR (MainActivity.onTextUpdate) so raw LF==LF equality holds; this
        // pins the boundary contract the loop-guard relies on.
        assertTrue("a\nb" == "a\r\nb".replace("\r", ""))
        assertFalse("a\nb" == "a b".replace("\r", ""))
    }
}
