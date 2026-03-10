package com.cognitive3d.android

import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.runtime.Session
import kotlinx.coroutines.flow.first

class AndroidXrControllerTrackingProvider(private val session: Session) : ControllerTrackingProvider {

    override fun start() {
        // Hand tracking is started via Session configuration; no additional setup needed
    }

    override fun stop() {
        // No-op; hand tracking lifecycle is managed by the Session
    }

    override suspend fun getActiveControllerType(isRight: Boolean): ControllerType {
        return try {
            val hand = if (isRight) Hand.right(session) else Hand.left(session)
            val handState = hand?.state?.first() ?: return ControllerType.NONE
            if (handState.handJoints[HandJointType.HAND_JOINT_TYPE_WRIST] != null) {
                ControllerType.HAND
            } else {
                ControllerType.NONE
            }
        } catch (e: Exception) {
            ControllerType.NONE
        }
    }

    override suspend fun getHandPose(isRight: Boolean): PoseData? {
        return try {
            val hand = if (isRight) Hand.right(session) else Hand.left(session)
            val handState = hand?.state?.first() ?: return null
            val handPose = handState.handJoints[HandJointType.HAND_JOINT_TYPE_WRIST]
                ?: return null
            handPose.toPoseData(session)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getControllerPose(isRight: Boolean): PoseData? {
        // Jetpack XR currently uses hand tracking only; no physical controllers
        return null
    }
}
