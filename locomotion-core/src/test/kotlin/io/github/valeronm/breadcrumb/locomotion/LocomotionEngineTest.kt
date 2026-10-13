package io.github.valeronm.breadcrumb.locomotion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocomotionEngineTest {
    @Test fun `observations flow through filtering motion decisions and bounded diagnostics`() {
        val engine = LocomotionEngine(diagnostics = EngineDiagnosticJournal(capacity = 5))
        val results = (1..4).map { index ->
            engine.process(
                observation(
                    id = "fix-$index",
                    timeNanos = index * FIVE_SECONDS,
                    longitude = (index - 1) * 0.0005,
                ),
            )
        }

        assertTrue(results.all { it.location?.accepted == true })
        assertEquals(MotionState.MOVING, results.last().motion?.state)
        assertEquals("motion.location_progress.confirmed", results.last().motion?.reasonCode)
        assertEquals(5, engine.diagnosticSnapshot().size)
        assertEquals("fix-4", engine.diagnosticSnapshot().last().evidenceObservationIds.first())
    }

    @Test fun `out of order input is ignored and does not rewind last decision`() {
        val engine = LocomotionEngine()
        engine.process(observation("new", 10 * FIVE_SECONDS, longitude = 0.0))
        val stale = engine.process(observation("stale", 9 * FIVE_SECONDS, longitude = 1.0))

        assertTrue(stale.ignored)
        assertEquals("input.out_of_order_observation", stale.diagnostics.single().code)
        assertEquals("new", engine.latestMotionEstimate()?.evidence?.first()?.observationId)
    }

    @Test fun `boot epoch change resets filters and cached evidence before new fixes`() {
        val engine = LocomotionEngine()
        val firstEpoch = engine.process(observation("first", FIVE_SECONDS, longitude = 0.0, epoch = "boot-a"))
        val nextEpoch = engine.process(observation("after-reboot", FIVE_SECONDS, longitude = 0.0, epoch = "boot-b"))

        assertNotNull(firstEpoch.location?.filtered)
        assertTrue(nextEpoch.location?.accepted == true)
        assertEquals("location.filter_initialized", nextEpoch.location.reasonCode)
        assertTrue(nextEpoch.diagnostics.any { it.code == "input.boot_epoch_changed" })
        assertFalse(nextEpoch.ignored)
    }

    @Test fun `restored engine preserves detector hysteresis and cached evidence`() {
        val uninterrupted = LocomotionEngine()
        uninterrupted.process(observation("fix-1", FIVE_SECONDS, longitude = 0.0))
        uninterrupted.process(observation("fix-2", 2 * FIVE_SECONDS, longitude = 0.0005))
        val snapshot = uninterrupted.snapshot()
        val persistedSnapshot = requireNotNull(
            LocomotionEngineSnapshotCodec.decode(LocomotionEngineSnapshotCodec.encode(snapshot)),
        )
        val resumed = LocomotionEngine()

        assertTrue(resumed.restore(persistedSnapshot, expectedBootEpochId = "boot-a"))
        assertEquals(snapshot, resumed.snapshot())
        val next = observation("fix-3", 3 * FIVE_SECONDS, longitude = 0.001)

        assertEquals(uninterrupted.process(next), resumed.process(next))
        val final = observation("fix-4", 4 * FIVE_SECONDS, longitude = 0.0015)
        assertEquals(uninterrupted.process(final), resumed.process(final))
        assertEquals(uninterrupted.snapshot(), resumed.snapshot())
    }

    @Test fun `engine refuses a checkpoint from another boot epoch without changing state`() {
        val source = LocomotionEngine()
        source.process(observation("source", FIVE_SECONDS, longitude = 0.0))
        val target = LocomotionEngine()
        val before = target.snapshot()

        assertFalse(source.snapshot().let { target.restore(it, expectedBootEpochId = "boot-b") })
        assertEquals(before, target.snapshot())
    }

    @Test fun `snapshot codec rejects truncated and unknown-version payloads`() {
        val engine = LocomotionEngine()
        engine.process(observation("one", FIVE_SECONDS, longitude = 0.0))
        val encoded = LocomotionEngineSnapshotCodec.encode(engine.snapshot())

        assertEquals(null, LocomotionEngineSnapshotCodec.decode(encoded.copyOf(encoded.size - 1)))
        val unknownVersion = encoded.copyOf().apply { this[7] = (this[7] + 1).toByte() }
        assertEquals(null, LocomotionEngineSnapshotCodec.decode(unknownVersion))
    }

    private fun observation(id: String, timeNanos: Long, longitude: Double, epoch: String = "boot-a") =
        Observation(
            id = id,
            sessionId = "session",
            bootEpochId = epoch,
            wallTimeMs = timeNanos / 1_000_000,
            elapsedRealtimeNanos = timeNanos,
            source = ObservationSource.RAW_GNSS,
            location = LocationMeasurement(1.0, longitude, 3.0),
        )

    private companion object {
        const val FIVE_SECONDS = 5_000_000_000L
    }
}
