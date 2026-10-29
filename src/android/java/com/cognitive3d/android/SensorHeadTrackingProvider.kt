package com.cognitive3d.android

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3DoF orientation from the device rotation sensor, in the SDK's left-handed frame
 * (Y up, +Z forward). Position is always the origin.
 *
 * "Forward" is the direction the screen faces away from the viewer: on a head-worn device
 * (RealWear) that approximates head direction; on a phone or tablet it is where the device
 * points, not where the user looks.
 *
 * Uses the game rotation vector (gyro + accelerometer, no magnetometer), whose heading is
 * arbitrary, so heading is zeroed on each [start]: +Z is the direction faced when recording
 * (re)started. Pitch and roll stay absolute relative to gravity.
 */
class SensorHeadTrackingProvider(activity: Activity) : HeadTrackingProvider, SensorEventListener {

    private val sensorManager = activity.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val display: Display? = getDisplay(activity)

    // Latest raw rotation vector; replaced (not mutated) on each event so readers see a whole sample
    @Volatile
    private var rotationVector: FloatArray? = null
    // Heading (radians) of the first sample after start; subtracted so recording starts facing +Z
    @Volatile
    private var yawReference: Float? = null
    private var registered = false

    override fun start(scope: CoroutineScope) {
        if (registered) return
        if (sensor == null) {
            Util.logWarning("No rotation vector sensor on this device — head orientation will not be recorded")
            return
        }
        yawReference = null
        rotationVector = null
        registered = sensorManager.registerListener(this, sensor, SAMPLING_PERIOD_US)
        if (!registered) Log.w(Util.TAG, "Failed to register rotation vector sensor")
    }

    override fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        rotationVector = event.values.copyOf()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    override fun getHeadPose(): PoseData {
        val m = currentRotation() ?: return PoseData(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val q = matrixToQuaternion(m)
        return PoseData(0f, 0f, 0f, q[0], q[1], q[2], q[3])
    }

    override fun getGazeRay(): GazeRayData {
        val m = currentRotation() ?: return GazeRayData(0f, 0f, 0f, 0f, 0f, 1f)
        // Forward is the rotated +Z axis, i.e. the third column
        return GazeRayData(0f, 0f, 0f, m[2], m[5], m[8])
    }

    /** Returns the current device-to-world rotation (row-major 3x3) in the SDK frame, or null before the first sample. */
    private fun currentRotation(): FloatArray? {
        val rv = rotationVector ?: return null

        val raw = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(raw, rv)
        // Express the device axes in the screen's current orientation (e.g. phone in landscape)
        val r = remapForDisplay(raw)

        // Sensor world is right-handed (X east, Y north, Z up) and the device looks along -Z.
        // SDK frame is left-handed (X right, Y up, Z forward): swap world Y/Z and negate device Z.
        val m = floatArrayOf(
            r[0], r[1], -r[2],
            r[6], r[7], -r[8],
            r[3], r[4], -r[5]
        )

        val reference = yawReference ?: atan2(m[2], m[8]).also { yawReference = it }
        return rotateAboutY(m, -reference)
    }

    private fun remapForDisplay(r: FloatArray): FloatArray {
        val (axisX, axisY) = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> return r
        }
        val out = FloatArray(9)
        SensorManager.remapCoordinateSystem(r, axisX, axisY, out)
        return out
    }

    /** Left-multiplies [m] by a rotation of [angle] radians about +Y (left-handed). */
    private fun rotateAboutY(m: FloatArray, angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        return floatArrayOf(
            c * m[0] + s * m[6], c * m[1] + s * m[7], c * m[2] + s * m[8],
            m[3], m[4], m[5],
            -s * m[0] + c * m[6], -s * m[1] + c * m[7], -s * m[2] + c * m[8]
        )
    }

    /** Converts a row-major rotation matrix to a quaternion (x, y, z, w). */
    private fun matrixToQuaternion(m: FloatArray): FloatArray {
        val trace = m[0] + m[4] + m[8]
        return if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            floatArrayOf((m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s, 0.25f * s)
        } else if (m[0] > m[4] && m[0] > m[8]) {
            val s = sqrt(1f + m[0] - m[4] - m[8]) * 2f
            floatArrayOf(0.25f * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s, (m[7] - m[5]) / s)
        } else if (m[4] > m[8]) {
            val s = sqrt(1f + m[4] - m[0] - m[8]) * 2f
            floatArrayOf((m[1] + m[3]) / s, 0.25f * s, (m[5] + m[7]) / s, (m[2] - m[6]) / s)
        } else {
            val s = sqrt(1f + m[8] - m[0] - m[4]) * 2f
            floatArrayOf((m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25f * s, (m[3] - m[1]) / s)
        }
    }

    private fun getDisplay(activity: Activity): Display? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        }
    }

    companion object {
        // 50 Hz; gaze is sampled slower, this just keeps the latest reading fresh
        private const val SAMPLING_PERIOD_US = 20_000
    }
}
