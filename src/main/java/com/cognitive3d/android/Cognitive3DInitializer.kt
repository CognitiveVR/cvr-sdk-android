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
import androidx.xr.runtime.Config
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.SessionCreateSuccess

class Cognitive3DInitializer : ContentProvider() {

    companion object {
        private const val HEAD_TRACKING_PERMISSION = "android.permission.HEAD_TRACKING"
        private const val HAND_TRACKING_PERMISSION = "android.permission.HAND_TRACKING"
        private val REQUIRED_PERMISSIONS = arrayOf(
            HEAD_TRACKING_PERMISSION,
            HAND_TRACKING_PERMISSION
        )
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(): Boolean {
        val context: Context? = context
        if (context != null && context.applicationContext is Application) {
            val application = context.applicationContext as Application
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private var startedActivityCount = 0
                private var isChangingConfiguration = false
                private var sessionInitialized = false
                private var xrSession: Session? = null

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

                override fun onActivityStarted(activity: Activity) {
                    startedActivityCount++
                    if (startedActivityCount == 1 && !isChangingConfiguration) {
                        if (hasRequiredPermissions(activity)) {
                            initializeXRSession(activity)
                        } else {
                            requestRequiredPermissions(activity)
                        }
                    } else if (isChangingConfiguration) {
                        isChangingConfiguration = false
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    if (!sessionInitialized) {
                        if (hasRequiredPermissions(activity)) {
                            initializeXRSession(activity)
                        }
                    } else {
                        // Resume recording and trackers when returning to the app
                        xrSession?.let {
                            Cognitive3DManager.resumeSession(it)
                        }
                    }
                }

                override fun onActivityPaused(activity: Activity) {
                    // Flush all pending data immediately on pause to prevent data loss
                    if (sessionInitialized) {
                        Cognitive3DManager.pauseSession()
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivityCount--
                    isChangingConfiguration = activity.isChangingConfigurations
                    if (startedActivityCount == 0 && !isChangingConfiguration) {
                        Cognitive3DManager.endSession()
                        sessionInitialized = false
                        xrSession = null
                    }
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}

                private fun hasRequiredPermissions(context: Context): Boolean {
                    val missing = REQUIRED_PERMISSIONS.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        Log.w(Util.TAG, "Missing permissions: $missing")
                    }
                    return missing.isEmpty()
                }

                private fun requestRequiredPermissions(activity: Activity) {
                    ActivityCompat.requestPermissions(
                        activity,
                        REQUIRED_PERMISSIONS,
                        PERMISSION_REQUEST_CODE
                    )
                }

                private fun initializeXRSession(activity: Activity) {
                    try {
                        val result = Session.create(activity)
                        if (result is SessionCreateSuccess) {
                            val session = result.session
                            xrSession = session
                            val newConfig = session.config.copy(
                                // For androidx.xr.runtime:runtime:1.0.0-alpha09
                                headTracking = Config.HeadTrackingMode.LAST_KNOWN,

                                // For androidx.xr.runtime:runtime:1.0.0-alpha10
                                // deviceTracking = Config.DeviceTrackingMode.LAST_KNOWN,
                                handTracking = Config.HandTrackingMode.BOTH
                            )
                            
                            val configResult = session.configure(newConfig)

                            if (configResult is SessionConfigureSuccess) {
                                Cognitive3DManager.initSession(session)
                                sessionInitialized = true
                            } else {
                                Log.w(Util.TAG, "Failed to configure Cognitive3D session: $configResult")
                            }
                        } else {
                            Log.e(Util.TAG, "Failed to create XR Session: $result")
                        }
                    } catch (e: Exception) {
                        Log.e(Util.TAG, "Error during XR Session initialization", e)
                    } catch (e: NoClassDefFoundError) {
                        Log.e(Util.TAG, "XR Runtime classes missing from classpath", e)
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
