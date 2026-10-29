package com.cognitive3d.android

import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Records uncaught exceptions (app crashes) before the process dies, with the same event and
 * tag as the external Android plugin: a `c3d.event.app_crashed` event with the reason and stack
 * trace, the `c3d.session_tag.Crash` session tag, and a `c3d.sessionEnd` with Reason "Crash".
 *
 * The final data goes to the offline cache rather than the network, since the process can't
 * wait on a request; it is uploaded on the next launch. The app's previous handler (Android's
 * default crash dialog, or a crash reporter such as Crashlytics) still runs afterwards.
 */
internal object CrashReporter {
    // Upper bound on how long the crash can be delayed while writing to the cache
    private const val WRITE_TIMEOUT_MS = 2_000L
    // Keeps one event's payload reasonable; the top of the trace is what identifies the crash
    private const val MAX_STACK_TRACE_CHARS = 8_000

    private var installed = false

    // Crashes outside a session (before init, after end) belong to no session, so aren't recorded
    @Volatile
    var sessionActive = false

    /** Installs the handler once per process; later sessions reuse it. */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (sessionActive) {
                sessionActive = false
                try {
                    recordCrash(throwable)
                } catch (t: Throwable) {
                    // Never let our own failure hide the original crash
                    Log.e(Util.TAG, "Failed to record crash", t)
                }
            }
            previousHandler?.uncaughtException(thread, throwable)
            // Only reached if the previous handler let the process live on
            NetworkManager.cacheOnly = false
        }
    }

    private fun recordCrash(throwable: Throwable) {
        Log.e(Util.TAG, "Recording app crash: $throwable")
        val stackTrace = Log.getStackTraceString(throwable).take(MAX_STACK_TRACE_CHARS)
        val sessionLength = System.currentTimeMillis() / 1000.0 - Serialization.sessionTimestamp

        NetworkManager.cacheOnly = true
        // Blocks the crashing thread, which is what keeps the process alive until the data is written
        runBlocking {
            withTimeoutOrNull(WRITE_TIMEOUT_MS) {
                Serialization.serializeCustomEvents("c3d.event.app_crashed", mapOf(
                    "Reason" to throwable.toString(),
                    "Stack Trace" to stackTrace
                ))
                Serialization.setSessionProperty("c3d.session_tag.Crash", true)
                Serialization.serializeCustomEvents("c3d.sessionEnd", mapOf(
                    "sessionlength" to sessionLength,
                    "Reason" to "Crash"
                ))
                Serialization.flush()
                // The session end is recorded, so the next launch shouldn't report this exit again.
                // On timeout the record stays and ExitReporter reports the crash from Android's exit info.
                ExitReporter.onSessionEnd()
            } ?: Log.w(Util.TAG, "Timed out writing crash data; some of it may be lost")
        }
    }
}
