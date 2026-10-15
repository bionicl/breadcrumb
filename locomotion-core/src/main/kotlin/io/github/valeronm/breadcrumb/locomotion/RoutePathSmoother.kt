package io.github.valeronm.breadcrumb.locomotion

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Input to the completed-track geometry smoother. Coordinates are always the original fixes. */
data class RouteSmoothingFix(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val timestampMs: Long,
    val horizontalAccuracyMeters: Double?,
    val speedMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
    val speedAccuracyMetersPerSecond: Double? = null,
    val bearingAccuracyDegrees: Double? = null,
    val segmentStart: Boolean = false,
)

/** A corrected coordinate; null means the original fix should remain the displayed coordinate. */
data class SmoothedRouteCoordinate(val latitudeDegrees: Double, val longitudeDegrees: Double)

/**
 * A bounded, two-direction constant-velocity Kalman smoother for finished paths. It uses future
 * fixes to refine earlier uncertain fixes, while leaving accurate measurements as fixed anchors.
 * It never creates fixes and never changes times or segment boundaries.
 */
object RoutePathSmoother {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    const val MIN_SMOOTHING_ACCURACY_METERS = 20.0
    const val MAX_CORRECTION_METERS = 50.0
    const val MAX_CONTIGUOUS_GAP_MS = 120_000L
    const val MAX_SPEED_ACCURACY_METERS_PER_SECOND = 5.0
    const val MAX_BEARING_ACCURACY_DEGREES = 45.0

    /** Tunable values are also used by the non-persistent path-smoothing debug preview. */
    data class Parameters(
        val minimumSmoothingAccuracyMeters: Double = MIN_SMOOTHING_ACCURACY_METERS,
        val horizontalAccuracyMultiplier: Double = 1.0,
        val maximumCorrectionMeters: Double = MAX_CORRECTION_METERS,
        val accelerationNoiseMetersPerSecondSquared: Double = LocationKalmanFilter.DEFAULT_ACCELERATION_NOISE_MPS2,
        val outlierSigmaLimit: Double = LocationKalmanFilter.DEFAULT_OUTLIER_SIGMA_LIMIT,
        val maximumSpeedAccuracyMetersPerSecond: Double = MAX_SPEED_ACCURACY_METERS_PER_SECOND,
        val maximumBearingAccuracyDegrees: Double = MAX_BEARING_ACCURACY_DEGREES,
        val maximumContiguousGapMs: Long = MAX_CONTIGUOUS_GAP_MS,
    ) {
        init {
            require(minimumSmoothingAccuracyMeters >= 0.0)
            require(horizontalAccuracyMultiplier > 0.0)
            require(maximumCorrectionMeters >= 0.0)
            require(accelerationNoiseMetersPerSecondSquared > 0.0)
            require(outlierSigmaLimit > 0.0)
            require(maximumSpeedAccuracyMetersPerSecond >= 0.1)
            require(maximumBearingAccuracyDegrees >= 0.0)
            require(maximumContiguousGapMs >= 0L)
        }
    }

    /** Returns one optional corrected coordinate per input fix, preserving the original order. */
    fun smooth(
        fixes: List<RouteSmoothingFix>,
        parameters: Parameters = Parameters(),
    ): List<SmoothedRouteCoordinate?> {
        if (fixes.isEmpty()) return emptyList()
        val output = MutableList<SmoothedRouteCoordinate?>(fixes.size) { null }
        var segmentStart = 0
        for (index in fixes.indices) {
            val fix = fixes[index]
            val breaksBefore = index > segmentStart &&
                (fix.segmentStart || fix.timestampMs - fixes[index - 1].timestampMs > parameters.maximumContiguousGapMs)
            val invalidAccuracy = fix.horizontalAccuracyMeters?.let { !it.isFinite() || it <= 0.0 } ?: true
            if (breaksBefore || invalidAccuracy) {
                smoothSegment(fixes, segmentStart, index, output, parameters)
                segmentStart = index + if (invalidAccuracy) 1 else 0
            }
            if (index == fixes.lastIndex) smoothSegment(fixes, segmentStart, fixes.size, output, parameters)
        }
        return output
    }

    private fun smoothSegment(
        fixes: List<RouteSmoothingFix>,
        start: Int,
        end: Int,
        output: MutableList<SmoothedRouteCoordinate?>,
        parameters: Parameters,
    ) {
        if (end - start < 3) return
        val segment = fixes.subList(start, end)
        val forward = filter(segment, reverse = false, parameters = parameters)
        val backward = filter(segment, reverse = true, parameters = parameters)
        for (localIndex in segment.indices) {
            val fix = segment[localIndex]
            val accuracy = fix.horizontalAccuracyMeters ?: continue
            if (accuracy < parameters.minimumSmoothingAccuracyMeters) continue
            val a = forward[localIndex] ?: continue
            val b = backward[localIndex] ?: continue
            val estimate = fuse(a, b) ?: continue
            val distance = distanceMeters(fix.latitudeDegrees, fix.longitudeDegrees, estimate.latitude, estimate.longitude)
            if (!distance.isFinite() || distance < 0.5) continue
            val maxShift = min(accuracy, parameters.maximumCorrectionMeters)
            val ratio = min(1.0, maxShift / distance)
            output[start + localIndex] = interpolate(fix, estimate.latitude, estimate.longitude, ratio)
        }
    }

