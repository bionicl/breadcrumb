package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.EngineUserCorrectionEntity
import io.github.valeronm.breadcrumb.locomotion.BoundaryKind
import io.github.valeronm.breadcrumb.locomotion.TransportMode
import io.github.valeronm.breadcrumb.locomotion.UserCorrection

/** Converts durable Android rows into the platform-free correction contract used by engine replay. */
internal fun EngineUserCorrectionEntity.toEngineCorrection(): UserCorrection? {
    val target = targetSegmentId.takeIf(String::isNotBlank) ?: return null
    return when (kind) {
        "CONFIRM_TRANSPORT" -> {
            val mode = transportMode?.let { runCatching { TransportMode.valueOf(it) }.getOrNull() }
                ?: return null
            UserCorrection.ConfirmTransport(id, createdAtWallTimeMs, target, mode)
        }
        "CONFIRM_VISIT" -> UserCorrection.ConfirmVisit(id, createdAtWallTimeMs, target)
        "LOCK_BOUNDARY" -> {
            val boundary = boundaryKind?.let { runCatching { BoundaryKind.valueOf(it) }.getOrNull() }
                ?: return null
            val at = atWallTimeMs ?: return null
            UserCorrection.LockBoundary(id, createdAtWallTimeMs, target, boundary, at)
        }
        "DELETE_SEGMENT" -> UserCorrection.DeleteSegment(id, createdAtWallTimeMs, target)
        "UNDO" -> {
            val reference = referenceCorrectionId?.takeIf(String::isNotBlank) ?: return null
            UserCorrection.Undo(id, createdAtWallTimeMs, target, reference)
        }
        else -> null
    }
}

internal fun engineTrackSegmentId(trackId: Long): String = "legacy-track:$trackId"
