package io.github.valeronm.breadcrumb.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable user assertions for future locomotion replay; model output never creates these rows. */
@Entity(
    tableName = "engine_user_corrections",
    indices = [Index("operationId"), Index("targetSegmentId", "createdAtWallTimeMs")],
)
data class EngineUserCorrectionEntity(
    @PrimaryKey val id: String,
    /** Groups the two boundary locks created by one split, so undo removes exactly those locks. */
    val operationId: String,
    /** Stable adapter identity for the legacy track or engine segment. */
    val targetSegmentId: String,
    val kind: String,
    val createdAtWallTimeMs: Long,
    val transportMode: String? = null,
    val boundaryKind: String? = null,
    val atWallTimeMs: Long? = null,
    val referenceCorrectionId: String? = null,
)
