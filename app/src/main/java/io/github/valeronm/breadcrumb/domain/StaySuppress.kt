package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary

/**
 * Hides a [StayDeriver.Stay] on the timeline when [TrackMerge] offers no merge — unlike activity or
 * writer, not stop length — without joining the bracketing tracks. The paths stay as they were; only
 * the row and its visit count go away until the reader undoes it.
 */
object StaySuppress {

    /** Stable key for one stay — the same pair [TimelineJump.Visit] matches on. */
    fun key(afterTrackId: Long, start: Long): String = "$afterTrackId:$start"

    data class Plan(val afterTrackId: Long, val start: Long)

    /**
     * A plan to hide the stay after [anchorTrackId], or null when [TrackMerge] already offers a
     * merge or the stay is still open. When [TrackMerge.plan] would refuse (unlike activity or
     * writer, not because the stop is long) the reader may hide the row without joining tracks.
     */
    fun plan(
        before: TrackSummary,
        after: TrackSummary,
        intervalStart: Long,
        intervalEnd: Long?,
        mergeOffered: Boolean,
    ): Plan? {
        if (mergeOffered || intervalEnd == null) return null
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
