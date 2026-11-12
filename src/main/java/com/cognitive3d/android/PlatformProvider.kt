package com.cognitive3d.android

import android.app.Activity

/**
 * Abstraction layer for platform-specific XR functionality.
 * Each supported platform (Android XR, Meta Spatial) provides its own implementation.
 */
interface PlatformProvider {
    /** Initializes platform-specific resources. Returns true if successful. */
    fun initialize(activity: Activity): Boolean
    /** Returns the runtime permissions the SDK needs before it can initialize on this platform. */
    fun getRequiredPermissions(): Array<String>
    /** Returns runtime permissions that improve tracking but must not block initialization (features degrade gracefully when denied). */
    fun getOptionalPermissions(): Array<String> = emptyArray()
    /** Returns the provider responsible for head/HMD pose and gaze tracking. */
    fun getHeadTrackingProvider(): HeadTrackingProvider
    /** Returns the provider responsible for controller and hand tracking. */
    fun getControllerTrackingProvider(): ControllerTrackingProvider
    /** Returns the provider responsible for dynamic object state and hit detection. */
    fun getDynamicObjectProvider(): DynamicObjectProvider
    /** Returns the XR runtime this SDK was built against (e.g. "androidx.xr.runtime"). */
    fun getXrPluginName(): String = BuildConfig.XR_RUNTIME_PACKAGE
    /** Returns true if eye tracking is available on this device at runtime. Only valid after initialize(). */
    fun isEyeTrackingAvailable(): Boolean = false
    /** Returns true if hand tracking is available on this device at runtime. Only valid after initialize(). */
    fun isHandTrackingAvailable(): Boolean = false
    /**
     * Returns true if tracking depends on the activity passed to [initialize] (e.g. its XR session
     * or scene), so the session must end when that activity is destroyed even if others remain.
     */
    fun isBoundToHostActivity(): Boolean = true
    /** Releases platform-specific resources. */
    fun destroy()
}
