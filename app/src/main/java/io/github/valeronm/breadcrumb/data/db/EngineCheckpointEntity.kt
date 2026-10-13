package io.github.valeronm.breadcrumb.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Atomic restart state for the engine; one checkpoint per active recording session. */
@Entity(tableName = "engine_checkpoints", indices = [Index("updatedAtWallTimeMs")])
data class EngineCheckpointEntity(
    @PrimaryKey val sessionId: String,
    val bootEpochId: String,
    val lastObservationId: String?,
    val lastElapsedRealtimeNanos: Long?,
    val lastWallTimeMs: Long?,
    val motionState: String,
    val recordingState: String,
    val filteredXmeters: Double?,
    val filteredYmeters: Double?,
    val filteredVelocityXMetersPerSecond: Double?,
    val filteredVelocityYMetersPerSecond: Double?,
    val covarianceXX: Double?,
    val covarianceYY: Double?,
    val activeSegmentId: String?,
    val configurationVersion: Int,
    val modelVersion: String?,
    val updatedAtWallTimeMs: Long,
)
