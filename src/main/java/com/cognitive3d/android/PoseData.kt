package com.cognitive3d.android

data class PoseData(
    val px: Float, val py: Float, val pz: Float,      // position
    val rx: Float, val ry: Float, val rz: Float, val rw: Float  // rotation quaternion
)

data class ScaleData(
    val sx: Float, val sy: Float, val sz: Float
)
