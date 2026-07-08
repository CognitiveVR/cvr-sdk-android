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
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class Cognitive3DInitializer : ContentProvider() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(): Boolean {
        val context: Context? = context
        if (context != null && context.applicationContext is Application) {
            val application = context.applicationContext as Application
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private var isChangingConfiguration = false
                private var sessionInitialized = false
                private var permissionsRequested = false
                private var platformProvider: PlatformProvider? = null

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

                override fun onActivityStarted(activity: Activity) {
                    if (isChangingConfiguration) {
                        isChangingConfiguration = false
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    if (!sessionInitialized) {
                        val provider = getOrCreateProvider(activity)
                        val missingRequired = missingPermissions(activity, provider.getRequiredPermissions())
                        val missingOptional = missingPermissions(activity, provider.getOptionalPermissions())
                        if ((missingRequired.isNotEmpty() || missingOptional.isNotEmpty()) && !permissionsRequested) {
                            // Ask at most once per process (re-requesting on every resume
                            // traps the user in a prompt loop on denial), and ask even when
                            // only optional permissions are missing — capability signals
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
                            Log.w(Util.TAG, "Missing required permissions: $missingRequired — Cognitive3D will not start this session")
                        }
                    } else {
                        Cognitive3DManager.resumeSession()
                    }
                }

                override fun onActivityPaused(activity: Activity) {
                    if (sessionInitialized) {
                        Cognitive3DManager.pauseSession()
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    isChangingConfiguration = activity.isChangingConfigurations
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {
                    if (!activity.isChangingConfigurations) {
                        Cognitive3DManager.endSession()
                        platformProvider?.destroy()
                        sessionInitialized = false
                        platformProvider = null
                    }
                }

                private fun getOrCreateProvider(activity: Activity): PlatformProvider {
                    return platformProvider ?: PlatformFactory.create(activity).also { platformProvider = it }
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
                            Cognitive3DManager.initSession(activity, provider)
                            sessionInitialized = true
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
