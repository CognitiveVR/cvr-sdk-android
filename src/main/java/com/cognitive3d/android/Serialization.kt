package com.cognitive3d.android

import android.content.Context
import androidx.xr.runtime.math.Pose
import com.cognitive3d.android.Util.toLeftHanded
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

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
    
    // Separate mutexes to reduce contention between different data streams
    private val gazeMutex = Mutex()
    private val eventMutex = Mutex()
    private val dynamicMutex = Mutex()
    private val sensorMutex = Mutex()
    private val boundaryMutex = Mutex()
    private val initializationMutex = Mutex()
    
    private val allSessionProperties = ConcurrentHashMap<String, Any>()
    private val dirtySessionProperties = ConcurrentHashMap<String, Any>()
    private val pendingCustomEvents = mutableListOf<Triple<String?, Map<String, Any?>, Pose?>>()
    
    private data class DynamicManifestEntry(
        val id: String,
        val name: String,
        val meshName: String,
        val isController: Boolean,
        val controllerType: String
    )
    private val pendingDynamicManifests = mutableListOf<DynamicManifestEntry>()

    private var GAZE_THRESHOLD = 256
    private var EVENT_THRESHOLD = 256
    private var DYNAMIC_THRESHOLD = 512
    private var SENSOR_THRESHOLD = 512
    private var BOUNDARY_THRESHOLD = 64
    private var SNAPSHOT_INTERVAL = 0.1f

    private var gazeBuilder = StringBuilder()
    private var eventBuilder = StringBuilder()
    private var dynamicBuilder = StringBuilder()
    private var dynamicManifestBuilder = StringBuilder()
    private var boundaryBuilder = StringBuilder()
    private var sensorBuilder = StringBuilder()

    private val sensorDataMap = ConcurrentHashMap<String, SensorData>()
    
    private data class SensorReading(val timestamp: Double, val value: Float)
    private val cachedSnapshots = ConcurrentHashMap<String, MutableList<SensorReading>>()

    private val trackingSpaces = mutableListOf<TrackingSpace>()
    private val boundaryShapes = mutableListOf<BoundaryShape>()

    private class SensorData(val rate: Float) {
        val rateString: String = String.format(java.util.Locale.US, "%.2f", rate)
        val updateInterval: Float = if (rate == 0f) 0.1f else 1f / rate
    }

    private data class TrackingSpace(val timestamp: Double, val px: Float, val py: Float, val pz: Float, val rx: Float, val ry: Float, val rz: Float, val rw: Float)
    private data class BoundaryShape(val timestamp: Double, val points: Array<FloatArray>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as BoundaryShape
            if (timestamp != other.timestamp) return false
            if (!points.contentDeepEquals(other.points)) return false
            return true
        }
        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + points.contentDeepHashCode()
            return result
        }
    }

    @Volatile
    private var isInitialized = false

    fun init(context: Context, config : Cognitive3DConfig) {
        Util.enableLogging = config.enableLogging

        gatewayURL = config.gatewayUrl

        initStringBuilders(config)
        setSceneId(config.sceneSettings[0])

        sessionTimestamp = System.currentTimeMillis().toDouble() / 1000.0
        userID = Util.getOrCreateUserId(context)
        
        sessionID = sessionTimestamp.toLong().toString() + "_" + userID

        NetworkManager.init(context, config)

        resetGazeBuilder()
        resetEventBuilder()
        resetDynamicBuilder()
        resetSensors()
        resetBoundary()
        
        CoroutineScope(Dispatchers.IO).launch {
            initializationMutex.withLock {
                // Process pending custom events
                if (pendingCustomEvents.isNotEmpty()) {
                    val eventsToProcess = pendingCustomEvents.toList()
                    pendingCustomEvents.clear()
                    
                    val payloads = eventMutex.withLock {
                        eventsToProcess.mapNotNull { (category, properties, storedPose) ->
                            val finalPose = storedPose?.toLeftHanded() ?: GazeManager.getHeadPose()
                            serializeCustomEventsInternal(category, properties, finalPose)
                        }
                    }
                    
                    payloads.forEach { payload ->
                        NetworkManager.send(eventURL, payload)
                    }
                }

                // Process pending dynamic manifests
                if (pendingDynamicManifests.isNotEmpty()) {
                    val manifestsToProcess = pendingDynamicManifests.toList()
                    pendingDynamicManifests.clear()
                    dynamicMutex.withLock {
                        manifestsToProcess.forEach { m ->
                            recordDynamicManifestInternal(m.id, m.name, m.meshName, m.isController, m.controllerType)
                        }
                    }
                }
                
                // Set isInitialized only after all pending data has been processed.
                isInitialized = true
            }
        }
    }

    fun initStringBuilders(config: Cognitive3DConfig) {
        GAZE_THRESHOLD = config.gazeSnapshotCount
        EVENT_THRESHOLD = config.eventSnapshotCount
        DYNAMIC_THRESHOLD = config.dynamicSnapshotCount
        SENSOR_THRESHOLD = config.sensorSnapshotCount
        BOUNDARY_THRESHOLD = config.boundarySnapshotCount

        gazeBuilder = StringBuilder(70 * GAZE_THRESHOLD + 1200)
        eventBuilder = StringBuilder(150 * EVENT_THRESHOLD + 1200)
        dynamicBuilder = StringBuilder(128 * DYNAMIC_THRESHOLD + 1200)
        dynamicManifestBuilder = StringBuilder(1024)
        boundaryBuilder = StringBuilder(200 * BOUNDARY_THRESHOLD + 1200)
        sensorBuilder = StringBuilder(150 * SENSOR_THRESHOLD + 1200)
    }

    fun setSceneId(scene: Cognitive3DConfig.SceneSetting) {
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
        cachedSnapshots.clear()
        sensorDataMap.clear()
        sensorCount = 0
    }

    private fun resetBoundary() {
        boundaryBuilder.setLength(0)
        trackingSpaces.clear()
        boundaryShapes.clear()
        boundaryCount = 0
    }

    fun setSessionProperty(key: String, value: Any) {
        val currentValue = allSessionProperties[key]
        if (currentValue != value) {
            allSessionProperties[key] = value
            dirtySessionProperties[key] = value
        }
    }

    /**
     * Records a single gaze snapshot, typically representing the user's head pose.
     */
    suspend fun recordGaze(px: Float, py: Float, pz: Float, rx: Float, ry: Float, rz: Float, rw: Float, timestamp: Double) {
        if (!isInitialized) return
        
        val payload = gazeMutex.withLock {
            if (gazeCount > 0) gazeBuilder.append(',')
            gazeBuilder.appendGazePoint(timestamp, px, py, pz, rx, ry, rz, rw)
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

    private fun finalizeGazePayload(): String {
        gazeBuilder.append("],")
        gazeBuilder.appendSessionHeader(gazeJsonPart++)
        gazeBuilder.appendDirtyProperties()
        gazeBuilder.append('}')
        return gazeBuilder.toString()
    }

    private fun StringBuilder.appendGazePoint(time: Double, px: Float, py: Float, pz: Float, rx: Float, ry: Float, rz: Float, rw: Float) {
        append("{\"time\":").append(time)
        append(",\"p\":[")
        appendFloat(px).append(',')
        appendFloat(py).append(',')
        appendFloat(pz).append(']')
        append(",\"r\":[")
        appendFloat(rx).append(',')
        appendFloat(ry).append(',')
        appendFloat(rz).append(',')
        appendFloat(rw).append(']')
        append('}')
    }

    private fun StringBuilder.appendFloat(value: Float): StringBuilder {
        if (value.isNaN()) return append("0")
        if (value.isInfinite()) return append(if (value > 0) "99999" else "-99999")
        
        var v = value
        if (v < 0) {
            append('-')
            v = -v
        }
        
        val multiplier = 10000
        val total = (v * multiplier + 0.5f).toLong()
        val integerPart = total / multiplier
        val fractionalPart = total % multiplier
        
        append(integerPart).append('.')
        
        if (fractionalPart == 0L) {
            append("0000")
        } else {
            if (fractionalPart < 1000) append('0')
            if (fractionalPart < 100) append('0')
            if (fractionalPart < 10) append('0')
            append(fractionalPart)
        }
        return this
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

    suspend fun flush() = coroutineScope {
        if (!isInitialized) return@coroutineScope
        
        val gazeP = gazeMutex.withLock {
            if (gazeCount > 0 || dirtySessionProperties.isNotEmpty()) {
                val p = finalizeGazePayload()
                gazeCount = 0
                resetGazeBuilder()
                p
            } else null
        }

        val eventP = eventMutex.withLock {
            if (eventCount > 0) {
                val p = finalizeEventPayload()
                eventCount = 0
                resetEventBuilder()
                p
            } else null
        }

        val dynamicP = dynamicMutex.withLock {
            if (dynamicCount > 0 || dynamicManifestBuilder.isNotEmpty()) {
                val p = finalizeDynamicPayload()
                dynamicCount = 0
                resetDynamicBuilder()
                p
            } else null
        }

        val sensorP = sensorMutex.withLock {
            if (sensorCount > 0) {
                val p = finalizeSensorPayload()
                sensorCount = 0
                p
            } else null
        }

        val boundaryP = boundaryMutex.withLock {
            if (trackingSpaces.isNotEmpty() || boundaryShapes.isNotEmpty()) {
                val p = finalizeBoundaryPayload()
                resetBoundary()
                p
            } else null
        }

        val jobs = listOfNotNull(
            gazeP?.let { launch { NetworkManager.send(gazeURL, it) } },
            eventP?.let { launch { NetworkManager.send(eventURL, it) } },
            dynamicP?.let { launch { NetworkManager.send(dynamicURL, it) } },
            sensorP?.let { launch { NetworkManager.send(sensorURL, it) } },
            boundaryP?.let { launch { NetworkManager.send(boundaryURL, it) } }
        )
        jobs.joinAll()
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
        if (!isInitialized) {
            initializationMutex.withLock {
                if (!isInitialized) {
                    pendingCustomEvents.add(Triple(category, properties, pose))
                    return
                }
            }
        }

        val finalPose = pose?.toLeftHanded() ?: GazeManager.getHeadPose()
        val payload = eventMutex.withLock {
            serializeCustomEventsInternal(category, properties, finalPose)
        }
        payload?.let { NetworkManager.send(eventURL, it) }
    }

    private fun serializeCustomEventsInternal(category: String?, properties: Map<String, Any?>, pose: Pose?): String? {
        if (eventCount > 0) eventBuilder.append(',')
        
        eventBuilder.apply {
            append('{')
            appendKeyValue("name", category)
            appendKeyValue("time", System.currentTimeMillis().toDouble() / 1000.0)
            append("\"point\":[")
            appendFloat(pose?.translation?.x ?: 0f).append(',')
            appendFloat(pose?.translation?.y ?: 0f).append(',')
            appendFloat(pose?.translation?.z ?: 0f).append(']')

            if (properties.isNotEmpty()) {
                append(",\"properties\":{")
                appendProperties(properties)
                append('}')
            }
            append('}')
        }
        eventCount++

        return if (eventCount >= EVENT_THRESHOLD) {
            val p = finalizeEventPayload()
            eventCount = 0
            resetEventBuilder()
            p
        } else null
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

    suspend fun recordDynamicManifest(id: String, name: String, meshName: String, isController: Boolean, controllerType: String) {
        if (!isInitialized) {
            initializationMutex.withLock {
                if (!isInitialized) {
                    pendingDynamicManifests.add(DynamicManifestEntry(id, name, meshName, isController, controllerType))
                    return
                }
            }
        }

        dynamicMutex.withLock {
            recordDynamicManifestInternal(id, name, meshName, isController, controllerType)
        }
    }

    private fun recordDynamicManifestInternal(id: String, name: String, meshName: String, isController: Boolean, controllerType: String) {
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

    /**
     * Records a snapshot of a dynamic object's state, including its position, rotation, and scale.
     */
    suspend fun recordDynamic(id: String, time: Double, px: Float, py: Float, pz: Float, rx: Float, ry: Float, rz: Float, rw: Float, sx: Float, sy: Float, sz: Float, hasScale: Boolean, properties: String?) {
        if (!isInitialized) return
        
        val payload = dynamicMutex.withLock {
            if (dynamicCount > 0) dynamicBuilder.append(',')
            dynamicBuilder.apply {
                append("{\"id\":\"").append(id).append("\",")
                append("\"time\":").append(time).append(",")
                append("\"p\":[")
                appendFloat(px).append(',')
                appendFloat(py).append(',')
                appendFloat(pz).append("],")
                append("\"r\":[")
                appendFloat(rx).append(',')
                appendFloat(ry).append(',')
                appendFloat(rz).append(',')
                appendFloat(rw).append("]")
                if (hasScale) {
                    append(",\"s\":[")
                    appendFloat(sx).append(',')
                    appendFloat(sy).append(',')
                    appendFloat(sz).append("]")
                }
                if (!properties.isNullOrEmpty()) {
                    append(",\"properties\":[").append(properties).append("]")
                }
                append('}')
            }
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

    fun initializeSensor(sensorName: String, hzRate: Float) {
        if (!isInitialized) return
        // Use putIfAbsent for an atomic check-and-put operation
        sensorDataMap.putIfAbsent(sensorName, SensorData(hzRate))
    }

    suspend fun recordSensor(category: String, value: Float, timestamp: Double) {
        if (!isInitialized) return
        
        val payload = sensorMutex.withLock {
            initializeSensor(category, 10f)
            
            val list = cachedSnapshots.getOrPut(category) { mutableListOf() }
            list.add(SensorReading(timestamp, value))
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
        sensorBuilder.setLength(0)
        sensorBuilder.append("{")
        sensorBuilder.appendKeyValue("name", userID)
        sensorBuilder.appendKeyValue("sessionid", sessionID)
        sensorBuilder.appendKeyValue("timestamp", sessionTimestamp.toLong())
        sensorBuilder.appendKeyValue("part", sensorJsonPart++)
        sensorBuilder.appendKeyValue("formatversion", "2.0")
        sensorBuilder.append("\"data\":[")
        
        var firstSensor = true
        for ((name, readings) in cachedSnapshots) {
            if (readings.isEmpty()) continue
            if (!firstSensor) sensorBuilder.append(',')
            firstSensor = false
            
            sensorBuilder.append("{")
            sensorBuilder.appendKeyValue("name", name)
            
            sensorDataMap[name]?.let { data ->
                sensorBuilder.appendKeyValue("sensorHzLimitType", data.rateString)
                if (data.updateInterval >= 0.1f) {
                    sensorBuilder.appendKeyValue("sensorHzLimited", "true")
                }
            }
            
            sensorBuilder.append("\"data\":[")
            for (i in readings.indices) {
                if (i > 0) sensorBuilder.append(',')
                val r = readings[i]
                sensorBuilder.append('[')
                sensorBuilder.append(r.timestamp)
                sensorBuilder.append(',')
                sensorBuilder.appendFloat(r.value)
                sensorBuilder.append(']')
            }
            sensorBuilder.append("]}")
            readings.clear()
        }
        
        sensorBuilder.append("]}")
        return sensorBuilder.toString()
    }

    /**
     * Records the position and orientation of the tracking space.
     * This is typically the headset's pose in the real world.
     */
    suspend fun recordTrackingSpace(px: Float, py: Float, pz: Float, rx: Float, ry: Float, rz: Float, rw: Float, timestamp: Double) {
        if (!isInitialized) return
        val payload = boundaryMutex.withLock {
            trackingSpaces.add(TrackingSpace(timestamp, px, py, pz, rx, ry, rz, rw))
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
        val payload = boundaryMutex.withLock {
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
        boundaryBuilder.setLength(0)
        boundaryBuilder.append("{\"data\":[")
        
        for (i in trackingSpaces.indices) {
            val ts = trackingSpaces[i]
            if (i > 0) boundaryBuilder.append(',')
            boundaryBuilder.apply {
                append("{\"time\":").append(ts.timestamp)
                append(",\"p\":[")
                appendFloat(ts.px).append(',')
                appendFloat(ts.py).append(',')
                appendFloat(ts.pz).append(']')
                append(",\"r\":[")
                appendFloat(ts.rx).append(',')
                appendFloat(ts.ry).append(',')
                appendFloat(ts.rz).append(',')
                appendFloat(ts.rw).append(']')
                append('}')
            }
        }
        boundaryBuilder.append("],")

        boundaryBuilder.append("\"shapes\":[")
        for (i in boundaryShapes.indices) {
            val bs = boundaryShapes[i]
            if (i > 0) boundaryBuilder.append(',')
            boundaryBuilder.apply {
                append("{\"time\":").append(bs.timestamp)
                append(",\"points\":[")
                for (j in bs.points.indices) {
                    val p = bs.points[j]
                    if (j > 0) append(',')
                    append("[")
                    appendFloat(p[0]).append(',')
                    appendFloat(p[1]).append(',')
                    appendFloat(p[2]).append("]")
                }
                append("]}")
            }
        }
        boundaryBuilder.append("],")

        boundaryBuilder.appendKeyValue("userid", userID)
        boundaryBuilder.appendKeyValue("time", sessionTimestamp.toLong())
        boundaryBuilder.appendKeyValue("sessionid", sessionID)
        boundaryBuilder.appendKeyValue("part", boundaryJsonPart++)
        boundaryBuilder.trimTrailingComma()
        boundaryBuilder.append('}')
        
        return boundaryBuilder.toString()
    }
}
