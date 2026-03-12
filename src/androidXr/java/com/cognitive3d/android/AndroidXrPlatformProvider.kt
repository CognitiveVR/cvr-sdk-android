package com.cognitive3d.android

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import androidx.xr.runtime.Config
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.SessionCreateSuccess

class AndroidXrPlatformProvider(private val activity: Activity) : PlatformProvider {

    private var session: Session? = null

    @SuppressLint("RestrictedApi")
    override fun initialize(activity: Activity): Boolean {
        return try {
            val result = Session.create(activity)
            if (result is SessionCreateSuccess) {
                session = result.session
                val newConfig = result.session.config.copy(
                    // For androidx.xr.runtime:runtime:1.0.0-alpha10
                    deviceTracking = Config.DeviceTrackingMode.LAST_KNOWN,
                    handTracking = Config.HandTrackingMode.BOTH,
                    eyeTracking = Config.EyeTrackingMode.FINE_TRACKING
                )
                val configResult = result.session.configure(newConfig)
                if (configResult is SessionConfigureSuccess) {
                    true
                } else {
                    Log.w(Util.TAG, "Failed to configure XR session: $configResult")
                    false
                }
            } else {
                Log.e(Util.TAG, "Failed to create XR Session: $result")
                false
            }
        } catch (e: Exception) {
            Log.e(Util.TAG, "Error during XR Session initialization", e)
            false
        } catch (e: NoClassDefFoundError) {
            Log.e(Util.TAG, "XR Runtime classes missing from classpath", e)
            false
        }
    }

    override fun getRequiredPermissions(): Array<String> = arrayOf(
        "android.permission.HEAD_TRACKING",
        "android.permission.HAND_TRACKING",
        "android.permission.EYE_TRACKING_COARSE",
        "android.permission.EYE_TRACKING_FINE"
    )

    override fun getHeadTrackingProvider(): HeadTrackingProvider {
        return AndroidXrHeadTrackingProvider(session!!)
    }

    override fun getControllerTrackingProvider(): ControllerTrackingProvider {
        return AndroidXrControllerTrackingProvider(session!!)
    }

    override fun getDynamicObjectProvider(): DynamicObjectProvider {
        return AndroidXrDynamicObjectProvider(session!!)
    }

    override fun getXrPluginName(): String = "Jetpack XR SDK"

    override fun destroy() {
        session = null
    }
}
