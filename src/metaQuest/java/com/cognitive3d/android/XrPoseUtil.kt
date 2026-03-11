package com.cognitive3d.android

import com.meta.spatial.core.Pose
import com.meta.spatial.toolkit.Scale

/**
 * Converts a Meta Spatial SDK Pose to a platform-agnostic PoseData.
 */
fun Pose.toPoseData(): PoseData {
    return PoseData(
        this.t.x, this.t.y, this.t.z,
        this.q.x, this.q.y, this.q.z, this.q.w
    )
}

fun Scale.toScaleData(): ScaleData {
    return ScaleData(
        this.scale.x, this.scale.y, this.scale.z
    )
}