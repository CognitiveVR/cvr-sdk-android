package com.cognitive3d.android

import kotlinx.coroutines.CoroutineScope

/**
 * Provides access to head pose and gaze ray data from the XR headset.
 * Implementations handle platform-specific tracking APIs.
 */
interface HeadTrackingProvider {
    /** Initializes tracking resources (e.g. obtains device and eye tracking references). */
    fun start(scope: CoroutineScope)
    /** Releases tracking resources. */
    fun stop()

    /** Returns the raw device/HMD pose (position + rotation). No eye data mixed in. */
    fun getHeadPose(): PoseData

    /**
     * Returns the gaze ray as origin (position) + forward direction (stored in rotation fields).
     * Uses eye tracking when available, otherwise falls back to HMD forward.
     */
    fun getGazeRay(): GazeRayData
}