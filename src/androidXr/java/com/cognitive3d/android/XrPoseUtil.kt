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
 * For gaze ray from perception space: converts to activity space, flips handedness,
 * then extracts position + forward direction as a GazeRayData.
 */
fun Pose.toGazeRayFromPerception(session: Session): GazeRayData {
    val lh = this.toActivitySpace(session)
    return GazeRayData(
        lh.translation.x, lh.translation.y, -lh.translation.z,
        lh.forward.x, lh.forward.y, -lh.forward.z
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

fun Quaternion.conjugate(): Quaternion {
    return Quaternion(-x, -y, -z, w)
}

fun Quaternion.rotate(v: Vector3): Vector3 {
    val qx = x; val qy = y; val qz = z; val qw = w
    val tx = 2f * (qy * v.z - qz * v.y)
    val ty = 2f * (qz * v.x - qx * v.z)
    val tz = 2f * (qx * v.y - qy * v.x)
    return Vector3(
        v.x + qw * tx + (qy * tz - qz * ty),
        v.y + qw * ty + (qz * tx - qx * tz),
        v.z + qw * tz + (qx * ty - qy * tx)
    )
}

fun Pose.inverse(): Pose {
    val invRot = rotation.conjugate()
    val negT = Vector3(-translation.x, -translation.y, -translation.z)
    val invT = invRot.rotate(negT)
    return Pose(invT, invRot)
}
