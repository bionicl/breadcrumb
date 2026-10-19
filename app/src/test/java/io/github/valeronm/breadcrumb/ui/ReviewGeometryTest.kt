package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewGeometryTest {
    private fun point(index: Int, newSegment: Boolean = false, ignored: Boolean = false) = TrackPoint(
        trackId = 1,
        timestamp = index * 10_000L,
        latitude = 52.25 + index * 0.001,
        longitude = 21.04,
        altitude = null,
        accuracy = 5f,
        speed = null,
        bearing = null,
        segmentStart = newSegment,
        ignored = ignored,
    )

    @Test fun `missing recording is never rendered as a measured connector`() {
        val points = listOf(point(0), point(1), point(2, newSegment = true), point(3))
        assertEquals(listOf(points.take(2), points.drop(2)), reviewPathRuns(points))
        assertEquals(listOf(points[1] to points[2]), reviewGapPairs(points))
    }

    @Test fun `separate arrival and departure paths never share a connecting run`() {
        val arrival = listOf(point(0), point(1))
        val departure = listOf(point(5), point(6))
        assertEquals(listOf(arrival, departure), reviewPathRuns(arrival) + reviewPathRuns(departure))
        assertTrue(reviewGapPairs(arrival).isEmpty())
        assertTrue(reviewGapPairs(departure).isEmpty())
    }

    @Test fun `unusable fixes and isolated segments add no invented geometry`() {
        val points = listOf(point(0), point(1, ignored = true), point(2, newSegment = true))
        assertTrue(reviewPathRuns(points).isEmpty())
        assertEquals(listOf(points[0] to points[2]), reviewGapPairs(points))
    }
}
