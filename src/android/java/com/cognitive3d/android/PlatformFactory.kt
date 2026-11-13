package com.cognitive3d.android

import android.app.Activity

object PlatformFactory {
    // No 3D scene graph on plain Android, so Dynamic Object registration is skipped (see NoDynamicObjectProvider)
    val supportsDynamicObjects: Boolean = false

    fun create(activity: Activity): PlatformProvider = AndroidPlatformProvider()
}
