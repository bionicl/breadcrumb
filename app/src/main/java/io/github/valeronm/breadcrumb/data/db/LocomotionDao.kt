package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query(
        "SELECT j.* FROM route_analysis_jobs j LEFT JOIN tracks t ON t.id = j.trackId " +
            "WHERE j.state IN ('QUEUED','RUNNING','FAILED') " +
            "ORDER BY CASE j.state WHEN 'QUEUED' THEN 0 ELSE 1 END, t.startedAt DESC, " +
            "j.updatedAtWallTimeMs DESC LIMIT :limit",
    )
    suspend fun analysisJobs(limit: Int): List<RouteAnalysisJobEntity>

    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN j.state = 'QUEUED' THEN 1 ELSE 0 END), 0) AS queued, " +
            "COALESCE(SUM(CASE WHEN j.state = 'RUNNING' THEN 1 ELSE 0 END), 0) AS running, " +
            "COALESCE(SUM(CASE WHEN j.state = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed, " +
            "(SELECT COUNT(*) FROM tracks t WHERE t.endedAt IS NOT NULL AND t.discardedAt IS NULL " +
            "AND (t.pathProcessorVersion < :processorVersion OR t.pathParametersVersion < :parametersVersion " +
            "OR t.pathProcessedRevision != t.pathRevision) " +
            "AND NOT EXISTS (SELECT 1 FROM route_analysis_jobs pending " +
            "WHERE pending.trackId = t.id AND pending.state IN ('QUEUED','RUNNING','FAILED'))) AS unprocessed " +
            "FROM route_analysis_jobs j",
    )
    fun observeAnalysisQueueStatus(processorVersion: Int, parametersVersion: Int): Flow<RouteAnalysisQueueStatus>

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

    @Query("SELECT * FROM review_queue")
    suspend fun allReviewItems(): List<ReviewQueueEntity>

    @Update
    suspend fun updateReviewItem(item: ReviewQueueEntity)
}
