package com.cognitive3d.android

import android.util.Log
import androidx.xr.arcore.ArDevice
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

import com.cognitive3d.android.Util.toActivitySpace
import com.cognitive3d.android.Util.toLeftHanded

object GazeManager {

    private var recordGazeJob: Job? = null
    private var isRecording = false

    // Use WeakReference to prevent memory leaks with Android context classes
    private var sessionRef: WeakReference<Session>? = null
    private var arDevice : ArDevice? = null

    fun startGazeRecording(scope: CoroutineScope, session : Session) {
        if (isRecording) return
        isRecording = true

        sessionRef = WeakReference(session)
        arDevice = ArDevice.getInstance(session)

        recordGazeJob = scope.launch(Dispatchers.Default) {
            arDevice?.state?.collect {
                if (!isRecording) return@collect
                
                val startTime = System.currentTimeMillis()
                val pose = getHeadPose()

                val position = pose.translation
                val rotation = pose.rotation

                Serialization.recordGaze(
                    floatArrayOf(position.x, position.y, position.z),
                    floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w),
                    startTime.toDouble() / 1000.0
                )

                val elapsedTime = System.currentTimeMillis() - startTime
                val delayTime = (Util.SNAPSHOTINTERVAL * 1000).toLong() - elapsedTime
                if (delayTime > 0) {
                    delay(delayTime)
                }
            }
        }
    }

    fun stopGazeRecording() {
        isRecording = false
        recordGazeJob?.cancel()
        recordGazeJob = null
        // Clearing references to help the GC
        sessionRef = null
        arDevice = null
    }

    fun getHeadPose() : Pose {
        val session = sessionRef?.get()
        val device = arDevice

        if (session == null || device == null) {
            return Pose.Identity
        }

        return device.state.value.devicePose
            .toActivitySpace(session)
            .toLeftHanded()
    }
}