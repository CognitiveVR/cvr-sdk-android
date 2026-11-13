package com.cognitive3d.android

import android.app.Activity

object PlatformFactory {
    val supportsDynamicObjects: Boolean = true

    fun create(activity: Activity): PlatformProvider = MetaQuestPlatformProvider()
}
