package com.cognitive3d.android

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.EyeTrackingMode
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureResult
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.SessionCreateSuccess

class AndroidXrPlatformProvider(private val activity: Activity) : PlatformProvider {

    private var session: Session? = null
    private var eyeTrackingAvailable = false
    private var handTrackingAvailable = false
    private var headTrackingProvider: AndroidXrHeadTrackingProvider? = null
    private var controllerTrackingProvider: AndroidXrControllerTrackingProvider? = null
    private var dynamicObjectProvider: AndroidXrDynamicObjectProvider? = null

    @SuppressLint("RestrictedApi")
    override fun initialize(activity: Activity): Boolean {
        return try {
            val result = Session.create(activity)
            if (result is SessionCreateSuccess) {
                session = result.session
                val configResult = configureSession(result.session)
                if (configResult is SessionConfigureSuccess) {
                    headTrackingProvider = AndroidXrHeadTrackingProvider(result.session)
                    controllerTrackingProvider = AndroidXrControllerTrackingProvider(result.session)
                    dynamicObjectProvider = AndroidXrDynamicObjectProvider(result.session)
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

    /**
     * Configures the session with progressively reduced configs covering every
     * eye/hand mode combination, so the accepted tier is an accurate capability
     * readback. Every eye mode (fine -> coarse -> disabled) is tried before
     * dropping hand tracking: the two eye permissions can be granted or denied
     * independently, and tryConfigure treats both permission denial and hardware
     * unsupport as a tier failure, so the accepted tier reflects what is actually
     * usable — not an all-or-nothing default.
     */
    private fun configureSession(session: Session): SessionConfigureResult? {
        val eyeModes = listOf(
            EyeTrackingMode.FINE_TRACKING,
            EyeTrackingMode.COARSE_TRACKING,
            EyeTrackingMode.DISABLED
        )
        val handModes = listOf(HandTrackingMode.BOTH, HandTrackingMode.DISABLED)
        for (handMode in handModes) {
            for (eyeMode in eyeModes) {
                val candidate = Config.Builder(session.config)
                    // SPATIAL is the renamed successor of alpha10's LAST_KNOWN mode
                    .setDeviceTracking(DeviceTrackingMode.SPATIAL)
                    .setHandTracking(handMode)
                    .setEyeTracking(eyeMode)
                    .build()
                val result = tryConfigure(session, candidate, eyeMode, handMode)
                if (result is SessionConfigureSuccess) {
                    eyeTrackingAvailable = eyeMode != EyeTrackingMode.DISABLED
                    handTrackingAvailable = handMode == HandTrackingMode.BOTH
                    return result
                }
            }
        }
        Log.w(Util.TAG, "No supported XR session configuration found on this device")
        return null
    }

    private fun tryConfigure(
        session: Session,
        config: Config,
        eyeMode: EyeTrackingMode,
        handMode: HandTrackingMode
    ): SessionConfigureResult? =
        try {
            session.configure(config)
        } catch (e: UnsupportedOperationException) {
            Log.w(Util.TAG, "XR config not supported (eye=$eyeMode, hand=$handMode)")
            null
        } catch (e: SecurityException) {
            // configure() throws SecurityException when a requested mode's permission
            // isn't granted (alpha05+); treat it as a tier failure so a reduced tier
            // that needs fewer permissions can still succeed.
            Log.w(Util.TAG, "XR config permission not granted (eye=$eyeMode, hand=$handMode)")
            null
        }

    override fun getRequiredPermissions(): Array<String> = arrayOf(
        "android.permission.HEAD_TRACKING",
        "android.permission.HAND_TRACKING"
    )

    // Eye tracking degrades gracefully via the configureSession fallback tiers
    // (configure() throws SecurityException for ungranted modes), so these must
    // not block SDK initialization: target glasses devices and privacy-conscious
    // users frequently cannot or will not grant them.
    override fun getOptionalPermissions(): Array<String> = arrayOf(
        "android.permission.EYE_TRACKING_COARSE",
        "android.permission.EYE_TRACKING_FINE"
    )

    override fun getHeadTrackingProvider(): HeadTrackingProvider {
        return headTrackingProvider!!
    }

    override fun getControllerTrackingProvider(): ControllerTrackingProvider {
        return controllerTrackingProvider!!
    }

    override fun getDynamicObjectProvider(): DynamicObjectProvider {
        return dynamicObjectProvider!!
    }

    override fun isEyeTrackingAvailable(): Boolean = eyeTrackingAvailable

    override fun isHandTrackingAvailable(): Boolean = handTrackingAvailable

    override fun destroy() {
        session = null
    }
}
