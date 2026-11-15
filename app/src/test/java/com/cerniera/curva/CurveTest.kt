package com.cerniera.curva

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveTest {
    @Test fun goalsBendTheLine() {
        val s = Curve.of(listOf(Goal(9, false), Goal(40, true), Goal(63, true)), null, null)
        assertTrue(s.exact)
        assertEquals(0f to 0f, s.points.first())
        assertEquals(1f, s.points.last().second)
        assertEquals(-1, s.low); assertEquals(1, s.high)
        assertEquals(90f, s.end)
    }

    @Test fun stoppageTimeStretchesTheEnd() {
        val s = Curve.of(listOf(Goal(94, true)), null, null)
        assertEquals(96f, s.end)
        assertEquals(1f, s.points.last().second)
    }

    @Test fun ownGoalsAndOrderDontMatter() {
        val s = Curve.of(listOf(Goal(70, true), Goal(10, false)), null, null)
        assertEquals(listOf(0f, 0f, -1f, -1f, 0f, 0f), s.points.map { it.second })
    }

    @Test fun withoutGoalTimesTheLineIsDashedThroughHalfAndFullTime() {
        val s = Curve.of(null, 0 to 1, 2 to 1)
        assertFalse(s.exact)
        assertEquals(listOf(0f to 0f, 45f to -1f, 90f to 1f), s.points)
    }

    @Test fun goallessDrawIsExactAndFlat() {
        val s = Curve.of(emptyList(), 0 to 0, 0 to 0)
        assertTrue(s.exact)
        assertTrue(s.points.all { it.second == 0f })
    }

    @Test fun oursFromTheAwaySide() {
        assertEquals(3 to 1, Curve.ours(1, 3, atHome = false))
        assertEquals("Won", Curve.verdict(3 to 1))
    }
}
