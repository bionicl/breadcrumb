package io.github.valeronm.breadcrumb.locomotion

import kotlin.test.Test
import kotlin.test.assertEquals

class EngineDiagnosticJournalTest {
    @Test fun `journal evicts oldest events at its configured bound`() {
        val journal = EngineDiagnosticJournal(capacity = 2)
        for (index in 1..3) {
            journal.append(
                EngineDiagnosticEvent(
                    id = "event-$index",
                    wallTimeMs = index.toLong(),
                    elapsedRealtimeNanos = index.toLong(),
                    subsystem = DiagnosticSubsystem.MOTION,
                    code = "motion.uncertain",
                    level = DiagnosticLevel.INFO,
                ),
            )
        }

        assertEquals(listOf("event-2", "event-3"), journal.recent().map { it.id })
    }
}
