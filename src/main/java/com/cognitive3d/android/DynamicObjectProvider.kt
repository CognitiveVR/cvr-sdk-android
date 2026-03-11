package com.cognitive3d.android

interface DynamicObjectProvider {
    /**
     * Given a platform-specific trackable (e.g., a Meta Entity or Jetpack XR node),
     * extract its current pose in left-handed coordinates.
     * Returns null if the trackable is unrecognized or pose is unavailable.
     */
    fun getStateFromTrackable(trackable: Any): DynamicTrackableState?
}

data class DynamicTrackableState(
    val pose: PoseData?,
    val scale: ScaleData?,
    val enabled: Boolean?
)