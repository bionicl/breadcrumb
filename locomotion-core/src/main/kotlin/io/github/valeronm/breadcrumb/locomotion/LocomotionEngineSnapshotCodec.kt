package io.github.valeronm.breadcrumb.locomotion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Versioned binary representation for the deterministic state persisted by the Android adapter. */
object LocomotionEngineSnapshotCodec {
    private const val MAGIC = 0x4C4B4532 // LKE2
    private const val VERSION = 1
    private const val MAX_COLLECTION_SIZE = 512

    fun encode(snapshot: LocomotionEngineSnapshot): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeNullableString(snapshot.bootEpochId)
            output.writeNullableLong(snapshot.lastProcessedElapsedRealtimeNanos)
            output.writeNullableString(snapshot.lastProcessedObservationId)
            output.writeNullableCoordinate(snapshot.lastFilteredCoordinate)
            output.writeNullableFilter(snapshot.filter)
            output.writeNullableSensorWindow(snapshot.lastSensorWindow)
            output.writeNullableString(snapshot.lastSensorObservationId)
            output.writeNullableActivity(snapshot.lastActivity)
            output.writeNullableString(snapshot.lastActivityObservationId)
            output.writeDetector(snapshot.motionDetector)
            output.writeNullableMotion(snapshot.latestMotion)
            output.writeNullableMotionSignal(snapshot.latestMotionSignal)
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): LocomotionEngineSnapshot? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val snapshot = LocomotionEngineSnapshot(
                bootEpochId = input.readNullableString(),
                lastProcessedElapsedRealtimeNanos = input.readNullableLong(),
                lastProcessedObservationId = input.readNullableString(),
                lastFilteredCoordinate = input.readNullableCoordinate(),
                filter = input.readNullableFilter(),
                lastSensorWindow = input.readNullableSensorWindow(),
                lastSensorObservationId = input.readNullableString(),
                lastActivity = input.readNullableActivity(),
                lastActivityObservationId = input.readNullableString(),
                motionDetector = input.readDetector(),
                latestMotion = input.readNullableMotion(),
                latestMotionSignal = input.readNullableMotionSignal(),
            )
            require(input.available() == 0)
            snapshot
        }
    }.getOrNull()

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataOutputStream.writeNullableDouble(value: Double?) {
        writeBoolean(value != null)
        if (value != null) writeDouble(value)
    }

    private fun DataInputStream.readNullableDouble(): Double? = if (readBoolean()) readDouble() else null

    private fun DataOutputStream.writeNullableCoordinate(value: GeoCoordinate?) {
        writeBoolean(value != null)
        if (value != null) {
            writeDouble(value.latitudeDegrees)
            writeDouble(value.longitudeDegrees)
        }
    }

    private fun DataInputStream.readNullableCoordinate(): GeoCoordinate? =
        if (readBoolean()) GeoCoordinate(readDouble(), readDouble()) else null

    private fun DataOutputStream.writeNullableFilter(value: FilterSnapshot?) {
        writeBoolean(value != null)
        if (value != null) {
            writeDouble(value.xMeters)
            writeDouble(value.yMeters)
            writeDouble(value.velocityXMetersPerSecond)
            writeDouble(value.velocityYMetersPerSecond)
            writeInt(value.covariance.size)
            value.covariance.forEach(::writeDouble)
            writeDouble(value.projectionOriginLatitudeDegrees)
            writeDouble(value.projectionOriginLongitudeDegrees)
            writeLong(value.lastElapsedRealtimeNanos)
        }
    }

    private fun DataInputStream.readNullableFilter(): FilterSnapshot? {
        if (!readBoolean()) return null
        val x = readDouble()
        val y = readDouble()
        val velocityX = readDouble()
        val velocityY = readDouble()
        val covarianceSize = readInt().also { require(it == 16) }
        val covariance = List(covarianceSize) { readDouble() }
        return FilterSnapshot(
            xMeters = x,
            yMeters = y,
            velocityXMetersPerSecond = velocityX,
            velocityYMetersPerSecond = velocityY,
            covariance = covariance,
            projectionOriginLatitudeDegrees = readDouble(),
            projectionOriginLongitudeDegrees = readDouble(),
            lastElapsedRealtimeNanos = readLong(),
        )
    }

    private fun DataOutputStream.writeNullableSensorWindow(value: SensorWindow?) {
        writeBoolean(value != null)
        if (value != null) {
            writeLong(value.startElapsedRealtimeNanos)
            writeLong(value.endElapsedRealtimeNanos)
            writeInt(value.sampleCount)
            writeNullableDouble(value.accelerationHorizontalMeanMetersPerSecondSquared)
            writeNullableDouble(value.accelerationHorizontalStdDevMetersPerSecondSquared)
            writeNullableDouble(value.accelerationVerticalMeanMetersPerSecondSquared)
            writeNullableDouble(value.accelerationVerticalStdDevMetersPerSecondSquared)
            writeNullableDouble(value.stepCadenceHz)
            writeNullableDouble(value.barometricAltitudeDeltaMeters)
            writeInt(value.missingFeatures.size)
            value.missingFeatures.sortedBy { it.name }.forEach { writeUTF(it.name) }
        }
    }

    private fun DataInputStream.readNullableSensorWindow(): SensorWindow? {
        if (!readBoolean()) return null
        val start = readLong()
        val end = readLong()
        val sampleCount = readInt()
        val horizontalMean = readNullableDouble()
        val horizontalStd = readNullableDouble()
        val verticalMean = readNullableDouble()
        val verticalStd = readNullableDouble()
        val cadence = readNullableDouble()
        val altitudeDelta = readNullableDouble()
        val missingCount = readInt().boundedCount()
        val missing = List(missingCount) { enumValueOf<SensorFeature>(readUTF()) }.toSet()
        return SensorWindow(
            startElapsedRealtimeNanos = start,
            endElapsedRealtimeNanos = end,
            sampleCount = sampleCount,
            accelerationHorizontalMeanMetersPerSecondSquared = horizontalMean,
            accelerationHorizontalStdDevMetersPerSecondSquared = horizontalStd,
            accelerationVerticalMeanMetersPerSecondSquared = verticalMean,
            accelerationVerticalStdDevMetersPerSecondSquared = verticalStd,
            stepCadenceHz = cadence,
            barometricAltitudeDeltaMeters = altitudeDelta,
            missingFeatures = missing,
        )
    }

    private fun DataOutputStream.writeNullableActivity(value: DetectedActivityEvidence?) {
        writeBoolean(value != null)
        if (value != null) {
            writeUTF(value.activity.name)
            writeInt(value.confidencePercent)
            writeLong(value.observedElapsedRealtimeNanos)
        }
    }

    private fun DataInputStream.readNullableActivity(): DetectedActivityEvidence? =
        if (readBoolean()) {
            DetectedActivityEvidence(
                activity = enumValueOf(readUTF()),
                confidencePercent = readInt(),
                observedElapsedRealtimeNanos = readLong(),
            )
        } else {
            null
        }

    private fun DataOutputStream.writeDetector(value: MotionStateDetectorSnapshot) {
        writeNullableLong(value.lastWindowEndElapsedRealtimeNanos)
        writeUTF(value.confirmedState.name)
        writeNullableEnum(value.candidateState)
        writeNullableLong(value.candidateStartElapsedRealtimeNanos)
        writeInt(value.candidateCount)
    }

    private fun DataInputStream.readDetector() = MotionStateDetectorSnapshot(
        lastWindowEndElapsedRealtimeNanos = readNullableLong(),
        confirmedState = enumValueOf(readUTF()),
        candidateState = readNullableEnum(),
        candidateStartElapsedRealtimeNanos = readNullableLong(),
        candidateCount = readInt(),
    )

    private fun DataOutputStream.writeNullableEnum(value: Enum<*>?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value.name)
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readNullableEnum(): T? =
        if (readBoolean()) enumValueOf(readUTF()) else null

    private fun DataOutputStream.writeNullableMotion(value: MotionEstimate?) {
        writeBoolean(value != null)
        if (value != null) {
            writeUTF(value.state.name)
            writeFloat(value.confidence)
            writeLong(value.sinceElapsedRealtimeNanos)
            writeInt(value.evidence.size)
            value.evidence.forEach {
                writeUTF(it.observationId)
                writeUTF(it.role)
            }
            writeUTF(value.reasonCode)
        }
    }

    private fun DataInputStream.readNullableMotion(): MotionEstimate? {
        if (!readBoolean()) return null
        val state = enumValueOf<MotionState>(readUTF())
        val confidence = readFloat()
        val since = readLong()
        val count = readInt().boundedCount()
        val evidence = List(count) { EvidenceRef(readUTF(), readUTF()) }
        return MotionEstimate(state, confidence, since, evidence, readUTF())
    }

    private fun DataOutputStream.writeNullableMotionSignal(value: EngineMotionSignal?) {
        writeBoolean(value != null)
        if (value != null) {
            writeUTF(value.state.name)
            writeFloat(value.confidence)
            writeNullableDouble(value.speedMetersPerSecond)
            writeLong(value.observedElapsedRealtimeNanos)
        }
    }

    private fun DataInputStream.readNullableMotionSignal(): EngineMotionSignal? =
        if (readBoolean()) {
            EngineMotionSignal(
                state = enumValueOf(readUTF()),
                confidence = readFloat(),
                speedMetersPerSecond = readNullableDouble(),
                observedElapsedRealtimeNanos = readLong(),
            )
        } else {
            null
        }

    private fun Int.boundedCount(): Int = also { require(it in 0..MAX_COLLECTION_SIZE) }
}
