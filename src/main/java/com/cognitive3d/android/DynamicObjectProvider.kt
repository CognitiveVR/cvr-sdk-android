package com.cognitive3d.android

interface DynamicObjectProvider {
    /**
     * Given a platform-specific trackable (e.g., a Meta Entity or Jetpack XR node),
     * extract its current pose in left-handed coordinates.
     * Returns null if the trackable is unrecognized or pose is unavailable.
     */
    fun getStateFromTrackable(trackable: Any): DynamicTrackableState?
    /** Sets up hit detection resources (e.g. caches bounding box) for a dynamic object. */
    fun attachHitDetection(dynamicObject: DynamicObject) {}
    /** Removes hit detection resources for a dynamic object. */
    fun detachHitDetection(dynamicObject: DynamicObject) {}
    /** Tests the gaze ray against all registered dynamic objects and returns the closest hit. */
    fun getLatestGazeHit(gazeRay: GazeRayData, maxDistance: Float): GazeHitResult? = null
}

data class DynamicTrackableState(
    val pose: PoseData?,
    val scale: ScaleData?,
    val enabled: Boolean?
)
