package com.cognitive3d.android

import kotlinx.coroutines.CoroutineScope
import com.meta.spatial.core.Pose
import com.meta.spatial.runtime.Scene

class MetaQuestHeadTrackingProvider(private val scene: Scene) : HeadTrackingProvider {
    override fun start(scope: CoroutineScope) {

    }
    override fun stop() { }
    override fun getHeadPose(): PoseData {
        // Get the current head pose from the Spatial SDK Scene
        val pose: Pose = scene.getViewerPose()
        return pose.toPoseData()
    }
}