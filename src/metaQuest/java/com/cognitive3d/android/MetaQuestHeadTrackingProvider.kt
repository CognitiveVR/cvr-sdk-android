package com.cognitive3d.android

import kotlinx.coroutines.CoroutineScope

class MetaQuestHeadTrackingProvider : HeadTrackingProvider {
    override fun start(scope: CoroutineScope) {
        // TODO: Initialize OpenXR head tracking
    }
    override fun stop() { }
    override fun getHeadPose(): PoseData = PoseData(0f, 0f, 0f, 0f, 0f, 0f, 1f)
}