package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaySuppressTest {

    private fun track(id: Long, activity: String, startedAt: Long) =
        TrackSummary(
            id, activity, startedAt, endedAt = startedAt + 1000, distanceMeters = 100.0,
            pointCount = 10, ignoredCount = 0, source = null, startPlaceId = null, endPlaceId = null,
        )

    private val walk = track(1, "WALKING", 0)
    private val drive = track(2, "IN_VEHICLE", 300_000)

    @Test fun `different activities yield a dissolve when merge is not offered`() {
        assertEquals(
            StaySuppress.Plan(afterTrackId = 1, start = 0),
            StaySuppress.plan(walk, drive, intervalStart = 0, intervalEnd = 60_000, mergeOffered = false),
        )
    }

    @Test fun `same activity yields no dissolve`() {
        val secondWalk = track(2, "WALKING", 300_000)
        assertNull(StaySuppress.plan(walk, secondWalk, 0, 60_000, mergeOffered = false))
    }

    @Test fun `a merge offer blocks dissolve`() {
        assertNull(StaySuppress.plan(walk, drive, 0, 60_000, mergeOffered = true))
    }

    @Test fun `dissolve is keyed by the track the stay follows`() {
        val stay = StayDeriver.Stay(start = 0, end = 60_000, afterTrackId = walk.id, clusterId = 0)
        val neighbors = mapOf(walk.id to TrackMerge.Neighbors(walk, drive))
        val plans = StaySuppress.plansByAnchor(listOf(stay), neighbors, mergePlans = emptyMap())
        assertEquals(StaySuppress.Plan(walk.id, 0), plans[walk.id])
    }
}
