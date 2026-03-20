package com.cognitive3d.android

import android.util.Log
import android.view.Choreographer
import kotlinx.coroutines.*

/**
 * Monitors application frame rate using Choreographer callbacks
 * and reports the average FPS as a sensor value every second.
 */
object PerformanceMonitor {
    private var frameCount = 0
    private var isRunning = false
    private var monitorJob: Job? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isRunning) {
                frameCount++
                // Schedule the next frame callback
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    /**
     * Starts monitoring the app's FPS.
     * Reports the average FPS every second as a sensor.
     */
    fun startMonitoring(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        frameCount = 0

        // Choreographer must be initialized on the Main thread
        scope.launch(Dispatchers.Main) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        // Background loop to calculate and report FPS
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive && isRunning) {
                delay(1000) // Sample every 1 second
                val fps = frameCount
                frameCount = 0

                // Record FPS as a sensor to track history
                Cognitive3DManager.recordSensor("c3d.fps.avg", fps.toFloat())
            }
        }
    }

    /**
     * Stops the performance monitoring.
     */
    fun stopMonitoring() {
        isRunning = false
        monitorJob?.cancel()
        monitorJob = null
    }
}
