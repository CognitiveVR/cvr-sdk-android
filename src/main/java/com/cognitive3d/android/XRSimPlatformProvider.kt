package com.cognitive3d.android

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import java.lang.reflect.Method

/**
 * Lets the XR Sim test module (com.cognitive3d.xrsim, added to debug builds of an app) drive the head and
 * controller/hand tracking the SDK records, so scripted sessions can be played on a headset sitting on a desk.
 *
 * XR Sim is found by reflection: the SDK has no dependency on it, and when it isn't in the app [wrap] returns the
 * platform provider unchanged. While no XR Sim timeline is playing, every call goes to the real provider.
 */
internal class XRSimPlatformProvider private constructor(
    private val inner: PlatformProvider,
    private val sim: XRSimReflection
) : PlatformProvider by inner {

    private var head: HeadTrackingProvider? = null
    private var controllers: ControllerTrackingProvider? = null

    override fun getHeadTrackingProvider(): HeadTrackingProvider =
        head ?: SimHead(inner.getHeadTrackingProvider()).also { head = it }

    override fun getControllerTrackingProvider(): ControllerTrackingProvider =
        controllers ?: SimControllers(inner.getControllerTrackingProvider()).also { controllers = it }

    override fun destroy() {
        head = null
        controllers = null
        inner.destroy()
    }

    private val convention get() = inner.getCoordinateConvention()

    private inner class SimHead(private val real: HeadTrackingProvider) : HeadTrackingProvider {
        override fun start(scope: CoroutineScope) = real.start(scope)
        override fun stop() = real.stop()

        override fun getHeadPose(): PoseData? {
            if (!sim.isActive()) return real.getHeadPose()
            return sim.hmd(convention)
        }

        override fun getGazeRay(): GazeRayData? {
            if (!sim.isActive()) return real.getGazeRay()
            val p = sim.hmd(convention) ?: return null
            val forwardZ = if (convention == CoordinateConvention.LEFT_HANDED_POS_Z_FORWARD) 1f else -1f
            val (fx, fy, fz) = rotate(p.rx, p.ry, p.rz, p.rw, 0f, 0f, forwardZ)
            return GazeRayData(p.px, p.py, p.pz, fx, fy, fz)
        }
    }

    private inner class SimControllers(private val real: ControllerTrackingProvider) : ControllerTrackingProvider {
        override fun start() = real.start()
        override fun stop() = real.stop()

        override suspend fun getHandPose(isRight: Boolean): PoseData? {
            if (!sim.isActive()) return real.getHandPose(isRight)
            val c = sim.controller(isRight, convention) ?: return null
            return if (c.handTracked) c.pose else null
        }

        override suspend fun getControllerPose(isRight: Boolean): PoseData? {
            if (!sim.isActive()) return real.getControllerPose(isRight)
            val c = sim.controller(isRight, convention) ?: return null
            return if (c.tracked) c.pose else null
        }

        override suspend fun getActiveControllerType(isRight: Boolean): ControllerType {
            if (!sim.isActive()) return real.getActiveControllerType(isRight)
            val c = sim.controller(isRight, convention) ?: return ControllerType.NONE
            return when {
                c.handTracked -> ControllerType.HAND
                c.tracked -> ControllerType.CONTROLLER
                else -> ControllerType.NONE
            }
        }
    }

    companion object {
        /** Returns [provider] wrapped when the XR Sim module is in this app, otherwise [provider] itself. */
        fun wrap(provider: PlatformProvider): PlatformProvider {
            val sim = XRSimReflection.find() ?: return provider
            Log.i(Util.TAG, "XR Sim module found: simulated tracking is used while an XR Sim timeline plays")
            return XRSimPlatformProvider(provider, sim)
        }

        private fun rotate(qx: Float, qy: Float, qz: Float, qw: Float, vx: Float, vy: Float, vz: Float): Triple<Float, Float, Float> {
            val tx = 2f * (qy * vz - qz * vy)
            val ty = 2f * (qz * vx - qx * vz)
            val tz = 2f * (qx * vy - qy * vx)
            return Triple(
                vx + qw * tx + (qy * tz - qz * ty),
                vy + qw * ty + (qz * tx - qx * tz),
                vz + qw * tz + (qx * ty - qy * tx)
            )
        }
    }
}

/** Handles to the XR Sim module's static entry points (com.cognitive3d.xrsim.XRSim), looked up once. */
internal class XRSimReflection private constructor(
    private val isActiveMethod: Method,
    private val openXRSpaceMethod: Method,
    private val hmdMethod: Method,
    private val controllerMethod: Method
) {
    class Controller(val pose: PoseData, val tracked: Boolean, val handTracked: Boolean)

    fun isActive(): Boolean = call { isActiveMethod.invoke(null) as Boolean } ?: false

    fun hmd(target: CoordinateConvention): PoseData? {
        val out = FloatArray(7)
        if (call { hmdMethod.invoke(null, out) as Boolean } != true) return null
        return toPose(out, target)
    }

    fun controller(isRight: Boolean, target: CoordinateConvention): Controller? {
        // [px,py,pz,qx,qy,qz,qw, trigger, grip, stickX, stickY, buttons, tracked, handTracked, curl x5]
        val out = FloatArray(19) // XRSim.CONTROLLER_FLOATS
        if (call { controllerMethod.invoke(null, if (isRight) 1 else 0, out) as Boolean } != true) return null
        return Controller(toPose(out, target), out[12] != 0f, out[13] != 0f)
    }

    /** XR Sim hands out OpenXR (RH, -Z fwd) or Unity (LH, +Z fwd) poses; flip Z when the platform wants the other. */
    private fun toPose(o: FloatArray, target: CoordinateConvention): PoseData {
        val simIsOpenXR = call { openXRSpaceMethod.invoke(null) as Boolean } ?: true
        val targetIsOpenXR = target == CoordinateConvention.RIGHT_HANDED_NEG_Z_FORWARD
        return if (simIsOpenXR == targetIsOpenXR) PoseData(o[0], o[1], o[2], o[3], o[4], o[5], o[6])
        else PoseData(o[0], o[1], -o[2], -o[3], -o[4], o[5], o[6])
    }

    private val reported = java.util.Collections.synchronizedSet(HashSet<String>())

    private inline fun <T> call(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        // reflection wraps the real error; report each distinct failure once so a per-frame call can't flood logcat
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        val key = "${cause.javaClass.name}: ${cause.message}"
        if (reported.add(key)) Log.w(Util.TAG, "XR Sim call failed: $key", cause)
        null
    }

    companion object {
        private const val CLASS_NAME = "com.cognitive3d.xrsim.XRSim"

        fun find(): XRSimReflection? = try {
            val c = Class.forName(CLASS_NAME)
            XRSimReflection(
                c.getMethod("isActive"),
                c.getMethod("getOpenXRSpace"),
                c.getMethod("nativeHmd", FloatArray::class.java),
                c.getMethod("nativeController", Int::class.javaPrimitiveType, FloatArray::class.java)
            )
        } catch (e: ClassNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w(Util.TAG, "XR Sim module found but incompatible, ignoring it: ${e.message}")
            null
        }
    }
}
