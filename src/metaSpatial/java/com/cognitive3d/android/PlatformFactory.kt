package com.cognitive3d.android

import android.app.Activity

object PlatformFactory {
    fun create(activity: Activity): PlatformProvider = MetaQuestPlatformProvider()
}
