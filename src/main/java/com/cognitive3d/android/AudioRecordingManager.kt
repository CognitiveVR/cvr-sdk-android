package com.cognitive3d.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.cognitive3d.android.capnp.AudioSerialization.AudioPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.capnproto.MessageBuilder
import org.capnproto.SerializePacked
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.Channels

/**
 * Records the participant's microphone for the whole session and uploads it in chunks.
 * AAC/ADTS mono at 22.05 kHz / 32 kbps, read from a growing temp file
 * every 10 s and POSTed to /v0/audio as a Cap'n Proto packed AudioPayload.
 *
 * Opt-in via `enable_audio_recording` in cognitive3d.json, and only active when the app
 * declares RECORD_AUDIO and the user grants it. The microphone is released on pause and while
 * the device is offline with a full cache (new chunks would only be dropped).
 *
 * By default the SDK requests RECORD_AUDIO with its other optional permissions at startup
 * (see [getOptionalPermissions]). Apps that want to control when the prompt appears (e.g. after
 * their own consent screen) set `request_audio_permission: false` and request it themselves.
 * Either way the permission is re-checked on every [start], so a grant made mid-session takes
 * effect on the next resume.
 *
 * Note: on Meta Spatial, any runtime permission dialog during the immersive session leaves the
 * app rendering black until relaunch, whoever requests it. This is a platform issue, reproduced
 * without the SDK.
 */
internal object AudioRecordingManager {
    private const val CHANNEL = "user"
    private const val SAMPLE_RATE = 22050
    private const val BIT_RATE = 32000
    private const val CHUNK_INTERVAL_MS = 10_000L
    private const val FORMAT_VERSION = "1.0"
    private const val TEMP_DIR = "c3daudio"
    // One chunk as it sits in the offline cache: 10 s at 32 kbps, Base64-inflated by 4/3
    private const val CACHED_CHUNK_BYTES = BIT_RATE / 8 * (CHUNK_INTERVAL_MS / 1000) * 4 / 3

    /**
     * State for one MediaRecorder run (one resume-to-pause span). Kept per run rather than
     * globally so the final read of a stopped file can't interleave with a newly started one.
     */
    private class Recording(
        val recorder: MediaRecorder,
        val file: File,
        val silenceCallback: AudioManager.AudioRecordingCallback,
        startMs: Long
    ) {
        var readPosition = 0L
        var chunkStartMs = startMs
    }

    /** True when the app opted in and RECORD_AUDIO is currently granted. */
    @Volatile
    var isEnabled = false
        private set

    // enable_audio_recording from cognitive3d.json
    private var optedIn = false
    // Logged once per session so a missing grant doesn't warn on every resume
    private var warnedNoPermission = false

    private var appContext: Context? = null
    private var audioURL = ""
    // Session-level: true between start() and stop()
    private var active = false
    // The current microphone capture; null while paused for a full cache
    @Volatile
    private var recording: Recording? = null
    private var pausedForCache = false
    private var chunkJob: Job? = null
    @Volatile
    private var isSilenced = false

    // Serializes chunk reads/sends so part numbers go out in order across pause/resume
    private val chunkMutex = Mutex()
    private var part = 1

