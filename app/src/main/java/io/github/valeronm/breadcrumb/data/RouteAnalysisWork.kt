package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.RouteAnalysisJobEntity
import io.github.valeronm.breadcrumb.util.DebugLog

/** Unique persistent scheduler for route post-processing. */
object RouteAnalysisWork {
    private const val UNIQUE_NAME = "route-analysis"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<RouteAnalysisWorker>().build()
        // Scheduling is deliberately best-effort here: a scheduler problem must never escape into
        // the recording path. App startup retries the queue reconciliation.
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }.onFailure { DebugLog.e("RouteAnalysis", "could not enqueue analysis", it) }
    }

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME) }
            .onFailure { DebugLog.e("RouteAnalysis", "could not cancel analysis", it) }
    }
}

class RouteAnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        if (!Settings.routeAnalysisEnabled(applicationContext)) return Result.success()
        val db = AppDatabase.get(applicationContext)
        val tracks = db.trackDao()
        val locomotion = db.locomotionDao()
        val now = System.currentTimeMillis()
        val staleIds = runCatching { tracks.trackIdsNeedingAnalysis(ROUTE_ANALYSIS_VERSION) }
            .getOrElse { return Result.retry() }
        for (trackId in staleIds) {
            val track = tracks.track(trackId) ?: continue
            locomotion.enqueueAnalysisJob(
                RouteAnalysisJobEntity(
                    trackId = trackId,
                    processorVersion = ROUTE_ANALYSIS_VERSION,
                    inputRevision = track.pathRevision,
                    updatedAtWallTimeMs = now,
                ),
            )
        }

        val jobs = runCatching { locomotion.analysisJobs(JOBS_PER_RUN) }.getOrElse { return Result.retry() }
        for (job in jobs) {
            if (!Settings.routeAnalysisEnabled(applicationContext)) return Result.success()
            val running = job.copy(
                state = RouteAnalysisJobEntity.RUNNING,
                attempts = job.attempts + 1,
                updatedAtWallTimeMs = System.currentTimeMillis(),
                lastError = null,
            )
            locomotion.updateAnalysisJob(running)
            try {
                val completed = TrackRepository(applicationContext, db)
                    .processRouteAnalysis(job.trackId, job.processorVersion, job.inputRevision)
                val latestTrack = tracks.track(job.trackId)
                if (!completed && latestTrack != null && latestTrack.pathRevision != job.inputRevision) {
                    locomotion.updateAnalysisJob(running.copy(state = RouteAnalysisJobEntity.QUEUED))
                    locomotion.enqueueAnalysisJob(
                        RouteAnalysisJobEntity(
                            trackId = latestTrack.id,
                    processorVersion = ROUTE_ANALYSIS_VERSION,
                            inputRevision = latestTrack.pathRevision,
                            updatedAtWallTimeMs = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    locomotion.updateAnalysisJob(running.copy(state = RouteAnalysisJobEntity.COMPLETE))
                }
            } catch (e: Exception) {
                DebugLog.e("RouteAnalysis", "track ${job.trackId} analysis failed", e)
                locomotion.updateAnalysisJob(
                    running.copy(state = RouteAnalysisJobEntity.FAILED, lastError = e.message?.take(500)),
                )
                return Result.retry()
            }
        }
        if (jobs.size == JOBS_PER_RUN) return Result.retry()
        return Result.success()
    }

    private companion object { const val JOBS_PER_RUN = 5 }
}
