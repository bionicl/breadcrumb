package io.github.valeronm.breadcrumb.locomotion

/** Window-level evidence; raw observations stay available in persistence for later replay. */
data class MotionEvidence(
    val windowStartElapsedRealtimeNanos: Long,
    val windowEndElapsedRealtimeNanos: Long,
    val evidenceObservationIds: List<String> = emptyList(),
    val locationDisplacementMeters: Double? = null,
    val horizontalAccuracyMeters: Double? = null,
    val reportedSpeedMetersPerSecond: Double? = null,
    val speedAccuracyMetersPerSecond: Double? = null,
    val stepCadenceHz: Double? = null,
    val accelerationStdDevMetersPerSecondSquared: Double? = null,
    val activity: CoarseActivity? = null,
    val activityConfidencePercent: Int? = null,
)

/** Combines independent physical evidence with time hysteresis (LocoKit2 LK-03). */
class MotionStateDetector(
    private val stationaryConfirmationNanos: Long = STATIONARY_CONFIRMATION_NANOS,
    private val movementConfirmationNanos: Long = MOVEMENT_CONFIRMATION_NANOS,
) {
    private var lastWindowEndNanos: Long? = null
    private var confirmedState = MotionState.UNCERTAIN
    private var candidateState: MotionState? = null
    private var candidateStartNanos: Long? = null
    private var candidateCount = 0

    init {
        require(stationaryConfirmationNanos > 0)
        require(movementConfirmationNanos > 0)
    }

    @Synchronized
    fun update(evidence: MotionEvidence): MotionEstimate {
        val duration = evidence.windowEndElapsedRealtimeNanos - evidence.windowStartElapsedRealtimeNanos
        val previousEnd = lastWindowEndNanos
        if (duration <= 0 || (previousEnd != null && evidence.windowEndElapsedRealtimeNanos <= previousEnd)) {
            return estimate(
                MotionState.UNCERTAIN,
                0.0f,
                evidence,
                "motion.non_monotonic_window",
                evidence.windowEndElapsedRealtimeNanos,
            )
        }
        lastWindowEndNanos = evidence.windowEndElapsedRealtimeNanos
        val judged = judge(evidence)
        if (judged.state == MotionState.UNCERTAIN) {
            clearCandidate()
            return estimate(
                MotionState.UNCERTAIN,
                judged.confidence,
                evidence,
                judged.reason,
                evidence.windowStartElapsedRealtimeNanos,
            )
        }
        if (judged.state == confirmedState) {
            clearCandidate()
            return estimate(
                confirmedState,
                judged.confidence,
                evidence,
                judged.reason,
                evidence.windowStartElapsedRealtimeNanos,
            )
        }
        if (candidateState != judged.state) {
            candidateState = judged.state
            // Confirmation begins when the evidence is observed; the rolling window may contain older data.
            candidateStartNanos = evidence.windowEndElapsedRealtimeNanos
            candidateCount = 1
        } else {
            candidateCount++
        }
        val candidateStart = candidateStartNanos ?: evidence.windowStartElapsedRealtimeNanos
        val observedSpan = evidence.windowEndElapsedRealtimeNanos - candidateStart
        val requiredSpan = if (judged.state == MotionState.STATIONARY) {
            stationaryConfirmationNanos
        } else {
            movementConfirmationNanos
        }
        if (observedSpan >= requiredSpan && candidateCount >= MIN_CONFIRMATION_WINDOWS) {
            confirmedState = judged.state
            clearCandidate()
            return estimate(
                confirmedState,
                judged.confidence,
                evidence,
                "${judged.reason}.confirmed",
                candidateStart,
            )
        }
        val visibleState = if (confirmedState == MotionState.UNCERTAIN) MotionState.UNCERTAIN else confirmedState
        return estimate(
            visibleState,
            (judged.confidence * CANDIDATE_CONFIDENCE_FACTOR).coerceAtMost(0.65f),
            evidence,
            "${judged.reason}.candidate",
            candidateStart,
        )
    }

    @Synchronized
    fun reset() {
        lastWindowEndNanos = null
        confirmedState = MotionState.UNCERTAIN
        clearCandidate()
    }

    @Synchronized
    fun snapshot() = MotionStateDetectorSnapshot(
        lastWindowEndElapsedRealtimeNanos = lastWindowEndNanos,
        confirmedState = confirmedState,
        candidateState = candidateState,
        candidateStartElapsedRealtimeNanos = candidateStartNanos,
        candidateCount = candidateCount,
    )

    @Synchronized
    fun restore(snapshot: MotionStateDetectorSnapshot) {
        lastWindowEndNanos = snapshot.lastWindowEndElapsedRealtimeNanos
        confirmedState = snapshot.confirmedState
        candidateState = snapshot.candidateState
        candidateStartNanos = snapshot.candidateStartElapsedRealtimeNanos
        candidateCount = snapshot.candidateCount
    }

    private fun judge(evidence: MotionEvidence): Judgment {
        val accuracy = evidence.horizontalAccuracyMeters?.takeIf { it.isFinite() && it > 0.0 }
        val displacement = evidence.locationDisplacementMeters?.takeIf { it.isFinite() && it >= 0.0 }
        val speed = evidence.reportedSpeedMetersPerSecond?.takeIf { it.isFinite() && it >= 0.0 }
        val speedAccuracy = evidence.speedAccuracyMetersPerSecond?.takeIf { it.isFinite() && it > 0.0 }
        val cadence = evidence.stepCadenceHz?.takeIf { it.isFinite() && it >= 0.0 }
        val acceleration = evidence.accelerationStdDevMetersPerSecondSquared?.takeIf { it.isFinite() && it >= 0.0 }
        val activity = evidence.activity
        val activityConfidence = evidence.activityConfidencePercent?.coerceIn(0, 100) ?: 0
        val trustedSpeed = speed != null && speedAccuracy != null && speedAccuracy <= MAX_SPEED_ACCURACY_MPS
        val strongDisplacement = displacement != null && accuracy != null &&
            displacement >= maxOf(MIN_DISPLACEMENT_METERS, accuracy * DISPLACEMENT_ACCURACY_MULTIPLIER)
        val activeSteps = cadence != null && cadence >= ACTIVE_STEP_CADENCE_HZ &&
            acceleration != null && acceleration >= ACTIVE_ACCELERATION_STD_MPS2
        val footActivity = activityConfidence >= MIN_ACTIVITY_CONFIDENCE &&
            activity in FOOT_ACTIVITIES && (activeSteps || strongDisplacement || (trustedSpeed && speed >= WALKING_SPEED_MPS))
        val bicycleActivity = activityConfidence >= MIN_ACTIVITY_CONFIDENCE &&
            activity == CoarseActivity.ON_BICYCLE && (strongDisplacement || (trustedSpeed && speed >= BICYCLE_SPEED_MPS))
        val vehicleActivity = activityConfidence >= MIN_ACTIVITY_CONFIDENCE &&
            activity == CoarseActivity.IN_VEHICLE && (strongDisplacement || (trustedSpeed && speed >= VEHICLE_SPEED_MPS))
        val reliableMovingSpeed = trustedSpeed && speed >= MIN_MOVING_SPEED_MPS
        val movingSignals = listOf(strongDisplacement, activeSteps, footActivity, bicycleActivity, vehicleActivity, reliableMovingSpeed)
            .count { it }
        if (movingSignals >= 2 || (strongDisplacement && (footActivity || bicycleActivity || vehicleActivity || reliableMovingSpeed))) {
            val confidence = (0.65f + movingSignals * 0.07f).coerceAtMost(0.97f)
            return Judgment(MotionState.MOVING, confidence, "motion.multisensor_movement")
        }
        if (strongDisplacement) return Judgment(MotionState.MOVING, 0.7f, "motion.location_progress")
        if (reliableMovingSpeed) return Judgment(MotionState.MOVING, 0.72f, "motion.reliable_speed")
        if (activeSteps && activity != CoarseActivity.IN_VEHICLE) {
            return Judgment(MotionState.MOVING, 0.68f, "motion.step_cadence")
        }

        val reliableLowSpeed = trustedSpeed && speed <= MAX_STATIONARY_SPEED_MPS
        val calmPhone = acceleration != null && acceleration <= CALM_ACCELERATION_STD_MPS2 &&
            (cadence == null || cadence <= MAX_STATIONARY_STEP_CADENCE_HZ)
        val explicitlyStill = activity == CoarseActivity.STILL && activityConfidence >= MIN_ACTIVITY_CONFIDENCE
        val vehicleMayBeMoving = activity == CoarseActivity.IN_VEHICLE && activityConfidence >= MIN_ACTIVITY_CONFIDENCE
        val stationary = !vehicleMayBeMoving &&
            (reliableLowSpeed || (calmPhone && (explicitlyStill || displacement == null || accuracy == null || accuracy >= MESSY_GPS_ACCURACY_METERS)))
        if (stationary) {
            val confidence = when {
                reliableLowSpeed && calmPhone -> 0.9f
                reliableLowSpeed -> 0.78f
                calmPhone && explicitlyStill -> 0.82f
                else -> 0.7f
            }
            return Judgment(MotionState.STATIONARY, confidence, "motion.stable_stationary_evidence")
        }
        return Judgment(MotionState.UNCERTAIN, 0.35f, "motion.insufficient_or_conflicting_evidence")
    }

    private fun estimate(
        state: MotionState,
        confidence: Float,
        evidence: MotionEvidence,
        reason: String,
        since: Long,
    ) = MotionEstimate(
        state = state,
        confidence = confidence,
        sinceElapsedRealtimeNanos = since,
        evidence = evidence.evidenceObservationIds.map { EvidenceRef(it, "motion-window") },
        reasonCode = reason,
    )

    private fun clearCandidate() {
        candidateState = null
        candidateStartNanos = null
        candidateCount = 0
    }

    private data class Judgment(val state: MotionState, val confidence: Float, val reason: String)

    companion object {
        private val FOOT_ACTIVITIES = setOf(CoarseActivity.ON_FOOT, CoarseActivity.WALKING, CoarseActivity.RUNNING)
        private const val MIN_ACTIVITY_CONFIDENCE = 70
        private const val MIN_DISPLACEMENT_METERS = 18.0
        private const val DISPLACEMENT_ACCURACY_MULTIPLIER = 1.5
        private const val ACTIVE_STEP_CADENCE_HZ = 0.65
        private const val ACTIVE_ACCELERATION_STD_MPS2 = 0.15
        private const val MAX_SPEED_ACCURACY_MPS = 3.0
        private const val MIN_MOVING_SPEED_MPS = 1.3
        private const val WALKING_SPEED_MPS = 0.8
        private const val BICYCLE_SPEED_MPS = 2.0
        private const val VEHICLE_SPEED_MPS = 2.5
        private const val MAX_STATIONARY_SPEED_MPS = 0.5
        private const val MAX_STATIONARY_STEP_CADENCE_HZ = 0.2
        private const val CALM_ACCELERATION_STD_MPS2 = 0.12
        private const val MESSY_GPS_ACCURACY_METERS = 60.0
        private const val MOVEMENT_CONFIRMATION_NANOS = 10_000_000_000L
        private const val STATIONARY_CONFIRMATION_NANOS = 60_000_000_000L
        private const val MIN_CONFIRMATION_WINDOWS = 2
        private const val CANDIDATE_CONFIDENCE_FACTOR = 0.7f
    }
}
