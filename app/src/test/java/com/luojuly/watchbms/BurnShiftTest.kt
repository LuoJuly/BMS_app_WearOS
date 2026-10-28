package com.luojuly.watchbms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BurnShiftTest {
    @Test
    fun originSitsAtTheCenter() {
        val (x, y) = burnOffset(burnOriginStep())
        assertEquals(0, x)
        assertEquals(0, y)
    }

    @Test
    fun walkStaysInsideTwoPixelsAndStepsOnePixelAtATime() {
        val seen = mutableSetOf<Pair<Int, Int>>()
        var step = burnOriginStep()
        var dir = 1
        seen += burnOffset(step)
        repeat(80) {
            val previous = burnOffset(step)
            val next = nextBurnStep(step, dir)
            step = next.first
            dir = next.second
            val offset = burnOffset(step)
            seen += offset
            assertTrue(abs(offset.first) <= BURN_RADIUS)
            assertTrue(abs(offset.second) <= BURN_RADIUS)
            assertEquals(1, abs(offset.first - previous.first) + abs(offset.second - previous.second))
        }
        assertEquals(25, seen.size)
    }
}
