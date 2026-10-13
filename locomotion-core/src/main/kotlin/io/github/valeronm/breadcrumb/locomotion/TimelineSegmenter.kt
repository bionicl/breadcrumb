package io.github.valeronm.breadcrumb.locomotion

/** A replayable pair of one observation and the motion estimate produced for it. */
data class TimelineFrame(val observation: Observation, val motion: MotionEstimate?)

/**
 * Materializes movement, visit, and uncovered-data intervals. Existing user identities and locks
 * survive refinement by overlap; uncertain samples do not create a stay or a fabricated route.
 */
class TimelineSegmenter(
    private val maximumContinuityGapNanos: Long = DEFAULT_MAX_CONTINUITY_GAP_NANOS,
) {
    init { require(maximumContinuityGapNanos > 0) }

    fun refine(
        frames: List<TimelineFrame>,
        previousSegments: List<TimelineSegment> = emptyList(),
    ): List<TimelineSegment> {
        val ordered = frames.distinctBy { it.observation.id }
            .sortedBy { it.observation.elapsedRealtimeNanos }
            .filter { it.observation.elapsedRealtimeNanos >= 0L }
        if (ordered.isEmpty()) return emptyList()

        val segments = mutableListOf<TimelineSegment>()
        var activeKind: SegmentKind? = null
        var activeStartMs: Long? = null
        var activeStartObservationId: String? = null
        var previousFrame: TimelineFrame? = null

        fun closeActive(endMs: Long?) {
            val kind = activeKind ?: return
            val start = activeStartMs ?: return
            if (endMs == null || endMs > start) {
                segments += TimelineSegment(
                    id = "${kind.name.lowercase()}:${activeStartObservationId.orEmpty()}",
                    kind = kind,
                    startWallTimeMs = start,
                    endWallTimeMs = endMs,
                    observationIds = ordered.asSequence()
                        .filter { it.observation.wallTimeMs in start..(endMs ?: Long.MAX_VALUE) }
                        .map { it.observation.id }
                        .toList(),
                )
            }
            activeKind = null
            activeStartMs = null
            activeStartObservationId = null
        }

        for (frame in ordered) {
            val previous = previousFrame
            val observation = frame.observation
            val largeGap = previous != null &&
                observation.elapsedRealtimeNanos - previous.observation.elapsedRealtimeNanos > maximumContinuityGapNanos
            if (largeGap) {
                val gapStart = previous.observation.wallTimeMs
                closeActive(gapStart)
                if (observation.wallTimeMs > gapStart) {
                    segments += TimelineSegment(
                        id = "gap:${previous.observation.id}:${observation.id}",
                        kind = SegmentKind.DATA_GAP,
                        startWallTimeMs = gapStart,
                        endWallTimeMs = observation.wallTimeMs,
                        observationIds = listOf(previous.observation.id, observation.id),
                    )
                }
            }

            val kind = when (frame.motion?.state) {
                MotionState.MOVING -> SegmentKind.MOVEMENT
                MotionState.STATIONARY -> SegmentKind.VISIT
                MotionState.UNCERTAIN, null -> null
            }
            if (kind != null) {
                val transitionElapsed = frame.motion?.sinceElapsedRealtimeNanos ?: observation.elapsedRealtimeNanos
                val continuousStart = if (largeGap) {
                    maxOf(transitionElapsed, observation.elapsedRealtimeNanos - maximumContinuityGapNanos)
                } else {
                    transitionElapsed
                }
                val transitionWall = wallTimeAt(ordered, continuousStart)
                if (activeKind != kind) {
                    closeActive(transitionWall)
                    activeKind = kind
                    activeStartMs = transitionWall
                    activeStartObservationId = observation.id
                }
            }
            previousFrame = frame
        }
        closeActive(null)
        return preserveExisting(segments, previousSegments)
    }

    private fun wallTimeAt(frames: List<TimelineFrame>, elapsedNanos: Long): Long {
        val before = frames.lastOrNull { it.observation.elapsedRealtimeNanos <= elapsedNanos }
        val after = frames.firstOrNull { it.observation.elapsedRealtimeNanos >= elapsedNanos }
        if (before == null) return after?.observation?.wallTimeMs ?: 0L
        if (after == null || before === after) return before.observation.wallTimeMs
        val elapsedSpan = after.observation.elapsedRealtimeNanos - before.observation.elapsedRealtimeNanos
        if (elapsedSpan <= 0L) return before.observation.wallTimeMs
        val fraction = (elapsedNanos - before.observation.elapsedRealtimeNanos).toDouble() / elapsedSpan
        val wallSpan = after.observation.wallTimeMs - before.observation.wallTimeMs
        return before.observation.wallTimeMs + (wallSpan * fraction).toLong()
    }

    private fun preserveExisting(
        inferred: List<TimelineSegment>,
        previous: List<TimelineSegment>,
    ): List<TimelineSegment> {
        val usedPreviousIds = mutableSetOf<String>()
        return inferred.map { current ->
            val match = previous.asSequence()
                .filter { it.kind == current.kind && it.id !in usedPreviousIds }
                .map { it to overlap(current, it) }
                .filter { it.second > 0L }
                .maxByOrNull { it.second }
                ?.first ?: return@map current
            usedPreviousIds += match.id
            current.copy(
                id = match.id,
                transportMode = match.confirmedTransportMode ?: match.transportMode ?: current.transportMode,
                confirmedTransportMode = match.confirmedTransportMode,
                startWallTimeMs = if (match.startBoundary == BoundaryOrigin.USER_CONFIRMED) {
                    match.startWallTimeMs
                } else {
                    current.startWallTimeMs
                },
                endWallTimeMs = if (match.endBoundary == BoundaryOrigin.USER_CONFIRMED) {
                    match.endWallTimeMs
                } else {
                    current.endWallTimeMs
                },
                startBoundary = if (match.startBoundary == BoundaryOrigin.USER_CONFIRMED) {
                    match.startBoundary
                } else {
                    current.startBoundary
                },
                endBoundary = if (match.endBoundary == BoundaryOrigin.USER_CONFIRMED) {
                    match.endBoundary
                } else {
                    current.endBoundary
                },
                lockedByUser = match.lockedByUser,
            )
        }
    }

    private fun overlap(first: TimelineSegment, second: TimelineSegment): Long {
        val start = maxOf(first.startWallTimeMs, second.startWallTimeMs)
        val end = minOf(first.endWallTimeMs ?: Long.MAX_VALUE, second.endWallTimeMs ?: Long.MAX_VALUE)
        return (end - start).coerceAtLeast(0L)
    }

    companion object { const val DEFAULT_MAX_CONTINUITY_GAP_NANOS = 120_000_000_000L }
}
