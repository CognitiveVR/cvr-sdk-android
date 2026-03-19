package com.cognitive3d.android

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
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

fun Quaternion.rotate(v: Vector3): Vector3 {
    val tx = 2f * (y * v.z - z * v.y)
    val ty = 2f * (z * v.x - x * v.z)
    val tz = 2f * (x * v.y - y * v.x)
    return Vector3(
        v.x + w * tx + (y * tz - z * ty),
        v.y + w * ty + (z * tx - x * tz),
        v.z + w * tz + (x * ty - y * tx)
    )
}