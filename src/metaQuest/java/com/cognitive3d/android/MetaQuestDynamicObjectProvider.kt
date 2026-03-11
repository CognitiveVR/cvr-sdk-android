package com.cognitive3d.android

import com.meta.spatial.core.Entity
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

class MetaQuestDynamicObjectProvider : DynamicObjectProvider {
    override fun getStateFromTrackable(trackable: Any): DynamicTrackableState? {
        if (trackable is Entity) {
            return try {
                val pose = trackable.getComponent<Transform>().transform.toPoseData()
                val scale = trackable.getComponent<Scale>().toScaleData()
                val enabled = trackable.tryGetComponent<Visible>()?.isVisible ?: true
                DynamicTrackableState(pose, scale, enabled)
            } catch (e: Exception) {
                null
            }
        }
        return null
    }
}