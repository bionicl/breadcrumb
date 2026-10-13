package io.github.valeronm.breadcrumb.locomotion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineCorrectionProcessorTest {
    @Test fun `confirmed transport survives inferred changes`() {
        val confirmed = correctionTransport("c1", TransportMode.TRAIN)
        val result = TimelineCorrectionProcessor.apply(segment(), listOf(confirmed))

        assertEquals(TransportMode.TRAIN, result.segment?.transportMode)
        assertEquals(TransportMode.TRAIN, result.segment?.confirmedTransportMode)
        assertEquals(listOf("c1"), result.appliedCorrectionIds)
    }

    @Test fun `confirm visit changes segment kind without inventing movement`() {
        val result = TimelineCorrectionProcessor.apply(
            segment(mode = TransportMode.CAR),
            listOf(UserCorrection.ConfirmVisit("visit", 20, "segment")),
        )

        assertEquals(SegmentKind.VISIT, result.segment?.kind)
        assertNull(result.segment?.transportMode)
        assertNull(result.segment?.confirmedTransportMode)
    }

    @Test fun `later transport confirmation turns a confirmed visit back into movement`() {
        val result = TimelineCorrectionProcessor.apply(
            segment(),
            listOf(
                UserCorrection.ConfirmVisit("visit", 10, "segment"),
                UserCorrection.ConfirmTransport("train", 20, "segment", TransportMode.TRAIN),
            ),
        )

        assertEquals(SegmentKind.MOVEMENT, result.segment?.kind)
        assertEquals(TransportMode.TRAIN, result.segment?.confirmedTransportMode)
    }

    @Test fun `locked user boundary is not replaced by later inferred boundary`() {
        val result = TimelineCorrectionProcessor.apply(
            segment(),
            listOf(UserCorrection.LockBoundary("lock", 20, "segment", BoundaryKind.START, 15)),
        )

        assertEquals(15L, result.segment?.startWallTimeMs)
        assertEquals(BoundaryOrigin.USER_CONFIRMED, result.segment?.startBoundary)
        assertTrue(result.segment?.lockedByUser == true)
    }

    @Test fun `undo restores original state and delete can be undone`() {
        val transport = correctionTransport("label", TransportMode.BUS)
        val delete = UserCorrection.DeleteSegment("delete", 30, "segment")
        val undoDelete = UserCorrection.Undo("undo-delete", 40, "segment", "delete")
        val undoLabel = UserCorrection.Undo("undo-label", 50, "segment", "label")
        val result = TimelineCorrectionProcessor.apply(segment(), listOf(transport, delete, undoDelete, undoLabel))

        assertEquals(segment(), result.segment)
        assertEquals(emptyList(), result.appliedCorrectionIds)
        assertNull(result.deletedByCorrectionId)
    }

    @Test fun `active delete suppresses segment and reports correction source`() {
        val result = TimelineCorrectionProcessor.apply(
            segment(),
            listOf(UserCorrection.DeleteSegment("delete", 30, "segment")),
        )

        assertNull(result.segment)
        assertEquals("delete", result.deletedByCorrectionId)
    }

    private fun correctionTransport(id: String, mode: TransportMode) =
        UserCorrection.ConfirmTransport(id, 20, "segment", mode)

    private fun segment(mode: TransportMode? = null) = TimelineSegment(
        id = "segment",
        kind = SegmentKind.MOVEMENT,
        startWallTimeMs = 10,
        endWallTimeMs = 100,
        transportMode = mode,
    )
}
