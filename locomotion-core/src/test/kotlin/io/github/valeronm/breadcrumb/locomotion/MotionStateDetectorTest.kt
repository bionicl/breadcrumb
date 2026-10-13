package io.github.valeronm.breadcrumb.locomotion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionStateDetectorTest {
    @Test fun `messy gps and calm phone confirm a stationary state only after sixty seconds`() {
        val detector = MotionStateDetector()
        val estimates = (1..13).map { index ->
            detector.update(
                evidence(
                    index = index,
                    displacementMeters = 35.0 + (index % 3) * 7,
                    accuracyMeters = 200.0,
                    accelerationStdDev = 0.05,
                    activity = CoarseActivity.STILL,
                ),
            )
        }

        assertTrue(estimates.take(12).all { it.state == MotionState.UNCERTAIN })
        assertEquals(MotionState.STATIONARY, estimates.last().state)
        assertTrue(estimates.last().reasonCode.endsWith(".confirmed"))
    }

    @Test fun `walking evidence confirms movement after candidate time not rolling window history`() {
        val detector = MotionStateDetector()
        val estimates = (1..3).map { index ->
            detector.update(
                evidence(
                    index = index,
                    displacementMeters = 3.0,
                    accuracyMeters = 100.0,
                    cadenceHz = 1.5,
                    accelerationStdDev = 0.5,
                    activity = CoarseActivity.WALKING,
                ),
            )
        }

        assertTrue(estimates.take(2).all { it.state == MotionState.UNCERTAIN })
        assertEquals(MotionState.MOVING, estimates.last().state)
    }

    @Test fun `calm phone inside a vehicle does not confirm stationary`() {
        val detector = MotionStateDetector()
        val estimates = (1..20).map { index ->
            detector.update(
                evidence(
                    index = index,
                    displacementMeters = 5.0,
                    accuracyMeters = 180.0,
                    accelerationStdDev = 0.04,
                    activity = CoarseActivity.IN_VEHICLE,
                    activityConfidence = 95,
                ),
            )
        }

        assertTrue(estimates.all { it.state != MotionState.STATIONARY })
        assertEquals(MotionState.UNCERTAIN, estimates.last().state)
    }

    @Test fun `speed without speed accuracy is not treated as movement evidence`() {
        val detector = MotionStateDetector()
        val result = detector.update(
            evidence(
                index = 1,
                displacementMeters = 2.0,
                accuracyMeters = 10.0,
                reportedSpeed = 15.0,
                speedAccuracy = null,
            ),
        )

        assertEquals(MotionState.UNCERTAIN, result.state)
        assertFalse(result.reasonCode.contains("reliable_speed"))
    }

    @Test fun `non monotonic windows are reported as uncertain`() {
        val detector = MotionStateDetector()
        detector.update(evidence(index = 1))
        val result = detector.update(evidence(index = 1))

        assertEquals(MotionState.UNCERTAIN, result.state)
        assertEquals("motion.non_monotonic_window", result.reasonCode)
    }

    private fun evidence(
        index: Int,
        displacementMeters: Double? = 5.0,
        accuracyMeters: Double? = 10.0,
        reportedSpeed: Double? = null,
        speedAccuracy: Double? = null,
        cadenceHz: Double? = null,
        accelerationStdDev: Double? = null,
        activity: CoarseActivity? = null,
        activityConfidence: Int? = 90,
    ) = MotionEvidence(
        windowStartElapsedRealtimeNanos = (index * WINDOW_STEP_NANOS) - WINDOW_LENGTH_NANOS,
        windowEndElapsedRealtimeNanos = index * WINDOW_STEP_NANOS,
        evidenceObservationIds = listOf("observation-$index"),
        locationDisplacementMeters = displacementMeters,
        horizontalAccuracyMeters = accuracyMeters,
        reportedSpeedMetersPerSecond = reportedSpeed,
        speedAccuracyMetersPerSecond = speedAccuracy,
        stepCadenceHz = cadenceHz,
        accelerationStdDevMetersPerSecondSquared = accelerationStdDev,
        activity = activity,
        activityConfidencePercent = activityConfidence,
    )

    private companion object {
        const val WINDOW_STEP_NANOS = 5_000_000_000L
        const val WINDOW_LENGTH_NANOS = 30_000_000_000L
    }
}