    private data class Estimate(val latitude: Double, val longitude: Double, val uncertainty: Double)

    private fun filter(
        fixes: List<RouteSmoothingFix>,
        reverse: Boolean,
        parameters: Parameters,
    ): List<Estimate?> {
        val filter = LocationKalmanFilter(
            accelerationNoiseMetersPerSecondSquared = parameters.accelerationNoiseMetersPerSecondSquared,
            outlierSigmaLimit = parameters.outlierSigmaLimit,
        )
        val indexed = fixes.indices.toList().let { if (reverse) it.asReversed() else it }
        val estimates = arrayOfNulls<Estimate>(fixes.size)
        var elapsedNanos = 0L
        var previousTimestamp: Long? = null
        for (index in indexed) {
            val fix = fixes[index]
            val accuracy = fix.horizontalAccuracyMeters ?: continue
            val timestamp = if (previousTimestamp == null) {
                0L
            } else {
                val delta = if (reverse) previousTimestamp!! - fix.timestampMs else fix.timestampMs - previousTimestamp!!
                elapsedNanos + delta.coerceAtLeast(1L) * 1_000_000L
            }
            val bearing = fix.bearingDegrees?.let { if (reverse) (it + 180.0) % 360.0 else it }
            val velocityReliable = fix.speedMetersPerSecond?.isFinite() == true &&
                fix.speedMetersPerSecond >= 0.0 &&
                fix.speedAccuracyMetersPerSecond?.let {
                    it.isFinite() && it >= 0.1 && it <= parameters.maximumSpeedAccuracyMetersPerSecond
                } == true &&
                bearing?.isFinite() == true &&
                fix.bearingAccuracyDegrees?.let {
                    it.isFinite() && it >= 0.0 && it <= parameters.maximumBearingAccuracyDegrees
                } == true
            val result = filter.update(
                LocationMeasurement(
                    latitudeDegrees = fix.latitudeDegrees,
                    longitudeDegrees = fix.longitudeDegrees,
                    horizontalAccuracyMeters = accuracy * parameters.horizontalAccuracyMultiplier,
                    speedMetersPerSecond = fix.speedMetersPerSecond.takeIf { velocityReliable },
                    bearingDegrees = bearing.takeIf { velocityReliable },
                    speedAccuracyMetersPerSecond = fix.speedAccuracyMetersPerSecond.takeIf { velocityReliable },
                ),
                timestamp,
            )
            result.filtered?.let { coordinate ->
                estimates[index] = Estimate(
                    coordinate.latitudeDegrees,
                    coordinate.longitudeDegrees,
                    result.horizontalUncertaintyMeters?.coerceAtLeast(1.0) ?: accuracy,
                )
            }
            elapsedNanos = timestamp
            previousTimestamp = fix.timestampMs
        }
        return estimates.toList()
    }

    private fun fuse(a: Estimate, b: Estimate): Estimate? {
        if (abs(a.longitude - b.longitude) > 180.0) return null
        val wa = 1.0 / (a.uncertainty * a.uncertainty)
        val wb = 1.0 / (b.uncertainty * b.uncertainty)
        val total = wa + wb
        if (!total.isFinite() || total <= 0.0) return null
        return Estimate(
            latitude = (a.latitude * wa + b.latitude * wb) / total,
            longitude = (a.longitude * wa + b.longitude * wb) / total,
            uncertainty = (1.0 / total).let { kotlin.math.sqrt(it) },
        )
    }

    private fun interpolate(fix: RouteSmoothingFix, latitude: Double, longitude: Double, fraction: Double) =
        SmoothedRouteCoordinate(
            latitudeDegrees = fix.latitudeDegrees + (latitude - fix.latitudeDegrees) * fraction,
            longitudeDegrees = fix.longitudeDegrees + (longitude - fix.longitudeDegrees) * fraction,
        )

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val meanLatitude = Math.toRadians((lat1 + lat2) / 2.0)
        val north = Math.toRadians(lat2 - lat1) * EARTH_RADIUS_METERS
        val east = Math.toRadians(lon2 - lon1) * EARTH_RADIUS_METERS * cos(meanLatitude)
        return hypot(north, east)
    }

}
