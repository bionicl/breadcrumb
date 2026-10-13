package io.github.valeronm.breadcrumb.locomotion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineSegmenterTest {
    @Test fun `movement changes to visit at retrospective stationary boundary`() {
        val frames = listOf(
            frame(1, MotionState.MOVING, sinceSeconds = 0),
            frame(2, MotionState.MOVING, sinceSeconds = 0),
            frame(3, MotionState.STATIONARY, sinceSeconds = 15),
        )

        val segments = TimelineSegmenter().refine(frames)

        assertEquals(listOf(SegmentKind.MOVEMENT, SegmentKind.VISIT), segments.map { it.kind })
        assertEquals(15_000L, segments[0].endWallTimeMs)
        assertEquals(15_000L, segments[1].startWallTimeMs)
        assertNull(segments[1].endWallTimeMs)
    }

    @Test fun `uncertain samples alone do not create stationary segments`() {
        val segments = TimelineSegmenter().refine(
            listOf(frame(1, MotionState.UNCERTAIN), frame(2, MotionState.UNCERTAIN)),
        )

        assertTrue(segments.isEmpty())
    }

    @Test fun `long observation absence creates an explicit data gap`() {
        val frames = listOf(
            frame(1, MotionState.MOVING, sinceSeconds = 0),
            frame(2, MotionState.MOVING, elapsedSeconds = 200, sinceSeconds = 0),
        )

        val segments = TimelineSegmenter().refine(frames)

        assertTrue(segments.any { it.kind == SegmentKind.DATA_GAP && it.startWallTimeMs == 10_000L })
        assertTrue(segments.none { it.kind == SegmentKind.MOVEMENT && it.startWallTimeMs < 1_000L && it.endWallTimeMs == null })
    }

    @Test fun `overlap refinement preserves segment id confirmed mode and user boundary`() {
        val old = TimelineSegment(
            id = "user-stable-id",
            kind = SegmentKind.MOVEMENT,
            startWallTimeMs = 0,
            endWallTimeMs = 20_000,
            transportMode = TransportMode.TRAIN,
            confirmedTransportMode = TransportMode.TRAIN,
            startBoundary = BoundaryOrigin.USER_CONFIRMED,
            lockedByUser = true,
        )
        val refined = TimelineSegmenter().refine(
            listOf(frame(1, MotionState.MOVING, sinceSeconds = 2), frame(2, MotionState.MOVING, sinceSeconds = 2)),
            previousSegments = listOf(old),
        ).single()

        assertEquals("user-stable-id", refined.id)
        assertEquals(0L, refined.startWallTimeMs)
        assertEquals(TransportMode.TRAIN, refined.confirmedTransportMode)
        assertEquals(BoundaryOrigin.USER_CONFIRMED, refined.startBoundary)
        assertTrue(refined.lockedByUser)
    }

    private fun frame(
        id: Int,
        state: MotionState,
        elapsedSeconds: Long = id * 10L,
        sinceSeconds: Long = elapsedSeconds,
    ): TimelineFrame {
        val elapsed = elapsedSeconds * SECOND
        val observation = Observation(
            id = "obs-$id",
            sessionId = "s",
            bootEpochId = "b",
            wallTimeMs = elapsed / 1_000_000,
            elapsedRealtimeNanos = elapsed,
            source = ObservationSource.RAW_GNSS,
        )
        return TimelineFrame(
            observation,
            MotionEstimate(
                state = state,
                confidence = 0.8f,
                sinceElapsedRealtimeNanos = sinceSeconds * SECOND,
                evidence = listOf(EvidenceRef(observation.id, "test")),
                reasonCode = "test",
            ),
        )
    }

    private companion object { const val SECOND = 1_000_000_000L }
}
