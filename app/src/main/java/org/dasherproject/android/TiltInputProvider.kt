package org.dasherproject.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Reads the device's game-rotation-vector sensor and converts it into normalised 2-D
 * cursor coordinates fed to [DasherEngine.onTiltNormalized].
 *
 * Ported from Dasher-Mobile (janmurin2) — the mapping is identical:
 * - **X axis** → device roll (left/right) mapped to `[0, 1]`, neutral = 0.5.
 * - **Y axis** → device pitch (forward/back) mapped to `[0, 1]`, neutral = 0.5.
 *
 * A *baseline* captured on the first sample after [calibrate] (or construction) lets the
 * user hold the device at any comfortable angle and treat that as the centre. Exponential
 * smoothing (alpha ≈ 0.2) and a dead zone reduce jitter near neutral.
 *
 * @param context context used to obtain the [SensorManager].
 * @param onTiltNormalized invoked with `(normalizedX, normalizedY)` on the sensor thread.
 */
class TiltInputProvider(
    context: Context,
    private val onTiltNormalized: (Float, Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var isRegistered = false
    private var hasBaseline = false
    private var baselinePitch = 0f
    private var baselineRoll = 0f
    private var smoothedX = 0.5f
    private var smoothedY = 0.5f

    private val pitchRangeRad = 0.6f
    private val rollRangeRad = 0.6f
    private val deadZoneRad = 0.05f
    private val smoothingAlpha = 0.2f

    /** `true` if the device has a game-rotation-vector sensor. */
    fun hasSensor(): Boolean = rotationSensor != null

    /** Registers the sensor listener. No-op if already registered or no sensor. */
    fun register() {
        if (isRegistered || rotationSensor == null) return
        isRegistered = sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    /** Unregisters the sensor listener. */
    fun unregister() {
        if (!isRegistered) return
        sensorManager.unregisterListener(this)
        isRegistered = false
    }

    /** Resets the baseline so the next sample becomes the neutral (centre) position. */
    fun calibrate() {
        hasBaseline = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val pitch = orientation[1]
        val roll = orientation[2]

        if (!hasBaseline) {
            baselinePitch = pitch
            baselineRoll = roll
            smoothedX = 0.5f
            smoothedY = 0.5f
            hasBaseline = true
        }

        val deltaPitch = applyDeadZone(pitch - baselinePitch)
        val deltaRoll = applyDeadZone(roll - baselineRoll)

        val targetX = (0.5f + (deltaRoll / rollRangeRad)).coerceIn(0f, 1f)
        val targetY = (0.5f - (deltaPitch / pitchRangeRad)).coerceIn(0f, 1f)

        smoothedX += (targetX - smoothedX) * smoothingAlpha
        smoothedY += (targetY - smoothedY) * smoothingAlpha

        onTiltNormalized(smoothedX.coerceIn(0f, 1f), smoothedY.coerceIn(0f, 1f))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun applyDeadZone(value: Float): Float {
        val mag = abs(value)
        if (mag <= deadZoneRad) return 0f
        return if (value > 0f) mag - deadZoneRad else -(mag - deadZoneRad)
    }
}
