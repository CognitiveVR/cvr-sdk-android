package com.cognitive3d.android

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import androidx.xr.runtime.Session
import androidx.xr.scenecore.Entity

object Cognitive3DManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushTimerJob: Job? = null
    private var config: Cognitive3DConfig? = null
    
    // Channel for high-frequency sensor data
    private val sensorChannel = Channel<SensorPoint>(capacity = Channel.UNLIMITED)
    private var sensorProcessorJob: Job? = null

    private data class SensorPoint(val category: String, val value: Float, val timestamp: Double)

    /**
     * Initializes global session metadata.
     */
    @JvmStatic
    fun initSession(session: Session)
    {
        val context = session.activity.applicationContext
        val loadedConfig = Cognitive3DConfig.fromAssets(context)
        if (loadedConfig == null) {
            Log.e(Util.TAG, "Cognitive3D initialization failed: cognitive3d.json not found in assets or invalid.")
            return
        }

        if (loadedConfig.sceneSettings.isEmpty()) {
            Log.e(Util.TAG, "Cognitive3D initialization failed: no scene found in cognitive3d.json.")
            return
        }
        config = loadedConfig
        Serialization.init(context, loadedConfig)
        startSession(session)
    }

    @JvmStatic
    fun startSession(session: Session)
    {
        Log.d(Util.TAG, "Cognitive3D Session Started")
        val context = session.activity.applicationContext

        startSensorProcessor()

        scope.launch {
            setInternalSessionProperties(context)
            Serialization.serializeCustomEvents("c3d.sessionStart", emptyMap())
            Serialization.flush()

            try {
                GazeManager.startGazeRecording(scope, session)
                DynamicManager.startDynamicRecording(scope, session)
                PerformanceMonitor.startMonitoring(scope)
                startFlushTimer()
            } catch (e: Exception) {
                Log.e(Util.TAG, "Failed to start recording loops", e)
            }
        }
    }

    private fun startSensorProcessor() {
        sensorProcessorJob?.cancel()
        sensorProcessorJob = scope.launch {
            for (point in sensorChannel) {
                Serialization.recordSensor(point.category, point.value, point.timestamp)
            }
        }
    }

    @JvmStatic
    fun pauseSession() {
        Log.d(Util.TAG, "Cognitive3D Session Paused")
        
        GazeManager.stopGazeRecording()
        DynamicManager.stopDynamicRecording()
        PerformanceMonitor.stopMonitoring()
        stopFlushTimer()

        scope.launch {
            DynamicManager.recordFinalDynamics()
            Serialization.flush()
        }
    }

    @JvmStatic
    fun resumeSession(session: Session) {
        Log.d(Util.TAG, "Cognitive3D Session Resumed")
        GazeManager.startGazeRecording(scope, session)
        DynamicManager.startDynamicRecording(scope, session)
        PerformanceMonitor.startMonitoring(scope)
        startFlushTimer()
    }

    @JvmStatic
    fun endSession()
    {
        Log.d(Util.TAG, "Cognitive3D Session Ending")
        
        GazeManager.stopGazeRecording()
        DynamicManager.stopDynamicRecording()
        PerformanceMonitor.stopMonitoring()
        stopFlushTimer()

        val endTime = System.currentTimeMillis().toDouble() / 1000.0
        val sessionLength = endTime - Serialization.sessionTimestamp

        val properties = mutableMapOf<String, Any?>()
        properties["sessionlength"] = sessionLength
        properties["Reason"] = "Quit from within app"

        scope.launch {
            DynamicManager.recordFinalDynamics()
            Serialization.serializeCustomEvents("c3d.sessionEnd", properties)
            Serialization.flush()
            
            sensorProcessorJob?.cancel()
            sensorProcessorJob = null
        }
    }

    private fun startFlushTimer() {
        val interval = config?.automaticSendTimer ?: 10
        if (interval <= 0) return
        
        flushTimerJob?.cancel()
        flushTimerJob = scope.launch {
            while (isActive) {
                delay(interval * 1000L)
                Serialization.flush()
            }
        }
    }

    private fun stopFlushTimer() {
        flushTimerJob?.cancel()
        flushTimerJob = null
    }

    private fun setInternalSessionProperties(context: Context)
    {
        val packageManager = context.packageManager
        val applicationInfo = context.applicationInfo
        val appName = packageManager.getApplicationLabel(applicationInfo).toString()
        val appVersion = try {
            val pInfo = packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }

        setSessionProperty("c3d.version", "1.0.0")
        setSessionProperty("c3d.app.engine", "Android Native")
        setSessionProperty("c3d.app.engine.version", "Android SDK " + Build.VERSION.SDK_INT)
        setSessionProperty("c3d.app.version", appVersion)
        setSessionProperty("c3d.app.sdktype", "Default")
        setSessionProperty("c3d.app.xrplugin", "Jetpack XR SDK")
        setSessionProperty("c3d.app.inEditor", false)
        setSessionProperty("c3d.app.name", appName)

        setSessionProperty("c3d.deviceid", Serialization.userID)
        setSessionProperty("c3d.device.type", "Mobile")
        setSessionProperty("c3d.device.model", Build.MODEL ?: "Unknown")
        
        setSessionProperty("c3d.device.os", "Android OS " + Build.VERSION.RELEASE)
        setSessionProperty("c3d.device.cpu", Build.HARDWARE ?: "Unknown")
        setSessionProperty("c3d.device.cpu.vendor", Build.MANUFACTURER ?: "Unknown")
        
        setSessionProperty("c3d.device.memory", 0.0) 

        val gpuInfo = Util.getGpuInfo()
        if (gpuInfo.isNotEmpty()) {
            setSessionProperty("c3d.device.gpu", gpuInfo["renderer"] ?: "Unknown")
            setSessionProperty("c3d.device.gpu.vendor", gpuInfo["vendor"] ?: "Unknown")
        } else {
            setSessionProperty("c3d.device.gpu", "Unknown")
            setSessionProperty("c3d.device.gpu.vendor", "Unknown")
        }

        setSessionProperty("c3d.device.hmd.type", Build.MODEL ?: "Unknown")
        setSessionProperty("c3d.device.eyetracking.enabled", false)
        setSessionProperty("c3d.device.controllerinputs.enabled", false)
        setSessionProperty("c3d.app.handtracking.enabled", true)
    }

    @JvmStatic
    fun setSessionProperty(key: String, value: Any)
    {
        Serialization.setSessionProperty(key, value)
    }

    @JvmStatic
    fun setParticipantProperty(key: String, value: Any)
    {
        Serialization.setSessionProperty("c3d.participant.$key", value)
    }

    @JvmStatic
    fun setParticipantFullName(name: String)
    {
        if (name.isEmpty())
        {
            Util.logWarning("Participant Full Name cannot be empty")
            return
        }
        setParticipantProperty("name", name)
        setSessionProperty("c3d.sessionname", name)
    }

    @JvmStatic
    fun setParticipantId(id: String)
    {
        if (id.isEmpty())
        {
            Util.logWarning("Participant Full Name cannot be empty")
            return
        }
        setParticipantProperty("id", id)
    }

    @JvmStatic
    @JvmOverloads
    fun sendCustomEvent(category: String, properties: Map<String, Any?> = emptyMap())
    {
        scope.launch {
            Serialization.serializeCustomEvents(category, properties)
        }
    }

    /**
     * Records a sensor data point using an efficient channel.
     */
    @JvmStatic
    fun recordSensor(category: String, value: Float) {
        val timestamp = System.currentTimeMillis().toDouble() / 1000.0
        sensorChannel.trySend(SensorPoint(category, value, timestamp))
    }

    @JvmStatic
    fun registerDynamicObject(name: String, meshName: String, entity: Entity?) {
        for (obj in DynamicManager.dynamics) {
            if (obj.entity == entity) {
                Log.w(Util.TAG, "Dynamic Object Already Registered: ${entity.toString()}")
                return
            }
        }

        var obj = DynamicObject(
            id = "",
            name = name,
            meshName = meshName,
            entity = entity,
            isController = false,
        )
        DynamicManager.registerDynamicObject(obj)
    }

    @JvmStatic
    fun unregisterDynamicObject(entity: Entity?) {
        if (entity == null) return

        val iterator = DynamicManager.dynamics.iterator()
        while (iterator.hasNext()) {
            val obj = iterator.next()
            if (obj.entity == entity) {
                DynamicManager.unregisterDynamicObject(obj)
                break
            }
        }
    }
}
