package com.cognitive3d.android

import android.os.SystemClock
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.Controller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.meta.spatial.toolkit.ControllerType

class MetaQuestControllerTrackingProvider(private val scene: Scene) : ControllerTrackingProvider {
    override fun start() {
    }

    override fun stop() {
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    override suspend fun getActiveControllerType(isRight: Boolean): com.cognitive3d.android.ControllerType =
    withContext(Dispatchers.Main) {
        try {
            val controllers = Query.where { has(Controller.id) }.eval().filter { it.isLocal() }.toList()

            // Find any active controller to determine the current input type
            val activeController = controllers
                .map { it.getComponent<Controller>() }
                .firstOrNull { it.isActive }
                ?: return@withContext com.cognitive3d.android.ControllerType.NONE

            return@withContext when (activeController.type) {
                ControllerType.HAND -> com.cognitive3d.android.ControllerType.HAND
                ControllerType.CONTROLLER -> com.cognitive3d.android.ControllerType.CONTROLLER
                else -> com.cognitive3d.android.ControllerType.NONE
            }
        } catch (e: Exception) {
            // Gracefully return NONE if the native session is shutting down
            com.cognitive3d.android.ControllerType.NONE
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
