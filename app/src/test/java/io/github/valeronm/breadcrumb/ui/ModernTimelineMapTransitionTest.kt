package io.github.valeronm.breadcrumb.ui

import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.data.Settings.TimelineColorPalette
import io.github.valeronm.breadcrumb.domain.Coordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernTimelineMapTransitionTest {
    @Test
    fun `changing map mode or detail target reframes the retained map`() {
        val overview = overview("day-1")
        val track = track(12L)
        val place = place("home")

        assertTrue(modernTimelineMapNeedsReframe(overview, track, paddingChanged = false))
        assertTrue(modernTimelineMapNeedsReframe(track, place, paddingChanged = false))
        assertTrue(modernTimelineMapNeedsReframe(place, overview, paddingChanged = false))
    }

    @Test
    fun `camera stays still while track selection or legend position changes`() {
        val first = track(12L)
        val second = first.copy(legendBottomPadding = 320.dp)

        assertFalse(modernTimelineMapNeedsReframe(first, second, paddingChanged = false))
    }

    @Test
    fun `settled drawer padding and overview day changes request a new frame`() {
        val overview = overview("day-1")

        assertTrue(modernTimelineMapNeedsReframe(overview, overview, paddingChanged = true))
        assertTrue(modernTimelineMapNeedsReframe(overview, overview("day-2"), paddingChanged = false))
    }

    private fun overview(frameKey: String) = ModernTimelineMapState.Overview(
        lines = emptyList(),
        places = emptyList(),
        frameKey = frameKey,
        linesComplete = true,
        colorPalette = TimelineColorPalette.CLASSIC,
        bottomPaddingPx = 0,
    )

    private fun track(id: Long) = ModernTimelineMapState.Track(
        id = id,
        points = emptyList(),
        noisyPoints = emptyList(),
        activity = null,
        colorMode = ColorMode.SPEED,
        selectedPoint = null,
        dwells = emptyList(),
        overruns = emptyList(),
        endPlaces = emptyList(),
        coloring = null,
        seams = null,
        contextLines = emptyList(),
        showContextLines = false,
        showLegend = false,
        legendBottomPadding = 0.dp,
        bottomPaddingPx = 0,
    )

    private fun place(key: String) = ModernTimelineMapState.PlaceTarget(
        key = key,
        marker = PlaceMarker(Coordinate(52.0, 21.0)),
        radiusM = 100.0,
        bottomPaddingPx = 0,
    )
}
