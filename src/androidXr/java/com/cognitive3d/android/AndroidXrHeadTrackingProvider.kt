package com.cognitive3d.android

import android.util.Log
import androidx.xr.arcore.ArDevice
import androidx.xr.runtime.Session
import kotlinx.coroutines.*

class AndroidXrHeadTrackingProvider(private val session: Session) : HeadTrackingProvider {
    private var arDevice: ArDevice? = null

    override fun start(scope: CoroutineScope) {
        arDevice = ArDevice.getInstance(session)
    }

    override fun stop() {
        arDevice = null
    }

    override fun getHeadPose(): PoseData {
        val device = arDevice ?: return PoseData(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        return try {
            device.state.value.devicePose.toPoseDataFromPerception(session)
        } catch (e: Exception) {
            Log.w(Util.TAG, "Failed to read head pose", e)
            PoseData(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        }
    }
}
