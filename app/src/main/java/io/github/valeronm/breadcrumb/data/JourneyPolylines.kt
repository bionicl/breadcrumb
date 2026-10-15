package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.GreatCircle
import io.github.valeronm.breadcrumb.domain.PolylineSimplifier
import io.github.valeronm.breadcrumb.domain.SegmentBreaks
import io.github.valeronm.breadcrumb.locomotion.RoutePathSmoother
import io.github.valeronm.breadcrumb.locomotion.RouteSmoothingFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One track's path as a many-track map draws it: flat lon/lat pairs per watched stretch, already
 * simplified to that map's fidelity. Holding this rather than [TrackPoint]s is the point — a
 * journey spans many tracks, and the full rows are read once, reduced, and let go.
 */
internal class JourneyLine(
    val trackId: Long,
    val activityType: String,
    val greatCircle: Boolean,
    val segments: List<DoubleArray>,
)

/**
 * Loads and keeps [JourneyLine]s — a read model over the point rows, like [DerivationStore] is
 * over the derivation. Reads are one-shot suspend loads, never an observation — a flow on
 * `track_points` re-runs on every fix while recording (see `TrackDao`) — and each entry is keyed
 * on the row's `endedAt` too, so a manual track rewritten in place misses its stale line.
 */
internal class JourneyPolylines(private val repository: TrackRepository) {

    /**
     * The lines for [tracks], in their order, emitted progressively — partial lists while the
     * loads run, always the complete one last. Emissions are batched rather than per track: each
     * one costs the collector a list copy and (on the journey map) a full GeoJSON rebuild, so a
     * cache-served reopen collapses to a handful instead of one per track.
     */
    fun linesFor(tracks: List<TrackSummary>): Flow<List<JourneyLine>> = flow {
        val loaded = ArrayList<JourneyLine>(tracks.size)
        for (track in tracks) {
            loaded += lineFor(track)
            if (loaded.size < tracks.size && loaded.size % PUBLISH_EVERY == 0) emit(ArrayList(loaded))
        }
        emit(loaded)
    }

    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<Triple<Long, Long?, Boolean>, JourneyLine>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Long, Long?, Boolean>, JourneyLine>) =
            size > MAX_LINES
    }

    private suspend fun lineFor(track: TrackSummary): JourneyLine {
        val key = Triple(track.id, track.endedAt, track.pathProcessed)
        mutex.withLock { cache[key] }?.let { return it }
        val points = repository.pointsFor(track.id)
        val line = withContext(Dispatchers.Default) {
            JourneyLine(
                trackId = track.id,
                activityType = track.activityType,
                greatCircle = GreatCircle.drawsPath(track.pointCount, ActivityType.ofName(track.activityType)),
                segments = SegmentBreaks.split(points).map(::segmentOf),
            )
        }
        mutex.withLock { cache[key] = line }
        return line
    }

    /** Builds an unpersisted comparison line from the raw path and supplied preview parameters. */
    suspend fun previewLinesFor(
        track: TrackSummary,
        parameters: RoutePathSmoother.Parameters,
    ): Pair<JourneyLine, JourneyLine> {
        val rawPoints = repository.rawPointsFor(track.id).map {
            it.copy(smoothedLatitude = null, smoothedLongitude = null)
        }
        return withContext(Dispatchers.Default) {
            val coordinates = RoutePathSmoother.smooth(
                rawPoints.map { point ->
                    RouteSmoothingFix(
                        latitudeDegrees = point.latitude,
                        longitudeDegrees = point.longitude,
                        timestampMs = point.timestamp,
                        horizontalAccuracyMeters = point.accuracy?.toDouble(),
                        speedMetersPerSecond = point.speed?.toDouble(),
                        bearingDegrees = point.bearing?.toDouble(),
                        speedAccuracyMetersPerSecond = point.speedAccuracy?.toDouble(),
                        bearingAccuracyDegrees = point.bearingAccuracy?.toDouble(),
                        segmentStart = point.segmentStart,
                    )
                },
                parameters,
            )
            val smoothedPoints = rawPoints.mapIndexed { index, point ->
                coordinates[index]?.let { point.copy(smoothedLatitude = it.latitudeDegrees, smoothedLongitude = it.longitudeDegrees) }
                    ?: point
            }
            rawLine(track, rawPoints) to rawLine(track, smoothedPoints)
        }
    }

    private fun rawLine(track: TrackSummary, points: List<TrackPoint>) = JourneyLine(
        trackId = track.id,
        activityType = track.activityType,
        greatCircle = GreatCircle.drawsPath(track.pointCount, ActivityType.ofName(track.activityType)),
        segments = SegmentBreaks.split(points).map(::segmentOf),
    )

    private fun segmentOf(points: List<TrackPoint>): DoubleArray {
        val coords = DoubleArray(points.size * 2)
        for (i in points.indices) {
            coords[i * 2] = points[i].pathLongitude
            coords[i * 2 + 1] = points[i].pathLatitude
        }
        return PolylineSimplifier.simplify(coords, TOLERANCE_DEG)
    }

    private companion object {
        /**
         * ~2 m of latitude — sub-pixel until around zoom 18, which is closer than a many-track
         * map is read at, while the radial pass still discards the bulk of 1 Hz fixes (walking
         * lays one down every ~1.5 m). Deliberately tighter than the web overview's 1e-4: that
         * map never zooms past a whole history, this one frames a single day, where a 10 m corner
         * cut is a visible few pixels. Full fidelity stays the track detail's job.
         */
        const val TOLERANCE_DEG = 2e-5

        /** Roughly a long journey's worth of tracks; a simplified line is a few KB. */
        const val MAX_LINES = 300

        /** Tracks per progressive emission — often enough to watch the map fill, rare enough
         *  that the per-emission costs stay negligible. */
        const val PUBLISH_EVERY = 8
    }
}
