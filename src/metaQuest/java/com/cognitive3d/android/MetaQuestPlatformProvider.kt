package com.cognitive3d.android

import android.app.Activity

class MetaQuestPlatformProvider : PlatformProvider {

    override fun initialize(activity: Activity): Boolean {
        // TODO: Initialize Meta OpenXR runtime
        return true
    }

    override fun getRequiredPermissions(): Array<String> = arrayOf(
        "com.oculus.permission.HAND_TRACKING"
    )

    override fun getHeadTrackingProvider(): HeadTrackingProvider {
        return MetaQuestHeadTrackingProvider()
    }

    override fun getControllerTrackingProvider(): ControllerTrackingProvider {
        return MetaQuestControllerTrackingProvider()
    }

    override fun getXrPluginName(): String = "Meta OpenXR"

    override fun destroy() {
        // TODO: Clean up OpenXR resources
    }
}
