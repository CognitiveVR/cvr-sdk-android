package com.cognitive3d.android

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles sending serialized data to the Cognitive3D backend.
 * Includes retry logic with exponential backoff and local file caching for offline resilience.
 */
object NetworkManager {
    private var cache: DualFileCache? = null
    private var isProcessingCache = false
    private var applicationKey: String? = null
    private var cacheCheckInterval: Long = 10000 // Default 10s

    private var lastRequestFailed = false
    private var lastFailureTime: Long = 0
    private var currentRetryDelay = 60f
    private const val MIN_RETRY_DELAY : Float = 60f
    private const val MAX_RETRY_DELAY : Float = 240f

    private const val CONTENT_TYPE_JSON = "application/json"
    private const val CONTENT_TYPE_BINARY = "application/octet-stream"

    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Initializes the network layer with API key, cache storage, and retry settings. */
    fun init(context: Context, config: Cognitive3DConfig) {
        // Ensure the API key starts with the required prefix
        val rawKey = config.apiKey
        applicationKey = if (rawKey.startsWith("APIKEY:DATA ")) {
            rawKey
        } else {
            "APIKEY:DATA $rawKey"
        }

        cacheCheckInterval = (config.automaticSendTimer * 1000).toLong()
        
        if (cache == null) {
            cache = DualFileCache(context, config.cacheLimit)
            networkScope.launch {
                processCacheLoop()
            }
        }
    }

    /**
     * Writes everything to the offline cache instead of the network, for sends that must finish
     * immediately (the app is crashing). The cache is uploaded on the next launch.
     */
    @Volatile
    var cacheOnly = false

    /** Sends JSON content to the given URL, falling back to local cache on failure. */
    suspend fun send(url: String, content: String) {
        sendWithFallback(url, content.toByteArray(Charsets.UTF_8), CONTENT_TYPE_JSON) { content }
    }

    /**
     * Sends a binary payload (e.g. audio) to the given URL, falling back to local cache on failure.
     * The cache is line-based text, so binary bodies are stored Base64-encoded and decoded again
     * when replayed (see [isBinaryUrl]).
     */
    suspend fun sendBytes(url: String, content: ByteArray) {
        sendWithFallback(url, content, CONTENT_TYPE_BINARY) { Base64.encodeToString(content, Base64.NO_WRAP) }
    }

    /**
     * True while sends are failing and the offline cache can't take [bytes] more, i.e. new
     * data of that size would be dropped rather than sent or cached.
     */
    fun isCacheFull(bytes: Long): Boolean = lastRequestFailed && cache?.hasRoomFor(bytes) == false

    /** Binary endpoints, whose cached bodies are Base64 text rather than JSON. */
    private fun isBinaryUrl(url: String): Boolean = url.endsWith("/audio")

    private suspend fun sendWithFallback(url: String, postData: ByteArray, contentType: String, cacheBody: () -> String) {
        if (cacheOnly) {
            cache?.writeContent(url, cacheBody())
            return
        }
        if (lastRequestFailed) {
            val currentTime = System.currentTimeMillis()
            if (currentTime < lastFailureTime + (currentRetryDelay * 1000).toLong()) {
                Util.logDebug("Network in cooldown period ($currentRetryDelay s). Caching locally.")
                cache?.writeContent(url, cacheBody())
                return
            }

            // Progressive wait times (exponential backoff)
            currentRetryDelay = getExponentialBackoff(currentRetryDelay)
        }

        val code = sendRequestInternal(url, applicationKey, postData, contentType)
        if (code == 0 || (code !in 200..299 && code != 400)) {
            Util.logWarning("Failed to send to $url (Code $code). Caching locally.")
            cache?.writeContent(url, cacheBody())
            lastFailureTime = System.currentTimeMillis()
            lastRequestFailed = true
        } else if (code in 200..299) {
            if (lastRequestFailed) {
                Util.logInfo("Network connection restored.")
            }

            Util.logDebug("Successfully sent to $url")
            currentRetryDelay = MIN_RETRY_DELAY
            lastRequestFailed = false
            
            // Re-established connection or successful send: trigger cache flush immediately
            if (cache?.hasContent() == true) {
                networkScope.launch { processCache() }
            }
        } else {
            Log.e(Util.TAG, "Failed to send to $url (Code $code). Not caching due to client error.")
        }
    }

    private suspend fun processCacheLoop() {
        while (true) {
            delay(cacheCheckInterval)
            try {
                if (cache?.hasContent() == true) {
                    processCache()
                }
            } catch (e: Exception) {
                Log.e(Util.TAG, "Error in processCacheLoop", e)
            }
        }
    }

    private suspend fun processCache() {
        val c = cache ?: return
        if (isProcessingCache) return
        isProcessingCache = true

        try {
            while (c.hasContent()) {
                val peek = c.peekContent() ?: break
                val (url, body, _) = peek

                val code = try {
                    if (isBinaryUrl(url)) {
                        sendRequestInternal(url, applicationKey, Base64.decode(body, Base64.NO_WRAP), CONTENT_TYPE_BINARY)
                    } else {
                        sendRequestInternal(url, applicationKey, body.toByteArray(Charsets.UTF_8), CONTENT_TYPE_JSON)
                    }
                } catch (e: IllegalArgumentException) {
                    // Corrupt Base64 entry: treat as a client error so it is dropped instead of blocking the cache
                    Log.e(Util.TAG, "Dropping unreadable cached payload for $url", e)
                    400
                }
                if (code in 200..299) {
                    c.popContent()
                    Util.logDebug("Successfully sent cached data to $url")
                } else if (code in 400..499) {
                    // Client errors, don't retry this specific one as it will likely fail again
                    c.popContent()
                } else {
                    // Still failing (0, 5xx, etc), stop processing for now
                    break
                }
                // Small delay between cache sends
                delay(2000) 
            }
        } finally {
            isProcessingCache = false
        }
    }

    private suspend fun sendRequestInternal(url: String, apiKey: String?, postData: ByteArray, contentType: String): Int {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val urlObj = URL(url)
                connection = urlObj.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", contentType)
                if (apiKey != null) {
                    connection.setRequestProperty("Authorization", apiKey)
                }

                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(postData.size)

                DataOutputStream(connection.outputStream).use { wr ->
                    wr.write(postData)
                }
                
                connection.responseCode
            } catch (e: IOException) {
                val responseCode = try { connection?.responseCode ?: 0 } catch (ignored: Exception) { 0 }
                Log.e(Util.TAG, "Network error sending to $url (Code $responseCode)", e)
                responseCode
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun getExponentialBackoff (retryDelay : Float): Float {
        if (retryDelay < MAX_RETRY_DELAY) {
            return retryDelay * 2
        }
        return MAX_RETRY_DELAY
    }
}
