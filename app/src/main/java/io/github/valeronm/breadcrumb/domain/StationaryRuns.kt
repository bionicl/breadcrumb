package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * The fixes inside a stop that say nothing its first and last fix don't: where it was, when it began
 * and when it ended. A receiver logging once a second through a red light or a parked hour writes the
 * same spot over and over, and its jitter around that spot reads as distance never driven and as
 * speed spikes on the graph. [TrackSettle] flags them [IgnoreReason.STATIONARY]; the two ends stay
 * on the path. Pure and Android-free.
 */
object StationaryRuns {

    /** Every fix of a stop stays within this of its first; GPS drift at a standstill stays under it. */
    const val RADIUS_M = 10.0

    /** With [RADIUS_M], bounds a thinned stop's net speed below [GraphBreaks.STOP_MAX_SPEED]. */
    const val MIN_SPAN_MS = 30_000L

    /**
     * Indices into [points] — all of a track's points in order, good and ignored alike — of the good
     * fixes strictly inside a stop. A stop is a run of good fixes no segment break interrupts, each
     * within [RADIUS_M] of the run's first, spanning at least [MIN_SPAN_MS]. Ignored fixes neither
     * end a run nor join it, except that a break on one ends the run as it does on a good fix
     * ([SegmentBreaks]).
     */
    fun inner(points: List<TrackPoint>, distance: DistanceFn): Set<Int> {
        val good = points.indices.filter { !points[it].ignored }
        // Index for index with [good]: the same fixes, a break on an ignored one carried onto them.
        val path = SegmentBreaks.goodWithCarriedBreaks(points)
        val inner = HashSet<Int>()
        var a = 0
        while (a < path.size) {
            val anchor = path[a]
            var b = a
            while (b + 1 < path.size && !path[b + 1].segmentStart && within(anchor, path[b + 1], distance)) b++
            if (b - a >= 2 && path[b].timestamp - anchor.timestamp >= MIN_SPAN_MS) {
                for (k in a + 1 until b) inner += good[k]
                a = b
            } else {
                a++
            }
        }
        return inner
    }

    private fun within(a: TrackPoint, b: TrackPoint, distance: DistanceFn): Boolean =
        distance.meters(a.latitude, a.longitude, b.latitude, b.longitude) <= RADIUS_M
}
