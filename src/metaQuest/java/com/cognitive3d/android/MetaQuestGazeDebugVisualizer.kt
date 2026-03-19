package com.cognitive3d.android

import android.net.Uri
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug visualizer for gaze hit points and dynamic object bounding boxes.
 * Creates visible 3D entities in the scene to help troubleshoot gaze ray hit detection.
 *
 * Usage:
 *   MetaQuestGazeDebugVisualizer.enabled = true  // before starting a session
 */
class MetaQuestGazeDebugVisualizer {

    companion object {
        /** Set to false to disable debug visuals. */
        var enabled = true

        private const val HIT_MARKER_SIZE = 0.01f // 1cm half-extent → 2cm cube
    }

    private var hitMarkerEntity: Entity? = null
    private val bboxEntities = ConcurrentHashMap<String, Entity>()

    fun initialize() {
        if (!enabled) {
            Util.logDebug("GazeDebugVisualizer — skipped, enabled=false")
            return
        }

        Util.logDebug("GazeDebugVisualizer — initializing...")

        try {
            // Small red cube that will move to the gaze hit point each frame
            hitMarkerEntity = Entity.create(
                Mesh(Uri.parse("mesh://box")),
                Box(
                    Vector3(-HIT_MARKER_SIZE, -HIT_MARKER_SIZE, -HIT_MARKER_SIZE),
                    Vector3(HIT_MARKER_SIZE, HIT_MARKER_SIZE, HIT_MARKER_SIZE)
                ),
                Material().apply {
                    baseColor = Color4(1f, 0f, 0f, 1f) // solid red
                    unlit = true // visible without scene lighting
                },
                Transform(Pose(Vector3(0f, -100f, 0f))) // hidden below scene initially
            )
            Util.logDebug("GazeDebugVisualizer initialized — hit marker entity created")
        } catch (e: Exception) {
            Util.logWarning("GazeDebugVisualizer — failed to create hit marker: ${e.message}")
        }
    }

    /**
     * Moves the hit marker to the gaze hit point, or hides it if no hit this frame.
     */
    fun updateHitPoint(hit: GazeHitResult?) {
        val marker = hitMarkerEntity ?: return
        try {
            if (hit != null) {
                marker.setComponent(Transform(Pose(Vector3(hit.hitX, hit.hitY, hit.hitZ))))
            } else {
                marker.setComponent(Transform(Pose(Vector3(0f, -100f, 0f))))
            }
        } catch (_: Exception) { /* entity not ready */ }
    }

    /**
     * Creates a semi-transparent green box entity that matches the bounding box of a dynamic object.
     */
    fun addBoundingBox(
        objectId: String,
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float
    ) {
        try {
            // Remove previous if re-registering
            bboxEntities.remove(objectId)?.destroy()

            val bboxEntity = Entity.create(
                Mesh(Uri.parse("mesh://box")),
                Box(Vector3(minX, minY, minZ), Vector3(maxX, maxY, maxZ)),
                Material().apply {
                    baseColor = Color4(0f, 1f, 0f, 0.3f) // semi-transparent green
                    unlit = true
                },
                Transform(Pose()),
                Scale(Vector3(1f, 1f, 1f))
            )
            bboxEntities[objectId] = bboxEntity
            Util.logDebug("GazeDebugVisualizer — bbox added for object $objectId " +
                    "[$minX,$minY,$minZ] -> [$maxX,$maxY,$maxZ]")
        } catch (e: Exception) {
            Util.logWarning("GazeDebugVisualizer — failed to create bbox for $objectId: ${e.message}")
        }
    }

    fun removeBoundingBox(objectId: String) {
        try {
            bboxEntities.remove(objectId)?.destroy()
        } catch (_: Exception) {}
    }

    /**
     * Syncs each bounding-box entity's transform/scale with its parent dynamic object
     * so the debug box tracks the object in world space.
     */
    fun updateBoundingBoxTransforms() {
        for (obj in DynamicManager.dynamics) {
            if (obj.isController) continue
            val entity = obj.trackableRef as? Entity ?: continue
            val bboxEntity = bboxEntities[obj.id] ?: continue
            try {
                bboxEntity.setComponent(entity.getComponent<Transform>())
                bboxEntity.setComponent(
                    try { entity.getComponent<Scale>() }
                    catch (_: Exception) { Scale(Vector3(1f, 1f, 1f)) }
                )
            } catch (_: Exception) { /* entity not ready yet */ }
        }
    }

    fun cleanup() {
        try {
            hitMarkerEntity?.destroy()
            hitMarkerEntity = null
            bboxEntities.values.forEach { it.destroy() }
            bboxEntities.clear()
            Util.logDebug("GazeDebugVisualizer cleaned up")
        } catch (_: Exception) {}
    }
}
