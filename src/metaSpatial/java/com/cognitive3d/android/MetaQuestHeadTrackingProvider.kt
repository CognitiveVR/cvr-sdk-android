package com.cognitive3d.android

import kotlinx.coroutines.CoroutineScope
import com.meta.spatial.core.Pose
import com.meta.spatial.runtime.Scene

class MetaQuestHeadTrackingProvider(private val scene: Scene) : HeadTrackingProvider {
    override fun start(scope: CoroutineScope) {

    }
    override fun stop() { }
    override fun getHeadPose(): PoseData? {
        val pose = trackedViewerPose() ?: return null
        return pose.toPoseData()
    }

    override fun getGazeRay(): GazeRayData? {
        val pose = trackedViewerPose() ?: return null
        val fwd = pose.forward()
        return GazeRayData(pose.t.x, pose.t.y, pose.t.z, fwd.x, fwd.y, fwd.z)
    }

    /**
     * Returns the viewer pose, or null while the headset isn't tracking. The Spatial SDK
     * exposes no tracking state here; it reports an identity pose at the origin until
     * tracking is available, which a real tracked head never produces exactly.
     */
    private fun trackedViewerPose(): Pose? {
        val pose: Pose = scene.getViewerPose()
        val untracked = pose.t.x == 0f && pose.t.y == 0f && pose.t.z == 0f &&
            pose.q.x == 0f && pose.q.y == 0f && pose.q.z == 0f && pose.q.w == 1f
        return if (untracked) null else pose
    }
}
