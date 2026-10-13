package io.github.valeronm.breadcrumb.data.db

import io.github.valeronm.breadcrumb.locomotion.EngineCheckpoint
import io.github.valeronm.breadcrumb.locomotion.FilterSnapshot
import io.github.valeronm.breadcrumb.locomotion.MotionState
import io.github.valeronm.breadcrumb.locomotion.RecordingState

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
    covarianceXX = filter?.covarianceXX,
    covarianceYY = filter?.covarianceYY,
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
)

private fun EngineCheckpointEntity.filterSnapshot(): FilterSnapshot? {
    val x = filteredXmeters ?: return null
    val y = filteredYmeters ?: return null
    val velocityX = filteredVelocityXMetersPerSecond ?: return null
    val velocityY = filteredVelocityYMetersPerSecond ?: return null
    val covarianceX = covarianceXX ?: return null
    val covarianceY = covarianceYY ?: return null
    return FilterSnapshot(x, y, velocityX, velocityY, covarianceX, covarianceY)
}
