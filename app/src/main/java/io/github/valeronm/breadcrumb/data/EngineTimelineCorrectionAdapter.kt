package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.locomotion.SegmentKind
import io.github.valeronm.breadcrumb.locomotion.TimelineCorrectionProcessor
import io.github.valeronm.breadcrumb.locomotion.TimelineSegment
import io.github.valeronm.breadcrumb.locomotion.UserCorrection

/** Applies the durable correction ledger when legacy tracks are projected into timeline rows. */
internal object EngineTimelineCorrectionAdapter {
    fun apply(track: TrackSummary, corrections: List<UserCorrection>): TrackSummary? {
        val segmentId = engineTrackSegmentId(track.id)
        val inferred = TimelineSegment(
            id = segmentId,
            kind = SegmentKind.MOVEMENT,
            startWallTimeMs = track.startedAt,
            endWallTimeMs = track.endedAt,
            transportMode = ActivityType.ofName(track.activityType)?.toTransportMode(),
        )
        val corrected = TimelineCorrectionProcessor.apply(inferred, corrections).segment ?: return null
        val correctedActivity = when (corrected.kind) {
            SegmentKind.VISIT -> ActivityType.STILL
            SegmentKind.MOVEMENT -> corrected.confirmedTransportMode?.toActivityType()
                ?: corrected.transportMode?.toActivityType()
            SegmentKind.DATA_GAP -> null
        }?.name ?: track.activityType
        val hasActivityConfirmation = corrected.confirmedTransportMode != null ||
            corrected.kind == SegmentKind.VISIT
        return track.copy(
            activityType = correctedActivity,
            startedAt = corrected.startWallTimeMs,
            endedAt = corrected.endWallTimeMs,
            activityConfirmedByUser = track.activityConfirmedByUser || hasActivityConfirmation,
        )
    }
}
