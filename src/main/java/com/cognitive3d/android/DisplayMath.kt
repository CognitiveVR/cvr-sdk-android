package com.cognitive3d.android

/**
 * Smaller side of a display in density-independent pixels (1 dp = 1 px at 160 dpi),
 * truncated to an integer. Returns 0 if either side or the density is not positive.
 */
internal fun smallestWidthDp(widthPixels: Int, heightPixels: Int, stableDensityDpi: Int): Int {
    val shortSidePixels = minOf(widthPixels, heightPixels)
    if (shortSidePixels <= 0 || stableDensityDpi <= 0) return 0
    return shortSidePixels * 160 / stableDensityDpi
}
