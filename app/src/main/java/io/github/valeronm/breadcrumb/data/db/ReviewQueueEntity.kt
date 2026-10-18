package io.github.valeronm.breadcrumb.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An evidence-backed question. User answers are durable and never overwrite source track points. */
@Entity(
    tableName = "review_queue",
    indices = [Index(value = ["evidenceKey"], unique = true), Index(value = ["state", "updatedAtWallTimeMs"])],
)
data class ReviewQueueEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val state: String = PENDING,
    val primaryTrackId: Long? = null,
    val secondaryTrackId: Long? = null,
    val startWallTimeMs: Long,
    val endWallTimeMs: Long? = null,
    val suggestedValue: String? = null,
    val currentValue: String? = null,
    val reasonCode: String,
    val evidenceKey: String,
    val inputRevision: Long,
    val createdAtWallTimeMs: Long,
    val updatedAtWallTimeMs: Long,
    val decision: String? = null,
    val correctedValue: String? = null,
) {
    companion object {
        const val PENDING = "PENDING"
        const val SNOOZED = "SNOOZED"
        const val ACCEPTED = "ACCEPTED"
        const val REJECTED = "REJECTED"
        const val CORRECTED = "CORRECTED"
        const val UNDONE = "UNDONE"
        const val STALE = "STALE"
        const val MERGE_TRACKS = "MERGE_TRACKS"
        const val SHORT_STOP = "SHORT_STOP"
        const val TRANSPORT = "TRANSPORT"
        const val PLACE_ASSIGNMENT = "PLACE_ASSIGNMENT"
        const val PLACE_DUPLICATE = "PLACE_DUPLICATE"
        const val VISIT_DRIFT = "VISIT_DRIFT"
    }
}
