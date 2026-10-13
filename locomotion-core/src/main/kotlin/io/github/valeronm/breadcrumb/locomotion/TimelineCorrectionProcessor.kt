package io.github.valeronm.breadcrumb.locomotion

/** Result is derived from the immutable inferred segment plus its ordered correction ledger. */
data class CorrectedSegment(
    val segment: TimelineSegment?,
    val appliedCorrectionIds: List<String>,
    val deletedByCorrectionId: String? = null,
)

/** User assertions always win over later inference; Undo removes only its referenced assertion. */
object TimelineCorrectionProcessor {
    fun apply(segment: TimelineSegment, corrections: List<UserCorrection>): CorrectedSegment {
        val relevant = corrections.filter { it.targetSegmentId == segment.id }.sortedBy { it.createdAtWallTimeMs }
        val undoneIds = relevant.filterIsInstance<UserCorrection.Undo>().map { it.correctionId }.toSet()
        val active = relevant.filterNot { it is UserCorrection.Undo || it.id in undoneIds }
        var current: TimelineSegment? = segment
        val appliedIds = mutableListOf<String>()
        var deletedBy: String? = null
        for (correction in active) {
            val value = current ?: break
            current = when (correction) {
                is UserCorrection.ConfirmTransport -> value.copy(
                    kind = SegmentKind.MOVEMENT,
                    transportMode = correction.mode,
                    confirmedTransportMode = correction.mode,
                )
                is UserCorrection.ConfirmVisit -> value.copy(
                    kind = SegmentKind.VISIT,
                    transportMode = null,
                    confirmedTransportMode = null,
                )
                is UserCorrection.LockBoundary -> when (correction.boundary) {
                    BoundaryKind.START -> value.copy(
                        startWallTimeMs = correction.atWallTimeMs,
                        startBoundary = BoundaryOrigin.USER_CONFIRMED,
                        lockedByUser = true,
                    )
                    BoundaryKind.END -> value.copy(
                        endWallTimeMs = correction.atWallTimeMs,
                        endBoundary = BoundaryOrigin.USER_CONFIRMED,
                        lockedByUser = true,
                    )
                }
                is UserCorrection.DeleteSegment -> {
                    deletedBy = correction.id
                    null
                }
                is UserCorrection.Undo -> value
            }
            appliedIds += correction.id
        }
        return CorrectedSegment(current, appliedIds, deletedBy)
    }

    fun applyAll(segments: List<TimelineSegment>, corrections: List<UserCorrection>): List<TimelineSegment> {
        val correctionsBySegment = corrections.groupBy { it.targetSegmentId }
        return segments.mapNotNull { segment ->
            apply(segment, correctionsBySegment[segment.id].orEmpty()).segment
        }
    }
}
