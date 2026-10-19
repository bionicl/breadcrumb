package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * A per-fix series flattened into the shape of the trip it came from — a jittery one plotted
 * straight through spends most of its ink on the jitter, and the shape it exists to show (pulling
 * away, cruising, slowing into a stop) has to be read past it. Which series want this is the
 * caller's to say; this only knows how.
 *
 * Averaged over a window of **real time**, not of point count: a track's fix spacing varies with
 * the sampling settings, with how long the receiver took to answer, and with a stop's cadence, so a
 * fixed count of neighbours would smooth a dense stretch far harder than a sparse one and neither
 * predictably. The window is short beside anything worth seeing — a stop lasting less than it is
 * not a stop the graph was showing anyway.
 *
 * The window is **balanced**: it reaches each way only as far as the fixes on its shorter side do.
 * A fix beside a break otherwise averages one side alone, and the last fix before a stop reads as
 * the braking behind it rather than its own speed.
 */
object MetricSmoother {

    /** Total width of the averaging window, centred on each point. */
    const val WINDOW_MS = 15_000L

    /**
     * [values] averaged over at most [windowMs] of surrounding time, index for index with [points].
     *
     * A null value is not a small one — it is a fix that never carried this metric — so nulls come
     * back null and are never averaged in. Averaging also stops at the boundaries the drawn line
     * breaks at: a missing value, and an index [breaksBefore] marks. Every run of consecutive
     * values is therefore smoothed on its own, and a run's first and last fixes keep their own
     * values.
     */
    fun timeAveraged(
        points: List<TrackPoint>,
        values: List<Float?>,
        breaksBefore: BooleanArray,
        windowMs: Long = WINDOW_MS,
    ): List<Float?> {
        val out = arrayOfNulls<Float>(values.size)
        val half = windowMs / 2

        /**
         * One run averaged off prefix sums: a balanced window's near edge moves both ways as the
         * shorter side changes, so a carried sum cannot follow it. A run holds no nulls by
         * construction.
         */
        fun averageRun(from: Int, to: Int) {
            val prefix = DoubleArray(to - from + 2)
            for (i in from..to) prefix[i - from + 1] = prefix[i - from] + checkNotNull(values[i])
            fun time(i: Int) = points[i].timestamp
            var earliest = from
            var latest = from
            for (i in from..to) {
                val t = time(i)
                while (time(earliest) < t - half) earliest++
                while (latest < to && time(latest + 1) <= t + half) latest++
                val back = t - time(earliest)
                val ahead = time(latest) - t
                var lo = earliest
                var hi = latest
                while (time(lo) < t - ahead) lo++
                while (time(hi) > t + back) hi--
                out[i] = ((prefix[hi - from + 1] - prefix[lo - from]) / (hi - lo + 1)).toFloat()
            }
        }

        var runStart = 0
        while (runStart < values.size) {
            if (values[runStart] == null) {
                runStart++
                continue
            }
            var runEnd = runStart
            while (runEnd + 1 < values.size && values[runEnd + 1] != null && !breaksBefore[runEnd + 1]) {
                runEnd++
            }
            averageRun(runStart, runEnd)
            runStart = runEnd + 1
        }
        return out.asList()
    }
}
