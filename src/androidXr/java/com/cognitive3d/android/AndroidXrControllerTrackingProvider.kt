package com.cognitive3d.android

import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.runtime.Session

class AndroidXrControllerTrackingProvider(private val session: Session) : ControllerTrackingProvider {

    private var leftHand: Hand? = null
    private var rightHand: Hand? = null

    override fun start() {
        leftHand = Hand.left(session)
        rightHand = Hand.right(session)
    }

    override fun stop() {
        leftHand = null
        rightHand = null
    }

    override suspend fun getActiveControllerType(isRight: Boolean): ControllerType {
        return try {
            val hand = if (isRight) rightHand else leftHand
            val handState = hand?.state?.value ?: return ControllerType.NONE
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
            val hand = if (isRight) rightHand else leftHand
            val handState = hand?.state?.value ?: return null
            val handPose = handState.handJoints[HandJointType.HAND_JOINT_TYPE_WRIST]
                ?: return null
            handPose.toPoseDataFromPerception(session)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getControllerPose(isRight: Boolean): PoseData? {
        // Jetpack XR currently uses hand tracking only; no physical controllers
        return null
    }
}
