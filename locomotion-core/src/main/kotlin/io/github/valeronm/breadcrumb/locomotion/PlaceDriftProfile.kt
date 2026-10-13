package io.github.valeronm.breadcrumb.locomotion

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A displacement from a user-confirmed place anchor, expressed in local east/north meters. */
data class PlaceDriftSample(
    val eastMeters: Double,
    val northMeters: Double,
    val horizontalAccuracyMeters: Double?,
)

enum class DriftTrainingProvenance { LIVE, IMPORTED, SYNTHETIC, DEMO }

data class DriftAssessment(
    val likelyKnownDrift: Boolean,
    val expectedRadiusMeters: Double?,
    val observedRadiusMeters: Double,
    val residualMeters: Double?,
    val supportCount: Int,
    val confidence: Float,
    val reasonCode: String,
)

/**
 * Robust, direction-aware place drift learning (LocoKit2 LK-06). It only provides soft evidence;
 * callers must continue combining it with motion sensors and time-based state confirmation.
 */
class PlaceDriftProfile(
    private val minimumAccuracyMeters: Double = DEFAULT_MINIMUM_ACCURACY_METERS,
    private val maximumAccuracyMeters: Double = DEFAULT_MAXIMUM_ACCURACY_METERS,
    private val maximumDriftMeters: Double = DEFAULT_MAXIMUM_DRIFT_METERS,
    private val minimumSupport: Int = DEFAULT_MINIMUM_SUPPORT,
    private val maxSamplesPerSector: Int = DEFAULT_MAX_SAMPLES_PER_SECTOR,
) {
    private val samplesBySector = Array(SECTOR_COUNT) { ArrayDeque<Double>() }
    private val trainedStayIds = mutableSetOf<String>()

    init {
        require(minimumAccuracyMeters >= 0.0)
        require(maximumAccuracyMeters >= minimumAccuracyMeters)
        require(maximumDriftMeters > 0.0)
        require(minimumSupport > 0)
        require(maxSamplesPerSector >= minimumSupport)
    }

    /** Adds at most one robust radius per direction and confirmed visit. */
    @Synchronized
    fun learnConfirmedStay(
        stayId: String,
        samples: List<PlaceDriftSample>,
        confirmedStationary: Boolean,
        provenance: DriftTrainingProvenance,
    ): Boolean {
        if (stayId.isBlank() || !confirmedStationary || provenance != DriftTrainingProvenance.LIVE) return false
        if (!trainedStayIds.add(stayId)) return false

        val valid = samples.mapNotNull(::validSample)
        if (valid.isEmpty()) {
            trainedStayIds.remove(stayId)
            return false
        }
        val mediansBySector = valid.groupBy { sectorFor(it.eastMeters, it.northMeters) }
            .mapValues { (_, values) -> median(values.map { hypot(it.eastMeters, it.northMeters) }) }
        mediansBySector.forEach { (sector, radius) ->
            val history = samplesBySector[sector]
            history.addLast(radius)
            while (history.size > maxSamplesPerSector) history.removeFirst()
        }
        return mediansBySector.isNotEmpty()
    }

    @Synchronized
    fun assess(eastMeters: Double, northMeters: Double, horizontalAccuracyMeters: Double?): DriftAssessment {
        val radius = hypot(eastMeters, northMeters)
        val accuracy = horizontalAccuracyMeters?.takeIf { it.isFinite() && it >= 0.0 }
        if (!eastMeters.isFinite() || !northMeters.isFinite() || radius > maximumDriftMeters || accuracy == null) {
            return DriftAssessment(false, null, radius, null, 0, 0f, "drift.invalid_or_outside_profile")
        }
        if (accuracy > maximumAccuracyMeters) {
            return DriftAssessment(false, null, radius, null, 0, 0f, "drift.accuracy_too_low")
        }
        val history = samplesBySector[sectorFor(eastMeters, northMeters)]
        if (history.size < minimumSupport) {
            return DriftAssessment(false, null, radius, null, history.size, 0f, "drift.insufficient_confirmed_visits")
        }
        val expected = median(history.toList())
        val deviations = history.map { kotlin.math.abs(it - expected) }
        val mad = median(deviations)
        val residual = kotlin.math.abs(radius - expected)
        val tolerance = maxOf(accuracy, MINIMUM_RESIDUAL_TOLERANCE_METERS, mad * MAD_TOLERANCE_MULTIPLIER)
        val likelyDrift = residual <= tolerance
        val supportConfidence = (history.size.toFloat() / (minimumSupport * 2)).coerceIn(0.0f, 1.0f)
        val residualConfidence = (1.0 - residual / (tolerance * 2)).coerceIn(0.0, 1.0).toFloat()
        return DriftAssessment(
            likelyKnownDrift = likelyDrift,
            expectedRadiusMeters = expected,
            observedRadiusMeters = radius,
            residualMeters = residual,
            supportCount = history.size,
            confidence = if (likelyDrift) (supportConfidence * residualConfidence).coerceAtMost(MAX_CONFIDENCE) else 0f,
            reasonCode = if (likelyDrift) "drift.matches_confirmed_profile" else "drift.deviates_from_profile",
        )
    }

    @Synchronized
    fun clear() {
        samplesBySector.forEach { it.clear() }
        trainedStayIds.clear()
    }

    private fun validSample(sample: PlaceDriftSample): PlaceDriftSample? {
        val radius = hypot(sample.eastMeters, sample.northMeters)
        val accuracy = sample.horizontalAccuracyMeters ?: return null
        if (!sample.eastMeters.isFinite() || !sample.northMeters.isFinite() || !accuracy.isFinite()) return null
        if (accuracy < minimumAccuracyMeters || accuracy > maximumAccuracyMeters) return null
        if (radius < MINIMUM_LEARNED_DRIFT_METERS || radius > maximumDriftMeters) return null
        return sample
    }

    private fun sectorFor(east: Double, north: Double): Int {
        val angle = atan2(north, east)
        val normalized = (angle + Math.PI) / (2.0 * Math.PI)
        return (normalized * SECTOR_COUNT).roundToInt().mod(SECTOR_COUNT)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    companion object {
        private const val SECTOR_COUNT = 8
        private const val DEFAULT_MINIMUM_ACCURACY_METERS = 1.0
        private const val DEFAULT_MAXIMUM_ACCURACY_METERS = 40.0
        private const val DEFAULT_MAXIMUM_DRIFT_METERS = 150.0
        private const val DEFAULT_MINIMUM_SUPPORT = 3
        private const val DEFAULT_MAX_SAMPLES_PER_SECTOR = 64
        private const val MINIMUM_LEARNED_DRIFT_METERS = 2.0
        private const val MINIMUM_RESIDUAL_TOLERANCE_METERS = 8.0
        private const val MAD_TOLERANCE_MULTIPLIER = 2.5
        private const val MAX_CONFIDENCE = 0.9f
    }
}
