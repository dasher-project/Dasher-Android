package at.dasher.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bug #5 delta arithmetic (pure — no engine/InputConnection):
 * engine deltas coalesce per frame, deletes split correctly across the
 * flushed-text/pending-insert boundary, UTF-16 units throughout.
 */
class ImeOutputCoalescerTest {

    @Test
    fun nothing_pending_flushes_null() {
        assertNull(ImeOutputCoalescer().takeFlush())
    }

    @Test
    fun commits_within_a_frame_merge_into_one_insert() {
        val c = ImeOutputCoalescer()
        c.onEngineOutput(0, "th")
        c.onEngineOutput(0, "ere")
        assertEquals(ImeOutputCoalescer.Flush(0, "there"), c.takeFlush())
    }

    @Test
    fun delete_of_flushed_text_becomes_delete_units() {
        val c = ImeOutputCoalescer()
        // Previous frame committed "there" and flushed; this frame the user
        // deletes "ere" — nothing pending, so the whole delete hits the field.
        c.onEngineOutput(1, "ere")
        assertEquals(ImeOutputCoalescer.Flush(3, ""), c.takeFlush())
    }

    @Test
    fun delete_inside_the_pending_insert_cancels_it() {
        val c = ImeOutputCoalescer()
        c.onEngineOutput(0, "ther")
        c.onEngineOutput(1, "r") // engine reversed before anything was sent
        assertEquals(ImeOutputCoalescer.Flush(0, "the"), c.takeFlush())
    }

    @Test
    fun delete_spanning_boundary_cancels_pending_and_deletes_remainder() {
        val c = ImeOutputCoalescer()
        // Field already holds "hello " (flushed last frame); this frame the
        // engine committed "wor" then the user deleted "o wor" — 5 units
        // removed, of which 3 were still pending.
        c.onEngineOutput(0, "wor")
        c.onEngineOutput(1, "o wor")
        assertEquals(ImeOutputCoalescer.Flush(2, ""), c.takeFlush())
    }

    @Test
    fun delete_then_commit_keeps_both_halves_in_order() {
        val c = ImeOutputCoalescer()
        c.onEngineOutput(1, "e ")   // fix "the " → "th"
        c.onEngineOutput(0, "at")   // now "that"
        assertEquals(ImeOutputCoalescer.Flush(2, "at"), c.takeFlush())
    }

    @Test
    fun take_flush_resets_state() {
        val c = ImeOutputCoalescer()
        c.onEngineOutput(0, "a")
        assertEquals(ImeOutputCoalescer.Flush(0, "a"), c.takeFlush())
        assertNull(c.takeFlush())
        // And accumulates afresh after a flush.
        c.onEngineOutput(0, "b")
        assertEquals(ImeOutputCoalescer.Flush(0, "b"), c.takeFlush())
    }

    @Test
    fun buffer_clear_emits_nothing() {
        // dasher.h type 2: buffer clear carries empty text — the deltas
        // cannot express it and the IME re-anchors instead. It must not
        // poison pending state either.
        val c = ImeOutputCoalescer()
        c.onEngineOutput(2, "")
        assertNull(c.takeFlush())
    }

    @Test
    fun surrogate_pairs_count_two_units() {
        // deleteSurroundingText counts UTF-16 code units: an emoji is 2 —
        // String.length already agrees, pinning the contract.
        val c = ImeOutputCoalescer()
        c.onEngineOutput(1, "😀")
        assertEquals(ImeOutputCoalescer.Flush(2, ""), c.takeFlush())
    }

    @Test
    fun empty_delete_text_is_ignored() {
        val c = ImeOutputCoalescer()
        c.onEngineOutput(1, "")
        assertNull(c.takeFlush())
    }
}
