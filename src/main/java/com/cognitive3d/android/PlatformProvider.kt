package com.cognitive3d.android

import android.app.Activity

interface PlatformProvider {
    fun initialize(activity: Activity): Boolean
    fun getRequiredPermissions(): Array<String>
    fun getHeadTrackingProvider(): HeadTrackingProvider
    fun getControllerTrackingProvider(): ControllerTrackingProvider
    fun getXrPluginName(): String   // "Jetpack XR SDK" vs "Meta OpenXR"
    fun destroy()
}