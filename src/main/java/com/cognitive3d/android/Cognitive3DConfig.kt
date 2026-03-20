package com.cognitive3d.android

import android.content.Context
import android.util.Log
import org.json.JSONObject

/** SDK configuration loaded from the cognitive3d.json asset file. */
data class Cognitive3DConfig(
    val apiKey: String,
    val gatewayUrl: String = "https://data.cognitive3d.com",
    val enableLogging: Boolean = true,
    val enableGaze: Boolean = true,
    val gazeSnapshotCount: Int = 256,
    val eventSnapshotCount: Int = 256,
    val dynamicSnapshotCount: Int = 512,
    val sensorSnapshotCount: Int = 512,
    val fixationSnapshotCount: Int = 256,
    val boundarySnapshotCount: Int = 64,
    val automaticSendTimer: Int = 10,
    val cacheLimit: Long = 1024 * 1024 * 100, // Default 100MB
    val sceneSettings: List<SceneSetting> = emptyList()
) {
    data class SceneSetting(
        val sceneName: String,
        val sceneId: String,
        val sceneVersion: String,
        val scenePath: String = ""
    )

    companion object {
        /** Parses cognitive3d.json from the app's assets directory. Returns null on failure. */
        fun fromAssets(context: Context): Cognitive3DConfig? {
            return try {
                val jsonString = context.assets.open("cognitive3d.json").bufferedReader().use { it.readText() }
                val json = JSONObject(jsonString)

                val scenesJson = json.optJSONArray("scene_settings")
                val scenes = mutableListOf<SceneSetting>()
                if (scenesJson != null) {
                    for (i in 0 until scenesJson.length()) {
                        val s = scenesJson.getJSONObject(i)
                        scenes.add(SceneSetting(
                            sceneName = s.optString("name"),
                            sceneId = s.getString("id"),
                            sceneVersion = s.getString("version"),
                            scenePath = s.optString("path")
                        ))
                    }
                }

                Cognitive3DConfig(
                    apiKey = json.getString("api_key"),
                    gatewayUrl = json.optString("gateway_url", "https://data.cognitive3d.com"),
                    enableLogging = json.optBoolean("enable_logging", true),
                    enableGaze = json.optBoolean("enable_gaze", true),
                    gazeSnapshotCount = json.optInt("gaze_snapshot_count", 256),
                    eventSnapshotCount = json.optInt("event_snapshot_count", 256),
                    dynamicSnapshotCount = json.optInt("dynamic_snapshot_count", 512),
                    sensorSnapshotCount = json.optInt("sensor_snapshot_count", 512),
                    fixationSnapshotCount = json.optInt("fixation_snapshot_count", 256),
                    boundarySnapshotCount = json.optInt("boundary_snapshot_count", 64),
                    automaticSendTimer = json.optInt("automatic_send_timer", 10),
                    cacheLimit = json.optLong("local_data_cache_size", 1024 * 1024 * 5),
                    sceneSettings = scenes
                )
            } catch (e: Exception) {
                Log.e(Util.TAG, "Failed to load cognitive3d.json from assets", e)
                null
            }
        }
    }
}
