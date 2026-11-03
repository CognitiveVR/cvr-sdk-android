<!-- markdownlint-disable MD033 -->

# The Cognitive3D SDK for Android

<!-- markdownlint-disable-next-line MD013 -->
Welcome! This SDK allows you to integrate your native Android projects with Cognitive3D, which provides analytics and insights about your project. In addition, Cognitive3D empowers you to take actions that will improve users' engagement with your experience.

**Supported Platforms:**

- Android XR (Jetpack XR)
- Meta Spatial SDK

**Requirements:**

- Android Studio Narwhal (2025.1.1) or later (the SDK builds with AGP 8.11)
<!-- markdownlint-disable-next-line MD013 -->
- Compile SDK: 36 (required by the Jetpack XR `1.0.0-beta01` dependencies on Android XR)
- Minimum SDK: 29
- Kotlin 1.9+ or Java 11+

## Community support

Please join our [Discord](https://discord.gg/x38sNUdDRH) for community support.

## License

<!-- markdownlint-disable-next-line MD013 -->
Licensed under the Cognitive3D SDK Software License. See [LICENSE](LICENSE) for details.

## Quickstart

### Installation

Add the Cognitive3D SDK to your project's `build.gradle` file.
Pin an explicit version — avoid `+` dynamic versions, which can
pull a new release with different build requirements without warning:

#### Android XR (Jetpack XR)

<details>
<summary>Kotlin DSL</summary>

```kotlin
dependencies {
    implementation("com.cognitive3d:android-xr-sdk:1.1.0")
}
```

</details>

<details>
<summary>Groovy DSL</summary>

```groovy
dependencies {
    implementation 'com.cognitive3d:android-xr-sdk:1.1.0'
}
```

</details>

#### Meta Spatial SDK

<details>
<summary>Kotlin DSL</summary>

```kotlin
dependencies {
    implementation("com.cognitive3d:meta-spatial-sdk:1.1.0")
}
```

</details>

<details>
<summary>Groovy DSL</summary>

```groovy
dependencies {
    implementation 'com.cognitive3d:meta-spatial-sdk:1.1.0'
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
| ----- | ----------- |
| `api_key` | Your Cognitive3D API key from the dashboard |
| `scene_settings.id` | Your scene ID from the Cognitive3D dashboard |
| `scene_settings.version` | Your scene version number |
<!-- markdownlint-disable-next-line MD013 -->
| `scene_settings.path` | Your main activity path (e.g., `com.myapp.MainActivity`) |

## Audio Recording

The SDK can record the participant's microphone for the whole session and
upload it alongside the rest of the session data. Audio recording is **off by
default** and requires two changes in your app.

### 1. Enable it in `cognitive3d.json`

```json
{
    "enable_audio_recording": true
}
```

### 2. Declare the microphone permission in your app's manifest

Add this to your app's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

<!-- markdownlint-disable-next-line MD013 -->
The SDK intentionally does **not** declare `RECORD_AUDIO` itself, so apps that don't use audio recording never gain microphone access just by including the SDK. Android can only prompt for permissions your app declares, so without this line audio will not be recorded and the SDK logs:

```text
enable_audio_recording is set but the app manifest does not declare RECORD_AUDIO — add <uses-permission android:name="android.permission.RECORD_AUDIO" /> to the app's AndroidManifest.xml. Audio will not be recorded.
```

### Permission prompt

<!-- markdownlint-disable-next-line MD013 -->
By default, the SDK requests `RECORD_AUDIO` when the session starts, in the same dialog as its other optional permissions. You don't need to write any permission code.

<!-- markdownlint-disable-next-line MD013 -->
If you want to control when the prompt appears (for example, after your own consent or onboarding screen), turn off the SDK's request and ask for the permission yourself:

```json
{
    "enable_audio_recording": true,
    "request_audio_permission": false
}
```

```kotlin
if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
}
```

<!-- markdownlint-disable-next-line MD013 -->
Either way, the SDK checks the permission every time the session starts or resumes. If the permission is granted mid-session, recording starts automatically on the next resume (closing the permission dialog resumes your activity), and no audio is recorded before that point. If the user denies it, the session continues without audio.

### Configuration

<!-- markdownlint-disable MD013 -->
| Field | Default | Description |
| ----- | ------- | ----------- |
| `enable_audio_recording` | `false` | Records the microphone during the session. Requires `RECORD_AUDIO` in your app's manifest |
| `request_audio_permission` | `true` | When `false`, the SDK never prompts for the microphone; your app requests it instead |
<!-- markdownlint-enable MD013 -->

### How it works

<!-- markdownlint-disable MD013 -->
- Audio is recorded as mono AAC (22.05 kHz, 32 kbps) and uploaded in 10-second chunks
- Recording pauses when your activity pauses and resumes with it
- When the device is offline, chunks are stored in the local data cache and uploaded later. If the cache is full, recording pauses until there is room again
- If the system silences the microphone (for example, another app such as RealWear's voice control has priority), the SDK sends a `c3d.audio.silenced` custom event
- The `c3d.device.audio_tracking.enabled` session property reports whether audio was recorded for the session
<!-- markdownlint-enable MD013 -->

### Known issue: Meta Spatial SDK

<!-- markdownlint-disable-next-line MD013 -->
On Meta Quest, an immersive Meta Spatial SDK app can render a black screen after **any** runtime permission dialog appears during the session, until the app is relaunched. This is a platform issue that also occurs without the Cognitive3D SDK. After relaunching, the permission is already granted and audio records normally. If this is a concern for your app, request `RECORD_AUDIO` before entering the immersive experience (for example, from a 2D launcher activity) with `request_audio_permission` set to `false`.
