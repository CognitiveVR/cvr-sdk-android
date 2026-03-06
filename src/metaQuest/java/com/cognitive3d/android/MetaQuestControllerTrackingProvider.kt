package com.cognitive3d.android

import android.os.SystemClock
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.meta.spatial.toolkit.ControllerType

class MetaQuestControllerTrackingProvider(private val scene: Scene) : ControllerTrackingProvider {

    override fun start(scope: CoroutineScope) {

    }

    override fun stop() {

    }

    override suspend fun getActiveControllerType(isRight: Boolean): com.cognitive3d.android.ControllerType =
    withContext(Dispatchers.Main) {
        val controllers = Query.where { has(Controller.id) }.eval().filter { it.isLocal() }.toList()

        val index = if (isRight) 1 else 0
        if (index >= controllers.size) {
            return@withContext com.cognitive3d.android.ControllerType.NONE
        }

        val controller = controllers[index].getComponent<Controller>()
        if (!controller.isActive) {
            return@withContext com.cognitive3d.android.ControllerType.NONE
        }

        return@withContext when (controller.type) {
            ControllerType.HAND -> com.cognitive3d.android.ControllerType.HAND
            ControllerType.CONTROLLER -> com.cognitive3d.android.ControllerType.CONTROLLER
            else -> com.cognitive3d.android.ControllerType.NONE
        }
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    override suspend fun getHandPose(isRight: Boolean): PoseData? = withContext(Dispatchers.Main) {
        try {
            val timestamp = SystemClock.elapsedRealtimeNanos()
            val controllerPose = scene.getControllerPoseAtTime(!isRight, timestamp)
            controllerPose.pose.toPoseData()
        } catch (e: Exception) {
            null
        }
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    override suspend fun getControllerPose(isRight: Boolean): PoseData? =
        withContext(Dispatchers.Main) {
        try {
            val timestamp = SystemClock.elapsedRealtimeNanos()
            val controllerPose = scene.getControllerPoseAtTime(!isRight, timestamp)
            controllerPose.pose.toPoseData()
        } catch (e: Exception) {
            null
        }
    }
}
