package com.cognitive3d.android

import com.meta.spatial.core.Pose

/**
 * Converts a Meta Spatial SDK Pose to a platform-agnostic PoseData.
 */
fun Pose.toPoseData(): PoseData {
    return PoseData(
        this.t.x, this.t.y, this.t.z,
        this.q.x, this.q.y, this.q.z, this.q.w
    )
}