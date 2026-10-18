package io.github.valeronm.breadcrumb.data.db

import androidx.room.Entity
import androidx.room.Index

/** Durable unit of post-processing. The composite key makes re-enqueue idempotent per input revision. */
@Entity(
    tableName = "route_analysis_jobs",
    primaryKeys = ["trackId", "processorVersion", "inputRevision"],
    indices = [Index(value = ["state", "updatedAtWallTimeMs"])],
)
data class RouteAnalysisJobEntity(
    val trackId: Long,
    val processorVersion: Int,
    val inputRevision: Long,
    val state: String = QUEUED,
    val attempts: Int = 0,
    val updatedAtWallTimeMs: Long,
    val lastError: String? = null,
) {
    companion object {
        const val QUEUED = "QUEUED"
        const val RUNNING = "RUNNING"
        const val COMPLETE = "COMPLETE"
        const val FAILED = "FAILED"
    }
}
