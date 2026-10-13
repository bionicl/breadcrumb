package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface LocomotionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUserCorrection(correction: EngineUserCorrectionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUserCorrections(corrections: List<EngineUserCorrectionEntity>)

    @Query(
        "SELECT * FROM engine_user_corrections WHERE targetSegmentId = :targetSegmentId " +
            "ORDER BY createdAtWallTimeMs, id",
    )
    suspend fun userCorrectionsFor(targetSegmentId: String): List<EngineUserCorrectionEntity>

    @Query("SELECT * FROM engine_user_corrections ORDER BY createdAtWallTimeMs, id")
    suspend fun allUserCorrections(): List<EngineUserCorrectionEntity>

    @Query("DELETE FROM engine_user_corrections WHERE operationId = :operationId")
    suspend fun deleteCorrectionsForOperation(operationId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertObservation(observation: EngineObservationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertObservations(observations: List<EngineObservationEntity>): List<Long>

    @Query(
        "SELECT * FROM engine_observations WHERE sessionId = :sessionId " +
            "AND elapsedRealtimeNanos > :afterElapsedNanos ORDER BY elapsedRealtimeNanos LIMIT :limit",
    )
    suspend fun observationsAfter(
        sessionId: String,
        afterElapsedNanos: Long,
        limit: Int,
    ): List<EngineObservationEntity>

    /** Training data must originate in a real, confirmed user correction. */
    @Query(
        "SELECT * FROM engine_observations WHERE provenance = 'LIVE' AND trainingEligible = 1 " +
            "AND confirmedTransportMode IS NOT NULL ORDER BY wallTimeMs DESC LIMIT 20000",
    )
    suspend fun confirmedTrainingObservations(): List<EngineObservationEntity>

    @Query(
        "SELECT * FROM engine_observations WHERE provenance = 'LIVE' " +
            "AND wallTimeMs BETWEEN :startedAt AND :endedAt ORDER BY elapsedRealtimeNanos",
    )
    suspend fun liveObservationsBetween(startedAt: Long, endedAt: Long): List<EngineObservationEntity>

    @Query(
        "UPDATE engine_observations SET confirmedTransportMode = :mode, confirmedTripId = :tripId, " +
            "trainingEligible = 1 " +
            "WHERE id IN (:observationIds) AND provenance = 'LIVE'",
    )
    suspend fun confirmTrainingLabel(observationIds: List<String>, tripId: String, mode: String)

    @Query(
        "UPDATE engine_observations SET confirmedTransportMode = NULL, confirmedTripId = NULL, trainingEligible = 0 " +
            "WHERE confirmedTripId = :tripId",
    )
    suspend fun clearTrainingLabel(tripId: String)

    @Query("SELECT COUNT(*) FROM engine_observations")
    suspend fun observationCount(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCheckpoint(checkpoint: EngineCheckpointEntity)

    @Query("SELECT * FROM engine_checkpoints WHERE sessionId = :sessionId")
    suspend fun checkpoint(sessionId: String): EngineCheckpointEntity?

    @Query("DELETE FROM engine_checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteCheckpoint(sessionId: String)

    /** Persist accepted inputs and their resulting state as one restart-safe unit. */
    @Transaction
    suspend fun storeBatchAndCheckpoint(
        observations: List<EngineObservationEntity>,
        checkpoint: EngineCheckpointEntity,
    ) {
        insertObservations(observations)
        saveCheckpoint(checkpoint)
    }
}
