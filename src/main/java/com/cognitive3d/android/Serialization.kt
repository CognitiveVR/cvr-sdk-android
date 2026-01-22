package com.cognitive3d.android

import android.content.Context
import android.util.Log
import androidx.xr.runtime.math.Pose
import com.cognitive3d.android.Util.toLeftHanded
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.text.append

object Serialization {
    private var gatewayURL: String = "https://data.cognitive3d.com"
    private var eventURL: String = ""
    private var gazeURL: String = ""
    private var dynamicURL: String = ""
    private var sensorURL: String = ""
    private var boundaryURL: String = ""

    var sessionID: String = ""
    var userID: String = ""
    var sessionTimestamp: Double = 0.0

    // State
    private var eventJsonPart: Int = 1
    private var gazeJsonPart: Int = 1
    private var dynamicJsonPart: Int = 1
    private var sensorJsonPart: Int = 1
    private var boundaryJsonPart: Int = 1
    
    private var gazeCount: Int = 0
    private var eventCount: Int = 0
    private var dynamicCount: Int = 0
    private var sensorCount: Int = 0
    private var boundaryCount: Int = 0
    
    private val mutex = Mutex()
    
    private val allSessionProperties = ConcurrentHashMap<String, Any>()
    private val dirtySessionProperties = ConcurrentHashMap<String, Any>()
    private val pendingCustomEvents = mutableListOf<Triple<String?, Map<String, Any?>, Pose?>>()

    private var GAZE_THRESHOLD = 256
    private var EVENT_THRESHOLD = 256
    private var DYNAMIC_THRESHOLD = 512
    private var SENSOR_THRESHOLD = 512
    private var BOUNDARY_THRESHOLD = 64
    private var SNAPSHOT_INTERVAL = 0.1f

    private lateinit var gazeBuilder : StringBuilder
    private lateinit var eventBuilder : StringBuilder
    private lateinit var dynamicBuilder : StringBuilder
    private lateinit var dynamicManifestBuilder : StringBuilder
    private lateinit var boundaryBuilder : StringBuilder

    private val sensorDataMap = ConcurrentHashMap<String, SensorData>()
    private val cachedSnapshots = ConcurrentHashMap<String, MutableList<String>>()

    private val trackingSpaces = mutableListOf<TrackingSpace>()
    private val boundaryShapes = mutableListOf<BoundaryShape>()

    private class SensorData(val name: String, val rate: Float) {
        val rateString: String = String.format(java.util.Locale.US, "%.2f", rate)
        val updateInterval: Float = if (rate == 0f) 0.1f else 1f / rate
    }

    private data class TrackingSpace(val timestamp: Double, val pos: FloatArray, val rot: FloatArray)
    private data class BoundaryShape(val timestamp: Double, val points: Array<FloatArray>)

    private var isInitialized = false

