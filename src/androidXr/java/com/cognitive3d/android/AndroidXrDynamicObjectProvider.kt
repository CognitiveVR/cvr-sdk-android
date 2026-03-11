package com.cognitive3d.android

import androidx.xr.runtime.Session
import androidx.xr.scenecore.Entity

class AndroidXrDynamicObjectProvider(private val session: Session) : DynamicObjectProvider {
    override fun getStateFromTrackable(trackable: Any): DynamicTrackableState? {
        if (trackable is Entity) {
            val pose = trackable.getPose().toPoseDataFromActivity()
            val scale = trackable.getScale().toScaleData()
            val enabled = trackable.isEnabled()
            return DynamicTrackableState(pose, scale, enabled)
        }
        return null
    }
}