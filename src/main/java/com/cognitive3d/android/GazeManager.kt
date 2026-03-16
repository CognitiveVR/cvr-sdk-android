package com.cognitive3d.android

import kotlinx.coroutines.*

object GazeManager {
    private var recordGazeJob: Job? = null
    private var isRecording = false
    private var headTrackingProvider: HeadTrackingProvider? = null
    private var dynamicObjectProvider: DynamicObjectProvider? = null
    private var gazeMaxDistance: Float = 100f

    fun startGazeRecording(
        scope: CoroutineScope,
        provider: HeadTrackingProvider,
        dynamicProvider: DynamicObjectProvider? = null,
        maxDistance: Float = 100f
    ) {
        if (isRecording) return
        isRecording = true
        headTrackingProvider = provider
        dynamicObjectProvider = dynamicProvider
        gazeMaxDistance = maxDistance
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
                    gazePoint[0] = hit.hitX
                    gazePoint[1] = hit.hitY
                    gazePoint[2] = hit.hitZ
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

    fun stopGazeRecording() {
        isRecording = false
        recordGazeJob?.cancel()
        recordGazeJob = null
        headTrackingProvider?.stop()
        headTrackingProvider = null
        dynamicObjectProvider = null
    }

    fun getHeadPose(): PoseData {
        return headTrackingProvider?.getHeadPose() ?: PoseData(0f, 0f, 0f, 0f, 0f, 0f, 1f)
    }
}