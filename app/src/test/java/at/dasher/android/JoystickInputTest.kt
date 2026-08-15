package at.dasher.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure joystick maths and the axis seam of
 * [JoystickInputProvider] (issue #6 stage 1). No Android classes needed —
 * the MotionEvent layer is covered by the instrumented test.
 */
class JoystickInputTest {

    // ── JoystickInputMapping.normalizeAxis ───────────────────────────────────

    @Test
    fun `dead zone zeroes small deflections`() {
        assertEquals(0f, JoystickInputMapping.normalizeAxis(0.10f, 0.18f), 0f)
        assertEquals(0f, JoystickInputMapping.normalizeAxis(-0.18f, 0.18f), 0f)
    }

    @Test
    fun `full deflection passes through`() {
        assertEquals(1f, JoystickInputMapping.normalizeAxis(1f, 0.18f), 1e-4f)
        assertEquals(-1f, JoystickInputMapping.normalizeAxis(-1f, 0.18f), 1e-4f)
    }

    @Test
    fun `travel beyond the dead zone is rescaled to keep range`() {
        // Half-way deflection after a 0.2 dead zone: (0.5-0.2)/0.8 = 0.375
        assertEquals(0.375f, JoystickInputMapping.normalizeAxis(0.5f, 0.2f), 1e-4f)
        assertEquals(-0.375f, JoystickInputMapping.normalizeAxis(-0.5f, 0.2f), 1e-4f)
    }

    @Test
    fun `boundary of dead zone is zero`() {
        assertEquals(0f, JoystickInputMapping.normalizeAxis(0.200001f, 0.2f), 1e-3f)
    }

    @Test
    fun `zero dead zone is identity`() {
        assertEquals(0.73f, JoystickInputMapping.normalizeAxis(0.73f, 0f), 1e-4f)
    }

    @Test
    fun `oversized values clamp`() {
        assertEquals(1f, JoystickInputMapping.normalizeAxis(2f, 0.18f), 1e-4f)
        assertEquals(-1f, JoystickInputMapping.normalizeAxis(-7f, 0.18f), 1e-4f)
        // A device reporting range beyond ±1 (some DInput pads) still works.
        assertEquals(0.1f, JoystickInputMapping.normalizeAxis(0.91f, 0.9f), 1e-2f)
    }

    @Test
    fun `dead zone is clamped to sane values`() {
        // dz > 0.9 would divide by (1 - dz) ≤ 0; clamped instead.
        assertEquals(1f, JoystickInputMapping.normalizeAxis(1f, 5f), 1e-4f)
    }

    // ── JoystickInputMapping.toUnit ──────────────────────────────────────────

    @Test
    fun `unit mapping centres neutral and spans the canvas`() {
        assertEquals(0.5f, JoystickInputMapping.toUnit(0f), 0f)
        assertEquals(1f, JoystickInputMapping.toUnit(1f), 0f)
        assertEquals(0f, JoystickInputMapping.toUnit(-1f), 0f)
    }

    @Test
    fun `positive Y maps downward on screen`() {
        assertTrue(JoystickInputMapping.toUnit(0.5f) > JoystickInputMapping.toUnit(-0.5f))
    }

    // ── JoystickInputProvider.handleAxisValues seam ──────────────────────────

    /** Collects emissions so assertions can inspect the callback stream. */
    private class Recorder : (Float, Float) -> Unit {
        val points = mutableListOf<Pair<Float, Float>>()
        override fun invoke(x: Float, y: Float) { points += x to y }
    }

    private fun feedToConvergence(
        rec: Recorder,
        x: Float, y: Float,
        hatX: Float = 0f, hatY: Float = 0f,
        z: Float = 0f, rz: Float = 0f,
        iterations: Int = 200
    ) {
        val p = JoystickInputProvider(rec)
        repeat(iterations) { p.handleAxisValues(x, y, hatX, hatY, z, rz) }
    }

    @Test
    fun `neutral axes emit nothing`() {
        val rec = Recorder()
        val p = JoystickInputProvider(rec)
        assertFalse(p.handleAxisValues(0f, 0f, 0f, 0f, 0f, 0f))
        assertTrue(rec.points.isEmpty())
    }

    @Test
    fun `full right stick converges to right edge`() {
        val rec = Recorder()
        feedToConvergence(rec, 1f, 0f)
        assertEquals(1f, rec.points.last().first, 1e-2f)
        assertEquals(0.5f, rec.points.last().second, 1e-2f)
    }

    @Test
    fun `up-left stick converges to top-left`() {
        val rec = Recorder()
        feedToConvergence(rec, -1f, -1f)
        assertEquals(0f, rec.points.last().first, 1e-2f)
        assertEquals(0f, rec.points.last().second, 1e-2f)
    }

    @Test
    fun `deflection inside dead zone emits nothing`() {
        val rec = Recorder()
        val p = JoystickInputProvider(rec)
        // 0.1 < default 0.18 dead zone.
        assertFalse(p.handleAxisValues(0.1f, 0.05f, 0f, 0f, 0f, 0f))
        assertTrue(rec.points.isEmpty())
    }

    @Test
    fun `hat axes used when stick silent (dpad-only controllers)`() {
        val rec = Recorder()
        feedToConvergence(rec, 0f, 0f, hatX = 1f, hatY = 0f)
        assertEquals(1f, rec.points.last().first, 1e-2f)
        assertEquals(0.5f, rec.points.last().second, 1e-2f)
    }

    @Test
    fun `right stick used when left and hat silent`() {
        val rec = Recorder()
        feedToConvergence(rec, 0f, 0f, hatX = 0f, hatY = 0f, z = -1f, rz = 0f)
        assertEquals(0f, rec.points.last().first, 1e-2f)
    }

    @Test
    fun `left stick takes priority over hat and right stick`() {
        val rec = Recorder()
        feedToConvergence(rec, x = 1f, y = 0f, hatX = -1f, hatY = 0f, z = -1f, rz = 0f)
        assertEquals(1f, rec.points.last().first, 1e-2f) // X=1 wins, not hat -1 / z -1
    }

    @Test
    fun `stick axes win over device-reported flat`() {
        val rec = Recorder()
        val p = JoystickInputProvider(rec)
        // Device reports a large flat (0.3) — 0.25 deflection is inside it, so the
        // stick pair is not "reported" and the hat pair (0 deflection) isn't either.
        assertFalse(p.handleAxisValues(0.25f, 0f, 0f, 0f, 0f, 0f, flatX = 0.3f, flatY = 0.3f))
        assertTrue(rec.points.isEmpty())
    }

    @Test
    fun `smoothing moves gradually not instantly`() {
        val rec = Recorder()
        val p = JoystickInputProvider(rec)
        p.handleAxisValues(1f, 0f)
        val first = rec.points.single()
        // First sample after neutral must be well short of the target (alpha 0.35
        // from centre 0.5 → ~0.675), proving no jump-to-target.
        assertEquals(0.5f + 0.5f * JoystickInputProvider.SMOOTHING_ALPHA, first.first, 1e-3f)
    }

    @Test
    fun `re-centring snaps exactly onto the crosshair and zoom stops`() {
        val rec = Recorder()
        // Deflect fully, then return to centre: the provider must keep consuming
        // (joysticks only fire on change) and land exactly on (0.5, 0.5) — a
        // smoothed near-centre pointer would keep the engine zooming forever
        // (found in the emulator e2e pass).
        val p = JoystickInputProvider(rec)
        repeat(100) { p.handleAxisValues(1f, 0f) }
        val consumed = p.handleAxisValues(0f, 0f)
        assertTrue(consumed)
        assertEquals(0.5f, rec.points.last().first, 0f)
        assertEquals(0.5f, rec.points.last().second, 0f)
        // Further centred events emit nothing new (already parked at centre).
        val before = rec.points.size
        p.handleAxisValues(0f, 0f)
        assertEquals(before, rec.points.size)
    }

    @Test
    fun `all emissions stay within canvas bounds`() {
        val rec = Recorder()
        val p = JoystickInputProvider(rec)
        repeat(50) {
            p.handleAxisValues(1f, -1f)
            p.handleAxisValues(-1f, 1f)
            p.handleAxisValues(0.2f, 0.2f)
        }
        rec.points.forEach { (x, y) ->
            assertTrue("x=$x", x in 0f..1f)
            assertTrue("y=$y", y in 0f..1f)
        }
    }
}
