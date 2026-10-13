package io.github.valeronm.breadcrumb.locomotion

/** Bounded recent-observation buffer for moving a confirmed departure boundary backwards. */
class DeparturePrebuffer(
    private val maximumAgeNanos: Long = DEFAULT_MAX_AGE_NANOS,
    private val maximumObservations: Int = DEFAULT_MAXIMUM_OBSERVATIONS,
) {
    private val observations = ArrayDeque<Observation>()
    private var bootEpochId: String? = null
    private var lastElapsedNanos: Long? = null

    init {
        require(maximumAgeNanos > 0)
        require(maximumObservations > 0)
    }

    @Synchronized
    fun add(observation: Observation) {
        val epoch = bootEpochId
        if (epoch != null && epoch != observation.bootEpochId) clear()
        bootEpochId = observation.bootEpochId
        val previous = lastElapsedNanos
        if (previous != null && observation.elapsedRealtimeNanos <= previous) return
        lastElapsedNanos = observation.elapsedRealtimeNanos
        observations.addLast(observation)
        trim(observation.elapsedRealtimeNanos)
    }

    /** Return observations from the earliest confirmed evidence through the present, once. */
    @Synchronized
    fun confirmedDeparture(firstEvidenceElapsedNanos: Long): List<Observation> {
        if (observations.isEmpty()) return emptyList()
        val result = observations.filter { it.elapsedRealtimeNanos >= firstEvidenceElapsedNanos }
        clear()
        return result
    }

    @Synchronized
    fun snapshot(nowElapsedRealtimeNanos: Long): List<Observation> {
        trim(nowElapsedRealtimeNanos)
        return observations.toList()
    }

    @Synchronized
    fun clear() {
        observations.clear()
        bootEpochId = null
        lastElapsedNanos = null
    }

    private fun trim(nowElapsedNanos: Long) {
        while (observations.isNotEmpty() &&
            (nowElapsedNanos - observations.first().elapsedRealtimeNanos > maximumAgeNanos ||
                observations.size > maximumObservations)
        ) {
            observations.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_MAX_AGE_NANOS = 60_000_000_000L
        const val DEFAULT_MAXIMUM_OBSERVATIONS = 2_000
    }
}
