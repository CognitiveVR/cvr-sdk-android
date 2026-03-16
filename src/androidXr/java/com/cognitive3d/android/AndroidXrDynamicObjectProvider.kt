package com.cognitive3d.android

import androidx.xr.runtime.Session
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.GltfModelEntity
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

class AndroidXrDynamicObjectProvider(private val session: Session) : DynamicObjectProvider {
    private val boundingBoxes = ConcurrentHashMap<String, BoundingBoxData>()
    private data class BoundingBoxData(
        val minX: Float, val minY: Float, val minZ: Float,
        val maxX: Float, val maxY: Float, val maxZ: Float
    )

    override fun getStateFromTrackable(trackable: Any): DynamicTrackableState? {
        if (trackable is Entity) {
            val pose = trackable.activitySpacePose.toPoseDataFromActivity()
            val scale = trackable.getScale().toScaleData()
            val enabled = trackable.isEnabled()
            return DynamicTrackableState(pose, scale, enabled)
        }
        return null
    }

    override fun attachHitDetection(dynamicObject: DynamicObject) {
        val entity = dynamicObject.trackableRef as? GltfModelEntity ?: return

        // Cache the bounding box for ray-AABB intersection (only available on GltfModelEntity)
        try {
            val bbox = entity.getGltfModelBoundingBox()
            boundingBoxes[dynamicObject.id] = BoundingBoxData(
                bbox.min.x, bbox.min.y, bbox.min.z,
                bbox.max.x, bbox.max.y, bbox.max.z
            )
        } catch (_: Exception) {
            // glTF model may not be loaded yet — entity will still be tracked for pos/rot
        }
    }

    override fun detachHitDetection(dynamicObject: DynamicObject) {
        if (dynamicObject.trackableRef !is Entity) return
        boundingBoxes.remove(dynamicObject.id)
    }

    override fun getLatestGazeHit(gazeRay: GazeRayData, maxDistance: Float): GazeHitResult? {
        return raycastAABBAgainstDynamics(gazeRay, maxDistance)
    }

    private fun raycastAABBAgainstDynamics(gazeRay: GazeRayData, maxDistance: Float): GazeHitResult? {
        // Gaze ray is in left-handed activity space (Z flipped).
        // Entity poses and bounding boxes are in right-handed activity space.
        // Un-flip Z to convert ray back to right-handed activity space.
        val rayOrigin = Vector3(gazeRay.px, gazeRay.py, -gazeRay.pz)
        val rayDir = Vector3(gazeRay.fx, gazeRay.fy, -gazeRay.fz)

        // Normalize direction
        val mag = sqrt(rayDir.x * rayDir.x + rayDir.y * rayDir.y + rayDir.z * rayDir.z)
        if (mag < 0.0001f) return null
        val nDir = Vector3(rayDir.x / mag, rayDir.y / mag, rayDir.z / mag)

        var closestHit: GazeHitResult? = null
        var closestDist = maxDistance

        for (obj in DynamicManager.dynamics) {
            if (!obj.lastTrackedState && obj.hasEnabled) continue
            if (obj.isController) continue

            val bbox = boundingBoxes[obj.id] ?: continue
            val entity = obj.trackableRef as? Entity ?: continue

            // Get entity's world pose (right-handed activity space)
            val worldPose = entity.activitySpacePose
            val invPose = worldPose.inverse()

            // Transform ray into entity local space
            val localOrigin = invPose.transformPoint(rayOrigin)
            val localDir = invPose.rotation.rotate(nDir)

            // Scale the bounding box by entity scale
            val scale = entity.getScale()
            val sMinX = bbox.minX * scale
            val sMinY = bbox.minY * scale
            val sMinZ = bbox.minZ * scale
            val sMaxX = bbox.maxX * scale
            val sMaxY = bbox.maxY * scale
            val sMaxZ = bbox.maxZ * scale

            val dist = rayAABBIntersect(
                localOrigin.x, localOrigin.y, localOrigin.z,
                localDir.x, localDir.y, localDir.z,
                sMinX, sMinY, sMinZ,
                sMaxX, sMaxY, sMaxZ
            )

            if (dist != null && dist in 0f..closestDist) {
                // Compute hit point in local space, then transform back to world space
                val localHit = Vector3(
                    localOrigin.x + localDir.x * dist,
                    localOrigin.y + localDir.y * dist,
                    localOrigin.z + localDir.z * dist
                )
                val worldHit = worldPose.transformPoint(localHit)

                closestHit = GazeHitResult(
                    objectId = obj.id,
                    distance = dist,
                    hitX = worldHit.x,
                    hitY = worldHit.y,
                    hitZ = -worldHit.z  // Flip Z back to left-handed
                )
                closestDist = dist
            }
        }
        return closestHit
    }

    private fun rayAABBIntersect(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float
    ): Float? {
        var tMin = Float.NEGATIVE_INFINITY
        var tMax = Float.POSITIVE_INFINITY

        val axes = floatArrayOf(dx, dy, dz)
        val origins = floatArrayOf(ox, oy, oz)
        val mins = floatArrayOf(minX, minY, minZ)
        val maxs = floatArrayOf(maxX, maxY, maxZ)

        for (i in 0..2) {
            if (axes[i] != 0f) {
                val t1 = (mins[i] - origins[i]) / axes[i]
                val t2 = (maxs[i] - origins[i]) / axes[i]
                tMin = maxOf(tMin, minOf(t1, t2))
                tMax = minOf(tMax, maxOf(t1, t2))
            } else {
                if (origins[i] < mins[i] || origins[i] > maxs[i]) return null
            }
        }

        if (tMin > tMax || tMax < 0f) return null
        return if (tMin >= 0f) tMin else tMax
    }
}
