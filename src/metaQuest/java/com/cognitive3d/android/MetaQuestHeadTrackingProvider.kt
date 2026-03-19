package com.cognitive3d.android

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import com.meta.spatial.core.Pose
import com.meta.spatial.runtime.Scene

class MetaQuestHeadTrackingProvider(private val scene: Scene) : HeadTrackingProvider {
    override fun start(scope: CoroutineScope) {

    }
    override fun stop() { }
    override fun getHeadPose(): PoseData {
        val pose: Pose = scene.getViewerPose()
        return pose.toPoseData()
    }

    override fun getGazeRay(): GazeRayData {
        val pose: Pose = scene.getViewerPose()
        val fwd = pose.forward()
        return GazeRayData(pose.t.x, pose.t.y, pose.t.z, fwd.x, fwd.y, fwd.z)
    }
}