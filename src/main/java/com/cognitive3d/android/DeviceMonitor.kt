package com.cognitive3d.android

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * Samples device health (battery, thermal, memory, Wi-Fi signal) as sensors and events.
 * Sensor and event names match the Unity SDK where it records the same signal
 * (BatteryLevel, WifiSignal, ProfilerSensor, OculusHardware components and the
 * Android plugin's low-memory events), so dashboards treat them the same.
 * Needs no runtime permissions: ACCESS_NETWORK_STATE is already in the SDK manifest.
 */
object DeviceMonitor {
    private const val SAMPLE_INTERVAL_MS = 10_000L
    private const val BYTES_PER_MB = 1024f * 1024f

    // Unity's BatteryStatus enum (Unknown, Charging, Discharging, NotCharging, Full);
    // Android's BatteryManager.BATTERY_STATUS_* values are the same order, offset by 1
    private val BATTERY_STATUS_NAMES = arrayOf("Unknown", "Charging", "Discharging", "NotCharging", "Full")

    private var appContext: Context? = null
    private var monitorJob: Job? = null
    private var lastBatteryStatus: Int? = null
    private var lastWifiRssi: Int? = null
    private var lastMemoryLevel = 0

    private val memoryCallbacks = object : ComponentCallbacks2 {
        @Suppress("DEPRECATION")
        override fun onTrimMemory(level: Int) {
            // Same levels and payload as the Android plugin. Not delivered on Android 14+,
            // which no longer sends the RUNNING_* levels.
            if (level == lastMemoryLevel) return
            lastMemoryLevel = level
            val status = when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "Memory running low"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "Memory running critical"
                else -> return
            }
            Cognitive3DManager.sendCustomEvent("c3d.event.app_low_memory", mapOf("Level" to level, "Status" to status))
            Cognitive3DManager.setSessionProperty("c3d.session_tag.LowMemory", true)
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {}
    }

    /** Prepares a new session. Sampling starts on [start]. */
    fun init(context: Context) {
        appContext = context.applicationContext
        lastBatteryStatus = null
        lastWifiRssi = null
        lastMemoryLevel = 0
    }

    /** Starts sampling every [SAMPLE_INTERVAL_MS]. Safe to call again after [stop]. */
    fun start(scope: CoroutineScope) {
        val context = appContext ?: return
        if (monitorJob != null) return
        context.registerComponentCallbacks(memoryCallbacks)

        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    sample(context)
                } catch (e: Exception) {
                    Log.w(Util.TAG, "Device sampling failed", e)
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        appContext?.unregisterComponentCallbacks(memoryCallbacks)
    }

    private fun sample(context: Context) {
        sampleBattery(context)
        samplePower(context)
        sampleMemory(context)
        sampleWifi(context)
    }

    private fun sampleBattery(context: Context) {
        // Sticky broadcast: returns the latest battery state without registering a receiver
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return

        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            Cognitive3DManager.recordSensor("HMD Battery Level", level * 100f / scale)
        }

        val status = (battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) - 1)
            .coerceIn(0, BATTERY_STATUS_NAMES.lastIndex)
        val previous = lastBatteryStatus
        if (previous != null && previous != status) {
            Cognitive3DManager.sendCustomEvent("c3d.battery status changed", mapOf(
                "Previous Battery Status" to BATTERY_STATUS_NAMES[previous],
                "New Battery Status" to BATTERY_STATUS_NAMES[status]
            ))
        }
        lastBatteryStatus = status
        Cognitive3DManager.recordSensor("HMD Battery Status", status.toFloat())

        // Reported in tenths of a degree Celsius
        val temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (temperature != Int.MIN_VALUE) {
            Cognitive3DManager.recordSensor("c3d.battery.temp", temperature / 10f)
        }
    }

    private fun samplePower(context: Context) {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        // 0 (none) to 6 (shutdown); devices slow themselves down from 2 (moderate) upward
        Cognitive3DManager.recordSensor("c3d.thermal.status", power.currentThermalStatus.toFloat())
        Cognitive3DManager.recordSensor("c3d.isPowerSavingMode", if (power.isPowerSaveMode) 1f else 0f)
    }

    private fun sampleMemory(context: Context) {
        // This app's proportional share of RAM, the closest Android match for Unity's "Total Used Memory"
        Cognitive3DManager.recordSensor("c3d.profiler.systemMemoryInMB", Debug.getPss() / 1024f)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        Cognitive3DManager.recordSensor("c3d.device.memory.availableInMB", memoryInfo.availMem / BYTES_PER_MB)
    }

    private fun sampleWifi(context: Context) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)

        // Wi-Fi RSSI in dBm, recorded on change like Unity's WifiSignal component. Read from
        // NetworkCapabilities so no ACCESS_WIFI_STATE permission is needed.
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            val rssi = capabilities.signalStrength
            if (rssi != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && rssi != lastWifiRssi) {
                Cognitive3DManager.recordSensor("c3d.app.WifiRSSI", rssi.toFloat())
                lastWifiRssi = rssi
            }
        } else {
            lastWifiRssi = null
        }
    }
}
