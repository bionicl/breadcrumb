package io.github.valeronm.breadcrumb.locomotion

/** Stable, platform-free input and output types for the local locomotion engine. */
data class Observation(
    val id: String,
    val sessionId: String,
    val bootEpochId: String,
    val wallTimeMs: Long,
    val elapsedRealtimeNanos: Long,
    val source: ObservationSource,
    val location: LocationMeasurement? = null,
    val sensors: SensorWindow? = null,
    val detectedActivity: DetectedActivityEvidence? = null,
    val provenance: ObservationProvenance = ObservationProvenance.LIVE,
)

enum class ObservationSource { RAW_GNSS, FUSED_LOCATION, ACTIVITY_RECOGNITION, SENSOR_WINDOW, MANUAL_MARKER }
enum class ObservationProvenance { LIVE, IMPORTED, SYNTHETIC, DEMO }

data class LocationMeasurement(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val horizontalAccuracyMeters: Double?,
    val speedMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
    val altitudeMeters: Double? = null,
    val verticalAccuracyMeters: Double? = null,
    val speedAccuracyMetersPerSecond: Double? = null,
)

/** Nullable values are intentional: unavailable is different from a measured zero. */
data class SensorWindow(
    val startElapsedRealtimeNanos: Long,
    val endElapsedRealtimeNanos: Long,
    val sampleCount: Int,
    val accelerationHorizontalMeanMetersPerSecondSquared: Double? = null,
    val accelerationHorizontalStdDevMetersPerSecondSquared: Double? = null,
    val accelerationVerticalMeanMetersPerSecondSquared: Double? = null,
    val accelerationVerticalStdDevMetersPerSecondSquared: Double? = null,
    val stepCadenceHz: Double? = null,
    val barometricAltitudeDeltaMeters: Double? = null,
    val missingFeatures: Set<SensorFeature> = emptySet(),
)

enum class SensorFeature(val bitMask: Int) {
    ACCELEROMETER(1 shl 0), STEP_COUNTER(1 shl 1), STEP_DETECTOR(1 shl 2),
    ROTATION_VECTOR(1 shl 3), BAROMETER(1 shl 4), GRAVITY(1 shl 5),
}

data class DetectedActivityEvidence(
    val activity: CoarseActivity,
    val confidencePercent: Int,
    val observedElapsedRealtimeNanos: Long,
)

enum class CoarseActivity { IN_VEHICLE, ON_BICYCLE, ON_FOOT, WALKING, RUNNING, STILL, TILTING, UNKNOWN }

enum class MotionState { MOVING, STATIONARY, UNCERTAIN }
enum class RecordingState { IDLE, VERIFYING_DEPARTURE, RECORDING, VERIFYING_ARRIVAL, DEGRADED }

enum class TransportMode {
    WALKING, RUNNING, CYCLING, CAR, TAXI, MOTORCYCLE, SCOOTER, BUS, TRAM, TRAIN, METRO, BOAT, AIRPLANE, TRANSIT, UNKNOWN
}

data class EvidenceRef(val observationId: String, val role: String)

data class MotionEstimate(
    val state: MotionState,
    val confidence: Float,
    val sinceElapsedRealtimeNanos: Long,
    val evidence: List<EvidenceRef>,
    val reasonCode: String,
) {
    init { require(confidence in 0f..1f) }
}

data class TransportScore(
    val mode: TransportMode,
    val probability: Float,
) {
    init { require(probability in 0f..1f) }
}

data class TransportPrediction(
    val scores: List<TransportScore>,
    val modelVersion: String?,
    val featureSchemaVersion: Int,
    val independentTripSupport: Int,
    val reasonCode: String,
)

enum class SegmentKind { MOVEMENT, VISIT, DATA_GAP }

/** A segment ID is stable across refinement; boundaries retain their origin and lock state. */
data class TimelineSegment(
    val id: String,
    val kind: SegmentKind,
    val startWallTimeMs: Long,
    val endWallTimeMs: Long?,
    val transportMode: TransportMode? = null,
    val confirmedTransportMode: TransportMode? = null,
    val startBoundary: BoundaryOrigin = BoundaryOrigin.INFERRED,
    val endBoundary: BoundaryOrigin = BoundaryOrigin.INFERRED,
    val lockedByUser: Boolean = false,
    val observationIds: List<String> = emptyList(),
)

