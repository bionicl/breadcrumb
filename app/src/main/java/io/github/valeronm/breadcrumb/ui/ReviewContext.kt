package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.Coordinate

/** Only the currently visible question loads its geometry. No per-card native maps in the queue. */
internal data class ReviewContext(
    val primary: Track?,
    val secondary: Track?,
    val primaryPoints: List<TrackPoint>,
    val secondaryPoints: List<TrackPoint>,
    val focus: Coordinate?,
    val beforePlace: String?,
    val afterPlace: String?,
)

/** Keep source boundaries and missing GPS segments separate: never invent a measured connector. */
internal fun reviewPathRuns(points: List<TrackPoint>): List<List<TrackPoint>> {
    val runs = mutableListOf<MutableList<TrackPoint>>()
    for (point in points) {
        if (point.ignored) continue
        if (runs.isEmpty() || point.segmentStart) runs.add(mutableListOf())
        runs.last().add(point)
    }
    return runs.filter { it.size >= 2 }
}

internal fun reviewGapPairs(points: List<TrackPoint>): List<Pair<TrackPoint, TrackPoint>> =
    points.filterNot { it.ignored }.zipWithNext().filter { (_, next) -> next.segmentStart }
