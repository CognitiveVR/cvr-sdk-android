package com.cognitive3d.android

import android.util.Log
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.runtime.Session
import androidx.xr.runtime.math.*
import androidx.xr.scenecore.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.acos
import kotlin.math.abs

import com.cognitive3d.android.Util.toActivitySpace
import com.cognitive3d.android.Util.toLeftHanded

object DynamicManager {

    private var recordDynamicJob: Job? = null
    private var isRecording = false
    private var currentSession: Session? = null

    internal val dynamics = CopyOnWriteArrayList<DynamicObject>()

    fun startDynamicRecording(scope: CoroutineScope, session : Session) {
        if (isRecording) return
        isRecording = true
        currentSession = session

        registerHands()

        recordDynamicJob = scope.launch(Dispatchers.Default) {
            while (isActive && isRecording) {
                val startTime = System.currentTimeMillis()
                
                try {
                    processDynamics(session)
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(Util.TAG, "Error in dynamic recording loop", e)
                    }
                }

                val elapsedTime = System.currentTimeMillis() - startTime
                val delayTime = (Util.SNAPSHOTINTERVAL * 1000).toLong() - elapsedTime
                if (delayTime > 0) {
                    delay(delayTime)
                }
            }
        }
    }

    private suspend fun processDynamics(session: Session, force: Boolean = false) {
        val currentTime = System.currentTimeMillis().toDouble() / 1000.0
        
        for (obj in dynamics) {
            if (!obj.active) continue

            // Get the current pose in Activity Space
            val currentPose: Pose? = when {
                obj.isController -> {
                    tryGetHandPose(session, obj)
                }
                else -> {
                    obj.entity?.getPose()
                }
            }?.toLeftHanded()

            val isTracked = currentPose != null &&
                    (obj.entity?.isEnabled() ?: true)
            var shouldRecord = obj.isDirty || force

            // Handle Enabled State Change
            var propertiesJson: String? = null
            
            if (!obj.hasEnabled || isTracked != obj.lastTrackedState) {
                shouldRecord = true
                obj.hasEnabled = true
                obj.lastTrackedState = isTracked
                propertiesJson = "{\"enabled\":$isTracked}"
            }

            // Check Movement Thresholds
            // Multiply by 100 only if it's an entity. Controllers (hands) stay at 1.0f.
            val currentScaleValue = obj.entity?.let { it.getScale() * 100f } ?: 1.0f
            obj.currentScale = Vector3(currentScaleValue, currentScaleValue, currentScaleValue)

            if (!force && isTracked && !shouldRecord && currentPose != null) {
                val pos = currentPose.translation
                val rot = currentPose.rotation
                
                // Position check
                val dx = pos.x - obj.lastSentPosition.x
                val dy = pos.y - obj.lastSentPosition.y
                val dz = pos.z - obj.lastSentPosition.z
                val distSqr = dx * dx + dy * dy + dz * dz
                
                if (distSqr > obj.positionThreshold * obj.positionThreshold) {
                    shouldRecord = true
                } else {
                    // Rotation check
                    val dot = obj.lastSentRotation.x * rot.x + 
                              obj.lastSentRotation.y * rot.y + 
                              obj.lastSentRotation.z * rot.z + 
                              obj.lastSentRotation.w * rot.w
                              
                    val angle = acos(dot.coerceIn(-1f, 1f).toDouble()) * 2.0 * (180.0 / Math.PI)
                    if (angle > obj.rotationThreshold) {
                        shouldRecord = true
                    } else if (abs(currentScaleValue - obj.lastSentScale.x) > obj.scaleThreshold) {
                        shouldRecord = true
                    }
                }
            }

            // Record Snapshot
            if (shouldRecord) {
                val pos = currentPose?.translation ?: obj.lastSentPosition
                val rot = currentPose?.rotation ?: obj.lastSentRotation

                Serialization.recordDynamic(
                    obj.id,
                    currentTime,
                    pos.x, pos.y, pos.z,
                    rot.x, rot.y, rot.z, rot.w,
                    currentScaleValue, currentScaleValue, currentScaleValue,
                    true,
                    propertiesJson
                )
                
                if (isTracked && currentPose != null) {
                    obj.lastSentPosition = currentPose.translation
                    obj.lastSentRotation = currentPose.rotation
                    obj.lastSentScale = obj.currentScale
                }
                obj.isDirty = false
            }
        }
    }

    /**
     * Helper to register default hands for tracking.
     * IDs are mapped to align with Dashboard expectations (1: Left, 2: Right).
     */
    fun registerHands() {
        if (dynamics.any { it.id == "1" || it.id == "2" }) return

        val leftHand = DynamicObject(
            id = "1",
            name = "Left Hand",
            meshName = "handLeft",
            isController = true,
            controllerType = "hand_left"
        )
        registerDynamicObject(leftHand)

        val rightHand = DynamicObject(
            id = "2",
            name = "Right Hand",
            meshName = "handRight",
            isController = true,
            controllerType = "hand_right"
        )
        registerDynamicObject(rightHand)
    }

    /**
     * Registers a dynamic object and sends its manifest entry.
     */
    fun registerDynamicObject(dynamicObject : DynamicObject) {
        dynamics.add(dynamicObject)
        
        CoroutineScope(Dispatchers.IO).launch {
            Serialization.recordDynamicManifest(
                dynamicObject.id,
                dynamicObject.name,
                dynamicObject.meshName,
                dynamicObject.isController,
                dynamicObject.controllerType
            )
        }
    }

    fun unregisterDynamicObject(dynamicObject: DynamicObject) {
        if (dynamicObject.isController) return // Hands should generally not be unregistered
        dynamics.remove(dynamicObject)
    }

    /**
     * Safely attempts to get the hand pose in Activity Space.
     * 
     * @param session The current XR Session.
     * @param obj The DynamicObject representing the hand.
     */
    suspend fun tryGetHandPose(session: Session, obj: DynamicObject): Pose? {
        val isRight = obj.controllerType == "hand_right"
        return try {
            val hand = if (isRight) Hand.right(session) else Hand.left(session)
            val handState = hand?.state?.first() ?: return null
            val handPose = handState.handJoints[HandJointType.HAND_JOINT_TYPE_WRIST]
                ?: return null

            // Hand poses from ARCore are in Perception Space; transform to Activity Space
            handPose.toActivitySpace(session)
        } catch (e: Exception) {
            null
        }
    }
    
    fun stopDynamicRecording() {
        isRecording = false
        recordDynamicJob?.cancel()
        recordDynamicJob = null
    }

    suspend fun recordFinalDynamics() {
        currentSession?.let { session ->
            try {
                processDynamics(session, force = true)
            } catch (e: Exception) {
                Log.e(Util.TAG, "Error recording final dynamics", e)
            }
        }
    }

    // First 4 ids will be reserved for controllers and hands
    private var lastValidId : Int = 4
    fun getUniqueDynamicId() : String {
        lastValidId++
        while (dynamics.any { it.id == lastValidId.toString() }) {
            lastValidId++
        }
        return lastValidId.toString()
    }
}

/**
 * Represents an object in the 3D scene that can move, rotate or scale.
 */
class DynamicObject(
    var id: String = "",
    var name: String = "",
    var meshName: String = "",
    var active: Boolean = true,
    var isController: Boolean = false,
    var controllerType: String = "",
    var entity: Entity? = null,
) {
    var currentScale: Vector3 = Vector3(1.0f, 1.0f, 1.0f)
    var isDirty: Boolean = true
    
    var hasEnabled: Boolean = false
    var lastTrackedState: Boolean = false

    var lastSentPosition: Vector3 = Vector3(0f, 0f, 0f)
    var lastSentRotation: Quaternion = Quaternion.Identity
    var lastSentScale: Vector3 = Vector3(1f, 1f, 1f)
    
    var positionThreshold: Float = 0.01f 
    var rotationThreshold: Float = 1.0f  
    var scaleThreshold: Float = 0.1f

    init {
        if (id.isEmpty()) {
            id = DynamicManager.getUniqueDynamicId()
        }
    }
}