enum class BoundaryOrigin { INFERRED, OBSERVED, USER_CONFIRMED, IMPORTED }

sealed interface UserCorrection {
    val id: String
    val createdAtWallTimeMs: Long
    val targetSegmentId: String

    data class ConfirmTransport(
        override val id: String,
        override val createdAtWallTimeMs: Long,
        override val targetSegmentId: String,
        val mode: TransportMode,
        val fromObservationId: String? = null,
        val throughObservationId: String? = null,
    ) : UserCorrection

    data class ConfirmVisit(
        override val id: String,
        override val createdAtWallTimeMs: Long,
        override val targetSegmentId: String,
    ) : UserCorrection

    data class LockBoundary(
        override val id: String,
        override val createdAtWallTimeMs: Long,
        override val targetSegmentId: String,
        val boundary: BoundaryKind,
        val atWallTimeMs: Long,
    ) : UserCorrection

    data class DeleteSegment(
        override val id: String,
        override val createdAtWallTimeMs: Long,
        override val targetSegmentId: String,
    ) : UserCorrection

    data class Undo(
        override val id: String,
        override val createdAtWallTimeMs: Long,
        override val targetSegmentId: String,
        val correctionId: String,
    ) : UserCorrection
}

enum class BoundaryKind { START, END }

/** Requested and actual service state must never be conflated. */
data class ResourceSnapshot(
    val resource: EngineResource,
    val requested: Boolean,
    val actual: Boolean,
    val lastRequestedElapsedRealtimeNanos: Long?,
    val lastAcknowledgedElapsedRealtimeNanos: Long?,
    val lastErrorCode: String? = null,
)

enum class EngineResource { GPS, FUSED_LOCATION, ACTIVITY_RECOGNITION, ACCELEROMETER, STEP_COUNTER, BAROMETER }

sealed interface EngineEffect {
    val id: String
    data class RequestResource(
        override val id: String,
        val resource: EngineResource,
        val enabled: Boolean,
        val intervalMillis: Long? = null,
    ) : EngineEffect
    data class PersistCheckpoint(override val id: String, val throughObservationId: String?) : EngineEffect
    data class RebuildTimeline(override val id: String, val fromWallTimeMs: Long) : EngineEffect
}

data class EngineSnapshot(
    val sessionId: String?,
    val bootEpochId: String?,
    val lastProcessedElapsedRealtimeNanos: Long?,
    val motion: MotionEstimate?,
    val recordingState: RecordingState,
    val resources: List<ResourceSnapshot>,
    val activeSegmentId: String?,
    val configurationVersion: Int,
    val modelVersion: String?,
    val pendingEffects: Int,
)

/** Complete deterministic state needed to resume filtering and timeline processing. */
data class EngineCheckpoint(
    val sessionId: String,
    val bootEpochId: String,
    val lastObservationId: String?,
    val lastElapsedRealtimeNanos: Long?,
    val lastWallTimeMs: Long?,
    val motionState: MotionState,
    val recordingState: RecordingState,
    val filter: FilterSnapshot?,
    val activeSegmentId: String?,
    val configurationVersion: Int,
    val modelVersion: String?,
    val updatedAtWallTimeMs: Long,
)

data class FilterSnapshot(
    val xMeters: Double,
    val yMeters: Double,
    val velocityXMetersPerSecond: Double,
    val velocityYMetersPerSecond: Double,
    val covariance: List<Double>,
    val projectionOriginLatitudeDegrees: Double,
    val projectionOriginLongitudeDegrees: Double,
    val lastElapsedRealtimeNanos: Long,
) {
    init {
        require(covariance.size == FILTER_STATE_SIZE * FILTER_STATE_SIZE)
        require(covariance.all(Double::isFinite))
        require(lastElapsedRealtimeNanos >= 0L)
        require(projectionOriginLatitudeDegrees.isFinite() && projectionOriginLatitudeDegrees in -90.0..90.0)
        require(projectionOriginLongitudeDegrees.isFinite() && projectionOriginLongitudeDegrees in -180.0..180.0)
    }

    companion object {
        private const val FILTER_STATE_SIZE = 4
    }
}
