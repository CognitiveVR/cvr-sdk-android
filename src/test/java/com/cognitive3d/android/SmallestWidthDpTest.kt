package com.cognitive3d.android

import org.junit.Assert.assertEquals
import org.junit.Test

class SmallestWidthDpTest {
    @Test
    fun portraitAndLandscapeGiveSameValue() {
        assertEquals(
            smallestWidthDp(1080, 2400, 420),
            smallestWidthDp(2400, 1080, 420)
        )
    }

    @Test
    fun typicalPhone() {
        // 1080 * 160 / 420 = 411.43
        assertEquals(411, smallestWidthDp(1080, 2400, 420))
    }

    @Test
    fun typicalTablet() {
        // 1600 * 160 / 320 = 800
        assertEquals(800, smallestWidthDp(1600, 2560, 320))
    }

    @Test
    fun truncatesInsteadOfRoundingUp() {
        // 1080 * 160 / 440 = 392.73, which would round to 393
        assertEquals(392, smallestWidthDp(1080, 2400, 440))
    }

    @Test
    fun zeroWidthGivesZero() {
        assertEquals(0, smallestWidthDp(0, 2400, 420))
    }

    @Test
    fun negativeWidthGivesZero() {
        assertEquals(0, smallestWidthDp(-1080, 2400, 420))
    }

    @Test
    fun zeroHeightGivesZero() {
        assertEquals(0, smallestWidthDp(1080, 0, 420))
    }

    @Test
    fun negativeHeightGivesZero() {
        assertEquals(0, smallestWidthDp(1080, -2400, 420))
    }

    @Test
    fun zeroDensityGivesZero() {
        assertEquals(0, smallestWidthDp(1080, 2400, 0))
    }

    @Test
    fun negativeDensityGivesZero() {
        assertEquals(0, smallestWidthDp(1080, 2400, -420))
    }
}
