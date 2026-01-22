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
import androidx.xr.runtime.Session
import androidx.xr.scenecore.Entity

object Cognitive3DManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushTimerJob: Job? = null
    private var config: Cognitive3DConfig? = null

    /**
     * Initializes global session metadata.
     * Should be called once at application start.
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

    /**
     * Starts the Cognitive3D session and begins recording.
     * 
     * @param session The active XR Session.
     */
    @JvmStatic
    fun startSession(session: Session)
    {
        Log.d(Util.TAG, "Cognitive3D Session Started")
        val context = session.activity.applicationContext

        scope.launch {
            // Set Initial Session Properties (Device Metadata)
            setInternalSessionProperties(context)

            // Serialize Session Start Event
            Serialization.serializeCustomEvents("c3d.sessionStart", emptyMap())

            // Force an initial flush so the Start event and Metadata go out immediately
            Serialization.flush()

            // Start Recording loops
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

    /**
     * Pauses the Cognitive3D recording and flushes pending data.
     * Call this in your Activity's onPause().
     */
    @JvmStatic
    fun pauseSession() {
        Log.d(Util.TAG, "Cognitive3D Session Paused")
        
        // Stop recording loops
        GazeManager.stopGazeRecording()
        DynamicManager.stopDynamicRecording()
        PerformanceMonitor.stopMonitoring()
        stopFlushTimer()

        scope.launch {
            // Record current state of dynamic objects before flushing
            DynamicManager.recordFinalDynamics()
            // Flush all pending data buffers immediately
            Serialization.flush()
        }
    }

    /**
     * Resumes Cognitive3D recording.
     * Call this in your Activity's onResume().
     * 
     * @param session The active XR Session.
     */
    @JvmStatic
    fun resumeSession(session: Session) {
        Log.d(Util.TAG, "Cognitive3D Session Resumed")
        GazeManager.startGazeRecording(scope, session)
        DynamicManager.startDynamicRecording(scope, session)
        PerformanceMonitor.startMonitoring(scope)
        startFlushTimer()
    }

    /**
     * Ends the Cognitive3D session and sends final session data.
     * Call this when the user is done with the experience.
     */
    @JvmStatic
    fun endSession()
    {
        Log.d(Util.TAG, "Cognitive3D Session Ending")
        
        // Stop recording loops
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
            // Record the last known state of all dynamic objects
            DynamicManager.recordFinalDynamics()
            
            // Send session end event
            Serialization.serializeCustomEvents("c3d.sessionEnd", properties)

            // Final flush to send all remaining data before closing
            Serialization.flush()
        }
    }

    /**
     * Starts the periodic flush timer based on the configuration.
     */
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

    /**
     * Stops the periodic flush timer.
     */
    private fun stopFlushTimer() {
        flushTimerJob?.cancel()
        flushTimerJob = null
    }

    /**
     * Gathers and sets hardware and application metadata for the session.
     */
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

        // App & SDK Info
        setSessionProperty("c3d.version", "1.0.0")
        setSessionProperty("c3d.app.engine", "Android Native")
        setSessionProperty("c3d.app.engine.version", "Android SDK " + Build.VERSION.SDK_INT)
        setSessionProperty("c3d.app.version", appVersion)
        setSessionProperty("c3d.app.sdktype", "Default")
        setSessionProperty("c3d.app.xrplugin", "Jetpack XR SDK")
        setSessionProperty("c3d.app.inEditor", false)
        setSessionProperty("c3d.app.name", appName)

        // Device ID & Type
        setSessionProperty("c3d.deviceid", Serialization.userID)
        setSessionProperty("c3d.device.type", "Mobile")
        setSessionProperty("c3d.device.model", Build.MODEL ?: "Unknown")
        
        // Device Hardware
        setSessionProperty("c3d.device.os", "Android OS " + Build.VERSION.RELEASE)
        setSessionProperty("c3d.device.cpu", Build.HARDWARE ?: "Unknown")
        setSessionProperty("c3d.device.cpu.vendor", Build.MANUFACTURER ?: "Unknown")
        
        // Memory
        setSessionProperty("c3d.device.memory", 0.0) 

        // GPU Info
        val gpuInfo = Util.getGpuInfo()
        if (gpuInfo.isNotEmpty()) {
            setSessionProperty("c3d.device.gpu", gpuInfo["renderer"] ?: "Unknown")
            setSessionProperty("c3d.device.gpu.vendor", gpuInfo["vendor"] ?: "Unknown")
        } else {
            setSessionProperty("c3d.device.gpu", "Unknown")
            setSessionProperty("c3d.device.gpu.vendor", "Unknown")
        }

        // Capabilities
        setSessionProperty("c3d.device.hmd.type", Build.MODEL ?: "Unknown")
        setSessionProperty("c3d.device.eyetracking.enabled", false)
        setSessionProperty("c3d.device.controllerinputs.enabled", false)
        setSessionProperty("c3d.app.handtracking.enabled", true)
    }

    /**
     * Sets a global property for the current session.
     * 
     * @param key The property name.
     * @param value The property value.
     */
    @JvmStatic
    fun setSessionProperty(key: String, value: Any)
    {
        Serialization.setSessionProperty(key, value)
    }

    /**
     * Sets a property specific to the participant (user).
     *
     * @param key The property name (prefixed with 'c3d.participant.').
     * @param value The property value.
     */
    @JvmStatic
    fun setParticipantProperty(key: String, value: Any)
    {
        Serialization.setSessionProperty("c3d.participant.$key", value)
    }

    /**
     * Sets the full name of the participant and updates the session name.
     *
     * @param name The participant's full name.
     */
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

    /**
     * Sets a unique identifier for the participant.
     *
     * @param id The participant's unique ID.
     */
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

    /**
     * Sends a custom event with optional properties to the dashboard.
     *
     * @param category The event category/name.
     * @param properties Optional key-value pairs associated with the event.
     */
    @JvmStatic
    @JvmOverloads
    fun sendCustomEvent(category: String, properties: Map<String, Any?> = emptyMap())
    {
        scope.launch {
            Serialization.serializeCustomEvents(category, properties)
        }
    }

    /**
     * Records a sensor data point.
     *
     * @param category The name of the sensor (e.g. "HeartRate", "Battery").
     * @param value The numerical value to record.
     */
    @JvmStatic
    fun recordSensor(category: String, value: Float) {
        val timestamp = System.currentTimeMillis().toDouble() / 1000.0
        scope.launch {
            Serialization.recordSensor(category, value, timestamp)
        }
    }

    /**
     * Registers an Entity as a dynamic object for tracking movement and state.
     *
     * @param name The display name of the object.
     * @param meshName The name of the mesh associated with this object.
     * @param entity The Scenecore Entity to track.
     */
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

    /**
     * Unregisters a previously registered dynamic entity.
     *
     * @param entity The Scenecore Entity to stop tracking.
     */
    @JvmStatic
    fun unregisterDynamicObject(entity: Entity?) {
        if (entity == null) return // Prevent accidental unregistration of hands/controllers

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
