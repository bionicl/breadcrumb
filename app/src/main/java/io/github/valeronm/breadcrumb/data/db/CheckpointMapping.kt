package io.github.valeronm.breadcrumb.data.db

import io.github.valeronm.breadcrumb.locomotion.EngineCheckpoint
import io.github.valeronm.breadcrumb.locomotion.FilterSnapshot
import io.github.valeronm.breadcrumb.locomotion.LocomotionEngineSnapshotCodec
import io.github.valeronm.breadcrumb.locomotion.MotionState
import io.github.valeronm.breadcrumb.locomotion.RecordingState
import java.nio.ByteBuffer

fun EngineCheckpoint.toEntity() = EngineCheckpointEntity(
    sessionId = sessionId,
    bootEpochId = bootEpochId,
    lastObservationId = lastObservationId,
    lastElapsedRealtimeNanos = lastElapsedRealtimeNanos,
    lastWallTimeMs = lastWallTimeMs,
    motionState = motionState.name,
    recordingState = recordingState.name,
    filteredXmeters = filter?.xMeters,
    filteredYmeters = filter?.yMeters,
    filteredVelocityXMetersPerSecond = filter?.velocityXMetersPerSecond,
    filteredVelocityYMetersPerSecond = filter?.velocityYMetersPerSecond,
    covarianceXX = filter?.covariance?.get(0),
    covarianceYY = filter?.covariance?.get(5),
    filterCovariance = filter?.covariance?.let { values ->
        ByteBuffer.allocate(values.size * java.lang.Double.BYTES).apply {
            values.forEach(::putDouble)
        }.array()
    },
    filterProjectionOriginLatitudeDegrees = filter?.projectionOriginLatitudeDegrees,
    filterProjectionOriginLongitudeDegrees = filter?.projectionOriginLongitudeDegrees,
    engineStateBlob = engineSnapshot?.let(LocomotionEngineSnapshotCodec::encode),
    activeSegmentId = activeSegmentId,
    configurationVersion = configurationVersion,
    modelVersion = modelVersion,
    updatedAtWallTimeMs = updatedAtWallTimeMs,
)

fun EngineCheckpointEntity.toCore() = EngineCheckpoint(
    sessionId = sessionId,
    bootEpochId = bootEpochId,
    lastObservationId = lastObservationId,
    lastElapsedRealtimeNanos = lastElapsedRealtimeNanos,
    lastWallTimeMs = lastWallTimeMs,
    motionState = MotionState.valueOf(motionState),
    recordingState = RecordingState.valueOf(recordingState),
    filter = filterSnapshot(),
    activeSegmentId = activeSegmentId,
    configurationVersion = configurationVersion,
    modelVersion = modelVersion,
    updatedAtWallTimeMs = updatedAtWallTimeMs,
    engineSnapshot = engineStateBlob?.let(LocomotionEngineSnapshotCodec::decode),
)

private fun EngineCheckpointEntity.filterSnapshot(): FilterSnapshot? {
    engineStateBlob?.let(LocomotionEngineSnapshotCodec::decode)?.filter?.let { return it }
    val x = filteredXmeters ?: return null
    val y = filteredYmeters ?: return null
    val velocityX = filteredVelocityXMetersPerSecond ?: return null
    val velocityY = filteredVelocityYMetersPerSecond ?: return null
    val covarianceBytes = filterCovariance ?: return null
    if (covarianceBytes.size != 16 * java.lang.Double.BYTES) return null
    val originLatitude = filterProjectionOriginLatitudeDegrees ?: return null
    val originLongitude = filterProjectionOriginLongitudeDegrees ?: return null
    val covariance = ByteBuffer.wrap(covarianceBytes).asDoubleBuffer().let { buffer ->
        List(buffer.remaining()) { buffer.get() }
    }
    val elapsedNanos = lastElapsedRealtimeNanos ?: return null
    return runCatching {
        FilterSnapshot(
            xMeters = x,
            yMeters = y,
            velocityXMetersPerSecond = velocityX,
            velocityYMetersPerSecond = velocityY,
            covariance = covariance,
            projectionOriginLatitudeDegrees = originLatitude,
            projectionOriginLongitudeDegrees = originLongitude,
            lastElapsedRealtimeNanos = elapsedNanos,
        )
    }.getOrNull()
}
