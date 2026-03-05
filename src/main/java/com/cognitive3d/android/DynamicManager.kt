package com.cognitive3d.android

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.acos
import kotlin.math.abs

object DynamicManager {

    private var recordDynamicJob: Job? = null
    private var isRecording = false
    private var controllerProvider: ControllerTrackingProvider? = null

    internal val dynamics = CopyOnWriteArrayList<DynamicObject>()

    fun startDynamicRecording(scope: CoroutineScope, controller: ControllerTrackingProvider) {
        if (isRecording) return
        isRecording = true
        controllerProvider = controller

        registerHands()

        recordDynamicJob = scope.launch(Dispatchers.Default) {
            while (isActive && isRecording) {
                val startTime = System.currentTimeMillis()

                try {
                    processDynamics()
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

    private suspend fun processDynamics(force: Boolean = false) {
        val currentTime = System.currentTimeMillis().toDouble() / 1000.0

        for (obj in dynamics) {
            if (!obj.active) continue

            // Get the current pose via provider callbacks
            val currentPose: PoseData? = when {
                obj.isController -> {
                    val isRight = obj.controllerType == "hand_right"
                    controllerProvider?.getHandPose(isRight)
                }
                else -> {
                    obj.poseProvider?.invoke()
                }
            }

            val isEnabled = obj.enabledProvider?.invoke() ?: true
            val isTracked = currentPose != null && isEnabled
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
            // Use scale provider if available, otherwise default 1.0
            val currentScaleValue = obj.scaleProvider?.invoke() ?: 1.0f
            obj.currentScale = currentScaleValue

            if (!force && isTracked && !shouldRecord) {
                // Position check
                val dx = currentPose.px - obj.lastSentPx
                val dy = currentPose.py - obj.lastSentPy
                val dz = currentPose.pz - obj.lastSentPz
                val distSqr = dx * dx + dy * dy + dz * dz

                if (distSqr > obj.positionThreshold * obj.positionThreshold) {
                    shouldRecord = true
                } else {
                    // Rotation check
                    val dot = obj.lastSentRx * currentPose.rx +
                              obj.lastSentRy * currentPose.ry +
                              obj.lastSentRz * currentPose.rz +
                              obj.lastSentRw * currentPose.rw

                    val angle = acos(dot.coerceIn(-1f, 1f).toDouble()) * 2.0 * (180.0 / Math.PI)
                    if (angle > obj.rotationThreshold) {
                        shouldRecord = true
                    } else if (abs(currentScaleValue - obj.lastSentScale) > obj.scaleThreshold) {
                        shouldRecord = true
                    }
                }
            }

            // Record Snapshot
            if (shouldRecord) {
                val px = currentPose?.px ?: obj.lastSentPx
                val py = currentPose?.py ?: obj.lastSentPy
                val pz = currentPose?.pz ?: obj.lastSentPz
                val rx = currentPose?.rx ?: obj.lastSentRx
                val ry = currentPose?.ry ?: obj.lastSentRy
                val rz = currentPose?.rz ?: obj.lastSentRz
                val rw = currentPose?.rw ?: obj.lastSentRw

                Serialization.recordDynamic(
                    obj.id,
                    currentTime,
                    px, py, pz,
                    rx, ry, rz, rw,
                    currentScaleValue, currentScaleValue, currentScaleValue,
                    true,
                    propertiesJson
                )

                if (isTracked) {
                    obj.lastSentPx = currentPose.px
                    obj.lastSentPy = currentPose.py
                    obj.lastSentPz = currentPose.pz
                    obj.lastSentRx = currentPose.rx
                    obj.lastSentRy = currentPose.ry
                    obj.lastSentRz = currentPose.rz
                    obj.lastSentRw = currentPose.rw
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

    fun stopDynamicRecording() {
        isRecording = false
        recordDynamicJob?.cancel()
        recordDynamicJob = null
    }

    suspend fun recordFinalDynamics() {
        if (!isRecording && controllerProvider == null) return
        try {
            processDynamics(force = true)
        } catch (e: Exception) {
            Log.e(Util.TAG, "Error recording final dynamics", e)
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
    var trackableRef: Any? = null,
    var poseProvider: (() -> PoseData?)? = null,
    var scaleProvider: (() -> Float)? = null,
    var enabledProvider: (() -> Boolean)? = null,
) {
    var currentScale: Float = 1.0f
    var isDirty: Boolean = true

    var hasEnabled: Boolean = false
    var lastTrackedState: Boolean = false

    var lastSentPx: Float = 0f
    var lastSentPy: Float = 0f
    var lastSentPz: Float = 0f
    var lastSentRx: Float = 0f
    var lastSentRy: Float = 0f
    var lastSentRz: Float = 0f
    var lastSentRw: Float = 1f
    var lastSentScale: Float = 1f

    var positionThreshold: Float = 0.01f
    var rotationThreshold: Float = 1.0f
    var scaleThreshold: Float = 0.1f

    init {
        if (id.isEmpty()) {
            id = DynamicManager.getUniqueDynamicId()
        }
    }
}
