package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The smoother is fed the series the graph plots, so the fixtures are timestamps and values: the
 * coordinates never matter to it and sit at the domain tests' neutral origin. Runs at the shipping
 * [MetricSmoother.WINDOW_MS] except where the window's width is the point of the case.
 */
class MetricSmootherTest {

    private fun pt(t: Long) = TrackPoint(
        trackId = 1,
        latitude = 1.0,
        longitude = 0.0,
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = t,
    )

    private fun smooth(points: List<TrackPoint>, values: List<Float?>, breaks: Set<Int> = emptySet()) =
        MetricSmoother.timeAveraged(
            points,
            values,
            BooleanArray(points.size) { it in breaks },
        )

    /** Points every [everyMs] from 0, one per value. */
    private fun series(vararg values: Float?, everyMs: Long = 5_000L): Pair<List<TrackPoint>, List<Float?>> =
        values.indices.map { pt(it * everyMs) } to values.toList()

    @Test
    fun `a spike is pulled toward the pace around it`() {
        val (points, values) = series(50f, 50f, 90f, 50f, 50f)
        val out = smooth(points, values)
        // At 5 s spacing the window holds one neighbour either side: (50+90+50)/3.
        // Down from the 90 it was recorded at, and not flattened into the 50 around it.
        assertEquals(63.33f, out[2]!!, 0.01f)
    }

    @Test
    fun `a steady pace comes back unchanged`() {
        val (points, values) = series(40f, 40f, 40f, 40f, 40f)
        smooth(points, values).forEach { assertEquals(40f, it!!, 0.001f) }
    }

    @Test
    fun `the window is real time, so the same fixes smooth differently at different cadences`() {
        val (densePoints, denseValues) = series(50f, 50f, 90f, 50f, 50f, everyMs = 1_000L)
        val (sparsePoints, sparseValues) = series(50f, 50f, 90f, 50f, 50f, everyMs = 60_000L)
        assertEquals(
            "a fix a second apart has every neighbour in the window",
            58f,
            smooth(densePoints, denseValues)[2]!!,
            0.01f,
        )
        assertEquals(
            "a fix a minute apart has none, so the spike stands",
            90f,
            smooth(sparsePoints, sparseValues)[2]!!,
            0.01f,
        )
    }

    @Test
    fun `a break is not averaged across`() {
        val (points, values) = series(10f, 10f, 90f, 90f)
        val out = smooth(points, values, breaks = setOf(2))
        assertEquals(10f, out[1]!!, 0.01f)
        assertEquals(90f, out[2]!!, 0.01f)
    }

    @Test
    fun `a fix beside a break keeps its own value rather than the side behind it`() {
        val (points, values) = series(40f, 30f, 20f, 10f, 5f, everyMs = 1_000L)
        val out = smooth(points, values)
        assertEquals(5f, out[4]!!, 0.01f)
        assertEquals((20f + 10f + 5f) / 3, out[3]!!, 0.01f)
        assertEquals(40f, out[0]!!, 0.01f)
    }

    @Test
    fun `the window reaches only as far as its shorter side`() {
        val points = listOf(pt(0), pt(6_000), pt(7_000), pt(9_000), pt(13_000))
        val values = listOf(100f, 100f, 10f, 20f, 100f)
        assertEquals((100f + 10f + 20f + 100f) / 4, smooth(points, values)[2]!!, 0.01f)
    }

    @Test
    fun `a fix without the metric stays without it, and is not averaged in`() {
        val (points, values) = series(10f, null, 30f)
        val out = smooth(points, values)
        assertNull(out[1])
        assertEquals("a null neighbour is absent, not a zero", 10f, out[0]!!, 0.01f)
        assertEquals(30f, out[2]!!, 0.01f)
    }

    @Test
    fun `an empty series is answered in kind`() {
        assertEquals(emptyList<Float?>(), smooth(emptyList(), emptyList()))
    }
}
