package com.cognitive3d.android

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.meta.spatial.runtime.VrActivity

class MetaQuestPlatformProvider : PlatformProvider {

    private var appContext: Context? = null
    private var headTrackingProvider: MetaQuestHeadTrackingProvider? = null
    private var controllerTrackingProvider: MetaQuestControllerTrackingProvider? = null
    private var dynamicObjectProvider: MetaQuestDynamicObjectProvider? = null

    override fun initialize(activity: Activity): Boolean {
        // Cast the activity to Meta's VrActivity to get the Scene
        if (activity is VrActivity) {
            appContext = activity.applicationContext
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

    // "oculus.software.*" are the feature strings Meta documents for manifest
    // <uses-feature> declarations (eye tracking: Quest Pro only, hand tracking:
    // all current Quests). Whether Quest OS reports them via hasSystemFeature is
    // unverified on hardware — check `adb shell pm list features` before release.
    override fun isEyeTrackingAvailable(): Boolean =
        appContext?.packageManager?.hasSystemFeature("oculus.software.eye_tracking") ?: false

    override fun isHandTrackingAvailable(): Boolean {
        val context = appContext ?: return false
        // The granted HAND_TRACKING permission (requested at init) is the regression
        // guard in case the feature probe returns false on Quest OS: the old value
        // was hardcoded true, and all current Quests support hand tracking.
        return context.packageManager.hasSystemFeature("oculus.software.handtracking") ||
            ContextCompat.checkSelfPermission(context, "com.oculus.permission.HAND_TRACKING") ==
                PackageManager.PERMISSION_GRANTED
    }

    override fun destroy() {

    }
}
