package com.cognitive3d.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reports why the previous session's process died when it never ended cleanly (native crash,
 * ANR, killed for memory, swiped away...), matching the external Android plugin used by Unity:
 * a `c3d.sessionEnd` for that session with `ExitCode` and `Reason`, plus a `c3d.event.app_crashed`
 * event and `c3d.session_tag.Crash` tag for native crashes.
 *
 * Android only reports exit reasons from Android 11 (API 30) on, and only at the next launch,
 * so the current session is saved on start and cleared when it ends cleanly. A record still
 * present at the next launch means the process died first.
 */
internal object ExitReporter {
    private const val PREFS = "com.cognitive3d.android.session"
    private const val KEY_SESSION_ID = "sessionId"
    private const val KEY_USER_ID = "userId"
    private const val KEY_TIMESTAMP = "sessionTimestamp"
    private const val KEY_EVENT_URL = "eventUrl"
    private const val KEY_GAZE_URL = "gazeUrl"
    private const val KEY_PID = "pid"

    private var appContext: Context? = null

    /**
     * Sends the exit reason of the previous session if it didn't end cleanly, then saves the
     * current session in its place. Call after Serialization.init so the current session exists.
     */
    fun onSessionStart(context: Context, scope: CoroutineScope) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val previous = readRecord(context)
        val previousPid = prefs.getInt(KEY_PID, 0)
        if (previous != null) {
            scope.launch {
                try {
                    reportExit(context, previous, previousPid)
                } catch (e: Exception) {
                    Log.w(Util.TAG, "Failed to report previous session exit", e)
                }
            }
        }

        val current = Serialization.currentSessionRecord()
        prefs.edit()
            .putString(KEY_SESSION_ID, current.sessionId)
            .putString(KEY_USER_ID, current.userId)
            .putString(KEY_TIMESTAMP, current.sessionTimestamp.toString())
            .putString(KEY_EVENT_URL, current.eventUrl)
            .putString(KEY_GAZE_URL, current.gazeUrl)
            .putInt(KEY_PID, Process.myPid())
            .commit()
    }

    /** Marks the current session as ended cleanly, so the next launch reports nothing for it. */
    fun onSessionEnd() {
        // commit, not apply: this also runs from the crash handler just before the process dies
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.commit()
    }

    private suspend fun reportExit(context: Context, session: Serialization.SessionRecord, pid: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return

        // Filter by the session's pid so a later run of the app can't be mistaken for it
        val exitInfo = activityManager.getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull()
        if (exitInfo == null || exitInfo.timestamp < session.sessionTimestamp * 1000) {
            Log.d(Util.TAG, "No exit reason found for previous session ${session.sessionId}")
            return
        }

        val reason = getExitReason(exitInfo.reason)
        val exitTime = exitInfo.timestamp / 1000.0
        Log.d(Util.TAG, "Previous session ${session.sessionId} ended without closing: $reason")

        if (exitInfo.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            Serialization.sendPastSessionEvent(session, "c3d.event.app_crashed", mapOf(
                "Reason" to reason,
                "Description" to (exitInfo.description ?: "")
            ), exitTime)
            Serialization.sendPastSessionProperties(session, mapOf("c3d.session_tag.Crash" to true))
        }

        Serialization.sendPastSessionEvent(session, "c3d.sessionEnd", mapOf(
            "sessionlength" to exitTime - session.sessionTimestamp,
            "ExitCode" to exitInfo.reason.toString(),
            "Reason" to reason
        ), exitTime)
    }

    private fun readRecord(context: Context): Serialization.SessionRecord? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Serialization.SessionRecord(
            sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null,
            userId = prefs.getString(KEY_USER_ID, null) ?: return null,
            sessionTimestamp = prefs.getString(KEY_TIMESTAMP, null)?.toDoubleOrNull() ?: return null,
            eventUrl = prefs.getString(KEY_EVENT_URL, null) ?: return null,
            gazeUrl = prefs.getString(KEY_GAZE_URL, null) ?: return null
        )
    }

    /** Same labels as the external Android plugin's getExitReason. */
    private fun getExitReason(reasonCode: Int): String = when (reasonCode) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "Crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native Crash"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency Died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive Resource Usage"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Self Exit"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization Failure"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Low Memory"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission Change"
        ApplicationExitInfo.REASON_SIGNALED -> "Signaled"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "User Requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "User Stopped"
        ApplicationExitInfo.REASON_UNKNOWN -> "Unknown"
        else -> "Other"
    }
}
