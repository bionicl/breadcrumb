package io.github.valeronm.breadcrumb.data.db

import io.github.valeronm.breadcrumb.locomotion.LocationMeasurement
import io.github.valeronm.breadcrumb.locomotion.Observation
import io.github.valeronm.breadcrumb.locomotion.ObservationProvenance
import io.github.valeronm.breadcrumb.locomotion.ObservationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ObservationMappingTest {
    @Test fun `unavailable velocity remains absent and imported or synthetic data is not eligible`() {
        val observation = Observation(
            id = "obs-1",
            sessionId = "session-1",
            bootEpochId = "boot-1",
            wallTimeMs = 1000,
            elapsedRealtimeNanos = 2000,
            source = ObservationSource.FUSED_LOCATION,
            location = LocationMeasurement(1.0, -2.0, 8.0),
            provenance = ObservationProvenance.SYNTHETIC,
        )

        val row = observation.toEntity()

        assertNull(row.speedMetersPerSecond)
        assertEquals("SYNTHETIC", row.provenance)
        assertFalse(row.trainingEligible)
        assertNull(row.confirmedTransportMode)
    }
}
