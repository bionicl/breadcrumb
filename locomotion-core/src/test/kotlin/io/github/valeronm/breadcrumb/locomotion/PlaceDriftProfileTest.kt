package io.github.valeronm.breadcrumb.locomotion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceDriftProfileTest {
    @Test fun `directional profile recognizes recurring drift and rejects a different direction`() {
        val profile = PlaceDriftProfile()
        repeat(3) { index ->
            assertTrue(
                profile.learnConfirmedStay(
                    stayId = "stay-$index",
                    samples = listOf(sample(east = 21.0), sample(east = 24.0), sample(east = 22.0)),
                    confirmedStationary = true,
                    provenance = DriftTrainingProvenance.LIVE,
                ),
            )
        }

        val recurring = profile.assess(eastMeters = 23.0, northMeters = 0.0, horizontalAccuracyMeters = 6.0)
        val otherDirection = profile.assess(eastMeters = 0.0, northMeters = 23.0, horizontalAccuracyMeters = 6.0)

        assertTrue(recurring.likelyKnownDrift)
        assertEquals(3, recurring.supportCount)
        assertEquals("drift.matches_confirmed_profile", recurring.reasonCode)
        assertFalse(otherDirection.likelyKnownDrift)
    }

    @Test fun `unconfirmed imported synthetic and demo stays cannot train profile`() {
        val profile = PlaceDriftProfile()
        val data = listOf(
            Triple("unconfirmed", false, DriftTrainingProvenance.LIVE),
            Triple("imported", true, DriftTrainingProvenance.IMPORTED),
            Triple("synthetic", true, DriftTrainingProvenance.SYNTHETIC),
            Triple("demo", true, DriftTrainingProvenance.DEMO),
        )

        data.forEach { (id, confirmed, provenance) ->
            assertFalse(profile.learnConfirmedStay(id, listOf(sample(east = 24.0)), confirmed, provenance))
        }
        val result = profile.assess(24.0, 0.0, 5.0)
        assertEquals("drift.insufficient_confirmed_visits", result.reasonCode)
    }

    @Test fun `repeated samples in one stay contribute one median and duplicate stay is ignored`() {
        val profile = PlaceDriftProfile()
        val visitSamples = listOf(sample(east = 19.0), sample(east = 20.0), sample(east = 150.0))

        assertTrue(profile.learnConfirmedStay("same", visitSamples, true, DriftTrainingProvenance.LIVE))
        assertFalse(profile.learnConfirmedStay("same", visitSamples, true, DriftTrainingProvenance.LIVE))
        repeat(2) { index ->
            profile.learnConfirmedStay("other-$index", listOf(sample(east = 20.0)), true, DriftTrainingProvenance.LIVE)
        }

        val result = profile.assess(20.0, 0.0, 4.0)
        assertTrue(result.likelyKnownDrift)
        assertEquals(3, result.supportCount)
        assertEquals(20.0, result.expectedRadiusMeters)
    }

    @Test fun `poor accuracy and isolated gps jumps remain unknown`() {
        val profile = PlaceDriftProfile()
        repeat(3) { index ->
            profile.learnConfirmedStay("stay-$index", listOf(sample(east = 20.0)), true, DriftTrainingProvenance.LIVE)
        }

        val poorFix = profile.assess(20.0, 0.0, 90.0)
        val isolatedJump = profile.assess(120.0, 0.0, 5.0)

        assertEquals("drift.accuracy_too_low", poorFix.reasonCode)
        assertFalse(isolatedJump.likelyKnownDrift)
    }

    private fun sample(east: Double, north: Double = 0.0, accuracy: Double = 5.0) =
        PlaceDriftSample(east, north, accuracy)
}
