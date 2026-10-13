package io.github.valeronm.breadcrumb.locomotion

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Result of processing one immutable observation through the platform-free engine pipeline. */
data class ObservationProcessingResult(
    val observationId: String,
    val location: LocationFilterResult? = null,
    val motion: MotionEstimate? = null,
    val diagnostics: List<EngineDiagnosticEvent> = emptyList(),
    val ignored: Boolean = false,
)

/** Deterministic observation -> filter -> motion pipeline; Android only supplies and persists inputs. */
class LocomotionEngine(
    private val locationFilter: LocationKalmanFilter = LocationKalmanFilter(),
    private val motionDetector: MotionStateDetector = MotionStateDetector(),
    private val diagnostics: EngineDiagnosticJournal = EngineDiagnosticJournal(),
    private val maximumSensorAgeNanos: Long = DEFAULT_MAX_SENSOR_AGE_NANOS,
    private val maximumActivityAgeNanos: Long = DEFAULT_MAX_ACTIVITY_AGE_NANOS,
) {
    private var bootEpochId: String? = null
    private var lastProcessedElapsedNanos: Long? = null
    private var lastFilteredCoordinate: GeoCoordinate? = null
    private var lastSensorWindow: SensorWindow? = null
    private var lastSensorObservationId: String? = null
    private var lastActivity: DetectedActivityEvidence? = null
    private var lastActivityObservationId: String? = null
    private var latestMotion: MotionEstimate? = null

    init {
        require(maximumSensorAgeNanos > 0)
        require(maximumActivityAgeNanos > 0)
    }

    @Synchronized
    fun process(observation: Observation): ObservationProcessingResult {
        val emitted = mutableListOf<EngineDiagnosticEvent>()
        val currentEpoch = bootEpochId
        if (currentEpoch != null && currentEpoch != observation.bootEpochId) {
            resetForBootEpoch(observation.bootEpochId)
            emitted += event(observation, DiagnosticSubsystem.INPUT, "input.boot_epoch_changed", DiagnosticLevel.INFO)
        } else if (currentEpoch == null) {
            bootEpochId = observation.bootEpochId
        }

        val previousTime = lastProcessedElapsedNanos
        if (previousTime != null && observation.elapsedRealtimeNanos <= previousTime) {
            val stale = event(observation, DiagnosticSubsystem.INPUT, "input.out_of_order_observation", DiagnosticLevel.WARNING)
            diagnostics.append(stale)
            return ObservationProcessingResult(observation.id, diagnostics = listOf(stale), ignored = true)
        }
        lastProcessedElapsedNanos = observation.elapsedRealtimeNanos

        observation.sensors?.let {
            lastSensorWindow = it
            lastSensorObservationId = observation.id
        }
        observation.detectedActivity?.let {
            lastActivity = it
            lastActivityObservationId = observation.id
        }

        var filterResult: LocationFilterResult? = null
        var displacement: Double? = null
        observation.location?.let { measurement ->
            val result = locationFilter.update(measurement, observation.elapsedRealtimeNanos)
            filterResult = result
            val level = if (result.accepted) DiagnosticLevel.INFO else DiagnosticLevel.WARNING
            emitted += EngineDiagnosticEvent(
                id = "${observation.id}:filter",
                wallTimeMs = observation.wallTimeMs,
                elapsedRealtimeNanos = observation.elapsedRealtimeNanos,
                subsystem = DiagnosticSubsystem.FILTER,
                code = result.reasonCode,
                level = level,
                evidenceObservationIds = listOf(observation.id),
                metrics = buildMap {
                    measurement.horizontalAccuracyMeters?.let { put(DiagnosticMetric.HORIZONTAL_ACCURACY_METERS, it) }
                },
            )
            val filteredCoordinate = result.filtered
            if (result.accepted && filteredCoordinate != null) {
                lastFilteredCoordinate?.let { previous -> displacement = distanceMeters(previous, filteredCoordinate) }
                lastFilteredCoordinate = filteredCoordinate
            }
        }

        val sensorWindow = lastSensorWindow?.takeIf {
            observation.elapsedRealtimeNanos >= it.endElapsedRealtimeNanos &&
                observation.elapsedRealtimeNanos - it.endElapsedRealtimeNanos <= maximumSensorAgeNanos
        }
        val activity = lastActivity?.takeIf {
            observation.elapsedRealtimeNanos >= it.observedElapsedRealtimeNanos &&
                observation.elapsedRealtimeNanos - it.observedElapsedRealtimeNanos <= maximumActivityAgeNanos
        }
        val shouldEvaluateMotion = filterResult != null || sensorWindow != null || activity != null
        var motion: MotionEstimate? = null
        if (shouldEvaluateMotion) {
            val accuracy = observation.location?.horizontalAccuracyMeters
                ?: filterResult?.horizontalUncertaintyMeters
            val evidenceIds = buildList {
                add(observation.id)
                lastSensorObservationId?.let { if (it != observation.id) add(it) }
                lastActivityObservationId?.let { if (it != observation.id && it !in this) add(it) }
            }
            val windowEnd = maxOf(
                observation.elapsedRealtimeNanos,
                sensorWindow?.endElapsedRealtimeNanos ?: observation.elapsedRealtimeNanos,
            )
            val fallbackWindowNanos = 5_000_000_000L
            val windowStart = sensorWindow?.startElapsedRealtimeNanos?.takeIf { it < windowEnd }
                ?: (windowEnd - fallbackWindowNanos).coerceAtLeast(0L)
            val evidence = MotionEvidence(
                windowStartElapsedRealtimeNanos = windowStart,
                windowEndElapsedRealtimeNanos = windowEnd,
                evidenceObservationIds = evidenceIds,
                locationDisplacementMeters = displacement,
                horizontalAccuracyMeters = accuracy,
                reportedSpeedMetersPerSecond = observation.location?.speedMetersPerSecond,
                speedAccuracyMetersPerSecond = observation.location?.speedAccuracyMetersPerSecond,
                stepCadenceHz = sensorWindow?.stepCadenceHz,
                accelerationStdDevMetersPerSecondSquared = sensorWindow?.accelerationHorizontalStdDevMetersPerSecondSquared,
                activity = activity?.activity,
                activityConfidencePercent = activity?.confidencePercent,
            )
            motion = motionDetector.update(evidence)
            latestMotion = motion
            emitted += EngineDiagnosticEvent(
                id = "${observation.id}:motion",
                wallTimeMs = observation.wallTimeMs,
                elapsedRealtimeNanos = observation.elapsedRealtimeNanos,
                subsystem = DiagnosticSubsystem.MOTION,
                code = motion.reasonCode,
                level = DiagnosticLevel.INFO,
                motionState = motion.state,
                evidenceObservationIds = evidenceIds,
                metrics = mapOf(DiagnosticMetric.CONFIDENCE to motion.confidence.toDouble()),
            )
        }
        emitted.forEach(diagnostics::append)
        return ObservationProcessingResult(observation.id, filterResult, motion, emitted)
    }

    @Synchronized
    fun diagnosticSnapshot(): List<EngineDiagnosticEvent> = diagnostics.recent()

    @Synchronized
    fun latestMotionEstimate(): MotionEstimate? = latestMotion

    @Synchronized
    fun reset() {
        locationFilter.reset()
        motionDetector.reset()
        diagnostics.clear()
        clearVolatileState()
    }

    private fun resetForBootEpoch(newEpoch: String) {
        locationFilter.reset()
        motionDetector.reset()
        clearVolatileState()
        bootEpochId = newEpoch
    }

    private fun clearVolatileState() {
        lastProcessedElapsedNanos = null
        lastFilteredCoordinate = null
        lastSensorWindow = null
        lastSensorObservationId = null
        lastActivity = null
        lastActivityObservationId = null
        latestMotion = null
    }

    private fun event(
        observation: Observation,
        subsystem: DiagnosticSubsystem,
        code: String,
        level: DiagnosticLevel,
    ) = EngineDiagnosticEvent(
        id = "${observation.id}:$code",
        wallTimeMs = observation.wallTimeMs,
        elapsedRealtimeNanos = observation.elapsedRealtimeNanos,
        subsystem = subsystem,
        code = code,
        level = level,
        evidenceObservationIds = listOf(observation.id),
    )

    private fun distanceMeters(start: GeoCoordinate, end: GeoCoordinate): Double {
        val startLat = Math.toRadians(start.latitudeDegrees)
        val endLat = Math.toRadians(end.latitudeDegrees)
        val deltaLat = endLat - startLat
        val deltaLon = Math.toRadians(end.longitudeDegrees - start.longitudeDegrees)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(startLat) * cos(endLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt((1 - a).coerceAtLeast(0.0)))
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_008.8
        private const val DEFAULT_MAX_SENSOR_AGE_NANOS = 60_000_000_000L
        private const val DEFAULT_MAX_ACTIVITY_AGE_NANOS = 120_000_000_000L
    }
}
