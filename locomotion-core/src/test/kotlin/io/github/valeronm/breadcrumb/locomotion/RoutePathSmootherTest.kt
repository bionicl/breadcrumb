package io.github.valeronm.breadcrumb.locomotion

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutePathSmootherTest {
    private fun fixes(
        count: Int = 7,
        noisyIndex: Int = 3,
        accuracy: Double = 40.0,
        noisyLongitude: Double = 0.0004,
    ) = (0 until count).map { index ->
        RouteSmoothingFix(
            latitudeDegrees = index * 0.0005,
            longitudeDegrees = if (index == noisyIndex) noisyLongitude else 0.0,
            timestampMs = index * 10_000L,
            horizontalAccuracyMeters = if (index == noisyIndex) accuracy else 5.0,
        )
    }

    @Test fun `future fixes pull a weak position toward the observed line`() {
        val input = fixes()
        val result = RoutePathSmoother.smooth(input)
        val corrected = assertNotNull(result[3])
        assertTrue(abs(corrected.longitudeDegrees) < abs(input[3].longitudeDegrees))
        assertNull(result[2], "accurate fixes remain fixed anchors")
        assertNull(result[4], "accurate fixes remain fixed anchors")
    }

    @Test fun `correction is bounded by reported accuracy and hard cap`() {
        val input = fixes(accuracy = 30.0, noisyLongitude = 0.01)
        val corrected = assertNotNull(RoutePathSmoother.smooth(input)[3])
        val longitudeMeters = abs(corrected.longitudeDegrees - input[3].longitudeDegrees) * 111_000.0
        assertTrue(longitudeMeters <= 30.01)
    }

    @Test fun `large uncertainty never permits a correction above the hard cap`() {
        val input = fixes(accuracy = 500.0, noisyLongitude = 0.01)
        val corrected = assertNotNull(RoutePathSmoother.smooth(input)[3])
        val longitudeMeters = abs(corrected.longitudeDegrees - input[3].longitudeDegrees) * 111_000.0
        assertTrue(longitudeMeters <= RoutePathSmoother.MAX_CORRECTION_METERS + 0.01)
    }

    @Test fun `segment starts and long gaps do not share smoothing context`() {
        val boundary = fixes(count = 4, noisyIndex = 1).mapIndexed { index, fix ->
            if (index == 2) fix.copy(segmentStart = true) else fix
        }
        assertTrue(RoutePathSmoother.smooth(boundary).all { it == null })

        val gap = fixes(count = 4, noisyIndex = 1).mapIndexed { index, fix ->
            if (index >= 2) fix.copy(timestampMs = fix.timestampMs + 300_000L) else fix
        }
        assertTrue(RoutePathSmoother.smooth(gap).all { it == null })
    }

    @Test fun `missing accuracy breaks a run and its point is left unchanged`() {
        val input = fixes().mapIndexed { index, fix ->
            if (index == 3) fix.copy(horizontalAccuracyMeters = null) else fix
        }
        assertEquals(List(input.size) { null }, RoutePathSmoother.smooth(input))
    }

    @Test fun `preview parameters can change the smoothing accuracy threshold and sample gap`() {
        val input = fixes(count = 5, noisyIndex = 2, accuracy = 40.0)
        assertNotNull(RoutePathSmoother.smooth(input)[2])
        val strictAccuracy = RoutePathSmoother.Parameters(minimumSmoothingAccuracyMeters = 50.0)
        assertTrue(RoutePathSmoother.smooth(input, strictAccuracy).all { it == null })

        val noContinuousSamples = RoutePathSmoother.Parameters(maximumContiguousGapMs = 0L)
        assertTrue(RoutePathSmoother.smooth(input, noContinuousSamples).all { it == null })
    }
}