    fun init(context: Context, config : Cognitive3DConfig) {
        Util.enableLogging = config.enableLogging

        gatewayURL = config.gatewayUrl

        initStringBuilders(config)
        setSceneId(config.sceneSettings[0])

        sessionTimestamp = System.currentTimeMillis().toDouble() / 1000.0
        userID = Util.getOrCreateUserId(context)
        
        // Use toLong() to avoid scientific notation (E12) in the string
        sessionID = sessionTimestamp.toLong().toString() + "_" + userID

        NetworkManager.init(context, config)

        resetGazeBuilder()
        resetEventBuilder()
        resetDynamicBuilder()
        resetSensors()
        resetBoundary()
        
        isInitialized = true
        
        // Process any pending events that were recorded before initialization
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                if (pendingCustomEvents.isNotEmpty()) {
                    val eventsToProcess = pendingCustomEvents.toList()
                    pendingCustomEvents.clear()
                    eventsToProcess.forEach { (category, properties, storedPose) ->
                        // If storedPose is null, we must fetch the head pose NOW
                        // because GazeManager is likely ready now that isInitialized is true.
                        val finalPose = storedPose?.toLeftHanded() ?: GazeManager.getHeadPose()

                        serializeCustomEventsInternal(category, properties, finalPose)
                    }
                }
            }
        }
    }

    fun initStringBuilders(config: Cognitive3DConfig) {
        GAZE_THRESHOLD = config.gazeSnapshotCount
        EVENT_THRESHOLD = config.eventSnapshotCount
        DYNAMIC_THRESHOLD = config.dynamicSnapshotCount
        SENSOR_THRESHOLD = config.sensorSnapshotCount
        BOUNDARY_THRESHOLD = config.boundarySnapshotCount

        // Pre-calculate sizes based on thresholds to avoid array copying during append
        gazeBuilder = StringBuilder(70 * GAZE_THRESHOLD + 1200)
        eventBuilder = StringBuilder(150 * EVENT_THRESHOLD + 1200)
        dynamicBuilder = StringBuilder(128 * DYNAMIC_THRESHOLD + 1200)
        dynamicManifestBuilder = StringBuilder(1024)
        boundaryBuilder = StringBuilder(200 * BOUNDARY_THRESHOLD + 1200)
    }

    fun setSceneId(scene: Cognitive3DConfig.SceneSetting) {
        // Construct URLs based on Scene ID
        val baseUrl = (if (gatewayURL.endsWith("/")) gatewayURL else "$gatewayURL/") + "v0/"
        eventURL = "${baseUrl}events/${scene.sceneId}?version=${scene.sceneVersion}"
        gazeURL = "${baseUrl}gaze/${scene.sceneId}?version=${scene.sceneVersion}"
        dynamicURL = "${baseUrl}dynamics/${scene.sceneId}?version=${scene.sceneVersion}"
        sensorURL = "${baseUrl}sensors/${scene.sceneId}?version=${scene.sceneVersion}"
        boundaryURL = "${baseUrl}boundary/${scene.sceneId}?version=${scene.sceneVersion}"
    }

    private fun resetGazeBuilder() {
        gazeBuilder.setLength(0)
        gazeBuilder.append("{\"data\":[")
    }

    private fun resetEventBuilder() {
        eventBuilder.setLength(0)
        eventBuilder.append("{\"data\":[")
    }

    private fun resetDynamicBuilder() {
        dynamicBuilder.setLength(0)
        dynamicBuilder.append("{\"data\":[")
    }

    private fun resetSensors() {
        sensorDataMap.clear()
        cachedSnapshots.clear()
        sensorCount = 0
        sensorJsonPart = 1
    }

    private fun resetBoundary() {
        boundaryBuilder.setLength(0)
        trackingSpaces.clear()
        boundaryShapes.clear()
        boundaryCount = 0
        boundaryJsonPart = 1
    }

    fun setSessionProperty(key: String, value: Any) {
        val currentValue = allSessionProperties[key]
        if (currentValue != value) {
            allSessionProperties[key] = value
            dirtySessionProperties[key] = value
        }
    }

    suspend fun recordGaze(hmdPosition: FloatArray, hmdRotation: FloatArray, timestamp: Double) {
        if (!isInitialized) return
        
        val payload = mutex.withLock {
            if (gazeCount > 0) gazeBuilder.append(',')
            gazeBuilder.appendGazePoint(timestamp, hmdPosition, hmdRotation)
            gazeCount++

            if (gazeCount >= GAZE_THRESHOLD) {
                val p = finalizeGazePayload()
                gazeCount = 0
                resetGazeBuilder()
                p
            } else null
        }
        payload?.let { NetworkManager.send(gazeURL, it) }
    }

    private suspend fun sendGazeBatchLocked() {
        if (gazeCount == 0 && dirtySessionProperties.isEmpty()) return

        val payload = finalizeGazePayload()
        gazeCount = 0
        resetGazeBuilder()

        NetworkManager.send(gazeURL, payload)
    }

    private fun finalizeGazePayload(): String {
        gazeBuilder.append("],")
        gazeBuilder.appendSessionHeader(gazeJsonPart++)
        gazeBuilder.appendDirtyProperties()
        gazeBuilder.append('}')
        return gazeBuilder.toString()
    }

    private fun StringBuilder.appendGazePoint(time: Double, pos: FloatArray, rot: FloatArray) {
        append("{\"time\":").append(time)
        append(",\"p\":[").append(pos[0]).append(',').append(pos[1]).append(',').append(pos[2]).append(']')
        append(",\"r\":[").append(rot[0]).append(',').append(rot[1]).append(',').append(rot[2]).append(',').append(rot[3]).append(']')
        append('}')
    }

    private fun StringBuilder.appendSessionHeader(part: Int) {
        append("\"userid\":\"").append(userID).append("\",")
        append("\"sessionid\":\"").append(sessionID).append("\",")
        append("\"timestamp\":").append(sessionTimestamp.toLong()).append(',')
        append("\"part\":").append(part).append(',')
        append("\"interval\":").append(SNAPSHOT_INTERVAL).append(',')
        append("\"formatversion\":\"1.0\"")
    }

    private fun StringBuilder.appendDirtyProperties() {
        if (dirtySessionProperties.isEmpty()) return
        append(",\"properties\":{")
        var first = true
        val iter = dirtySessionProperties.entries.iterator()
        while (iter.hasNext()) {
            val (key, value) = iter.next()
            if (!first) append(',')
            first = false
            append('"').append(key).append("\":")
            appendJsonValue(value)
            iter.remove()
        }
        append('}')
    }

    private fun StringBuilder.appendJsonValue(value: Any?) {
        when (value) {
            is String -> append('"').append(value).append('"')
            is Number, is Boolean -> append(value)
            null -> append("null")
            else -> append('"').append(value).append('"')
        }
    }

    private fun StringBuilder.trimTrailingComma() {
        if (isNotEmpty() && this[length - 1] == ',') {
            setLength(length - 1)
        }
    }

    //region Custom Events Serialization
    /**
     * Flushes all current data buffers to the gateway.
     * Sends Gaze, Events, and Dynamics in parallel to minimize time.
     */
    suspend fun flush() = coroutineScope {
        if (!isInitialized) return@coroutineScope
        
        val gazeP: String?
        val eventP: String?
        val dynamicP: String?
        val sensorP: String?
        val boundaryP: String?

        mutex.withLock {
            gazeP = if (gazeCount > 0 || dirtySessionProperties.isNotEmpty()) finalizeGazePayload() else null
            if (gazeP != null) { gazeCount = 0; resetGazeBuilder() }

            eventP = if (eventCount > 0) finalizeEventPayload() else null
            if (eventP != null) { eventCount = 0; resetEventBuilder() }

            dynamicP = if (dynamicCount > 0 || dynamicManifestBuilder.isNotEmpty()) finalizeDynamicPayload() else null
            if (dynamicP != null) { dynamicCount = 0; resetDynamicBuilder() }

            sensorP = if (sensorCount > 0) finalizeSensorPayload() else null
            if (sensorP != null) { sensorCount = 0 }

            boundaryP = if (trackingSpaces.isNotEmpty() || boundaryShapes.isNotEmpty()) finalizeBoundaryPayload() else null
            if (boundaryP != null) { resetBoundary() }
        }

        // Send all payloads in parallel
        val jobs = listOfNotNull(
            gazeP?.let { launch { NetworkManager.send(gazeURL, it) } },
            eventP?.let { launch { NetworkManager.send(eventURL, it) } },
            dynamicP?.let { launch { NetworkManager.send(dynamicURL, it) } },
            sensorP?.let { launch { NetworkManager.send(sensorURL, it) } },
            boundaryP?.let { launch { NetworkManager.send(boundaryURL, it) } }
        )
        jobs.joinAll()
    }

    private suspend fun sendEventBatchLocked() {
        if (eventCount == 0) return
        val payload = finalizeEventPayload()
        eventCount = 0
        resetEventBuilder()
        NetworkManager.send(eventURL, payload)
    }

    private fun finalizeEventPayload(): String {
        eventBuilder.append("],")
        eventBuilder.appendKeyValue("userid", userID)
        eventBuilder.appendKeyValue("timestamp", sessionTimestamp.toLong())
        eventBuilder.appendKeyValue("sessionid", sessionID)
        eventBuilder.appendKeyValue("part", eventJsonPart++)
        eventBuilder.append("\"formatversion\":\"1.0\"")
        eventBuilder.append('}')
        return eventBuilder.toString()
    }

    suspend fun serializeCustomEvents(category: String?, properties: Map<String, Any?>, pose: Pose? = null) {
        mutex.withLock {
            if (!isInitialized) {
                pendingCustomEvents.add(Triple(category, properties, pose))
                return
            }

            val finalPose = pose?.toLeftHanded() ?: GazeManager.getHeadPose()
            serializeCustomEventsInternal(category, properties, finalPose)
        }
    }

    private suspend fun serializeCustomEventsInternal(category: String?, properties: Map<String, Any?>, pose: Pose?) {
        val payload = run {
            if (eventCount > 0) eventBuilder.append(',')
            
            eventBuilder.apply {
                append('{')
                appendKeyValue("name", category)
                appendKeyValue("time", System.currentTimeMillis().toDouble() / 1000.0)
                append("\"point\":[").append(pose?.translation?.x).append(',').append(pose?.translation?.y).append(',').append(pose?.translation?.z).append(']')

                if (properties.isNotEmpty()) {
                    append(",\"properties\":{")
                    appendProperties(properties)
                    append('}')
                }
                append('}')
            }
            eventCount++

            if (eventCount >= EVENT_THRESHOLD) {
                val p = finalizeEventPayload()
                eventCount = 0
                resetEventBuilder()
                p
            } else null
        }
        payload?.let { NetworkManager.send(eventURL, it) }
    }

    private fun StringBuilder.appendKeyValue(key: String, value: Any?) {
        append('"').append(key).append("\":")
        appendJsonValue(value)
        append(',')
    }

    private fun StringBuilder.appendProperties(properties: Map<String, Any?>) {
        var first = true
        for ((key, value) in properties) {
            if (!first) append(',')
            first = false
            append('"').append(key).append("\":")
            appendJsonValue(value)
        }
    }
    //endregion

    //region Dynamic Objects Serialization
    suspend fun recordDynamicManifest(id: String, name: String, meshName: String, isController: Boolean, controllerType: String) {
        mutex.withLock {
            if (dynamicManifestBuilder.isNotEmpty()) dynamicManifestBuilder.append(',')
            dynamicManifestBuilder.append('"').append(id).append("\":{")
            dynamicManifestBuilder.append("\"name\":\"").append(name).append("\",")
            dynamicManifestBuilder.append("\"mesh\":\"").append(meshName).append("\",")
            dynamicManifestBuilder.append("\"fileType\":\"gltf\"")
            if (isController) {
                dynamicManifestBuilder.append(",\"controllerType\":\"").append(controllerType).append("\"")
            }
            dynamicManifestBuilder.append('}')
        }
    }

    suspend fun recordDynamic(id: String, time: Double, pos: FloatArray, rot: FloatArray, scale: FloatArray?, properties: String?) {
        if (!isInitialized) return
        
        val payload = mutex.withLock {
            if (dynamicCount > 0) dynamicBuilder.append(',')
            dynamicBuilder.append("{\"id\":\"").append(id).append("\",")
            dynamicBuilder.append("\"time\":").append(time).append(",")
            dynamicBuilder.append("\"p\":[").append(pos[0]).append(',').append(pos[1]).append(',').append(pos[2]).append("],")
            dynamicBuilder.append("\"r\":[").append(rot[0]).append(',').append(rot[1]).append(',').append(rot[2]).append(',').append(rot[3]).append("]")
            if (scale != null) {
                dynamicBuilder.append(",\"s\":[").append(scale[0]).append(',').append(scale[1]).append(',').append(scale[2]).append("]")
            }
            if (!properties.isNullOrEmpty()) {
                dynamicBuilder.append(",\"properties\":[").append(properties).append("]")
            }
            dynamicBuilder.append('}')
            dynamicCount++

            if (dynamicCount >= DYNAMIC_THRESHOLD) {
                val p = finalizeDynamicPayload()
                dynamicCount = 0
                resetDynamicBuilder()
                p
            } else null
        }
        payload?.let { NetworkManager.send(dynamicURL, it) }
    }

    private suspend fun sendDynamicBatchLocked() {
        if (dynamicCount == 0 && dynamicManifestBuilder.isEmpty()) return
        val payload = finalizeDynamicPayload()
        dynamicCount = 0
        resetDynamicBuilder()
        NetworkManager.send(dynamicURL, payload)
    }

    private fun finalizeDynamicPayload(): String {
        dynamicBuilder.append("],")
        if (dynamicManifestBuilder.isNotEmpty()) {
            dynamicBuilder.append("\"manifest\":{").append(dynamicManifestBuilder.toString()).append("},")
            dynamicManifestBuilder.setLength(0)
        }
        dynamicBuilder.appendKeyValue("userid", userID)
        dynamicBuilder.appendKeyValue("timestamp", sessionTimestamp.toLong())
        dynamicBuilder.appendKeyValue("sessionid", sessionID)
        dynamicBuilder.appendKeyValue("part", dynamicJsonPart++)
        dynamicBuilder.append("\"formatversion\":\"1.0\"")
        dynamicBuilder.append('}')
        return dynamicBuilder.toString()
    }
    //endregion

    //region Sensors Serialization
    fun initializeSensor(sensorName: String, hzRate: Float) {
        if (!isInitialized) return
        if (!sensorDataMap.containsKey(sensorName)) {
            sensorDataMap[sensorName] = SensorData(sensorName, hzRate)
        }
    }

    suspend fun recordSensor(category: String, value: Float, timestamp: Double) {
        if (!isInitialized) return
        
        initializeSensor(category, 10f)
        
        val payload = mutex.withLock {
            val list = cachedSnapshots.getOrPut(category) { mutableListOf() }
            list.add("[$timestamp,$value]")
            sensorCount++
            
            if (sensorCount >= SENSOR_THRESHOLD) {
                val p = finalizeSensorPayload()
                sensorCount = 0
                p
            } else null
        }
        payload?.let { NetworkManager.send(sensorURL, it) }
    }

    private fun finalizeSensorPayload(): String {
        val sb = StringBuilder(1024)
        sb.append("{")
        sb.appendKeyValue("name", userID)
        sb.appendKeyValue("sessionid", sessionID)
        sb.appendKeyValue("timestamp", sessionTimestamp.toLong())
        sb.appendKeyValue("part", sensorJsonPart++)
        sb.appendKeyValue("formatversion", "2.0")
        sb.append("\"data\":[")
        
        var firstSensor = true
        for ((name, snapshots) in cachedSnapshots) {
            if (snapshots.isEmpty()) continue
            if (!firstSensor) sb.append(',')
            firstSensor = false
            
            sb.append("{")
            sb.appendKeyValue("name", name)
            
            sensorDataMap[name]?.let { data ->
                sb.appendKeyValue("sensorHzLimitType", data.rateString)
                if (data.updateInterval >= 0.1f) {
                    sb.appendKeyValue("sensorHzLimited", "true")
                }
            }
            
            sb.append("\"data\":[")
            for (i in snapshots.indices) {
                if (i > 0) sb.append(',')
                sb.append(snapshots[i])
            }
            sb.append("]}")
            snapshots.clear()
        }
        
        sb.append("]}")
        return sb.toString()
    }
    //endregion

    //region Boundary Serialization
    suspend fun recordTrackingSpace(pos: FloatArray, rot: FloatArray, timestamp: Double) {
        if (!isInitialized) return
        val payload = mutex.withLock {
            trackingSpaces.add(TrackingSpace(timestamp, pos, rot))
            boundaryCount++
            if (boundaryCount >= BOUNDARY_THRESHOLD) {
                val p = finalizeBoundaryPayload()
                resetBoundary()
                p
            } else null
        }
        payload?.let { NetworkManager.send(boundaryURL, it) }
    }

    suspend fun recordBoundaryShape(points: Array<FloatArray>, timestamp: Double) {
        if (!isInitialized) return
        val payload = mutex.withLock {
            boundaryShapes.add(BoundaryShape(timestamp, points))
            boundaryCount++
            if (boundaryCount >= BOUNDARY_THRESHOLD) {
                val p = finalizeBoundaryPayload()
                resetBoundary()
                p
            } else null
        }
        payload?.let { NetworkManager.send(boundaryURL, it) }
    }

    private fun finalizeBoundaryPayload(): String {
        val sb = StringBuilder(1024)
        sb.append("{\"data\":[")
        
        // Add Tracking Spaces to "data" array
        for (i in trackingSpaces.indices) {
            val ts = trackingSpaces[i]
            if (i > 0) sb.append(',')
            sb.append("{\"time\":").append(ts.timestamp)
            sb.append(",\"p\":[").append(ts.pos[0]).append(',').append(ts.pos[1]).append(',').append(ts.pos[2]).append(']')
            sb.append(",\"r\":[").append(ts.rot[0]).append(',').append(ts.rot[1]).append(',').append(ts.rot[2]).append(',').append(ts.rot[3]).append(']')
            sb.append('}')
        }
        sb.append("],")

        // Add Boundary Shapes
        sb.append("\"shapes\":[")
        for (i in boundaryShapes.indices) {
            val bs = boundaryShapes[i]
            if (i > 0) sb.append(',')
            sb.append("{\"time\":").append(bs.timestamp)
            sb.append(",\"points\":[")
            for (j in bs.points.indices) {
                val p = bs.points[j]
                if (j > 0) sb.append(',')
                sb.append("[").append(p[0]).append(',').append(p[1]).append(',').append(p[2]).append("]")
            }
            sb.append("]}")
        }
        sb.append("],")

        // Header
        sb.appendKeyValue("userid", userID)
        sb.appendKeyValue("time", sessionTimestamp.toLong())
        sb.appendKeyValue("sessionid", sessionID)
        sb.appendKeyValue("part", boundaryJsonPart++)
        sb.trimTrailingComma()
        sb.append('}')
        
        return sb.toString()
    }
    //endregion
}
