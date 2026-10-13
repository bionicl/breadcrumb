package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocomotionDao {
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
            "AND confirmedTransportMode IS NOT NULL ORDER BY sessionId, elapsedRealtimeNanos",
    )
    suspend fun confirmedTrainingObservations(): List<EngineObservationEntity>

    @Query(
        "UPDATE engine_observations SET confirmedTransportMode = :mode, trainingEligible = 1 " +
            "WHERE id IN (:observationIds) AND provenance = 'LIVE'",
    )
    suspend fun confirmTrainingLabel(observationIds: List<String>, mode: String)

    @Query("SELECT COUNT(*) FROM engine_observations")
    suspend fun observationCount(): Long
}
