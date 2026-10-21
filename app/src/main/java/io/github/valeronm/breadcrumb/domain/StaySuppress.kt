package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary

/**
 * Hides a [StayDeriver.Stay] on the timeline when the two tracks either side cannot merge — different
 * activities, or any other reason [TrackMerge.plan] refuses — without joining those tracks. The
 * tracks and their paths stay as they were; only the stop row and its visit count go away until the
 * reader undoes it.
 */
object StaySuppress {

    /** Stable key for one stay — the same pair [TimelineJump.Visit] matches on. */
    fun key(afterTrackId: Long, start: Long): String = "$afterTrackId:$start"

    data class Plan(val afterTrackId: Long, val start: Long)

    /**
     * A plan to hide the stay after [anchorTrackId], or null when [TrackMerge] already offers a
     * merge or the two tracks share an activity (nothing to dissolve separately from merge).
     */
    fun plan(
        before: TrackSummary,
        after: TrackSummary,
        intervalStart: Long,
        intervalEnd: Long?,
        mergeOffered: Boolean,
    ): Plan? {
        if (mergeOffered || intervalEnd == null) return null
        if (before.activityType == after.activityType) return null
        return Plan(afterTrackId = before.id, start = intervalStart)
    }

    fun plansByAnchor(
        intervals: List<StayDeriver.Interval>,
        neighbors: Map<Long, TrackMerge.Neighbors>,
        mergePlans: Map<Long, TrackMerge.Plan>,
    ): Map<Long, Plan> = buildMap {
        for (interval in intervals) {
            if (interval !is StayDeriver.Stay) continue
            val anchor = interval.afterTrackId
            val n = neighbors[anchor] ?: continue
            plan(n.before, n.after, interval.start, interval.end, mergePlans[anchor] != null)
                ?.let { put(anchor, it) }
        }
    }
}
