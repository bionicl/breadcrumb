package io.github.valeronm.breadcrumb.data.db

import io.github.valeronm.breadcrumb.locomotion.EngineCheckpoint
import io.github.valeronm.breadcrumb.locomotion.FilterSnapshot
import io.github.valeronm.breadcrumb.locomotion.MotionState
import io.github.valeronm.breadcrumb.locomotion.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckpointMappingTest {
    @Test fun `filter checkpoint round trips projection and full covariance`() {
        val checkpoint = EngineCheckpoint(
            sessionId = "session",
            bootEpochId = "boot",
            lastObservationId = "observation",
            lastElapsedRealtimeNanos = 42L,
            lastWallTimeMs = 1_700_000_000_000L,
            motionState = MotionState.MOVING,
            recordingState = RecordingState.RECORDING,
            filter = FilterSnapshot(
                xMeters = 1.0,
                yMeters = 2.0,
                velocityXMetersPerSecond = 3.0,
                velocityYMetersPerSecond = 4.0,
                covariance = (0..15).map(Int::toDouble),
                projectionOriginLatitudeDegrees = 60.0,
                projectionOriginLongitudeDegrees = 24.0,
                lastElapsedRealtimeNanos = 42L,
            ),
            activeSegmentId = "segment",
            configurationVersion = 1,
            modelVersion = null,
            updatedAtWallTimeMs = 1_700_000_000_001L,
        )

        assertEquals(checkpoint, checkpoint.toEntity().toCore())
    }
}
