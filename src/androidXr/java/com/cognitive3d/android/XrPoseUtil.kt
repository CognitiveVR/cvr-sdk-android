package com.cognitive3d.android

import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.scene

/**
 * Transforms a pose from perception space to activity space.
 */
fun Pose.toActivitySpace(session: Session): Pose {
    return session.scene.perceptionSpace.transformPoseTo(
        this,
        session.scene.activitySpace
    )
}

/**
 * Converts a pose from a right-handed coordinate system to a left-handed one.
 */
fun Pose.toLeftHanded(): Pose {
    return Pose(
        Vector3(translation.x, translation.y, -translation.z),
        Quaternion(-rotation.x, -rotation.y, rotation.z, rotation.w)
    )
}

/**
 * Converts a Jetpack XR Pose to a platform-agnostic PoseData (left-handed).
 */
fun Pose.toPoseData(session: Session): PoseData {
    val lh = this.toActivitySpace(session).toLeftHanded()
    return PoseData(
        lh.translation.x, lh.translation.y, lh.translation.z,
        lh.rotation.x, lh.rotation.y, lh.rotation.z, lh.rotation.w
    )
}
