package com.cognitive3d.android

import android.app.Activity

/**
 * Plain Android platform (phones, tablets, and head-worn devices without an XR runtime,
 * such as RealWear). Orientation comes from the device rotation sensor (3DoF, no position);
 * there are no controllers, hands, eye tracking or 3D dynamic objects.
 */
class AndroidPlatformProvider : PlatformProvider {

    private var headTrackingProvider: SensorHeadTrackingProvider? = null
    private val controllerTrackingProvider = NoControllerTrackingProvider()
    private val dynamicObjectProvider = NoDynamicObjectProvider()

    override fun initialize(activity: Activity): Boolean {
        headTrackingProvider = SensorHeadTrackingProvider(activity)
        return true
    }

    override fun getRequiredPermissions(): Array<String> = emptyArray()

    override fun getHeadTrackingProvider(): HeadTrackingProvider {
        return headTrackingProvider ?: throw IllegalStateException("AndroidPlatformProvider must be initialized before requesting tracking.")
    }

    override fun getControllerTrackingProvider(): ControllerTrackingProvider = controllerTrackingProvider

    override fun getDynamicObjectProvider(): DynamicObjectProvider = dynamicObjectProvider

    override fun destroy() {
        headTrackingProvider?.stop()
        headTrackingProvider = null
    }
}

/** No controllers or hand tracking on plain Android. */
class NoControllerTrackingProvider : ControllerTrackingProvider {
    override fun start() {}
    override fun stop() {}
    override suspend fun getHandPose(isRight: Boolean): PoseData? = null
    override suspend fun getControllerPose(isRight: Boolean): PoseData? = null
    override suspend fun getActiveControllerType(isRight: Boolean): ControllerType = ControllerType.NONE
}

/** No 3D scene graph to track on plain Android, so no trackable is recognized. */
class NoDynamicObjectProvider : DynamicObjectProvider {
    override fun getStateFromTrackable(trackable: Any): DynamicTrackableState? = null
}
