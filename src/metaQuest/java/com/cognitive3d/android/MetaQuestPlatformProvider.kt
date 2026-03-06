package com.cognitive3d.android

import android.app.Activity
import com.meta.spatial.runtime.VrActivity

class MetaQuestPlatformProvider : PlatformProvider {

    private var sceneReference: com.meta.spatial.runtime.Scene? = null

    override fun initialize(activity: Activity): Boolean {
        // Cast the activity to Meta's VrActivity to get the Scene
        if (activity is VrActivity) {
            sceneReference = activity.scene
            return true
        }
        return false
    }

    override fun getRequiredPermissions(): Array<String> = arrayOf(
        "com.oculus.permission.HAND_TRACKING"
    )

    override fun getHeadTrackingProvider(): HeadTrackingProvider {
        val scene = sceneReference ?: throw IllegalStateException("MetaQuestPlatformProvider must be initialized with an AppSystemActivity before requesting tracking.")
        return MetaQuestHeadTrackingProvider(scene)
    }

    override fun getControllerTrackingProvider(): ControllerTrackingProvider {
        return MetaQuestControllerTrackingProvider()
    }

    override fun getXrPluginName(): String = "Meta Spatial SDK"

    override fun destroy() {

    }
}
