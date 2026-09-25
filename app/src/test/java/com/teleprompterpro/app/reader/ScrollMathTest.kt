package com.teleprompterpro.app.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollMathTest {

    @Test
    fun `duration is words over wpm`() {
        // 300 words at 150 wpm = 2 minutes
        assertEquals(120_000L, ScrollMath.totalDurationMs(300, 150))
    }

    @Test
    fun `remaining and elapsed always sum to total`() {
        for (p in listOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
            val total = ScrollMath.totalDurationMs(777, 133)
            val rem = ScrollMath.remainingMs(777, 133, p)
            val el = ScrollMath.elapsedMs(777, 133, p)
            assertEquals(total, rem + el)
        }
    }

    @Test
    fun `eta halves at half progress`() {
        assertEquals(60_000L, ScrollMath.remainingMs(300, 150, 0.5f))
    }

    @Test
    fun `wpm for duration round-trips`() {
        val wpm = ScrollMath.wpmForDuration(300, 120_000L)
        assertEquals(150, wpm)
        assertEquals(120_000L, ScrollMath.totalDurationMs(300, wpm))
    }

    @Test
    fun `advance reaches exactly one after the full duration`() {
        var p = 0f
        val words = 200; val wpm = 100 // 2 minutes
        repeat(120) { p = ScrollMath.advance(p, 1000L, words, wpm) }
        assertEquals(1f, p, 0.0001f)
    }

    @Test
    fun `changing wpm mid-read keeps progress and recomputes eta`() {
        val p = 0.5f
        assertEquals(60_000L, ScrollMath.remainingMs(300, 150, p))
        assertEquals(30_000L, ScrollMath.remainingMs(300, 300, p))
    }

    @Test
    fun `clock formatting`() {
        assertEquals("0:00", ScrollMath.clock(0))
        assertEquals("1:05", ScrollMath.clock(65_000))
        assertEquals("1:00:00", ScrollMath.clock(3_600_000))
        assertEquals("0:00", ScrollMath.clock(-5))
    }

    @Test
    fun `empty script has zero duration and completes immediately`() {
        assertEquals(0L, ScrollMath.totalDurationMs(0, 150))
        assertEquals(1f, ScrollMath.advance(0f, 16L, 0, 150), 0f)
    }
}
