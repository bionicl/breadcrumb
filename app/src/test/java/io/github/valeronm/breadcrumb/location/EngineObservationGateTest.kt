package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.locomotion.LocationMeasurement
import io.github.valeronm.breadcrumb.locomotion.LocomotionEngine
import io.github.valeronm.breadcrumb.locomotion.Observation
import io.github.valeronm.breadcrumb.locomotion.ObservationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EngineObservationGateTest {
    @Test fun `classic session leaves analysis engine untouched`() {
        val engine = LocomotionEngine()
        val gate = EngineObservationGate(engine)

        assertNull(gate.process(Settings.RecorderEngineGeneration.CLASSIC, observation("classic")))
        assertNull(engine.snapshot().bootEpochId)
        assertEquals(0, engine.diagnosticSnapshot().size)
    }

    @Test fun `v2 session processes one observation from existing recorder stream`() {
        val engine = LocomotionEngine()
        val gate = EngineObservationGate(engine)
        val input = observation("v2")

        val result = gate.process(Settings.RecorderEngineGeneration.LOCOMOTION_V2, input)

        assertNotNull(result)
        assertEquals("v2", engine.snapshot().lastProcessedObservationId)
        assertEquals("boot", engine.snapshot().bootEpochId)
    }

    private fun observation(id: String) = Observation(
        id = id,
        sessionId = "session",
        bootEpochId = "boot",
        wallTimeMs = 1_700_000_000_000L,
        elapsedRealtimeNanos = 1_000_000_000L,
        source = ObservationSource.RAW_GNSS,
        location = LocationMeasurement(60.0, 24.0, 5.0),
    )
}
