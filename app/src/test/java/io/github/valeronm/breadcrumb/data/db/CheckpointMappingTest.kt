package io.github.valeronm.breadcrumb.data.db

import io.github.valeronm.breadcrumb.locomotion.EngineCheckpoint
import io.github.valeronm.breadcrumb.locomotion.LocationMeasurement
import io.github.valeronm.breadcrumb.locomotion.LocomotionEngine
import io.github.valeronm.breadcrumb.locomotion.MotionState
import io.github.valeronm.breadcrumb.locomotion.Observation
import io.github.valeronm.breadcrumb.locomotion.ObservationSource
import io.github.valeronm.breadcrumb.locomotion.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckpointMappingTest {
    @Test fun `filter checkpoint round trips projection and full covariance`() {
        val engine = LocomotionEngine()
        engine.process(
            Observation(
                id = "engine-observation",
                sessionId = "session",
                bootEpochId = "boot",
                wallTimeMs = 1_700_000_000_000L,
                elapsedRealtimeNanos = 42L,
                source = ObservationSource.RAW_GNSS,
                location = LocationMeasurement(60.0, 24.0, 5.0),
            ),
        )
        val engineSnapshot = engine.snapshot()
        val checkpoint = EngineCheckpoint(
            sessionId = "session",
            bootEpochId = "boot",
            lastObservationId = "observation",
            lastElapsedRealtimeNanos = 42L,
            lastWallTimeMs = 1_700_000_000_000L,
            motionState = MotionState.MOVING,
            recordingState = RecordingState.RECORDING,
            filter = engineSnapshot.filter,
            activeSegmentId = "segment",
            configurationVersion = 1,
            modelVersion = null,
            updatedAtWallTimeMs = 1_700_000_000_001L,
            engineSnapshot = engineSnapshot,
        )

        assertEquals(checkpoint, checkpoint.toEntity().toCore())
    }
}
