package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * Where a per-fix series is neither joined fix to fix nor averaged across: a
 * [TrackPoint.segmentStart], and a stop that left no fixes behind. A receiver holding a stationary position under a minimum update
 * distance reports nothing until it drifts that far, so a stop can sit between two fixes taken
 * while braking and pulling away, and a line joined across it shows the stop as steady movement.
 * Pure and Android-free.
 */
object GraphBreaks {

    /** Slower than this across a whole gap is standing still: GPS drift over a stop stays well under it. */
    val STOP_MAX_SPEED: Speed = Speed.mps(0.5)

    /** A gap within the averaging window is already spanned by the fixes around it. */
    const val STOP_MIN_GAP_MS = MetricSmoother.WINDOW_MS

    /**
     * A gap must also exceed the track's usual fix spacing by this factor, since a track sampled
     * every 30 s or more records its stops as fixes that far apart.
     */
    const val SPACING_FACTOR = 3

    /**
     * Index for index with [points]: true where a stop with no fixes lies between `points[i - 1]`
     * and `points[i]`. [metersBefore] is the distance from each fix's predecessor, 0 at index 0
     * (`TrackQuality.Seams.meters`). A segment start is never a stop: nobody watched that gap.
     */
    fun stopsBefore(points: List<TrackPoint>, metersBefore: DoubleArray): BooleanArray {
        val stops = BooleanArray(points.size)
        val minGapMs = maxOf(STOP_MIN_GAP_MS, SPACING_FACTOR * medianSpacingMs(points))
        for (i in 1 until points.size) {
            if (points[i].segmentStart) continue
            val gapMs = points[i].timestamp - points[i - 1].timestamp
            stops[i] = gapMs > minGapMs && Speed.mps(metersBefore[i] * 1000.0 / gapMs) < STOP_MAX_SPEED
        }
        return stops
    }

    /** Index for index with [points]: true where nothing joins `points[i - 1]` to `points[i]`. */
    fun of(points: List<TrackPoint>, stopsBefore: BooleanArray): BooleanArray =
        BooleanArray(points.size) { points[it].segmentStart || stopsBefore[it] }

    /** Median time between consecutive fixes within a recorded stretch; 0 when there is none. */
    private fun medianSpacingMs(points: List<TrackPoint>): Long {
        val gaps = LongArray(points.size)
        var n = 0
        for (i in 1 until points.size) {
            val gap = points[i].timestamp - points[i - 1].timestamp
            if (gap > 0 && !points[i].segmentStart) gaps[n++] = gap
        }
        if (n == 0) return 0
        gaps.sort(0, n)
        return gaps[n / 2]
    }
}
