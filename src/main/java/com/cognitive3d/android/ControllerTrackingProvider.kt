package com.cognitive3d.android

import kotlinx.coroutines.CoroutineScope

interface ControllerTrackingProvider {
    fun start(scope: CoroutineScope)
    fun stop()
    suspend fun getHandPose(isRight: Boolean): PoseData?
    suspend fun getControllerPose(isRight: Boolean): PoseData?
}