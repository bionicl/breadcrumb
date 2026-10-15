package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.locomotion.RoutePathSmoother
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun PathSmoothingDebugScreen(
    onBack: () -> Unit,
    viewModel: TrackListViewModel,
) {
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    var day by rememberSaveable { mutableStateOf(LocalDate.now(timelineZone()).toString()) }
    val selectedDay = remember(day) { LocalDate.parse(day) }
    var parameters by remember { mutableStateOf(RoutePathSmoother.Parameters()) }
    var originalLines by remember { mutableStateOf(emptyList<JourneyLine>()) }
    var previewLines by remember { mutableStateOf(emptyList<JourneyLine>()) }
    var calculating by remember { mutableStateOf(false) }
    var calculationFailed by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var pendingCalculation by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val dayTracks = remember(timeline, selectedDay) {
        timeline.orEmpty()
            .filter { it.filedOn == selectedDay }
            .filterIsInstance<TimelineItem.TrackItem>()
            .map { it.summary }
    }
    val trackIds = remember(dayTracks) { dayTracks.map { it.id } }

    fun scheduleCalculation(config: RoutePathSmoother.Parameters, delayMs: Long) {
        pendingCalculation?.cancel()
        val token = ++generation
        calculating = true
        calculationFailed = false
        pendingCalculation = scope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                val raw = ArrayList<JourneyLine>(dayTracks.size)
                val smoothed = ArrayList<JourneyLine>(dayTracks.size)
                for (track in dayTracks) {
                    coroutineContext.ensureActive()
                    val (original, preview) = viewModel.previewPathLines(track, config)
                    raw += original
                    smoothed += preview
                }
                coroutineContext.ensureActive()
                if (token == generation) {
                    originalLines = raw
                    previewLines = smoothed
                    calculating = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == generation) {
                    originalLines = emptyList()
                    previewLines = emptyList()
                    calculating = false
                    calculationFailed = true
                }
            }
        }
    }

    LaunchedEffect(selectedDay, trackIds) {
        scheduleCalculation(parameters, delayMs = 0)
    }

    fun changeParameters(updated: RoutePathSmoother.Parameters) {
        parameters = updated
        scheduleCalculation(updated, delayMs = PREVIEW_DEBOUNCE_MS)
    }

    SettingsSubScreen(stringResource(R.string.path_smoothing_debug_title), onBack) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { day = selectedDay.minusDays(1).toString() }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.path_smoothing_previous_day))
            }
            Text(
                dayLabel(
                    selectedDay,
                    LocalDate.now(timelineZone()),
                    stringResource(R.string.relative_today).standaloneCase(),
                    stringResource(R.string.relative_yesterday).standaloneCase(),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(
                onClick = { day = selectedDay.plusDays(1).toString() },
                enabled = selectedDay < LocalDate.now(timelineZone()),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.path_smoothing_next_day))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (calculating) CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.path_smoothing_route_count, previewLines.size, dayTracks.size),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (calculating) Text(stringResource(R.string.path_smoothing_calculating), style = MaterialTheme.typography.bodySmall)
        }
        if (calculationFailed) {
            Text(
                stringResource(R.string.path_smoothing_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            stringResource(R.string.path_smoothing_preview_only),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(300.dp)) {
            MapLibreJourneyMap(
                lines = previewLines,
                originalLines = originalLines,
                places = emptyList(),
                frameKey = selectedDay,
                linesComplete = !calculating && previewLines.size == dayTracks.size,
                onOpenPlace = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendLabel(
                stringResource(R.string.path_smoothing_original_legend),
                androidx.compose.ui.graphics.Color(0xFF858585),
            )
            LegendLabel(stringResource(R.string.path_smoothing_preview_legend), MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.path_smoothing_parameters), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { changeParameters(RoutePathSmoother.Parameters()) }) {
                Icon(Icons.Filled.Restore, contentDescription = null)
                Text(stringResource(R.string.path_smoothing_reset))
            }
        }
        DebugPathSlider(
            stringResource(R.string.path_smoothing_min_accuracy),
            parameters.minimumSmoothingAccuracyMeters.toFloat(),
            0f..100f,
            5,
            { "${it.toInt()} m" },
        ) { changeParameters(parameters.copy(minimumSmoothingAccuracyMeters = it.toDouble())) }
        DebugPathSlider(
            stringResource(R.string.path_smoothing_accuracy_weight),
            parameters.horizontalAccuracyMultiplier.toFloat() * 100f,
            25f..400f,
            25,
            { String.format(java.util.Locale.getDefault(), "%.2f×", it / 100f) },
        ) { changeParameters(parameters.copy(horizontalAccuracyMultiplier = it.toDouble() / 100.0)) }
        DebugPathSlider(
            stringResource(R.string.path_smoothing_max_correction),
            parameters.maximumCorrectionMeters.toFloat(),
            0f..150f,
            5,
            { "${it.toInt()} m" },
        ) { changeParameters(parameters.copy(maximumCorrectionMeters = it.toDouble())) }
        DebugPathSlider(
            stringResource(R.string.path_smoothing_motion_noise),
            parameters.accelerationNoiseMetersPerSecondSquared.toFloat() * 10f,
            5f..100f,
            5,
            { String.format(java.util.Locale.getDefault(), "%.1f m/s²", it / 10f) },
        ) { changeParameters(parameters.copy(accelerationNoiseMetersPerSecondSquared = it.toDouble() / 10.0)) }
        DebugPathSlider(
            stringResource(R.string.path_smoothing_outlier_sigma),
            parameters.outlierSigmaLimit.toFloat() * 10f,
            20f..200f,
            10,
            { String.format(java.util.Locale.getDefault(), "%.1fσ", it / 10f) },
        ) { changeParameters(parameters.copy(outlierSigmaLimit = it.toDouble() / 10.0)) }
        DebugPathSlider(
            stringResource(R.string.path_smoothing_speed_accuracy),
            parameters.maximumSpeedAccuracyMetersPerSecond.toFloat() * 10f,
            1f..150f,
            5,
            { String.format(java.util.Locale.getDefault(), "%.1f m/s", it / 10f) },
        ) { changeParameters(parameters.copy(maximumSpeedAccuracyMetersPerSecond = it.toDouble() / 10.0)) }
        DebugPathSlider(
            stringResource(R.string.path_smoothing_bearing_accuracy),
            parameters.maximumBearingAccuracyDegrees.toFloat(),
            0f..180f,
            5,
            { "${it.toInt()}°" },
        ) { changeParameters(parameters.copy(maximumBearingAccuracyDegrees = it.toDouble())) }
        DebugPathSlider(
            stringResource(R.string.path_smoothing_max_gap),
            parameters.maximumContiguousGapMs.toFloat() / 1_000f,
            0f..600f,
            10,
            { "${it.toInt()} s" },
        ) { changeParameters(parameters.copy(maximumContiguousGapMs = it.toLong() * 1_000L)) }
    }
}

@Composable
private fun LegendLabel(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DebugPathSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Int,
    valueText: @Composable (Float) -> String,
    onChange: (Float) -> Unit,
) {
    SliderSetting(label, value, range, step, valueText, onChange)
}

private const val PREVIEW_DEBOUNCE_MS = 2_000L
