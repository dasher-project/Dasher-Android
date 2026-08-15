package at.dasher.android

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the MotionEvent layer of [JoystickInputProvider]
 * (issue #6 stage 1). Synthesises joystick-source motion events — for
 * joystick events the pointer coordinates carry AXIS_X/AXIS_Y, which is how
 * the input pipeline reports them — and asserts the provider consumes them
 * and emits normalised coordinates. Complements the JVM maths tests.
 */
@RunWith(AndroidJUnit4::class)
class JoystickInputInstrumentedTest {

    private class Recorder : (Float, Float) -> Unit {
        val points = mutableListOf<Pair<Float, Float>>()
        override fun invoke(x: Float, y: Float) { points += x to y }
    }

    private fun joystickEvent(x: Float, y: Float, source: Int = InputDevice.SOURCE_JOYSTICK): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, x, y, 0).apply {
            setSource(source)
        }
    }

    @Test
    fun nonJoystickSourceIsIgnored() {
        val rec = Recorder()
        val p = JoystickInputProvider(rec)
        assertFalse(p.onGenericMotionEvent(joystickEvent(1f, 0f, source = InputDevice.SOURCE_MOUSE)))
        assertFalse(p.onGenericMotionEvent(joystickEvent(1f, 0f, source = InputDevice.SOURCE_TOUCHSCREEN)))
        assertTrue(rec.points.isEmpty())
    }

    @Test
    fun joystickSourceFullRightIsConsumedAndEmits() {
        val rec = Recorder()
        val p = JoystickInputProvider(rec)
        var handled = false
        repeat(200) { handled = p.onGenericMotionEvent(joystickEvent(1f, 0f)) }
        assertTrue(handled)
        assertEquals(1f, rec.points.last().first, 1e-2f)
        assertEquals(0.5f, rec.points.last().second, 1e-2f)
    }

    @Test
    fun joystickSourceDeadZoneIsNotConsumed() {
        val rec = Recorder()
        val p = JoystickInputProvider(rec)
        // Deflection below the default dead zone: nothing reported, not consumed.
        assertFalse(p.onGenericMotionEvent(joystickEvent(0.05f, 0.05f)))
        assertTrue(rec.points.isEmpty())
    }

    @Test
    fun joystickSourceUpLeftConverges() {
        val rec = Recorder()
        val p = JoystickInputProvider(rec)
        repeat(200) { p.onGenericMotionEvent(joystickEvent(-1f, -1f)) }
        assertEquals(0f, rec.points.last().first, 1e-2f)
        assertEquals(0f, rec.points.last().second, 1e-2f)
    }
}
