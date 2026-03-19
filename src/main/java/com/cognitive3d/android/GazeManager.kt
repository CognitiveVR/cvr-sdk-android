package com.cognitive3d.android

import kotlinx.coroutines.*

/**
 * Records head pose, gaze direction, and gaze hit results at a fixed interval.
 * Runs a coroutine loop that samples the head tracking provider, tests gaze against
 * dynamic objects, and serializes the results for upload.
 */
object GazeManager {
    private var recordGazeJob: Job? = null
    private var isRecording = false
    private var headTrackingProvider: HeadTrackingProvider? = null
    private var dynamicObjectProvider: DynamicObjectProvider? = null
    private val gazeMaxDistance: Float = 100f

    /** Starts the gaze recording loop at the configured snapshot interval. */
    fun startGazeRecording(
        scope: CoroutineScope,
        provider: HeadTrackingProvider,
        dynamicProvider: DynamicObjectProvider? = null
    ) {
        if (isRecording) return
        isRecording = true
        headTrackingProvider = provider
        dynamicObjectProvider = dynamicProvider
        provider.start(scope)

        recordGazeJob = scope.launch(Dispatchers.Default) {
            while (isActive && isRecording) {
                val startTime = System.currentTimeMillis()
                val hmdPose = provider.getHeadPose()
                val gazeRay = provider.getGazeRay()

                // Test gaze ray against registered dynamic objects
                val hit = dynamicObjectProvider?.getLatestGazeHit(gazeRay, gazeMaxDistance)

                val objectId: String?
                var gazePoint: FloatArray?

                if (hit != null) {
                    gazePoint = FloatArray(3)
                    gazePoint[0] = hit.localHitX
                    gazePoint[1] = hit.localHitY
                    gazePoint[2] = hit.localHitZ
                    objectId = hit.objectId
                } else {
                    gazePoint = null
                    objectId = null
                }

                Serialization.recordGaze(
                    hmdPose.px, hmdPose.py, hmdPose.pz,
                    hmdPose.rx, hmdPose.ry, hmdPose.rz, hmdPose.rw,
                    gazePoint,
                    startTime.toDouble() / 1000.0,
                    objectId
                )

                // Record controller/hand dynamics in sync with gaze
                DynamicManager.processControllerDynamics()

                val elapsed = System.currentTimeMillis() - startTime
                val delayTime = (Util.SNAPSHOTINTERVAL * 1000).toLong() - elapsed
                if (delayTime > 0) delay(delayTime)
            }
        }
    }

    /** Stops the gaze recording loop and releases the tracking provider. */
    fun stopGazeRecording() {
        isRecording = false
        recordGazeJob?.cancel()
        recordGazeJob = null
        headTrackingProvider?.stop()
        headTrackingProvider = null
        dynamicObjectProvider = null
    }

    /** Returns the current head pose, or a default identity pose if tracking is unavailable. */
    fun getHeadPose(): PoseData {
        return headTrackingProvider?.getHeadPose() ?: PoseData(0f, 0f, 0f, 0f, 0f, 0f, 1f)
    }
}