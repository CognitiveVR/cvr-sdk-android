package com.cognitive3d.android

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class Cognitive3DInitializer : ContentProvider() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        // How long the app must have no resumed activity before the session pauses. Moving
        // between activities pauses one before the next resumes; without this grace period every
        // screen change would record a pause and resume. Same value ProcessLifecycleOwner uses.
        private const val PAUSE_DELAY_MS = 700L
    }

    override fun onCreate(): Boolean {
        val context: Context? = context
        if (context != null && context.applicationContext is Application) {
            val application = context.applicationContext as Application
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private var sessionInitialized = false
                private var sessionPaused = false
                private var permissionsRequested = false
                private var platformProvider: PlatformProvider? = null
                private var audioPermissions: Array<String>? = null

                // The session follows the app, not a single activity: it pauses only when no
                // activity is resumed and ends only when the last activity is destroyed. Lifecycle
                // callbacks run on the main thread, so these need no synchronization.
                private val aliveActivities = mutableSetOf<Activity>()
                private val resumedActivities = mutableSetOf<Activity>()
                // The activity the platform provider was initialized with
                private var hostActivity: Activity? = null
                private var awaitingHostRecreation = false

                private val mainHandler = Handler(Looper.getMainLooper())
                private val pauseRunnable = Runnable {
                    if (sessionInitialized && !sessionPaused && resumedActivities.isEmpty()) {
                        sessionPaused = true
                        Cognitive3DManager.pauseSession()
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    aliveActivities.add(activity)
                    // A configuration change destroys the host and creates its replacement
                    if (awaitingHostRecreation) {
                        awaitingHostRecreation = false
                        hostActivity = activity
                    }
                }

                override fun onActivityStarted(activity: Activity) {}

                override fun onActivityResumed(activity: Activity) {
                    resumedActivities.add(activity)
                    mainHandler.removeCallbacks(pauseRunnable)

                    if (!sessionInitialized) {
                        val provider = getOrCreateProvider(activity)
                        val missingRequired = missingPermissions(activity, provider.getRequiredPermissions())
                        val missingOptional = missingPermissions(activity, provider.getOptionalPermissions() + getAudioPermissions(activity))
                        if ((missingRequired.isNotEmpty() || missingOptional.isNotEmpty()) && !permissionsRequested) {
                            // Ask at most once per process (re-requesting on every resume
                            // traps the user in a prompt loop on denial), and ask even when
                            // only optional permissions are missing; capability signals
                            // like eye tracking must reflect a real user decision, not a
                            // never-asked default. onActivityResumed fires again after the
                            // dialog and initialization proceeds with whatever was granted.
                            permissionsRequested = true
                            ActivityCompat.requestPermissions(
                                activity,
                                (missingRequired + missingOptional).toTypedArray(),
                                PERMISSION_REQUEST_CODE
                            )
                        } else if (missingRequired.isEmpty()) {
                            initializePlatform(activity)
                        } else {
                            Log.w(Util.TAG, "Missing required permissions: $missingRequired. Cognitive3D will not start this session")
                        }
                    } else if (sessionPaused) {
                        sessionPaused = false
                        Cognitive3DManager.resumeSession()
                    }
                }

                override fun onActivityPaused(activity: Activity) {
                    resumedActivities.remove(activity)
                    if (sessionInitialized && resumedActivities.isEmpty()) {
                        mainHandler.removeCallbacks(pauseRunnable)
                        mainHandler.postDelayed(pauseRunnable, PAUSE_DELAY_MS)
                    }
                }

                override fun onActivityStopped(activity: Activity) {}

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {
                    aliveActivities.remove(activity)
                    resumedActivities.remove(activity)
                    val isHost = activity === hostActivity

                    if (activity.isChangingConfigurations) {
                        if (isHost) awaitingHostRecreation = true
                        return
                    }

                    if (!sessionInitialized) {
                        // Only the pre-init provider (used for its permission lists) exists; it may
                        // hold this activity, and initialization creates a fresh one anyway
                        platformProvider = null
                        return
                    }

                    // XR providers track through their host activity's session or scene, so they
                    // still end with it; the plain Android provider keeps going while any activity
                    // remains
                    val hostLost = isHost && platformProvider?.isBoundToHostActivity() != false
                    if (aliveActivities.isEmpty() || hostLost) {
                        endSessionAndReset()
                    } else if (isHost) {
                        // Session continues without it; don't keep the destroyed activity alive
                        hostActivity = null
                    }
                }

                private fun endSessionAndReset() {
                    mainHandler.removeCallbacks(pauseRunnable)
                    if (sessionInitialized) {
                        Cognitive3DManager.endSession()
                    }
                    platformProvider?.destroy()
                    platformProvider = null
                    sessionInitialized = false
                    sessionPaused = false
                    hostActivity = null
                    awaitingHostRecreation = false
                }

                private fun getOrCreateProvider(activity: Activity): PlatformProvider {
                    return platformProvider ?: PlatformFactory.create(activity).also { platformProvider = it }
                }

                // Platform-independent, so requested here rather than via PlatformProvider.
                // Cached: it reads cognitive3d.json, and this runs on every pre-init resume.
                private fun getAudioPermissions(activity: Activity): Array<String> {
                    return audioPermissions ?: AudioRecordingManager.getOptionalPermissions(activity).also { audioPermissions = it }
                }

                private fun missingPermissions(context: Context, permissions: Array<String>): List<String> {
                    return permissions.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                }

                private fun initializePlatform(activity: Activity) {
                    try {
                        val provider = PlatformFactory.create(activity)
                        if (provider.initialize(activity)) {
                            platformProvider = provider
                            hostActivity = activity
                            Cognitive3DManager.initSession(activity, provider)
                            sessionInitialized = true
                            sessionPaused = false
                        } else {
                            Log.w(Util.TAG, "Failed to initialize platform provider")
                        }
                    } catch (e: Exception) {
                        Log.e(Util.TAG, "Error during platform initialization", e)
                    }
                }
            })
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
