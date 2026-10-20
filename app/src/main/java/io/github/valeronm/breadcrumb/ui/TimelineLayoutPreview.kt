package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.Settings.TimelineColorPalette
import io.github.valeronm.breadcrumb.data.Settings.TimelineDisplayOptions
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import java.time.LocalDate
import java.time.ZoneId

private val SAMPLE_ORIGIN = Coordinate(lat = 1.0, lon = -2.0)
private val SAMPLE_DAY = LocalDate.of(2021, 3, 4)
private const val SAMPLE_HOUR_MS = 3_600_000L
private val sampleRowShape = RoundedCornerShape(0.dp)

private fun sampleShape(index: Int, grouped: Boolean) =
    if (grouped) groupedRowShape(index, 4) else sampleRowShape

private val classicPageLabels = listOf(R.string.timeline_view_list, R.string.timeline_view_map)

/** The colour page's preview: the same sample day, colored by the palette and location mode in hand. */
@Composable
internal fun TimelineColourSamplePreview(
    displayOptions: TimelineDisplayOptions,
    colorPalette: TimelineColorPalette,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        TimelineLayoutSampleRows(
            displayOptions = displayOptions,
            colorPalette = colorPalette,
            rowSpacing = 2.dp,
            contentPadding = PaddingValues(12.dp),
            grouped = true,
        )
    }
}

@Composable
internal fun TimelineLayoutSamplePreview(
    modern: Boolean,
    displayOptions: TimelineDisplayOptions,
    colorPalette: TimelineColorPalette,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        if (modern) {
            ModernTimelineLayoutSample(displayOptions, colorPalette)
        } else {
            ClassicTimelineLayoutSample(displayOptions, colorPalette)
        }
    }
}

@Composable
private fun ModernTimelineLayoutSample(
    displayOptions: TimelineDisplayOptions,
    colorPalette: TimelineColorPalette,
) {
    val sheetColor = timelineSheetColor()
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            Modifier
                .padding(top = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(sheetColor),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)),
                )
            }
            TimelineLayoutSampleRows(
                displayOptions = displayOptions,
                colorPalette = colorPalette,
                rowSpacing = 0.dp,
                contentPadding = PaddingValues(0.dp),
                rowColor = sheetColor,
            )
        }
    }
}

@Composable
private fun ClassicTimelineLayoutSample(
    displayOptions: TimelineDisplayOptions,
    colorPalette: TimelineColorPalette,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        ViewSwitchRow(
            labelsRes = classicPageLabels,
            selectedIndex = 0,
            onSelect = {},
        )
        TimelineLayoutSampleRows(
            displayOptions = displayOptions,
            colorPalette = colorPalette,
            rowSpacing = 2.dp,
            contentPadding = PaddingValues(top = 8.dp),
        )
    }
}

@Composable
private fun timelineSheetColor() = lerp(
    MaterialTheme.colorScheme.surfaceContainerLow,
    MaterialTheme.colorScheme.surfaceContainerLowest,
    0.35f,
)

@Composable
private fun TimelineLayoutSampleRows(
    displayOptions: TimelineDisplayOptions,
    colorPalette: TimelineColorPalette,
    rowSpacing: Dp,
    contentPadding: PaddingValues,
    rowColor: Color? = null,
    grouped: Boolean = false,
) {
    val zone = timelineZone()
    val placeLabel = stringResource(R.string.settings_timeline_layout_sample_place)
    val samples = remember(placeLabel, zone) { timelineLayoutSamples(placeLabel, zone) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        StayCard(
            item = samples.stayNamed,
            shape = sampleShape(0, grouped),
            named = true,
            highlighted = false,
            zone = zone,
            onClick = {},
            displayOptions = displayOptions,
            colorPalette = colorPalette,
            lastTripActivity = ActivityType.WALKING,
            containerColor = rowColor,
        )
        TrackRow(
            track = samples.walkTrack,
            shape = sampleShape(1, grouped),
            onOpen = {},
            onDelete = {},
            showVisitTime = displayOptions.showVisitTime,
            colorPalette = colorPalette,
            containerColor = rowColor,
        )
        TrackRow(
            track = samples.driveTrack,
            shape = sampleShape(2, grouped),
            onOpen = {},
            onDelete = {},
            showVisitTime = displayOptions.showVisitTime,
            colorPalette = colorPalette,
            containerColor = rowColor,
        )
        StayCard(
            item = samples.stayPlain,
            shape = sampleShape(3, grouped),
            named = false,
            highlighted = false,
            zone = zone,
            onClick = {},
            displayOptions = displayOptions,
            colorPalette = colorPalette,
            lastTripActivity = ActivityType.DRIVING,
            containerColor = rowColor,
        )
    }
}

private data class TimelineLayoutSamples(
    val stayNamed: TimelineItem.StayItem,
    val walkTrack: TrackSummary,
    val driveTrack: TrackSummary,
    val stayPlain: TimelineItem.StayItem,
)

private fun timelineLayoutSamples(placeLabel: String, zone: ZoneId): TimelineLayoutSamples {
    val midnight = SAMPLE_DAY.atStartOfDay(zone).toInstant().toEpochMilli()
    val firstStayStart = midnight + 7 * SAMPLE_HOUR_MS
    val firstStayEnd = firstStayStart + 2 * SAMPLE_HOUR_MS
    val walkEnd = firstStayEnd + 2 * SAMPLE_HOUR_MS
    val secondStayStart = midnight + 13 * SAMPLE_HOUR_MS
    val secondStayEnd = secondStayStart + SAMPLE_HOUR_MS
    val place = Place(
        id = 1,
        label = placeLabel,
        lat = SAMPLE_ORIGIN.lat,
        lon = SAMPLE_ORIGIN.lon,
        createdAt = 0,
        radiusM = 60.0,
        category = PlaceCategory.HOME.code,
    )
    return TimelineLayoutSamples(
        stayNamed = TimelineItem.StayItem(
            stay = StayDeriver.Stay(start = firstStayStart, end = firstStayEnd, afterTrackId = 1, clusterId = 0),
            place = PlaceResolver.ResolvedStay(place = place, visitCount = 4, centroid = SAMPLE_ORIGIN),
            merge = null,
            holdsStart = true,
            holdsEnd = true,
        ),
        walkTrack = TrackSummary(
            id = 1,
            activityType = ActivityType.WALKING.name,
            startedAt = firstStayEnd,
            endedAt = walkEnd,
            distanceMeters = 2_400.0,
            pointCount = 80,
            ignoredCount = 0,
            source = TrackOrigin.RECORDED.code,
            startPlaceId = null,
            endPlaceId = null,
        ),
        driveTrack = TrackSummary(
            id = 2,
            activityType = ActivityType.DRIVING.name,
            startedAt = walkEnd,
            endedAt = secondStayStart,
            distanceMeters = 18_000.0,
            pointCount = 120,
            ignoredCount = 2,
            source = TrackOrigin.RECORDED.code,
            startPlaceId = null,
            endPlaceId = null,
        ),
        stayPlain = TimelineItem.StayItem(
            stay = StayDeriver.Stay(start = secondStayStart, end = secondStayEnd, afterTrackId = 2, clusterId = 1),
            place = PlaceResolver.ResolvedStay(place = null, visitCount = 2, centroid = SAMPLE_ORIGIN),
            merge = null,
            holdsStart = true,
            holdsEnd = true,
        ),
    )
}
