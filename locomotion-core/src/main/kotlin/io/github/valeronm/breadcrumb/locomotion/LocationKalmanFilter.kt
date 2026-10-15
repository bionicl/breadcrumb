package io.github.valeronm.breadcrumb.locomotion

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Raw and filtered coordinates travel together so callers can inspect every rejected observation. */
data class LocationFilterResult(
    val raw: GeoCoordinate,
    val filtered: GeoCoordinate?,
    val horizontalUncertaintyMeters: Double?,
    val velocityEastMetersPerSecond: Double?,
    val velocityNorthMetersPerSecond: Double?,
    val velocityUncertaintyMetersPerSecond: Double?,
    val speedMeasurementUsed: Boolean,
    val accepted: Boolean,
    val reasonCode: String,
)

data class GeoCoordinate(val latitudeDegrees: Double, val longitudeDegrees: Double)

/** Constant-velocity Kalman filter in a local east/north tangent plane (LocoKit2 LK-02). */
class LocationKalmanFilter(
    private val accelerationNoiseMetersPerSecondSquared: Double = DEFAULT_ACCELERATION_NOISE_MPS2,
    private val outlierSigmaLimit: Double = DEFAULT_OUTLIER_SIGMA_LIMIT,
) {
    private var projection: LocalEnuProjection? = null
    private var state = DoubleArray(STATE_SIZE)
    private var covariance = Array(STATE_SIZE) { DoubleArray(STATE_SIZE) }
    private var lastElapsedNanos: Long? = null

    init {
        require(accelerationNoiseMetersPerSecondSquared > 0.0)
        require(outlierSigmaLimit > 0.0)
    }

    @Synchronized
    fun update(measurement: LocationMeasurement, elapsedRealtimeNanos: Long): LocationFilterResult {
        val raw = GeoCoordinate(measurement.latitudeDegrees, measurement.longitudeDegrees)
        if (!raw.isValid()) return rejected(raw, "location.invalid_coordinates")
        val accuracy = measurement.horizontalAccuracyMeters
        if (accuracy == null || !accuracy.isFinite() || accuracy <= 0.0) {
            return rejected(raw, "location.missing_horizontal_accuracy")
        }
        val previousNanos = lastElapsedNanos
        if (previousNanos != null && elapsedRealtimeNanos <= previousNanos) {
            return rejected(raw, "location.stale_monotonic_time")
        }

        if (projection == null) {
            projection = LocalEnuProjection(raw)
            state[POS_EAST] = 0.0
            state[POS_NORTH] = 0.0
            covariance = zeroMatrix()
            covariance[POS_EAST][POS_EAST] = accuracy * accuracy
            covariance[POS_NORTH][POS_NORTH] = accuracy * accuracy
            covariance[VEL_EAST][VEL_EAST] = INITIAL_VELOCITY_VARIANCE
            covariance[VEL_NORTH][VEL_NORTH] = INITIAL_VELOCITY_VARIANCE
            lastElapsedNanos = elapsedRealtimeNanos
            val usedSpeedMeasurement = updateVelocityIfReliable(measurement)
            return accepted(raw, "location.filter_initialized", usedSpeedMeasurement)
        }

        val dt = (elapsedRealtimeNanos - previousNanos!!) / NANOS_PER_SECOND
        predict(dt)
        val (east, north) = projection!!.project(raw)
        val eastResidual = east - state[POS_EAST]
        val northResidual = north - state[POS_NORTH]
        val eastVariance = covariance[POS_EAST][POS_EAST] + accuracy * accuracy
        val northVariance = covariance[POS_NORTH][POS_NORTH] + accuracy * accuracy
        val innovationSigma = sqrt(eastResidual * eastResidual / eastVariance + northResidual * northResidual / northVariance)
        lastElapsedNanos = elapsedRealtimeNanos
        if (innovationSigma > outlierSigmaLimit) {
            return currentResult(raw, accepted = false, reason = "location.innovation_outlier")
        }

        updateScalar(POS_EAST, east, accuracy * accuracy)
        updateScalar(POS_NORTH, north, accuracy * accuracy)
        val usedSpeedMeasurement = updateVelocityIfReliable(measurement)
        return accepted(raw, "location.filter_updated", usedSpeedMeasurement)
    }

    @Synchronized
    fun snapshot(): FilterSnapshot? = if (projection == null) {
        null
    } else {
        FilterSnapshot(
            xMeters = state[POS_EAST],
            yMeters = state[POS_NORTH],
            velocityXMetersPerSecond = state[VEL_EAST],
            velocityYMetersPerSecond = state[VEL_NORTH],
            covariance = covariance.flatMap { it.toList() },
            projectionOriginLatitudeDegrees = projection!!.origin.latitudeDegrees,
            projectionOriginLongitudeDegrees = projection!!.origin.longitudeDegrees,
            lastElapsedRealtimeNanos = lastElapsedNanos
                ?: error("Initialized projection must have a monotonic timestamp"),
        )
    }

    @Synchronized
    fun currentCoordinate(): GeoCoordinate? = projection?.unproject(state[POS_EAST], state[POS_NORTH])

    /** Restores the exact filter state; callers must reject checkpoints from a different boot epoch. */
    @Synchronized
    fun restore(snapshot: FilterSnapshot) {
        val restoredState = doubleArrayOf(
            snapshot.xMeters,
            snapshot.yMeters,
            snapshot.velocityXMetersPerSecond,
            snapshot.velocityYMetersPerSecond,
        )
        require(restoredState.all(Double::isFinite))
        val restoredCovariance = Array(STATE_SIZE) { row ->
            DoubleArray(STATE_SIZE) { column -> snapshot.covariance[row * STATE_SIZE + column] }
        }
        require((0 until STATE_SIZE).all { restoredCovariance[it][it] >= 0.0 })
        require((0 until STATE_SIZE).all { row ->
            (0 until STATE_SIZE).all { column ->
                kotlin.math.abs(restoredCovariance[row][column] - restoredCovariance[column][row]) <= COVARIANCE_SYMMETRY_EPSILON
            }
        })
        projection = LocalEnuProjection(
            GeoCoordinate(snapshot.projectionOriginLatitudeDegrees, snapshot.projectionOriginLongitudeDegrees),
        )
        state = restoredState
        covariance = restoredCovariance
        lastElapsedNanos = snapshot.lastElapsedRealtimeNanos
    }

    @Synchronized
    fun reset() {
        projection = null
        state = DoubleArray(STATE_SIZE)
        covariance = zeroMatrix()
        lastElapsedNanos = null
    }

    private fun predict(dtSeconds: Double) {
        val dt = dtSeconds.coerceAtMost(MAX_PREDICTION_STEP_SECONDS)
        val transition = identityMatrix().apply {
            this[POS_EAST][VEL_EAST] = dt
            this[POS_NORTH][VEL_NORTH] = dt
        }
        state = multiply(transition, state)
        covariance = multiply(multiply(transition, covariance), transpose(transition))
        val q = accelerationNoiseMetersPerSecondSquared * accelerationNoiseMetersPerSecondSquared
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt2 * dt2
        for ((position, velocity) in listOf(POS_EAST to VEL_EAST, POS_NORTH to VEL_NORTH)) {
            covariance[position][position] += q * dt4 / 4.0
            covariance[position][velocity] += q * dt3 / 2.0
            covariance[velocity][position] += q * dt3 / 2.0
            covariance[velocity][velocity] += q * dt2
        }
    }

    private fun updateScalar(index: Int, value: Double, variance: Double) {
        val innovationVariance = covariance[index][index] + variance
        if (innovationVariance <= 0.0 || !innovationVariance.isFinite()) return
        val gain = DoubleArray(STATE_SIZE) { covariance[it][index] / innovationVariance }
        val residual = value - state[index]
        for (i in 0 until STATE_SIZE) state[i] += gain[i] * residual
        val row = covariance[index].copyOf()
        for (i in 0 until STATE_SIZE) {
            for (j in 0 until STATE_SIZE) covariance[i][j] -= gain[i] * row[j]
        }
        // Keep tiny floating-point asymmetry from accumulating across thousands of updates.
        for (i in 0 until STATE_SIZE) {
            for (j in i + 1 until STATE_SIZE) {
                val average = (covariance[i][j] + covariance[j][i]) / 2.0
                covariance[i][j] = average
                covariance[j][i] = average
            }
        }
    }

    private fun updateVelocityIfReliable(measurement: LocationMeasurement): Boolean {
        val speed = measurement.speedMetersPerSecond ?: return false
        val bearing = measurement.bearingDegrees ?: return false
        val speedAccuracy = measurement.speedAccuracyMetersPerSecond ?: return false
        if (!speed.isFinite() || speed < 0.0 || !bearing.isFinite() ||
            !speedAccuracy.isFinite() || speedAccuracy <= 0.0
        ) return false
        val radians = Math.toRadians(bearing)
        val variance = speedAccuracy * speedAccuracy
        updateScalar(VEL_EAST, speed * sin(radians), variance)
        updateScalar(VEL_NORTH, speed * cos(radians), variance)
        return true
    }

    private fun accepted(raw: GeoCoordinate, reason: String, speedMeasurementUsed: Boolean) =
        currentResult(raw, accepted = true, reason = reason, speedMeasurementUsed = speedMeasurementUsed)

    private fun rejected(raw: GeoCoordinate, reason: String) =
        LocationFilterResult(
            raw, null, null, null, null, null, speedMeasurementUsed = false,
            accepted = false, reasonCode = reason,
        )

    private fun currentResult(
        raw: GeoCoordinate,
        accepted: Boolean,
        reason: String,
        speedMeasurementUsed: Boolean = false,
    ): LocationFilterResult {
        val plane = projection ?: return rejected(raw, reason)
        return LocationFilterResult(
            raw = raw,
            filtered = plane.unproject(state[POS_EAST], state[POS_NORTH]),
            horizontalUncertaintyMeters = sqrt(
                (covariance[POS_EAST][POS_EAST] + covariance[POS_NORTH][POS_NORTH]).coerceAtLeast(0.0),
            ),
            velocityEastMetersPerSecond = state[VEL_EAST],
            velocityNorthMetersPerSecond = state[VEL_NORTH],
            velocityUncertaintyMetersPerSecond = sqrt(
                (covariance[VEL_EAST][VEL_EAST] + covariance[VEL_NORTH][VEL_NORTH]).coerceAtLeast(0.0),
            ),
            speedMeasurementUsed = speedMeasurementUsed,
            accepted = accepted,
            reasonCode = reason,
        )
    }

    private fun GeoCoordinate.isValid() =
        latitudeDegrees.isFinite() && longitudeDegrees.isFinite() &&
            latitudeDegrees in -90.0..90.0 && longitudeDegrees in -180.0..180.0

    private fun zeroMatrix() = Array(STATE_SIZE) { DoubleArray(STATE_SIZE) }

    private fun identityMatrix() = zeroMatrix().apply {
        for (i in 0 until STATE_SIZE) this[i][i] = 1.0
    }

    private fun transpose(matrix: Array<DoubleArray>) = Array(STATE_SIZE) { row ->
        DoubleArray(STATE_SIZE) { column -> matrix[column][row] }
    }

    private fun multiply(left: Array<DoubleArray>, right: Array<DoubleArray>) =
        Array(STATE_SIZE) { row ->
            DoubleArray(STATE_SIZE) { column ->
                (0 until STATE_SIZE).sumOf { left[row][it] * right[it][column] }
            }
        }

    private fun multiply(matrix: Array<DoubleArray>, vector: DoubleArray) =
        DoubleArray(STATE_SIZE) { row -> (0 until STATE_SIZE).sumOf { matrix[row][it] * vector[it] } }

    private class LocalEnuProjection(val origin: GeoCoordinate) {
        private val originLat = Math.toRadians(origin.latitudeDegrees)
        private val originLon = Math.toRadians(origin.longitudeDegrees)
        private val originX = EARTH_RADIUS_METERS * cos(originLat) * cos(originLon)
        private val originY = EARTH_RADIUS_METERS * cos(originLat) * sin(originLon)
        private val originZ = EARTH_RADIUS_METERS * sin(originLat)
        private val eastX = -sin(originLon)
        private val eastY = cos(originLon)
        private val northX = -sin(originLat) * cos(originLon)
        private val northY = -sin(originLat) * sin(originLon)
        private val northZ = cos(originLat)

        fun project(point: GeoCoordinate): Pair<Double, Double> {
            val lat = Math.toRadians(point.latitudeDegrees)
            val lon = Math.toRadians(point.longitudeDegrees)
            val x = EARTH_RADIUS_METERS * cos(lat) * cos(lon) - originX
            val y = EARTH_RADIUS_METERS * cos(lat) * sin(lon) - originY
            val z = EARTH_RADIUS_METERS * sin(lat) - originZ
            return (x * eastX + y * eastY) to (x * northX + y * northY + z * northZ)
        }

        fun unproject(east: Double, north: Double): GeoCoordinate {
            val x = originX + east * eastX + north * northX
            val y = originY + east * eastY + north * northY
            val z = originZ + north * northZ
            val radius = sqrt(x * x + y * y + z * z)
            return GeoCoordinate(Math.toDegrees(asin(z / radius)), Math.toDegrees(atan2(y, x)))
        }
    }

    companion object {
        private const val STATE_SIZE = 4
        private const val POS_EAST = 0
        private const val POS_NORTH = 1
        private const val VEL_EAST = 2
        private const val VEL_NORTH = 3
        private const val EARTH_RADIUS_METERS = 6_371_008.8
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val INITIAL_VELOCITY_VARIANCE = 25.0
        const val DEFAULT_ACCELERATION_NOISE_MPS2 = 2.5
        const val DEFAULT_OUTLIER_SIGMA_LIMIT = 12.0
        private const val MAX_PREDICTION_STEP_SECONDS = 120.0
        private const val COVARIANCE_SYMMETRY_EPSILON = 1e-8
    }
}
