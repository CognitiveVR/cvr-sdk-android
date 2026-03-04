package com.cognitive3d.android

import android.util.Log
import androidx.xr.arcore.ArDevice
import androidx.xr.runtime.Session
import kotlinx.coroutines.*

class AndroidXrHeadTrackingProvider(private val session: Session) : HeadTrackingProvider {
    private var arDevice: ArDevice? = null
    private var collectJob: Job? = null
    private var latestPose: PoseData = PoseData(0f, 0f, 0f, 0f, 0f, 0f, 1f)

    override fun start(scope: CoroutineScope) {
        arDevice = ArDevice.getInstance(session)
        collectJob = scope.launch(Dispatchers.Default) {
            arDevice?.state?.collect {
                val pose = it.devicePose.toPoseData(session)
                latestPose = pose
            }
        }
    }

    override fun stop() {
        collectJob?.cancel()
        collectJob = null
        arDevice = null
    }

    override fun getHeadPose(): PoseData = latestPose
}
