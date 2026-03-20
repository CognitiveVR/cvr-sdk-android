package com.cognitive3d.android

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class MetaQuestDynamicObjectProvider : DynamicObjectProvider {
    private val boundingBoxes = ConcurrentHashMap<String, BoundingBoxData>()

    private data class BoundingBoxData(
        val minX: Float, val minY: Float, val minZ: Float,
        val maxX: Float, val maxY: Float, val maxZ: Float
    )

    override fun getStateFromTrackable(trackable: Any): DynamicTrackableState? {
        if (trackable is Entity) {
            return try {
                val pose = trackable.getComponent<Transform>().transform.toPoseData()
                val scale = trackable.getComponent<Scale>().toScaleData()
                val enabled = trackable.tryGetComponent<Visible>()?.isVisible ?: true
                DynamicTrackableState(pose, scale, enabled)
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    override fun attachHitDetection(dynamicObject: DynamicObject) {
        val entity = dynamicObject.trackableRef as? Entity ?: return
        try {
            val bbox = entity.getComponent<Box>()
            boundingBoxes[dynamicObject.id] = BoundingBoxData(
                bbox.min.x, bbox.min.y, bbox.min.z,
                bbox.max.x, bbox.max.y, bbox.max.z
            )
        } catch (_: Exception) {
            entity.setComponent(Box(Vector3(-0.5f, -0.5f, -0.05f), Vector3(0.5f, 0.5f, 0.05f)))
            boundingBoxes[dynamicObject.id] = BoundingBoxData(-0.5f, -0.5f, -0.05f, 0.5f, 0.5f, 0.05f)
        }
    }

    override fun detachHitDetection(dynamicObject: DynamicObject) {
        if (dynamicObject.trackableRef !is Entity) return
        boundingBoxes.remove(dynamicObject.id)
    }

    override fun getLatestGazeHit(gazeRay: GazeRayData, maxDistance: Float): GazeHitResult? {
        val mag = sqrt(gazeRay.fx * gazeRay.fx + gazeRay.fy * gazeRay.fy + gazeRay.fz * gazeRay.fz)
        if (mag < 0.0001f) return null

        // Normalized world-space ray
        val rox = gazeRay.px;  val roy = gazeRay.py;  val roz = gazeRay.pz
        val rdx = gazeRay.fx / mag
        val rdy = gazeRay.fy / mag
        val rdz = gazeRay.fz / mag

        var closestHit: GazeHitResult? = null
        var closestDist = maxDistance

        for (obj in DynamicManager.dynamics) {
            if (obj.isController) continue
            if (obj.hasEnabled && !obj.lastTrackedState) continue

            val entity = obj.trackableRef as? Entity ?: continue
            val bbox = boundingBoxes[obj.id] ?: run {
                try {
                    val b = entity.getComponent<Box>()
                    BoundingBoxData(b.min.x, b.min.y, b.min.z, b.max.x, b.max.y, b.max.z)
                        .also { boundingBoxes[obj.id] = it }
                } catch (_: Exception) { null }
            } ?: continue

            val transform = try { entity.getComponent<Transform>().transform } catch (_: Exception) { continue }
            val wPos = transform.t
            // Canonicalize quaternion: w=-1 and w=1 are the same rotation but w<0 corrupts math
            val rawQ = transform.q
            val wRot = if (rawQ.w < 0f) Quaternion(-rawQ.x, -rawQ.y, -rawQ.z, -rawQ.w) else rawQ
            val wScl = try { entity.getComponent<Scale>().scale } catch (_: Exception) { Vector3(1f, 1f, 1f) }

            // Translate ray origin into entity-local space
            val relX = rox - wPos.x
            val relY = roy - wPos.y
            val relZ = roz - wPos.z

            // Rotate by inverse quaternion (conjugate)
            val invRot = wRot.conjugate()
            val localOrigin = invRot.rotate(Vector3(relX, relY, relZ))
            val loX = localOrigin.x;  val loY = localOrigin.y;  val loZ = localOrigin.z
            val localDir = invRot.rotate(Vector3(rdx, rdy, rdz))
            val ldX = localDir.x;  val ldY = localDir.y;  val ldZ = localDir.z

            // Apply inverse scale so ray and bbox are in the same model space
            val isx = if (wScl.x != 0f) 1f / wScl.x else 0f
            val isy = if (wScl.y != 0f) 1f / wScl.y else 0f
            val isz = if (wScl.z != 0f) 1f / wScl.z else 0f

            val soX = loX * isx;  val soY = loY * isy;  val soZ = loZ * isz
            val sdX = ldX * isx;  val sdY = ldY * isy;  val sdZ = ldZ * isz

            // Intersect with raw (unscaled) bbox — only accept front-face hits (tMin >= 0)
            val tScaled = Util.rayAABBIntersect(
                soX, soY, soZ,
                sdX, sdY, sdZ,
                bbox.minX, bbox.minY, bbox.minZ,
                bbox.maxX, bbox.maxY, bbox.maxZ
            ) ?: continue

            // Reconstruct hit in world space
            // scaled-local hit → unscale → rotate → translate
            val slhX = soX + sdX * tScaled
            val slhY = soY + sdY * tScaled
            val slhZ = soZ + sdZ * tScaled

            val localHit = Vector3(slhX * wScl.x, slhY * wScl.y, slhZ * wScl.z)
            val worldHit = wRot.rotate(localHit)
            val whX = worldHit.x + wPos.x
            val whY = worldHit.y + wPos.y
            val whZ = worldHit.z + wPos.z

            // Real world-space distance from ray origin to hit point
            val dx = whX - rox;  val dy = whY - roy;  val dz = whZ - roz
            val realDist = sqrt(dx * dx + dy * dy + dz * dz)

            if (realDist < closestDist) {
                closestHit = GazeHitResult(obj.id, realDist, whX, whY, whZ, slhX, slhY, slhZ)
                closestDist = realDist
            }
        }

        return closestHit
    }

}
