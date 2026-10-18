package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.StayDeriver.TrackEnd

/**
 * Decides whether the interval between two tracks can be closed by merging them — the fix for a
 * continuous outing that got split (e.g. a walk broken by a run misdetection whose track was
 * discarded, leaving a 1-min stay between two walk tracks). Stays (endpoints agree) and gaps (they
 * don't — the recorder missed the leg) both qualify. The merged route draws a straight connector
 * between the known endpoint fixes without inventing GPS samples. Pure; the UI offers the merge on
 * a swipe and the repository performs it.
 */
object TrackMerge {

    const val AUTO_MAX_INTERVAL_MS = 4 * 60_000L
    const val AUTO_MAX_ENDPOINT_DISTANCE_M = 800.0

    /** Merge the two tracks bracketing the interval into a new track; [earlierId] precedes [laterId]. */
    data class Plan(val earlierId: Long, val laterId: Long)

    /**
     * A plan to merge across the interval between [before] (ends into it) and [after] (starts out
     * of it), or null if the interval is still ongoing or the tracks aren't the same
     * activity and source. This direct two-track merge copies fixes into one row, which must have a
     * single truthful source. Small-gap composition merges retain original child rows and can keep
     * different sources per path. A stay on
     * a named place is mergeable like any other: the pin is untouched, only
     * that one visit stops counting, and the merge undoes — the stay re-derives from the tracks
     * the moment it does.
     */
    fun plan(
        before: TrackSummary,
        after: TrackSummary,
        intervalStart: Long,
        intervalEnd: Long?,
    ): Plan? {
        if (intervalEnd == null || intervalEnd < intervalStart) return null
        if (!sameRecordedActivity(before, after) || before.source != after.source) return null
        return Plan(earlierId = before.id, laterId = after.id)
    }

    private fun sameRecordedActivity(before: TrackSummary, after: TrackSummary): Boolean {
        if (before.activityType != after.activityType) return false
        val activity = ActivityType.ofName(before.activityType) ?: return false
        return activity.recording && activity.trackGroup != TrackGroup.UNKNOWN
    }

    /**
     * Every mergeable interval's plan, keyed by the track it follows — the handle a timeline row
     * looks its own offer up by, whatever the row's bounds say. That indirection is the point: pass
     * the intervals **as derived**, never the per-day slices the timeline renders — a stop across
     * local midnight slices into pieces, and a merge offered on the two-minute pre-midnight piece
     * of a half-hour stay would fuse the two tracks across the whole stop; judging the interval
     * whole, once, keeps a slice from claiming a duration that isn't its own. [neighbors] holds
     * each anchor's own summary and its chronological successor, so an anchor with nothing after it
     * (the tail, into a track still recording) isn't in the map and yields no plan — one map rather
     * than an id-keyed pair, because two lookups that must agree on what follows what are two
     * chances to disagree.
     */
    fun plansByAnchor(
        intervals: List<StayDeriver.Interval>,
        neighbors: Map<Long, Neighbors>,
    ): Map<Long, Plan> = buildMap {
        for (interval in intervals) {
            val anchor = interval.afterTrackId
            val n = neighbors[anchor] ?: continue
            plan(n.before, n.after, interval.start, interval.end)?.let { put(anchor, it) }
        }
    }

    /** Consecutive same-mode paths that can be quietly presented as one trip. */
    data class AutoSequence(
        val tracks: List<TrackSummary>,
        val intervals: List<StayDeriver.Interval>,
    )

    data class AutoSequenceRules(
        val resolvedVisitAnchors: Set<Long>,
        val distance: DistanceFn,
        val protectedSeams: Set<Pair<Long, Long>> = emptySet(),
    )

    /**
     * Auto-merge only intervals whose clock span and endpoint distance both fit the user's small-gap
     * rule. Missing coordinates are not evidence of proximity. Any active user visit correction
     * keeps its seam separate from automatic composition.
     */
    fun autoSequences(
        summariesNewestFirst: List<TrackSummary>,
        intervals: List<StayDeriver.Interval>,
        endpoints: Map<Long, TrackEnd>,
        rules: AutoSequenceRules,
    ): List<AutoSequence> {
        val chronological = summariesNewestFirst.asReversed()
        if (chronological.size < 2) return emptyList()
        val intervalByAnchor = intervals.associateBy { it.afterTrackId }
        val groups = mutableListOf<AutoSequence>()
        var tracks = mutableListOf<TrackSummary>()
        var links = mutableListOf<StayDeriver.Interval>()

        fun finish() {
            if (tracks.size >= 2) groups += AutoSequence(tracks.toList(), links.toList())
            tracks = mutableListOf()
            links = mutableListOf()
        }

        for (index in 0 until chronological.lastIndex) {
            val before = chronological[index]
            val after = chronological[index + 1]
            val interval = intervalByAnchor[before.id]
            val beforeEndedAt = before.endedAt
            val actualGapDuration = beforeEndedAt?.let { after.startedAt - it }
            val beforeEnd = endpoints[before.id]?.end
            val afterStart = endpoints[after.id]?.start
            val eligible = interval != null &&
                before.id !in rules.resolvedVisitAnchors &&
                (before.id to after.id) !in rules.protectedSeams &&
                sameRecordedActivity(before, after) &&
                actualGapDuration != null &&
                actualGapDuration >= 0 &&
                actualGapDuration <= AUTO_MAX_INTERVAL_MS &&
                beforeEnd != null &&
                afterStart != null &&
                rules.distance.meters(beforeEnd.lat, beforeEnd.lon, afterStart.lat, afterStart.lon) <= AUTO_MAX_ENDPOINT_DISTANCE_M

            if (eligible) {
                if (tracks.isEmpty()) tracks += before
                tracks += after
                links += checkNotNull(interval)
            } else {
                finish()
            }
        }
        finish()
        return groups
    }

    data class Neighbors(val before: TrackSummary, val after: TrackSummary)
}
