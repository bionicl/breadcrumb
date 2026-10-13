package io.github.valeronm.breadcrumb.locomotion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveCapturePolicyTest {
    private val policy = AdaptiveCapturePolicy()

    @Test fun `idle profile uses low power location and keeps wake sensors`() {
        val plan = policy.plan(CapturePolicyInput(RecordingState.IDLE, MotionState.STATIONARY))

        assertFalse(plan.demands.getValue(EngineResource.GPS).enabled)
        assertEquals(60_000L, plan.demands.getValue(EngineResource.FUSED_LOCATION).intervalMillis)
        assertTrue(plan.demands.getValue(EngineResource.ACTIVITY_RECOGNITION).enabled)
        assertEquals(0, plan.requestedAccelerometerPeriodMicros)
    }

    @Test fun `departure verification probes frequently before activating full sensor rate`() {
        val plan = policy.plan(CapturePolicyInput(RecordingState.VERIFYING_DEPARTURE, MotionState.UNCERTAIN))

        assertEquals(1_000L, plan.demands.getValue(EngineResource.GPS).intervalMillis)
        assertEquals(100L, plan.demands.getValue(EngineResource.ACCELEROMETER).intervalMillis)
        assertEquals(100_000, plan.requestedAccelerometerPeriodMicros)
    }

    @Test fun `moving cadence adapts to transport family and selected energy profile`() {
        val balancedVehicle = policy.plan(
            CapturePolicyInput(RecordingState.RECORDING, MotionState.MOVING, CoarseActivity.IN_VEHICLE),
        )
        val batteryWalking = policy.plan(
            CapturePolicyInput(
                RecordingState.RECORDING,
                MotionState.MOVING,
                CoarseActivity.WALKING,
                CaptureProfile.BATTERY_SAVER,
            ),
        )

        assertEquals(2_000L, balancedVehicle.demands.getValue(EngineResource.GPS).intervalMillis)
        assertEquals(10_000L, batteryWalking.demands.getValue(EngineResource.GPS).intervalMillis)
        assertEquals(40_000, balancedVehicle.requestedAccelerometerPeriodMicros)
    }

    @Test fun `only resource demand changes become effects`() {
        val before = policy.plan(CapturePolicyInput(RecordingState.IDLE, MotionState.STATIONARY))
        val after = policy.plan(CapturePolicyInput(RecordingState.RECORDING, MotionState.MOVING))
        val first = policy.effects(before, after) { "effect-${it.name}" }
        val second = policy.effects(after, after) { "effect-${it.name}" }

        assertTrue(first.any { it.resource == EngineResource.GPS && it.enabled })
        assertTrue(first.any { it.resource == EngineResource.FUSED_LOCATION && !it.enabled })
        assertTrue(first.any { it.resource == EngineResource.ACCELEROMETER && it.intervalMillis == 40L })
        assertTrue(second.isEmpty())
    }
}
