package at.dasher.android

import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Converts gamepad / joystick input into normalised 2-D cursor coordinates fed to
 * [DasherEngine.onJoystickNormalized] (issue #6, stage 1: pointer steering).
 *
 * Mirrors [TiltInputProvider]'s contract: `(normalizedX, normalizedY)` in `[0, 1]`
 * with `(0.5, 0.5)` neutral. Press/release semantics live in the wiring: a
 * deflected stick drives a continuously-pressed pointer that steers the zoom;
 * once the axes fall inside the dead zone the provider emits exactly
 * `(0.5, 0.5)` — the caller releases the pointer so Dasher pauses.
 *
 * Axis priority per event — first axis pair the device actually reports wins:
 * left stick (AXIS_X/AXIS_Y) → dpad (AXIS_HAT_X/AXIS_HAT_Y) → right stick
 * (AXIS_Z/AXIS_RZ). Some DInput controllers (per issue #6) report only one pair.
 *
 * The axis math is pure (see [JoystickInputMapping]) so it is JVM-unit-testable;
 * only [onGenericMotionEvent] touches Android classes.
 *
 * @param onJoystickNormalized invoked with `(normalizedX, normalizedY)`.
 */
class JoystickInputProvider(
    private val onJoystickNormalized: (Float, Float) -> Unit
) {

    private var engaged = false
    private var hasSmoothed = false
    private var smoothedX = 0.5f
    private var smoothedY = 0.5f

    /**
     * Feeds a generic motion event. Returns `true` if the event was a joystick
     * event this provider consumed.
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) return false
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val z = event.getAxisValue(MotionEvent.AXIS_Z)
        val rz = event.getAxisValue(MotionEvent.AXIS_RZ)
        val device = InputDevice.getDevice(event.deviceId)
        return handleAxisValues(
            x, y, hatX, hatY, z, rz,
            flatX = device?.getMotionRange(MotionEvent.AXIS_X)?.flat ?: 0f,
            flatY = device?.getMotionRange(MotionEvent.AXIS_Y)?.flat ?: 0f
        )
    }

    /**
     * Axis-value seam (pure aside from smoothing state): applies axis priority,
     * dead zones, smoothing and the `[0, 1]` mapping. Exposed for unit tests.
     *
     * Joysticks — unlike tilt sensors — only report on change, so once a stick has
     * been deflected we stay engaged and treat subsequent centred events as a
     * return-to-neutral target, letting the smoothed position settle exactly on
     * the crosshair (zoom stops). Events from an idle, never-touched device are
     * ignored (some pads stream spurious zero-events).
     */
    fun handleAxisValues(
        x: Float, y: Float,
        hatX: Float = 0f, hatY: Float = 0f,
        z: Float = 0f, rz: Float = 0f,
        flatX: Float = 0f, flatY: Float = 0f
    ): Boolean {
        val reported = when {
            reports(x, y, flatX, flatY) -> Triple(x, y, maxOf(flatX, flatY))
            reports(hatX, hatY, 0f, 0f) -> Triple(hatX, hatY, DEFAULT_DEAD_ZONE)
            reports(z, rz, 0f, 0f) -> Triple(z, rz, DEFAULT_DEAD_ZONE)
            else -> null
        }
        if (reported != null) {
            engaged = true
        } else if (!engaged) {
            return false // idle device noise — never touched
        }

        if (reported == null) {
            // Centred while engaged: snap exactly onto the crosshair. Joysticks
            // stop emitting at rest, so a merely-smoothed near-centre pointer
            // would leave the engine zooming forever on the last offset (the
            // e2e test caught exactly this). The spring-back motion itself is
            // still smooth — real events converge before the snap.
            if (smoothedX != 0.5f || smoothedY != 0.5f) {
                smoothedX = 0.5f
                smoothedY = 0.5f
                onJoystickNormalized(0.5f, 0.5f)
            }
            return true
        }
        val (rawX, rawY, flat) = reported

        val nx = JoystickInputMapping.normalizeAxis(rawX, flat)
        val ny = JoystickInputMapping.normalizeAxis(rawY, flat)

        if (!hasSmoothed) {
            smoothedX = 0.5f
            smoothedY = 0.5f
            hasSmoothed = true
        }
        smoothedX += (JoystickInputMapping.toUnit(nx) - smoothedX) * SMOOTHING_ALPHA
        smoothedY += (JoystickInputMapping.toUnit(ny) - smoothedY) * SMOOTHING_ALPHA

        onJoystickNormalized(
            smoothedX.coerceIn(0f, 1f),
            smoothedY.coerceIn(0f, 1f)
        )
        return true
    }

    /** A pair counts as reported when either axis is deflected beyond its dead zone. */
    private fun reports(x: Float, y: Float, flatX: Float, flatY: Float): Boolean =
        abs(x) > maxOf(flatX, DEFAULT_DEAD_ZONE) || abs(y) > maxOf(flatY, DEFAULT_DEAD_ZONE)

    companion object {
        /** Applied when the device reports no usable `flat` (MotionRange) value. */
        const val DEFAULT_DEAD_ZONE = 0.18f

        /** Exponential smoothing factor; higher = snappier (tilt uses 0.2). */
        const val SMOOTHING_ALPHA = 0.35f

        /** `true` if any attached device reports joystick-class input. */
        fun hasJoystick(): Boolean =
            InputDevice.getDeviceIds().any { id ->
                InputDevice.getDevice(id)?.let { d ->
                    d.sources and InputDevice.SOURCE_CLASS_JOYSTICK != 0
                } == true
            }
    }
}

/**
 * Pure axis math (JVM-testable): dead zone with re-scaling so the full `[0, 1]`
 * output range stays reachable, then the `[0, 1]` canvas mapping with the
 * stick's spring centre at `(0.5, 0.5)`.
 */
object JoystickInputMapping {

    /** Dead zone with re-scale: `|v| <= dz → 0`, otherwise the remaining travel is
     *  stretched back to `[-1, 1]`. */
    fun normalizeAxis(value: Float, deadZone: Float): Float {
        val dz = deadZone.coerceIn(0f, 0.9f)
        val mag = abs(value)
        if (mag <= dz) return 0f
        val scaled = (mag - dz) / (1f - dz)
        return (if (value > 0f) scaled else -scaled).coerceIn(-1f, 1f)
    }

    /** `[-1, 1] → [0, 1]` (Y axis: down = positive, matching screen coords). */
    fun toUnit(normalized: Float): Float = (0.5f + normalized / 2f).coerceIn(0f, 1f)
}
