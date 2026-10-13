package io.github.valeronm.breadcrumb.location

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import io.github.valeronm.breadcrumb.locomotion.InertialReading
import io.github.valeronm.breadcrumb.locomotion.SensorFeature
import io.github.valeronm.breadcrumb.locomotion.SensorWindow
import io.github.valeronm.breadcrumb.locomotion.SensorWindowAggregator
import io.github.valeronm.breadcrumb.util.isGranted

/** Android sensor adapter for the platform-free feature-window aggregator. */
class MotionSensorSampler(
    private val context: Context,
    private val onWindow: (SensorWindow) -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensors by lazy {
        listOfNotNull(
            manager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
            manager?.getDefaultSensor(Sensor.TYPE_GRAVITY),
            manager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.takeIf { hasActivityRecognitionPermission() },
            manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.takeIf { hasActivityRecognitionPermission() },
        )
    }
    private val aggregator by lazy {
        val available = buildSet {
            if (sensors.any { it.type == Sensor.TYPE_LINEAR_ACCELERATION }) add(SensorFeature.ACCELEROMETER)
            if (sensors.any { it.type == Sensor.TYPE_GRAVITY }) add(SensorFeature.GRAVITY)
            if (sensors.any { it.type == Sensor.TYPE_STEP_COUNTER }) add(SensorFeature.STEP_COUNTER)
            if (sensors.any { it.type == Sensor.TYPE_STEP_DETECTOR }) add(SensorFeature.STEP_DETECTOR)
        }
        SensorWindowAggregator(availableFeatures = available)
    }

    private fun hasActivityRecognitionPermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || context.isGranted(Manifest.permission.ACTIVITY_RECOGNITION)

    private var started = false
    private val registeredSensorTypes = mutableSetOf<Int>()
    private var accelerationX: Double? = null
    private var accelerationY: Double? = null
    private var accelerationZ: Double? = null
    private var gravityX: Double? = null
    private var gravityY: Double? = null
    private var gravityZ: Double? = null
    private var cumulativeSteps: Long? = null
    private var lastAccelerationTimestamp: Long? = null
    private var accelerationIntervalTotalNanos = 0L
    private var accelerationIntervalCount = 0L

    @Synchronized
    fun start(): SensorSamplerStatus {
        if (started) return status()
        started = true
        sensors.forEach { sensor ->
            try {
                if (manager?.registerListener(this, sensor, REQUESTED_PERIOD_MICROS) == true) {
                    registeredSensorTypes += sensor.type
                }
            } catch (_: SecurityException) {
                // Step sensors can lose ACTIVITY_RECOGNITION between the pre-check and registration.
            }
        }
        return status()
    }

    @Synchronized
    fun stop() {
        if (started) manager?.unregisterListener(this)
        started = false
        registeredSensorTypes.clear()
        aggregator.reset()
        accelerationX = null
        accelerationY = null
        accelerationZ = null
        gravityX = null
        gravityY = null
        gravityZ = null
        cumulativeSteps = null
        lastAccelerationTimestamp = null
        accelerationIntervalTotalNanos = 0L
        accelerationIntervalCount = 0L
    }

    @Synchronized
    fun status(): SensorSamplerStatus {
        val registeredTypes = registeredSensorTypes.toSet()
        val accelerationPeriod = if (accelerationIntervalCount == 0L) {
            null
        } else {
            (accelerationIntervalTotalNanos / accelerationIntervalCount / NANOS_PER_MICROSECOND).toInt()
        }
        return SensorSamplerStatus(
            availableSensors = sensors.map { SensorDescriptor(it.type, it.name, it.vendor, it.isWakeUpSensor) },
            registeredSensorTypes = registeredTypes,
            requestedPeriodMicros = REQUESTED_PERIOD_MICROS,
            measuredAccelerationPeriodMicros = accelerationPeriod,
            running = started,
        )
    }

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        val timestamp = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val previous = lastAccelerationTimestamp
                if (previous != null && timestamp > previous) {
                    accelerationIntervalTotalNanos += timestamp - previous
                    accelerationIntervalCount++
                }
                lastAccelerationTimestamp = timestamp
                accelerationX = event.values.getOrNull(0)?.toDouble()
                accelerationY = event.values.getOrNull(1)?.toDouble()
                accelerationZ = event.values.getOrNull(2)?.toDouble()
            }
            Sensor.TYPE_GRAVITY -> {
                gravityX = event.values.getOrNull(0)?.toDouble()
                gravityY = event.values.getOrNull(1)?.toDouble()
                gravityZ = event.values.getOrNull(2)?.toDouble()
                return
            }
            Sensor.TYPE_STEP_COUNTER -> {
                cumulativeSteps = event.values.firstOrNull()?.toLong()
            }
        }
        val window = aggregator.add(
            InertialReading(
                elapsedRealtimeNanos = timestamp,
                linearAccelerationXMetersPerSecondSquared = accelerationX,
                linearAccelerationYMetersPerSecondSquared = accelerationY,
                linearAccelerationZMetersPerSecondSquared = accelerationZ,
                gravityXMetersPerSecondSquared = gravityX,
                gravityYMetersPerSecondSquared = gravityY,
                gravityZMetersPerSecondSquared = gravityZ,
                cumulativeSteps = cumulativeSteps,
                stepDetected = event.sensor.type == Sensor.TYPE_STEP_DETECTOR,
            ),
        )
        if (window != null) onWindow(window)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val REQUESTED_PERIOD_MICROS = 40_000
        private const val NANOS_PER_MICROSECOND = 1_000L
    }
}

data class SensorSamplerStatus(
    val availableSensors: List<SensorDescriptor>,
    val registeredSensorTypes: Set<Int>,
    val requestedPeriodMicros: Int,
    val measuredAccelerationPeriodMicros: Int?,
    val running: Boolean,
)

data class SensorDescriptor(val type: Int, val name: String, val vendor: String, val wakeUp: Boolean)
