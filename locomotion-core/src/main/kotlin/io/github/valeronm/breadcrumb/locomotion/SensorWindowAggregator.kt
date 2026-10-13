package io.github.valeronm.breadcrumb.locomotion

import kotlin.math.sqrt

/** Android adapter feeds SI-unit readings here; this type contains no Android sensor classes. */
data class InertialReading(
    val elapsedRealtimeNanos: Long,
    val linearAccelerationXMetersPerSecondSquared: Double? = null,
    val linearAccelerationYMetersPerSecondSquared: Double? = null,
    val linearAccelerationZMetersPerSecondSquared: Double? = null,
    val gravityXMetersPerSecondSquared: Double? = null,
    val gravityYMetersPerSecondSquared: Double? = null,
    val gravityZMetersPerSecondSquared: Double? = null,
    /** Cumulative Android step counter, when available. */
    val cumulativeSteps: Long? = null,
    /** True for a single TYPE_STEP_DETECTOR event. */
    val stepDetected: Boolean = false,
)

/** Produces bounded, overlapping feature windows (LocoKit2 LK-04). */
class SensorWindowAggregator(
    private val availableFeatures: Set<SensorFeature>,
    private val windowDurationNanos: Long = DEFAULT_WINDOW_DURATION_NANOS,
    private val strideNanos: Long = DEFAULT_STRIDE_NANOS,
    private val maximumRetainedSamples: Int = DEFAULT_MAXIMUM_RETAINED_SAMPLES,
) {
    private val readings = ArrayDeque<InertialReading>()
    private var lastAcceptedNanos: Long? = null
    private var lastEmittedNanos: Long? = null

    init {
        require(windowDurationNanos > 0)
        require(strideNanos > 0)
        require(maximumRetainedSamples > 0)
    }

    /** A late or duplicate sensor callback cannot rewind the current live window. */
    @Synchronized
    fun add(reading: InertialReading): SensorWindow? {
        val previous = lastAcceptedNanos
        if (previous != null && reading.elapsedRealtimeNanos <= previous) return null
        if (previous != null && reading.elapsedRealtimeNanos - previous > RESET_GAP_NANOS) {
            readings.clear()
            lastEmittedNanos = null
        }
        readings.addLast(reading)
        lastAcceptedNanos = reading.elapsedRealtimeNanos
        while (readings.size > maximumRetainedSamples) readings.removeFirst()
        while (readings.size > 1 &&
            reading.elapsedRealtimeNanos - readings.first().elapsedRealtimeNanos > windowDurationNanos
        ) {
            readings.removeFirst()
        }

        val start = readings.firstOrNull()?.elapsedRealtimeNanos ?: return null
        if (reading.elapsedRealtimeNanos - start < windowDurationNanos) return null
        val lastWindow = lastEmittedNanos
        if (lastWindow != null && reading.elapsedRealtimeNanos - lastWindow < strideNanos) return null
        lastEmittedNanos = reading.elapsedRealtimeNanos
        return summarize(readings.toList())
    }

    @Synchronized
    fun reset() {
        readings.clear()
        lastAcceptedNanos = null
        lastEmittedNanos = null
    }

    private fun summarize(window: List<InertialReading>): SensorWindow {
        val acceleration = window.mapNotNull(::accelerationMagnitudes)
        val horizontal = acceleration.map { it.first }
        val vertical = acceleration.mapNotNull { it.second }
        val steps = stepCount(window)
        val durationSeconds =
            (window.last().elapsedRealtimeNanos - window.first().elapsedRealtimeNanos) / NANOS_PER_SECOND
        val cadence = if (durationSeconds > 0.0 && steps != null) steps / durationSeconds else null
        val observedFeatures = buildSet {
            if (acceleration.isNotEmpty()) add(SensorFeature.ACCELEROMETER)
            if (vertical.isNotEmpty()) add(SensorFeature.ROTATION_VECTOR)
            if (window.any { it.cumulativeSteps != null }) add(SensorFeature.STEP_COUNTER)
            if (window.any { it.stepDetected }) add(SensorFeature.STEP_DETECTOR)
        }
        return SensorWindow(
            startElapsedRealtimeNanos = window.first().elapsedRealtimeNanos,
            endElapsedRealtimeNanos = window.last().elapsedRealtimeNanos,
            sampleCount = acceleration.size,
            accelerationHorizontalMeanMetersPerSecondSquared = horizontal.meanOrNull(),
            accelerationHorizontalStdDevMetersPerSecondSquared = horizontal.stdDevOrNull(),
            accelerationVerticalMeanMetersPerSecondSquared = vertical.meanOrNull(),
            accelerationVerticalStdDevMetersPerSecondSquared = vertical.stdDevOrNull(),
            stepCadenceHz = cadence,
            missingFeatures = availableFeatures - observedFeatures,
        )
    }

    /** Return orientation-independent horizontal magnitude and gravity-axis vertical acceleration. */
    private fun accelerationMagnitudes(reading: InertialReading): Pair<Double, Double?>? {
        val x = reading.linearAccelerationXMetersPerSecondSquared ?: return null
        val y = reading.linearAccelerationYMetersPerSecondSquared ?: return null
        val z = reading.linearAccelerationZMetersPerSecondSquared ?: return null
        val total = sqrt(x * x + y * y + z * z)
        val gx = reading.gravityXMetersPerSecondSquared
        val gy = reading.gravityYMetersPerSecondSquared
        val gz = reading.gravityZMetersPerSecondSquared
        val gravityMagnitude = if (gx != null && gy != null && gz != null) sqrt(gx * gx + gy * gy + gz * gz) else 0.0
        val vertical = if (gravityMagnitude > MIN_GRAVITY_MPS2) {
            (x * gx!! + y * gy!! + z * gz!!) / gravityMagnitude
        } else {
            null
        }
        val horizontal = if (vertical == null) total else sqrt((total * total - vertical * vertical).coerceAtLeast(0.0))
        return horizontal to vertical
    }

    /** Prefer the cumulative counter, falling back to detector events when that counter is absent. */
    private fun stepCount(window: List<InertialReading>): Double? {
        val counterReadings = window.mapNotNull { it.cumulativeSteps }
        if (counterReadings.size >= 2) {
            return (counterReadings.last() - counterReadings.first()).coerceAtLeast(0).toDouble()
        }
        return if (window.any { it.stepDetected }) window.count { it.stepDetected }.toDouble() else null
    }

    private fun List<Double>.meanOrNull(): Double? = if (isEmpty()) null else average()

    private fun List<Double>.stdDevOrNull(): Double? {
        if (isEmpty()) return null
        val mean = average()
        return sqrt(sumOf { (it - mean) * (it - mean) } / size)
    }

    companion object {
        const val DEFAULT_WINDOW_DURATION_NANOS = 30_000_000_000L
        const val DEFAULT_STRIDE_NANOS = 5_000_000_000L
        const val DEFAULT_MAXIMUM_RETAINED_SAMPLES = 1000
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val RESET_GAP_NANOS = 10 * 60 * 1_000_000_000L
        private const val MIN_GRAVITY_MPS2 = 1.0
    }
}
