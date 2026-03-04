package com.cognitive3d.android

import kotlinx.coroutines.CoroutineScope

interface HeadTrackingProvider {
    fun start(scope: CoroutineScope)
    fun stop()
    fun getHeadPose(): PoseData   // your own data class, not Jetpack XR's Pose
}