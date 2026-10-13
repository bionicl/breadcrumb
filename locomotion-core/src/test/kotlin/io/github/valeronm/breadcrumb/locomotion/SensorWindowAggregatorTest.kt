package io.github.valeronm.breadcrumb.locomotion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensorWindowAggregatorTest {
    @Test fun `emits complete rolling windows on the configured stride`() {
        val aggregator = SensorWindowAggregator(setOf(SensorFeature.ACCELEROMETER, SensorFeature.STEP_COUNTER))
        val first = 1_000_000_000L

        assertNull(aggregator.add(reading(first, steps = 10)))
        var result: SensorWindow? = null
        for (second in 1..35) {
            result = aggregator.add(reading(first + second * SECOND, steps = 10L + second.toLong())) ?: result
        }

        assertEquals(first + 35 * SECOND, result?.endElapsedRealtimeNanos)
        assertEquals(1.0, result?.stepCadenceHz ?: 0.0, 0.15)
        assertFalse(SensorFeature.ACCELEROMETER in result?.missingFeatures.orEmpty())
    }

    @Test fun `rotating the phone does not change the horizontal magnitude`() {
        val first = SensorWindowAggregator(setOf(SensorFeature.ACCELEROMETER, SensorFeature.GRAVITY))
        val rotated = SensorWindowAggregator(setOf(SensorFeature.ACCELEROMETER, SensorFeature.GRAVITY))
        var a: SensorWindow? = null
        var b: SensorWindow? = null
        for (second in 0L..30L) {
            a = first.add(reading(second * SECOND, ax = 3.0, ay = 4.0, az = 12.0, gx = 0.0, gy = 0.0, gz = 9.8)) ?: a
            b = rotated.add(reading(second * SECOND, ax = 4.0, ay = 12.0, az = 3.0, gx = 0.0, gy = 9.8, gz = 0.0)) ?: b
        }

        assertEquals(a?.accelerationHorizontalMeanMetersPerSecondSquared, b?.accelerationHorizontalMeanMetersPerSecondSquared)
        assertEquals(a?.accelerationVerticalMeanMetersPerSecondSquared, b?.accelerationVerticalMeanMetersPerSecondSquared)
    }

    @Test fun `missing sensors stay null and late callbacks do not rewind the window`() {
        val aggregator = SensorWindowAggregator(setOf(SensorFeature.ACCELEROMETER, SensorFeature.STEP_COUNTER))
        assertNull(aggregator.add(InertialReading(2 * SECOND)))
        assertNull(aggregator.add(InertialReading(SECOND)))
        var result: SensorWindow? = null
        for (second in 3L..33L) result = aggregator.add(InertialReading(second * SECOND)) ?: result

        assertNull(result?.accelerationHorizontalMeanMetersPerSecondSquared)
        assertNull(result?.stepCadenceHz)
        assertTrue(SensorFeature.ACCELEROMETER in result?.missingFeatures.orEmpty())
        assertTrue(SensorFeature.STEP_COUNTER in result?.missingFeatures.orEmpty())
    }

    private fun reading(
        elapsed: Long,
        steps: Long = 0L,
        ax: Double = 0.0,
        ay: Double = 0.0,
        az: Double = 0.0,
        gx: Double? = 0.0,
        gy: Double? = 0.0,
        gz: Double? = 9.8,
    ) = InertialReading(
        elapsedRealtimeNanos = elapsed,
        linearAccelerationXMetersPerSecondSquared = ax,
        linearAccelerationYMetersPerSecondSquared = ay,
        linearAccelerationZMetersPerSecondSquared = az,
        gravityXMetersPerSecondSquared = gx,
        gravityYMetersPerSecondSquared = gy,
        gravityZMetersPerSecondSquared = gz,
        cumulativeSteps = steps,
    )

    companion object { const val SECOND = 1_000_000_000L }
}
