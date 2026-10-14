package io.github.valeronm.breadcrumb.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable provenance for a movement track created by closing the interval between two tracks. */
@Entity(
    tableName = "track_compositions",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["mergedTrackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("earlierTrackId"), Index("laterTrackId")],
)
data class TrackCompositionEntity(
    @PrimaryKey val mergedTrackId: Long,
    val earlierTrackId: Long,
    val laterTrackId: Long,
    val visitAfterTrackId: Long,
    val visitStartMs: Long,
    val visitEndMs: Long,
    /** Null for an automatically absorbed short stop. */
    val visitCorrectionId: String?,
)

/** Inputs known at the timeline boundary when a merge is requested. */
data class TrackCompositionDraft(
    val visitAfterTrackId: Long,
    val visitStartMs: Long,
    val visitEndMs: Long,
    val visitCorrectionId: String? = null,
)

/** One ordered part of a composed trip. Source paths remain soft-deleted and recoverable. */
@Entity(
    tableName = "track_composition_parts",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["mergedTrackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mergedTrackId"), Index("sourceTrackId"), Index("beforeTrackId"), Index("afterTrackId")],
)
data class TrackCompositionPartEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mergedTrackId: Long,
    val ordinal: Int,
    /** PATH, STATIONARY, or UNKNOWN. */
    val kind: String,
    val sourceTrackId: Long? = null,
    val activityType: String? = null,
    val startedAt: Long,
    val endedAt: Long,
    val beforeTrackId: Long? = null,
    val afterTrackId: Long? = null,
    val visitCorrectionId: String? = null,
)

data class TrackCompositionPartDraft(
    val kind: String,
    val sourceTrackId: Long? = null,
    val activityType: String? = null,
    val startedAt: Long,
    val endedAt: Long,
    val beforeTrackId: Long? = null,
    val afterTrackId: Long? = null,
    val visitCorrectionId: String? = null,
)
