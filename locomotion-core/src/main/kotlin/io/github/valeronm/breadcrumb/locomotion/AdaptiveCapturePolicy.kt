package io.github.valeronm.breadcrumb.locomotion

/** User-facing balance; Android still reports the delivery rate the platform actually grants. */
enum class CaptureProfile { BALANCED, BATTERY_SAVER, COMPLETE }

data class CapturePolicyInput(
    val recordingState: RecordingState,
    val motionState: MotionState,
    val coarseActivity: CoarseActivity? = null,
    val profile: CaptureProfile = CaptureProfile.BALANCED,
)

data class ResourceDemand(
    val enabled: Boolean,
    /** Desired minimum interval. Null means the platform default or event-driven registration. */
    val intervalMillis: Long? = null,
)

data class CapturePlan(
    val demands: Map<EngineResource, ResourceDemand>,
    val requestedAccelerometerPeriodMicros: Int,
    val reasonCode: String,
) {
    init { require(requestedAccelerometerPeriodMicros >= 0) }
}

/** Pure resource policy (LocoKit2 LK-05); effects express requests, never successful registration. */
class AdaptiveCapturePolicy {
    fun plan(input: CapturePolicyInput): CapturePlan {
        val requests = when (input.recordingState) {
            RecordingState.IDLE -> idleDemands(input.profile)
            RecordingState.VERIFYING_DEPARTURE -> departureDemands(input.profile)
            RecordingState.RECORDING -> movingDemands(input)
            RecordingState.VERIFYING_ARRIVAL -> arrivalDemands(input.profile)
            RecordingState.DEGRADED -> degradedDemands(input.profile)
        }
        return CapturePlan(
            demands = EngineResource.entries.associateWith { requests[it] ?: ResourceDemand(enabled = false) },
            requestedAccelerometerPeriodMicros = when {
                EngineResource.ACCELEROMETER !in requests -> 0
                input.recordingState == RecordingState.VERIFYING_DEPARTURE -> REQUESTED_PROBE_SENSOR_PERIOD_MICROS
                else -> REQUESTED_ACTIVE_SENSOR_PERIOD_MICROS
            },
            reasonCode = when (input.recordingState) {
                RecordingState.IDLE -> "capture.idle_low_power"
                RecordingState.VERIFYING_DEPARTURE -> "capture.verify_departure"
                RecordingState.RECORDING -> "capture.active_movement"
                RecordingState.VERIFYING_ARRIVAL -> "capture.verify_arrival"
                RecordingState.DEGRADED -> "capture.degraded_recovery"
            },
        )
    }

    fun effects(previous: CapturePlan?, next: CapturePlan, effectId: (EngineResource) -> String): List<EngineEffect.RequestResource> =
        next.demands.mapNotNull { (resource, demand) ->
            val old = previous?.demands?.get(resource)
            if (old == demand) return@mapNotNull null
            EngineEffect.RequestResource(
                id = effectId(resource),
                resource = resource,
                enabled = demand.enabled,
                intervalMillis = demand.intervalMillis,
            )
        }

    private fun idleDemands(profile: CaptureProfile) = mapOf(
        EngineResource.FUSED_LOCATION to ResourceDemand(true, idleInterval(profile)),
        EngineResource.ACTIVITY_RECOGNITION to ResourceDemand(true),
        EngineResource.STEP_COUNTER to ResourceDemand(true),
    )

    private fun departureDemands(profile: CaptureProfile) = mapOf(
        EngineResource.GPS to ResourceDemand(true, probeInterval(profile)),
        EngineResource.ACTIVITY_RECOGNITION to ResourceDemand(true),
        EngineResource.ACCELEROMETER to ResourceDemand(true, 100L),
        EngineResource.STEP_COUNTER to ResourceDemand(true),
    )

    private fun movingDemands(input: CapturePolicyInput) = mapOf(
        EngineResource.GPS to ResourceDemand(true, movingInterval(input.coarseActivity, input.profile)),
        EngineResource.ACTIVITY_RECOGNITION to ResourceDemand(true),
        EngineResource.ACCELEROMETER to ResourceDemand(true, 40L),
        EngineResource.STEP_COUNTER to ResourceDemand(true),
    )

    private fun arrivalDemands(profile: CaptureProfile) = mapOf(
        EngineResource.GPS to ResourceDemand(true, arrivalInterval(profile)),
        EngineResource.ACTIVITY_RECOGNITION to ResourceDemand(true),
        EngineResource.ACCELEROMETER to ResourceDemand(true, 40L),
        EngineResource.STEP_COUNTER to ResourceDemand(true),
    )

    private fun degradedDemands(profile: CaptureProfile) = mapOf(
        EngineResource.FUSED_LOCATION to ResourceDemand(true, degradedInterval(profile)),
        EngineResource.ACTIVITY_RECOGNITION to ResourceDemand(true),
        EngineResource.STEP_COUNTER to ResourceDemand(true),
    )

    private fun idleInterval(profile: CaptureProfile) = when (profile) {
        CaptureProfile.BATTERY_SAVER -> 120_000L
        CaptureProfile.BALANCED -> 60_000L
        CaptureProfile.COMPLETE -> 30_000L
    }

    private fun probeInterval(profile: CaptureProfile) = when (profile) {
        CaptureProfile.BATTERY_SAVER -> 2_000L
        CaptureProfile.BALANCED -> 1_000L
        CaptureProfile.COMPLETE -> 1_000L
    }

    private fun movingInterval(activity: CoarseActivity?, profile: CaptureProfile) = when (profile) {
        CaptureProfile.BATTERY_SAVER -> if (activity in VEHICLE_ACTIVITIES) 5_000L else 10_000L
        CaptureProfile.BALANCED -> if (activity in VEHICLE_ACTIVITIES) 2_000L else 5_000L
        CaptureProfile.COMPLETE -> if (activity in VEHICLE_ACTIVITIES) 1_000L else 2_000L
    }

    private fun arrivalInterval(profile: CaptureProfile) = when (profile) {
        CaptureProfile.BATTERY_SAVER -> 10_000L
        CaptureProfile.BALANCED -> 5_000L
        CaptureProfile.COMPLETE -> 2_000L
    }

    private fun degradedInterval(profile: CaptureProfile) = when (profile) {
        CaptureProfile.BATTERY_SAVER -> 120_000L
        CaptureProfile.BALANCED -> 60_000L
        CaptureProfile.COMPLETE -> 30_000L
    }

    companion object {
        private val VEHICLE_ACTIVITIES = setOf(CoarseActivity.IN_VEHICLE, CoarseActivity.ON_BICYCLE)
        private const val REQUESTED_ACTIVE_SENSOR_PERIOD_MICROS = 40_000
        private const val REQUESTED_PROBE_SENSOR_PERIOD_MICROS = 100_000
    }
}
