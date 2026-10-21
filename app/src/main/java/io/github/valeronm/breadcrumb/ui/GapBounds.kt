package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.DistanceFn
import io.github.valeronm.breadcrumb.domain.StayDeriver

/** What a gap row states about its ends, carrying none of the wording, as [StayBounds] is for a stay row. */
internal sealed interface GapBounds {

    /** Holds only the departure: the absence runs on from [start] past the day's end. */
    data class From(val start: Long) : GapBounds

    /** Holds only the arrival: the absence ran from before the day began until [end]. */
    data class Until(val end: Long) : GapBounds

    /**
     * Both ends on this row. [lastingMs] is null where the gap is shorter than [StayDeriver.REPORTABLE_DURATION_MS], and [apartMeters] — the straight line
     * between the two fixes, not a distance travelled — where either side has no fix; with both
     * null the row states nothing between its ends.
     */
    data class Both(val start: Long, val end: Long, val lastingMs: Long?, val apartMeters: Double?) : GapBounds
}

/**
 * [gap] as the bounds a row states, [holdsDeparture]/[holdsArrival] saying which of its ends are
 * this row's own — read from [io.github.valeronm.breadcrumb.domain.TimelineItem.GapItem], never
 * worked out here. A row holding neither is refused: the slicer never emits one.
 */
internal fun gapBounds(
    gap: StayDeriver.Gap,
    holdsDeparture: Boolean,
    holdsArrival: Boolean,
    distance: DistanceFn,
): GapBounds = when {
    holdsDeparture && holdsArrival -> {
        val a = gap.from
        val b = gap.to
        GapBounds.Both(
            start = gap.start,
            end = gap.end,
            lastingMs = StayDeriver.reportable(gap.end - gap.start),
            apartMeters = if (a == null || b == null) null else distance.meters(a.lat, a.lon, b.lat, b.lon),
        )
    }
    holdsDeparture -> GapBounds.From(gap.start)
    holdsArrival -> GapBounds.Until(gap.end)
    else -> error("a gap row holds at least one end; this one was stamped with neither")
}
