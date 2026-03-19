package com.cognitive3d.android

/** The type of input device currently active. */
enum class ControllerType {
    HAND,
    CONTROLLER,
    NONE
}

/**
 * Provides access to controller and hand tracking data.
 * Implementations handle platform-specific input device APIs.
 */
interface ControllerTrackingProvider {
    /** Initializes tracking resources (e.g. caches platform hand/controller instances). */
    fun start()
    /** Releases tracking resources. */
    fun stop()
    /** Returns the wrist pose for the specified hand, or null if unavailable. */
    suspend fun getHandPose(isRight: Boolean): PoseData?
    /** Returns the pose of a physical controller, or null if unavailable. */
    suspend fun getControllerPose(isRight: Boolean): PoseData?
    /** Detects whether the specified side is using a hand, controller, or neither. */
    suspend fun getActiveControllerType(isRight: Boolean): ControllerType
}