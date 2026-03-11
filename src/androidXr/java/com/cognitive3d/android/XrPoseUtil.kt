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
 * For Raw Tracking (Head/Hands): They are in Perception Space and NEED conversion.
 */
fun Pose.toPoseDataFromPerception(session: Session): PoseData {
    val lh = this.toActivitySpace(session).toLeftHanded()
    return PoseData(
        lh.translation.x, lh.translation.y, lh.translation.z,
        lh.rotation.x, lh.rotation.y, lh.rotation.z, lh.rotation.w
    )
}

/**
 * For Entities/Trackables: They are likely already in Activity Space.
 * We ONLY flip the handedness.
 */
fun Pose.toPoseDataFromActivity(): PoseData {
    val lh = this.toLeftHanded()
    return PoseData(
        lh.translation.x, lh.translation.y, lh.translation.z,
        lh.rotation.x, lh.rotation.y, lh.rotation.z, lh.rotation.w
    )
}

/**
 * Converts a uniform scale factor into a 3-axis ScaleData (applies equally to x, y, z).
 */
fun Float.toScaleData(): ScaleData {
    return ScaleData(this, this, this)
}
