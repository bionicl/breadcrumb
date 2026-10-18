package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT * FROM engine_user_corrections ORDER BY createdAtWallTimeMs, id")
    fun observeUserCorrections(): Flow<List<EngineUserCorrectionEntity>>

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

    @Query(
        "UPDATE engine_observations SET confirmedPlaceId = :placeId, confirmedLocationClass = :locationClass, " +
            "placeTrainingEligible = 1 WHERE id IN (:observationIds) AND provenance = 'LIVE'",
    )
    suspend fun confirmPlaceTrainingLabel(observationIds: List<String>, placeId: Long?, locationClass: String)

    @Query(
        "UPDATE engine_observations SET confirmedPlaceId = NULL, confirmedLocationClass = NULL, " +
            "placeTrainingEligible = 0 WHERE id IN (:observationIds)",
    )
    suspend fun clearPlaceTrainingLabels(observationIds: List<String>)

    @Query("SELECT COUNT(*) FROM engine_observations")
    suspend fun observationCount(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueAnalysisJob(job: RouteAnalysisJobEntity)

    @Query("SELECT * FROM route_analysis_jobs WHERE state IN ('QUEUED','RUNNING','FAILED') ORDER BY CASE state WHEN 'QUEUED' THEN 0 ELSE 1 END, updatedAtWallTimeMs DESC LIMIT :limit")
    suspend fun analysisJobs(limit: Int): List<RouteAnalysisJobEntity>

    @Query("SELECT * FROM route_analysis_jobs WHERE trackId = :trackId AND processorVersion = :processorVersion AND inputRevision = :inputRevision")
    suspend fun analysisJob(trackId: Long, processorVersion: Int, inputRevision: Long): RouteAnalysisJobEntity?

    @Update
    suspend fun updateAnalysisJob(job: RouteAnalysisJobEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReview(item: ReviewQueueEntity): Long

    @Query("SELECT * FROM review_queue WHERE state = :state ORDER BY updatedAtWallTimeMs DESC")
    fun observeReviewItems(state: String): Flow<List<ReviewQueueEntity>>

    @Query("SELECT * FROM review_queue ORDER BY updatedAtWallTimeMs DESC")
    fun observeAllReviewItems(): Flow<List<ReviewQueueEntity>>

    @Query("SELECT * FROM review_queue WHERE id = :id")
    suspend fun reviewItem(id: String): ReviewQueueEntity?

    @Update
    suspend fun updateReviewItem(item: ReviewQueueEntity)
}
