package com.cognitive3d.android

import kotlinx.coroutines.*

object GazeManager {
    private var recordGazeJob: Job? = null
    private var isRecording = false
    private var headTrackingProvider: HeadTrackingProvider? = null

    fun startGazeRecording(scope: CoroutineScope, provider: HeadTrackingProvider) {
        if (isRecording) return
        isRecording = true
        headTrackingProvider = provider
        provider.start(scope)

        recordGazeJob = scope.launch(Dispatchers.Default) {
            while (isActive && isRecording) {
                val startTime = System.currentTimeMillis()
                val pose = provider.getHeadPose()

                Serialization.recordGaze(
                    pose.px, pose.py, pose.pz,
                    pose.rx, pose.ry, pose.rz, pose.rw,
                    startTime.toDouble() / 1000.0
                )

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
    }

    fun getHeadPose(): PoseData {
        return headTrackingProvider?.getHeadPose() ?: PoseData(0f, 0f, 0f, 0f, 0f, 0f, 1f)
    }
}