package io.github.valeronm.breadcrumb.locomotion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationKalmanFilterTest {
    @Test fun `missing speed is not inserted as a zero velocity measurement`() {
        val filter = LocationKalmanFilter()
        val result = filter.update(LocationMeasurement(1.0, -2.0, 8.0), 1_000_000_000L)

        assertTrue(result.accepted)
        assertFalse(result.speedMeasurementUsed)
        assertTrue((result.velocityUncertaintyMetersPerSecond ?: 0.0) > 5.0)
        assertNotNull(result.filtered)
    }

    @Test fun `rejects a large gps jump while preserving the raw fix and filtered path`() {
        val filter = LocationKalmanFilter()
        filter.update(LocationMeasurement(1.0, -2.0, 5.0), 1_000_000_000L)
        val result = filter.update(LocationMeasurement(1.0, -1.0, 5.0), 2_000_000_000L)

        assertFalse(result.accepted)
        assertEquals("location.innovation_outlier", result.reasonCode)
        assertEquals(-1.0, result.raw.longitudeDegrees)
        assertNotNull(result.filtered)
    }

    @Test fun `position without accuracy cannot initialize a filter`() {
        val filter = LocationKalmanFilter()
        val result = filter.update(LocationMeasurement(1.0, -2.0, null), 1_000_000_000L)

        assertFalse(result.accepted)
        assertEquals("location.missing_horizontal_accuracy", result.reasonCode)
        assertNull(filter.snapshot())
    }

    @Test fun `east north projection returns close coordinates at high latitude`() {
        val filter = LocationKalmanFilter()
        filter.update(LocationMeasurement(60.0, 24.0, 4.0), 1_000_000_000L)
        val result = filter.update(LocationMeasurement(60.0, 24.001, 4.0), 2_000_000_000L)

        assertTrue(result.accepted)
        assertNotNull(result.filtered)
        assertTrue(result.filtered.longitudeDegrees in 24.0..24.0011)
    }
}