    /**
     * Returns RECORD_AUDIO if the app opted into audio recording, lets the SDK request it, and
     * declares the permission in its manifest. The SDK manifest intentionally does not declare it,
     * so apps that don't use this feature never gain microphone access by merging the SDK.
     */
    fun getOptionalPermissions(context: Context): Array<String> {
        val config = Cognitive3DConfig.fromAssets(context) ?: return emptyArray()
        if (!config.enableAudioRecording || !config.requestAudioPermission) return emptyArray()
        if (!declaresRecordAudio(context)) return emptyArray() // init() logs the warning
        return arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    /** Prepares a new session. Recording starts on [start]. */
    fun init(context: Context, config: Cognitive3DConfig) {
        appContext = context.applicationContext
        val baseUrl = (if (config.gatewayUrl.endsWith("/")) config.gatewayUrl else "${config.gatewayUrl}/") + "v0/"
        audioURL = "${baseUrl}audio"
        part = 1
        optedIn = config.enableAudioRecording
        warnedNoPermission = false
        // Undeclared means it can never be granted: disable for the session so start() doesn't
        // also log a misleading "starts once granted" warning
        if (optedIn && !declaresRecordAudio(context)) {
            Log.w(Util.TAG, "enable_audio_recording is set but the app manifest does not declare RECORD_AUDIO — " +
                "add <uses-permission android:name=\"android.permission.RECORD_AUDIO\" /> to the app's AndroidManifest.xml. Audio will not be recorded.")
            optedIn = false
        }
        isEnabled = optedIn && hasPermission(context)

        // Temp files left behind by a killed process hold audio that was never uploaded; drop them
        if (recording == null) {
            File(context.cacheDir, TEMP_DIR).listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * Starts capturing the microphone. No-op if not opted in, RECORD_AUDIO isn't granted yet,
     * or already recording.
     */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (!optedIn || active) return
        val context = appContext ?: return
        if (!hasPermission(context)) {
            if (!warnedNoPermission) {
                warnedNoPermission = true
                Util.logWarning("Audio recording enabled but RECORD_AUDIO not granted — recording starts on the next resume after it is granted")
            }
            return
        }
        if (!isEnabled) {
            // Granted mid-session: update the property reported at session start
            isEnabled = true
            Cognitive3DManager.setSessionProperty("c3d.device.audio_tracking.enabled", true)
        }
        active = true
        startCapture()
        chunkJob = scope.launch {
            while (isActive) {
                delay(CHUNK_INTERVAL_MS)
                recording?.let { sendChunk(it) }
                updateCachePause(scope)
            }
        }
    }

    /**
     * Stops capturing and releases the microphone synchronously (so a following resume can
     * reacquire it), then sends the remaining audio and deletes the temp file in the background.
     */
    fun stop(scope: CoroutineScope) {
        synchronized(this) {
            if (!active) return
            active = false
            pausedForCache = false
            chunkJob?.cancel()
            chunkJob = null
        }
        stopCapture(scope)
    }

    /**
     * While offline with a full cache, new chunks would be dropped, so release the microphone
     * until the cache drains or the network comes back.
     */
    private fun updateCachePause(scope: CoroutineScope) {
        val full = NetworkManager.isCacheFull(CACHED_CHUNK_BYTES)
        synchronized(this) {
            if (!active) return
            if (full && recording != null) {
                Util.logWarning("Offline cache is full — pausing audio recording until there is room")
                pausedForCache = true
                stopCapture(scope)
            } else if (!full && pausedForCache) {
                Util.logInfo("Offline cache has room — resuming audio recording")
                pausedForCache = false
                startCapture()
            }
        }
    }

    @Synchronized
    private fun startCapture() {
        if (!active || recording != null) return
        val context = appContext ?: return

        var recorder: MediaRecorder? = null
        var file: File? = null
        try {
            val dir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
            file = File.createTempFile("audio", ".aac", dir)
            recorder = createRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                // ADTS frames are self-delimiting, so the file can be read in chunks while still growing
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioEncodingBitRate(BIT_RATE)
                setAudioSamplingRate(SAMPLE_RATE)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            val silenceCallback = createSilenceCallback()
            recorder.registerAudioRecordingCallback(Runnable::run, silenceCallback)
            // The callback only fires on changes, so check whether capture started out silenced
            recorder.activeRecordingConfiguration?.let { updateSilenced(it.isClientSilenced) }

            recording = Recording(recorder, file, silenceCallback, System.currentTimeMillis())
            Util.logDebug("Audio recording started")
        } catch (e: Exception) {
            Log.e(Util.TAG, "Failed to start audio recording", e)
            recorder?.release()
            file?.delete()
        }
    }

    private fun stopCapture(scope: CoroutineScope) {
        val rec = synchronized(this) {
            val current = recording ?: return
            recording = null
            current.recorder.unregisterAudioRecordingCallback(current.silenceCallback)
            try {
                current.recorder.stop()
            } catch (e: RuntimeException) {
                // stop() throws if no valid audio was captured yet; the file is just short
                Util.logWarning("Audio recorder stopped without data: ${e.message}")
            }
            current.recorder.release()
            current
        }

        scope.launch {
            sendChunk(rec)
            if (!rec.file.delete()) {
                Util.logWarning("Failed to delete audio temp file ${rec.file}")
            }
        }
        Util.logDebug("Audio recording stopped")
    }

    private suspend fun sendChunk(rec: Recording) = chunkMutex.withLock {
        val bytes = try {
            readNewBytes(rec)
        } catch (e: IOException) {
            Log.e(Util.TAG, "Failed to read audio chunk", e)
            return@withLock
        }
        if (bytes.isEmpty()) return@withLock

        val endMs = System.currentTimeMillis()
        val payload = serialize(bytes, rec.chunkStartMs, endMs, part)
        part++
        rec.chunkStartMs = endMs

        // Not cancellable: once the bytes are consumed from the file they must be sent or cached
        withContext(NonCancellable) {
            NetworkManager.sendBytes(audioURL, payload)
        }
    }

    private fun readNewBytes(rec: Recording): ByteArray {
        RandomAccessFile(rec.file, "r").use { raf ->
            val length = raf.length()
            if (length <= rec.readPosition) return ByteArray(0)
            raf.seek(rec.readPosition)
            val buffer = ByteArray((length - rec.readPosition).toInt())
            raf.readFully(buffer)
            rec.readPosition = length
            return buffer
        }
    }

    private fun serialize(audio: ByteArray, startMs: Long, endMs: Long, part: Int): ByteArray {
        val message = MessageBuilder()
        val payload = message.initRoot(AudioPayload.factory)
        payload.setUserId(Serialization.userID)
        payload.setSessionId(Serialization.sessionID)
        payload.setFormatVersion(FORMAT_VERSION)
        payload.setTimestamp((Serialization.sessionTimestamp * 1000).toLong())
        payload.setSamplerate(SAMPLE_RATE)
        payload.setBitrate(BIT_RATE)
        payload.setPart(part)

        val chunk = payload.initData(1).get(0)
        chunk.setChannel(CHANNEL)
        chunk.setStartTime(startMs)
        chunk.setEndTime(endMs)
        chunk.setAudioData(audio)

        ByteArrayOutputStream().use { out ->
            SerializePacked.writeToUnbuffered(Channels.newChannel(out), message)
            return out.toByteArray()
        }
    }

    /**
     * Reports when the system silences our capture, e.g. because another app holds the
     * microphone with priority (RealWear's WearHF speech service listens continuously).
     */
    private fun createSilenceCallback() = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            updateSilenced(configs.any { it.isClientSilenced })
        }
    }

    private fun updateSilenced(silenced: Boolean) {
        if (silenced == isSilenced) return
        isSilenced = silenced
        if (silenced) Util.logWarning("Microphone capture silenced by the system")
        Cognitive3DManager.sendCustomEvent("c3d.audio.silenced", mapOf("issilenced" to silenced))
    }

    private fun createRecorder(context: Context): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun declaresRecordAudio(context: Context): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions?.contains(Manifest.permission.RECORD_AUDIO) == true
        } catch (e: Exception) {
            false
        }
    }
}
