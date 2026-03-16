package com.cognitive3d.android

import kotlinx.coroutines.CoroutineScope

interface HeadTrackingProvider {
    fun start(scope: CoroutineScope)
    fun stop()

    /** Returns the raw device/HMD pose (position + rotation). No eye data mixed in. */
    fun getHeadPose(): PoseData

    /**
     * Returns the gaze ray as origin (position) + forward direction (stored in rotation fields).
     * Uses eye tracking when available, otherwise falls back to HMD forward.
     */
    fun getGazeRay(): GazeRayData
}