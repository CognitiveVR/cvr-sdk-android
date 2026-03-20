# The Cognitive3D SDK for Android

Welcome! This SDK allows you to integrate your native Android projects with Cognitive3D, which provides analytics and insights about your project. In addition, Cognitive3D empowers you to take actions that will improve users' engagement with your experience.

**Supported Platforms:**
- Android XR (Jetpack XR)
- Meta Spatial SDK

**Requirements:**
- Android Studio Ladybug (2024.2.1) or later
- Minimum SDK: 29
- Kotlin 1.9+ or Java 11+

## Community support

Please join our [Discord](https://discord.gg/x38sNUdDRH) for community support.

## License

Licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.

## Quickstart

### Installation

Add the Cognitive3D SDK to your project's `build.gradle` file:

#### Android XR (Jetpack XR)

<details>
<summary>Kotlin DSL</summary>

```kotlin
dependencies {
    implementation("com.cognitive3d:android-xr-sdk:+")
}
```

</details>

<details>
<summary>Groovy DSL</summary>

```groovy
dependencies {
    implementation 'com.cognitive3d:android-xr-sdk:+'
}
```

</details>

#### Meta Spatial SDK

<details>
<summary>Kotlin DSL</summary>

```kotlin
dependencies {
    implementation("com.cognitive3d:meta-spatial-sdk:+")
}
```

</details>

<details>
<summary>Groovy DSL</summary>

```groovy
dependencies {
    implementation 'com.cognitive3d:meta-spatial-sdk:+'
}
```

</details>

### SDK Initialization

### Generate the Config File

We provide a Gradle task to generate a template configuration file automatically.

#### 1. Add the Gradle Task

Add the following code to your app's build file:

<details>
<summary>Kotlin DSL (build.gradle.kts)</summary>

```kotlin
tasks.register("generateCognitiveConfig") {
    group = "cognitive3d"
    description = "Generates a template cognitive3d.json file in assets"
    doLast {
        val assetsDir = File(projectDir, "src/main/assets")
        if (!assetsDir.exists()) assetsDir.mkdirs()

        val configFile = File(assetsDir, "cognitive3d.json")
        if (!configFile.exists()) {
            configFile.writeText("""
                {
                    "api_key": "YOUR_API_KEY_HERE",
                    "gateway_url": "https://data.cognitive3d.com",
                    "enable_logging": false,
                    "enable_gaze": true,
                    "gaze_snapshot_count": 256,
                    "event_snapshot_count": 256,
                    "dynamic_snapshot_count": 512,
                    "sensor_snapshot_count": 512,
                    "fixation_snapshot_count": 256,
                    "boundary_snapshot_count": 64,
                    "automatic_send_timer": 10,
                    "local_data_cache_size": 104857600,
                    "scene_settings": [
                        {
                            "name": "Default Scene",
                            "id": "YOUR_SCENE_ID_HERE",
                            "version": "YOUR_SCENE_VERSION_HERE",
                            "path": "com.example.app.MainActivity"
                        }
                    ]
                }
            """.trimIndent())
            println("Created template at: ${configFile.absolutePath}")
        } else {
            println("cognitive3d.json already exists.")
        }
    }
}
```
</details>

<details>
<summary>Groovy DSL (build.gradle)</summary>

```groovy
tasks.register("generateCognitiveConfig") {
    group = "cognitive3d"
    description = "Generates a template cognitive3d.json file in assets"
    doLast {
        def assetsDir = new File(projectDir, "src/main/assets")
        if (!assetsDir.exists()) assetsDir.mkdirs()

        def configFile = new File(assetsDir, "cognitive3d.json")
        if (!configFile.exists()) {
            configFile.text = '''{
    "api_key": "YOUR_API_KEY_HERE",
    "gateway_url": "https://data.cognitive3d.com",
    "enable_logging": false,
    "enable_gaze": true,
    "gaze_snapshot_count": 256,
    "event_snapshot_count": 256,
    "dynamic_snapshot_count": 512,
    "sensor_snapshot_count": 512,
    "fixation_snapshot_count": 256,
    "boundary_snapshot_count": 64,
    "automatic_send_timer": 10,
    "local_data_cache_size": 104857600,
    "scene_settings": [
        {
            "name": "Default Scene",
            "id": "YOUR_SCENE_ID_HERE",
            "version": "YOUR_SCENE_VERSION_HERE",
            "path": "com.example.app.MainActivity"
        }
    ]
}'''
            println "Created template at: ${configFile.absolutePath}"
        } else {
            println "cognitive3d.json already exists."
        }
    }
}
```
</details>

#### 2. Run the Task

1. Open the **Gradle** tab in Android Studio (right sidebar)
2. Navigate to **Tasks → cognitive3d → generateCognitiveConfig**
3. Double-click to run

The config file will be created at `app/src/main/assets/cognitive3d.json`.

#### 3. Configure Your Settings

Open the generated `cognitive3d.json` and update the following values:

| Field | Description |
|-------|-------------|
| `api_key` | Your Cognitive3D API key from the dashboard |
| `scene_settings.id` | Your scene ID from the Cognitive3D dashboard |
| `scene_settings.version` | Your scene version number |
| `scene_settings.path` | Your main activity path (e.g., `com.myapp.MainActivity`) |
