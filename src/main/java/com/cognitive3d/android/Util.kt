package com.cognitive3d.android

import android.content.Context
import android.util.Log
import java.util.UUID
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.opengles.GL10
import androidx.core.content.edit

object Util {
    const val TAG : String = "Cognitive3D"
    const val SNAPSHOTINTERVAL : Float = 0.1f

    private const val PREFS_NAME = "cognitive3d_prefs"
    private const val KEY_USER_ID = "user_id"
    
    internal var enableLogging: Boolean = true

    /**
     * Gets a persistent unique identifier for this app installation.
     * Generates a new one if it doesn't exist.
     */
    fun getOrCreateUserId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var userId = prefs.getString(KEY_USER_ID, null)
        if (userId == null) {
            userId = UUID.randomUUID().toString().replace("-", "")
            prefs.edit { putString(KEY_USER_ID, userId) }
        }
        return userId
    }
    
    fun logInfo(message: String) {
        if (enableLogging) {
            Log.i(TAG, message)
        }
    }

    fun logDebug(message: String) {
        if (enableLogging) {
            Log.d(TAG, message)
        }
    }

    fun logWarning(message: String) {
        if (enableLogging) {
            Log.w(TAG, message)
        }
    }

    /**
     * Helper to get GPU info by creating a temporary off-screen EGL context.
     * Safe to call from background threads.
     */
    internal fun getGpuInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        try {
            val egl = EGLContext.getEGL() as EGL10
            val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            egl.eglInitialize(display, version)

            val configAttribs = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_NONE
            )
            val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            egl.eglChooseConfig(display, configAttribs, configs, 1, numConfigs)

            if (numConfigs[0] > 0) {
                val contextAttribs = intArrayOf(0x3098, 2, EGL10.EGL_NONE) // EGL_CONTEXT_CLIENT_VERSION
                val context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, contextAttribs)

                val pbufferAttribs = intArrayOf(EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE)
                val surface = egl.eglCreatePbufferSurface(display, configs[0], pbufferAttribs)

                egl.eglMakeCurrent(display, surface, surface, context)

                val gl = context.gl as GL10
                info["renderer"] = gl.glGetString(GL10.GL_RENDERER) ?: "Unknown"
                info["vendor"] = gl.glGetString(GL10.GL_VENDOR) ?: "Unknown"

                egl.eglDestroySurface(display, surface)
                egl.eglDestroyContext(display, context)
                egl.eglTerminate(display)
            }
        } catch (e: Exception) {
            // Fail silently
        }
        return info
    }
}
