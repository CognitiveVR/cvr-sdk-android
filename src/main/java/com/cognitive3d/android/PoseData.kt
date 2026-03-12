package com.cognitive3d.android

data class PoseData(
    val px: Float, val py: Float, val pz: Float,      // position
    val rx: Float, val ry: Float, val rz: Float, val rw: Float  // rotation quaternion
)

data class ScaleData(
    val sx: Float, val sy: Float, val sz: Float
)

/** Ray origin (position) + forward direction for gaze raycasting. */
data class GazeRayData(
    val px: Float, val py: Float, val pz: Float,
    val fx: Float, val fy: Float, val fz: Float
)
