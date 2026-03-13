package com.cognitive3d.android

import android.util.Log
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.Eye
import androidx.xr.runtime.Session
import androidx.xr.runtime.TrackingState
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.scenecore.scene
import kotlinx.coroutines.*

class AndroidXrHeadTrackingProvider(private val session: Session) : HeadTrackingProvider {
    private var arDevice: ArDevice? = null
    private var leftEye: Eye? = null
    private var rightEye: Eye? = null

    override fun start(scope: CoroutineScope) {
        arDevice = ArDevice.getInstance(session)
        leftEye = Eye.left(session)
        rightEye = Eye.right(session)
    }

    override fun stop() {
        arDevice = null
        leftEye = null
        rightEye = null
    }

    /** Returns the raw device/HMD pose only */
    override fun getHeadPose(): PoseData {
        val device = arDevice ?: return PoseData(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        return try {
            device.state.value.devicePose.toPoseDataFromPerception(session)
        } catch (e: Exception) {
            Log.w(Util.TAG, "Failed to read head pose", e)
            PoseData(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        }
    }

    /**
     * Returns the gaze ray: position from device, rotation from averaged eye gaze.
     * Falls back to device-only pose if eye tracking is unavailable.
     */
    override fun getGazeRay(): GazeRayData {
        val device = arDevice ?: return GazeRayData(0f, 0f, 0f, 0f, 0f, -1f)
        return try {
            getEyeGaze(device) ?: device.state.value.devicePose.toGazeRayFromPerception(session)
        } catch (e: Exception) {
            Log.w(Util.TAG, "Failed to read gaze ray", e)
            GazeRayData(0f, 0f, 0f, 0f, 0f, -1f)
        }
    }

    /**
     * Uses the device pose for position and the averaged gaze direction of both eyes
     * for rotation. Eye poses are in device coordinate space, so they must be transformed
     * via the device pose into activity space. Returns null if eye tracking is unavailable
     * or either eye is not tracking/open, falling back to device pose only.
     */
    private fun getEyeGaze(device: ArDevice): GazeRayData? {
        val left = leftEye ?: return null
        val right = rightEye ?: return null

        val leftState = left.state.value
        val rightState = right.state.value

        if (leftState.trackingState != TrackingState.TRACKING ||
            rightState.trackingState != TrackingState.TRACKING) return null
        if (!leftState.isOpen || !rightState.isOpen) return null

        val devicePose = device.state.value.devicePose
        val deviceInActivity = devicePose.toActivitySpace(session)

        val leftInActivity = deviceInActivity.compose(leftState.pose)
        val rightInActivity = deviceInActivity.compose(rightState.pose)

        // Position from device, rotation from averaged eye gaze
        val gazeRotation = slerp(leftInActivity.rotation, rightInActivity.rotation, 0.5f)

        val combined = Pose(deviceInActivity.translation, gazeRotation)

        return GazeRayData(
            combined.translation.x, combined.translation.y, -combined.translation.z,
            // Flip the direction to point along the +Z axis in Left-Handed space
            combined.forward.x, combined.forward.y, -combined.forward.z
        )
    }

    private fun slerp(a: Quaternion, b: Quaternion, t: Float): Quaternion {
        var dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
        var bx = b.x; var by = b.y; var bz = b.z; var bw = b.w

        // If dot is negative, negate one quaternion to take the shorter path
        if (dot < 0f) {
            dot = -dot
            bx = -bx; by = -by; bz = -bz; bw = -bw
        }

        // If quaternions are very close, use linear interpolation
        if (dot > 0.9995f) {
            return Quaternion(
                a.x + t * (bx - a.x),
                a.y + t * (by - a.y),
                a.z + t * (bz - a.z),
                a.w + t * (bw - a.w)
            )
        }

        val theta = Math.acos(dot.toDouble()).toFloat()
        val sinTheta = Math.sin(theta.toDouble()).toFloat()
        val wa = Math.sin(((1 - t) * theta).toDouble()).toFloat() / sinTheta
        val wb = Math.sin((t * theta).toDouble()).toFloat() / sinTheta

        return Quaternion(
            wa * a.x + wb * bx,
            wa * a.y + wb * by,
            wa * a.z + wb * bz,
            wa * a.w + wb * bw
        )
    }
}
