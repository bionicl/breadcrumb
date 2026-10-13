package io.github.valeronm.breadcrumb.locomotion

/** Structured, coordinate-free evidence for explaining engine decisions (LocoKit2 LK-14). */
data class EngineDiagnosticEvent(
    val id: String,
    val wallTimeMs: Long,
    val elapsedRealtimeNanos: Long,
    val subsystem: DiagnosticSubsystem,
    val code: String,
    val level: DiagnosticLevel,
    val motionState: MotionState? = null,
    val recordingState: RecordingState? = null,
    val evidenceObservationIds: List<String> = emptyList(),
    val metrics: Map<DiagnosticMetric, Double> = emptyMap(),
)

enum class DiagnosticSubsystem { INPUT, FILTER, MOTION, CLASSIFIER, TIMELINE, CAPTURE, STORAGE, MODEL }
enum class DiagnosticLevel { INFO, WARNING, ERROR }

enum class DiagnosticMetric {
    FIX_AGE_MILLIS,
    HORIZONTAL_ACCURACY_METERS,
    SPEED_METERS_PER_SECOND,
    CONFIDENCE,
    WINDOW_DURATION_MILLIS,
    SAMPLE_COUNT,
    QUEUE_DEPTH,
    PROCESSING_DURATION_MILLIS,
    REQUESTED_SENSOR_PERIOD_MICROS,
    OBSERVED_SENSOR_PERIOD_MICROS,
    AVAILABLE_SENSOR_COUNT,
    REGISTERED_SENSOR_COUNT,
}

/** Bounded in-memory journal. Callers can persist selected summaries separately when needed. */
class EngineDiagnosticJournal(private val capacity: Int = DEFAULT_CAPACITY) {
    private val events = ArrayDeque<EngineDiagnosticEvent>()

    init { require(capacity > 0) }

    @Synchronized
    fun append(event: EngineDiagnosticEvent) {
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
    }

    @Synchronized
    fun recent(): List<EngineDiagnosticEvent> = events.toList()

    @Synchronized
    fun clear() = events.clear()

    companion object { const val DEFAULT_CAPACITY = 500 }
}
