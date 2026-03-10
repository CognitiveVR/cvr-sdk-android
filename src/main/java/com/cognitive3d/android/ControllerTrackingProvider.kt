package com.cognitive3d.android

import kotlinx.coroutines.CoroutineScope

enum class ControllerType {
    HAND,
    CONTROLLER,
    NONE
}

interface ControllerTrackingProvider {
    fun start()
    fun stop()
    suspend fun getHandPose(isRight: Boolean): PoseData?
    suspend fun getControllerPose(isRight: Boolean): PoseData?
    suspend fun getActiveControllerType(isRight: Boolean): ControllerType
}