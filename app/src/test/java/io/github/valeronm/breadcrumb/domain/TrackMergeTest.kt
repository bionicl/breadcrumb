package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TrackMergeTest {

    private fun track(id: Long, activity: String, startedAt: Long, source: String? = null) =
        TrackSummary(id, activity, startedAt, endedAt = startedAt + 1000, distanceMeters = 100.0, pointCount = 10, ignoredCount = 0, source = source, startPlaceId = null, endPlaceId = null)

    private val before = track(1, "WALKING", 0)
    private val after = track(2, "WALKING", 300_000)

    @Test fun `short same-activity interval yields a merge into the earlier track`() {
        assertEquals(
            TrackMerge.Plan(earlierId = 1, laterId = 2),
            TrackMerge.plan(before, after, intervalStart = 0, intervalEnd = 60_000), // 1 min
        )
    }

    @Test fun `an arbitrarily long stationary interval is mergeable when movement agrees`() {
        assertEquals(
            TrackMerge.Plan(earlierId = 1, laterId = 2),
            TrackMerge.plan(before, after, intervalStart = 0, intervalEnd = 2 * 60 * 60_000),
        )
    }

    @Test fun `different activities are not mergeable`() {
        val running = track(2, "RUNNING", 300_000)
        assertNull(TrackMerge.plan(before, running, intervalStart = 0, intervalEnd = 60_000))
    }

    @Test fun `direct track merge rejects tracks written by different sources`() {
        val recorded = track(1, "WALKING", 0, TrackOrigin.RECORDED.code)
        val imported = track(2, "WALKING", 300_000, TrackOrigin.IMPORTED.code)
        assertNull(TrackMerge.plan(recorded, imported, intervalStart = 0, intervalEnd = 60_000))
    }

    @Test fun `a direct manual trip merge rejects a recording or an import`() {
        val manual = track(1, "WALKING", 0, TrackOrigin.MANUAL.code)
        val recorded = track(2, "WALKING", 300_000, TrackOrigin.RECORDED.code)
        val imported = track(2, "WALKING", 300_000, TrackOrigin.IMPORTED.code)
        assertNull(TrackMerge.plan(manual, recorded, intervalStart = 0, intervalEnd = 60_000))
        assertNull(TrackMerge.plan(manual, imported, intervalStart = 0, intervalEnd = 60_000))
    }

    @Test fun `same source on both sides merges as usual`() {
        val first = track(1, "WALKING", 0, TrackOrigin.IMPORTED.code)
        val second = track(2, "WALKING", 300_000, TrackOrigin.IMPORTED.code)
        assertEquals(
            TrackMerge.Plan(earlierId = 1, laterId = 2),
            TrackMerge.plan(first, second, intervalStart = 0, intervalEnd = 60_000),
        )
    }

    @Test fun `an ongoing interval is not mergeable`() {
        assertNull(TrackMerge.plan(before, after, intervalStart = 0, intervalEnd = null))
    }

    // --- plansByAnchor: what the timeline actually asks -------------------------------

    private val neighbors = mapOf(before.id to TrackMerge.Neighbors(before, after))

    private fun stay(start: Long, end: Long) = StayDeriver.Stay(
        start = start, end = end,
        afterTrackId = before.id, clusterId = 0,
    )

    @Test fun `a short interval's plan is keyed by the track it follows`() {
        val plans = TrackMerge.plansByAnchor(listOf(stay(0, 60_000)), neighbors)
        assertEquals(TrackMerge.Plan(earlierId = 1, laterId = 2), plans[before.id])
    }

    @Test fun `a stay across midnight is judged whole, not by the short slice it renders as`() {
        // 23:58 to 00:30 — half an hour at one place, drawn as a two-minute row and a
        // thirty-minute one. Merging on the short row would fuse the tracks across the whole stop.
        val start = DAY - 2 * MINUTE
        val stay = stay(start, start + 32 * MINUTE)
        val slices = TimelineRows.slicePerDay(listOf(stay), { Clocks.both(ZoneId.of("UTC")) }, nowMs = 2 * DAY)
            .map { it.interval }
        assertEquals(2, slices.size)

        val plans = TrackMerge.plansByAnchor(listOf(stay), neighbors)
        // Every slice looks its offer up by the same anchor; judging the whole interval means each
        // carries the same merge plan, even where one rendered slice is short.
        assertEquals(TrackMerge.Plan(earlierId = 1, laterId = 2), plans[before.id])
        assertTrue(slices.all { plans[it.afterTrackId] != null })
    }

    @Test fun `an interval whose later side is missing yields no plan`() {
        // The tail interval, running into a track that is still recording.
        assertNull(TrackMerge.plansByAnchor(listOf(stay(0, 60_000)), emptyMap())[before.id])
    }

    @Test fun `auto merge accepts both inclusive boundaries`() {
        val sequence = autoSequence(gapMs = 4 * MINUTE, distanceM = 800.0)
        assertEquals(1, sequence.size)
        assertEquals(listOf(1L, 2L), sequence.single().tracks.map { it.id })
    }

    @Test fun `auto merge judges and stores the gap from exact track boundaries`() {
        // Visit derivation can adjust stay bounds; the immutable trip endpoints define the seam.
        assertEquals(1, autoSequence(gapMs = 4 * MINUTE, distanceM = 10.0, intervalOffsetMs = 1).size)
        assertTrue(autoSequence(gapMs = 4 * MINUTE + 1, distanceM = 10.0, intervalOffsetMs = -1).isEmpty())
    }

    @Test fun `auto merge rejects a gap over four minutes or 800 meters`() {
        assertTrue(autoSequence(gapMs = 4 * MINUTE + 1, distanceM = 100.0).isEmpty())
        assertTrue(autoSequence(gapMs = 4 * MINUTE, distanceM = 800.01).isEmpty())
    }

    @Test fun `auto merge rejects missing endpoints but accepts different sources`() {
        assertTrue(autoSequence(distanceM = 10.0, hasEndpoints = false).isEmpty())
        assertEquals(1, autoSequence(distanceM = 10.0, secondSource = TrackOrigin.MANUAL.code).size)
    }

    @Test fun `auto merge accepts stationary and unknown small gaps`() {
        assertEquals(1, autoSequence(gapKind = "STAY", distanceM = 10.0).size)
        assertEquals(1, autoSequence(gapKind = "GAP", distanceM = 10.0).size)
    }

    @Test fun `auto merge accepts tracks whose activity type was manually confirmed`() {
        assertEquals(1, autoSequence(distanceM = 10.0, manuallyConfirmed = true).size)
    }

    private fun autoSequence(
        gapMs: Long = MINUTE,
        distanceM: Double,
        hasEndpoints: Boolean = true,
        secondSource: String? = null,
        gapKind: String = "STAY",
        manuallyConfirmed: Boolean = false,
        intervalOffsetMs: Long = 0,
    ): List<TrackMerge.AutoSequence> {
        val first = track(1, "WALKING", 0, TrackOrigin.RECORDED.code)
            .copy(endedAt = 100_000, activityConfirmedByUser = manuallyConfirmed)
        val second = track(2, "WALKING", 100_000 + gapMs, secondSource ?: TrackOrigin.RECORDED.code)
            .copy(activityConfirmedByUser = manuallyConfirmed)
        val interval: StayDeriver.Interval = if (gapKind == "STAY") {
            StayDeriver.Stay(100_000 + intervalOffsetMs, 100_000 + gapMs + intervalOffsetMs, afterTrackId = first.id, clusterId = 0)
        } else {
            StayDeriver.Gap(100_000 + intervalOffsetMs, 100_000 + gapMs + intervalOffsetMs, StayDeriver.GapReason.MOVED_UNRECORDED,
                afterTrackId = first.id)
        }
        val p1 = Coordinate(52.0, 21.0)
        val p2 = Coordinate(52.0, 21.0)
        val endpoints = if (hasEndpoints) mapOf(
            1L to StayDeriver.TrackEnd(1, 0, 100_000, p1, p1),
            2L to StayDeriver.TrackEnd(2, 100_000 + gapMs, 200_000 + gapMs, p2, p2),
        ) else emptyMap()
        return TrackMerge.autoSequences(
            summariesNewestFirst = listOf(second, first), intervals = listOf(interval),
            endpoints = endpoints, assignedVisitAnchors = emptySet(),
            distance = DistanceFn { _, _, _, _ -> distanceM },
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
