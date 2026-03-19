package com.cognitive3d.android

import android.app.Activity

/**
 * Abstraction layer for platform-specific XR functionality.
 * Each supported platform (Android XR, Meta Spatial) provides its own implementation.
 */
interface PlatformProvider {
    /** Initializes platform-specific resources. Returns true if successful. */
    fun initialize(activity: Activity): Boolean
    /** Returns the list of runtime permissions required by this platform. */
    fun getRequiredPermissions(): Array<String>
    /** Returns the provider responsible for head/HMD pose and gaze tracking. */
    fun getHeadTrackingProvider(): HeadTrackingProvider
    /** Returns the provider responsible for controller and hand tracking. */
    fun getControllerTrackingProvider(): ControllerTrackingProvider
    /** Returns the provider responsible for dynamic object state and hit detection. */
    fun getDynamicObjectProvider(): DynamicObjectProvider
    /** Returns the display name of the XR platform (e.g. "Jetpack XR SDK", "Meta OpenXR"). */
    fun getXrPluginName(): String
    /** Releases platform-specific resources. */
    fun destroy()
}