package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.locomotion.BoundaryKind
import io.github.valeronm.breadcrumb.locomotion.TransportMode
import io.github.valeronm.breadcrumb.locomotion.UserCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTimelineCorrectionAdapterTest {
    @Test fun `confirmed transport and boundary locks shape the projected timeline row`() {
        val track = track()
        val id = engineTrackSegmentId(track.id)
        val corrections = listOf(
            UserCorrection.ConfirmTransport("label", 10, id, TransportMode.BUS),
            UserCorrection.LockBoundary("start", 11, id, BoundaryKind.START, 900),
            UserCorrection.LockBoundary("end", 12, id, BoundaryKind.END, 1900),
        )

        val projected = EngineTimelineCorrectionAdapter.apply(track, corrections)!!

        assertEquals(ActivityType.BUS.name, projected.activityType)
        assertEquals(900L, projected.startedAt)
        assertEquals(1900L, projected.endedAt)
        assertTrue(projected.activityConfirmedByUser)
    }

    @Test fun `visit confirmation and an undone deletion are respected`() {
        val track = track()
        val id = engineTrackSegmentId(track.id)
        val visit = EngineTimelineCorrectionAdapter.apply(
            track,
            listOf(UserCorrection.ConfirmVisit("visit", 10, id)),
        )!!
        assertEquals(ActivityType.STILL.name, visit.activityType)
        assertTrue(visit.activityConfirmedByUser)

        val restored = EngineTimelineCorrectionAdapter.apply(
            track,
            listOf(
                UserCorrection.DeleteSegment("delete", 20, id),
                UserCorrection.Undo("undo", 30, id, "delete"),
            ),
        )
        assertEquals(track, restored)
        assertNull(
            EngineTimelineCorrectionAdapter.apply(
                track,
                listOf(UserCorrection.DeleteSegment("delete", 20, id)),
            ),
        )
    }

    @Test fun `uncorrected legacy activity and geometry remain untouched`() {
        val track = track(activityType = "OLD_LEGACY_MODE")

        assertEquals(track, EngineTimelineCorrectionAdapter.apply(track, emptyList()))
    }

    private fun track(activityType: String = ActivityType.WALKING.name) = TrackSummary(
        id = 41,
        activityType = activityType,
        startedAt = 1000,
        endedAt = 2000,
        distanceMeters = 500.0,
        pointCount = 20,
        ignoredCount = 0,
        source = "RECORDED",
        startPlaceId = null,
        endPlaceId = null,
    )
}
