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
    /** Returns the raw, version-qualified identifier of the XR runtime this SDK was built against (e.g. "androidx.xr.runtime:1.0.0-alpha10"). */
    fun getXrPluginName(): String = "${BuildConfig.XR_RUNTIME_PACKAGE}:${BuildConfig.XR_RUNTIME_VERSION}"
    /** Returns the version of the XR runtime this SDK was built against (e.g. "1.0.0-alpha10"). */
    fun getXrRuntimeVersion(): String = BuildConfig.XR_RUNTIME_VERSION
    /** Returns true if eye tracking is available on this device at runtime. Only valid after initialize(). */
    fun isEyeTrackingAvailable(): Boolean = false
    /** Returns true if hand tracking is available on this device at runtime. Only valid after initialize(). */
    fun isHandTrackingAvailable(): Boolean = false
    /** Releases platform-specific resources. */
    fun destroy()
}
