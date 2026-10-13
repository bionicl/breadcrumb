package io.github.valeronm.breadcrumb.locomotion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeparturePrebufferTest {
    @Test fun `keeps only recent observations within capacity`() {
        val buffer = DeparturePrebuffer(maximumAgeNanos = 10, maximumObservations = 3)
        (1L..5L).forEach { buffer.add(observation("$it", it)) }

        assertEquals(listOf("3", "4", "5"), buffer.snapshot(13).map { it.id })
    }

    @Test fun `confirmed departure extracts evidence tail exactly once`() {
        val buffer = DeparturePrebuffer()
        (1L..5L).forEach { buffer.add(observation("$it", it * SECOND)) }

        assertEquals(listOf("3", "4", "5"), buffer.confirmedDeparture(3 * SECOND).map { it.id })
        assertTrue(buffer.snapshot(5 * SECOND).isEmpty())
    }

    @Test fun `boot epoch change discards prior boot observations`() {
        val buffer = DeparturePrebuffer()
        buffer.add(observation("before", SECOND, "boot-a"))
        buffer.add(observation("after", SECOND, "boot-b"))

        assertEquals(listOf("after"), buffer.snapshot(SECOND).map { it.id })
    }

    @Test fun `duplicate and out of order observations are ignored`() {
        val buffer = DeparturePrebuffer()
        buffer.add(observation("new", 2 * SECOND))
        buffer.add(observation("old", SECOND))
        buffer.add(observation("duplicate", 2 * SECOND))

        assertEquals(listOf("new"), buffer.snapshot(2 * SECOND).map { it.id })
    }

    private fun observation(id: String, elapsedNanos: Long, epoch: String = "boot-a") = Observation(
        id = id,
        sessionId = "session",
        bootEpochId = epoch,
        wallTimeMs = elapsedNanos / 1_000_000,
        elapsedRealtimeNanos = elapsedNanos,
        source = ObservationSource.MANUAL_MARKER,
    )

    private companion object { const val SECOND = 1_000_000_000L }
}
