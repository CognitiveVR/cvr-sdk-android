package com.cognitive3d.android

import android.app.Activity
import com.meta.spatial.runtime.VrActivity

class MetaQuestPlatformProvider : PlatformProvider {

    private var sceneReference: com.meta.spatial.runtime.Scene? = null
    private var headTrackingProvider: MetaQuestHeadTrackingProvider? = null
    private var controllerTrackingProvider: MetaQuestControllerTrackingProvider? = null
    private var dynamicObjectProvider: MetaQuestDynamicObjectProvider? = null

    override fun initialize(activity: Activity): Boolean {
        // Cast the activity to Meta's VrActivity to get the Scene
        if (activity is VrActivity) {
            sceneReference = activity.scene
            headTrackingProvider = MetaQuestHeadTrackingProvider(activity.scene)
            controllerTrackingProvider = MetaQuestControllerTrackingProvider(activity.scene)
            dynamicObjectProvider = MetaQuestDynamicObjectProvider()
            return true
        }
        return false
    }

    override fun getRequiredPermissions(): Array<String> = arrayOf(
        "com.oculus.permission.HAND_TRACKING"
    )

    override fun getHeadTrackingProvider(): HeadTrackingProvider {
        return headTrackingProvider ?: throw IllegalStateException("MetaQuestPlatformProvider must be initialized before requesting tracking.")
    }

    override fun getControllerTrackingProvider(): ControllerTrackingProvider {
        return controllerTrackingProvider ?: throw IllegalStateException("MetaQuestPlatformProvider must be initialized before requesting tracking.")
    }

    override fun getDynamicObjectProvider(): DynamicObjectProvider {
        return dynamicObjectProvider ?: throw IllegalStateException("MetaQuestPlatformProvider must be initialized before requesting tracking.")
    }

    override fun getXrPluginName(): String = "Meta Spatial SDK"

    override fun destroy() {

    }
}
