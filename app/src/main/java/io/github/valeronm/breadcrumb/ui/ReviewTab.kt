package io.github.valeronm.breadcrumb.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.db.ReviewQueueEntity
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ReviewTab(viewModel: TrackListViewModel) {
    val all by viewModel.reviewQueue.collectAsStateWithLifecycle()
    val analysis by viewModel.analysisQueueStatus.collectAsStateWithLifecycle()
    val analysisEnabled = viewModel.analysisEnabled
    val analysisMessage = when {
        !analysisEnabled -> stringResource(R.string.review_analysis_disabled)
        analysis.running + analysis.queued + analysis.failed + analysis.unprocessed == 0 ->
            stringResource(R.string.review_analysis_current)
        else -> stringResource(
            R.string.review_analysis_status,
            analysis.running,
            analysis.queued,
            analysis.unprocessed,
            analysis.failed,
        )
    }
    var showResolved by remember { mutableStateOf(false) }
    val visible = all.filter { item ->
        if (showResolved) {
            item.state != ReviewQueueEntity.PENDING && item.state != ReviewQueueEntity.SNOOZED
        } else {
            item.state == ReviewQueueEntity.PENDING
        }
    }
    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                analysisMessage,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = viewModel::runRouteAnalysisNow,
            enabled = analysisEnabled &&
                analysis.queued + analysis.running + analysis.failed + analysis.unprocessed > 0,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            Text(stringResource(R.string.review_process_queue_now))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(!showResolved, { showResolved = false }, label = { Text(stringResource(R.string.review_pending, all.count { it.state == ReviewQueueEntity.PENDING })) })
            FilterChip(showResolved, { showResolved = true }, label = { Text(stringResource(R.string.review_resolved)) })
        }
        if (visible.isEmpty()) {
            Text(
                stringResource(if (showResolved) R.string.review_empty_resolved else R.string.review_empty),
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visible, key = ReviewQueueEntity::id) { item ->
                    ReviewCard(item, viewModel, showResolved)
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(item: ReviewQueueEntity, viewModel: TrackListViewModel, resolved: Boolean) {
    var primaryPoints by remember(item.id) { mutableStateOf<List<TrackPoint>>(emptyList()) }
    var secondaryPoints by remember(item.id) { mutableStateOf<List<TrackPoint>>(emptyList()) }
    LaunchedEffect(item.id) {
        item.primaryTrackId?.let { primaryPoints = viewModel.reviewPoints(it) }
        item.secondaryTrackId?.let { secondaryPoints = viewModel.reviewPoints(it) }
    }
    val duration = item.endWallTimeMs?.let { (it - item.startWallTimeMs).coerceAtLeast(0L) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(reviewTitle(item), style = MaterialTheme.typography.titleMedium)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.startWallTimeMs)) +
                    (duration?.let { " · ${DateUtils.formatElapsedTime(it / 1000)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(reviewReason(item.reasonCode), style = MaterialTheme.typography.bodyMedium)
            if (primaryPoints.isNotEmpty()) {
                MapLibreTrackMap(
                    points = primaryPoints,
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    activity = item.currentValue?.let(ActivityType::ofName),
                )
            }
            if (secondaryPoints.isNotEmpty()) {
                MapLibreTrackMap(
                    points = secondaryPoints,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    activity = item.currentValue?.let(ActivityType::ofName),
                )
            }
            Text(
                stringResource(
                    R.string.review_suggestion,
                    item.currentValue ?: stringResource(R.string.review_unknown),
                    item.suggestedValue ?: reviewSuggestion(item),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (resolved) {
                Text(item.state.lowercase(), style = MaterialTheme.typography.labelMedium)
                if (item.state == ReviewQueueEntity.ACCEPTED &&
                    (item.kind == ReviewQueueEntity.MERGE_TRACKS || item.kind == ReviewQueueEntity.SHORT_STOP)
                ) {
                    OutlinedButton(onClick = { viewModel.undoReviewMerge(item) }) {
                        Text(stringResource(R.string.review_undo_merge))
                    }
                }
            } else {
                ReviewActions(item, viewModel)
            }
        }
    }
}

@Composable
private fun ReviewActions(item: ReviewQueueEntity, viewModel: TrackListViewModel) {
    val other = stringResource(R.string.review_other_type)
    val options = remember { ActivityType.entries.filter { it != ActivityType.STILL && it != ActivityType.UNKNOWN } }
    var showOptions by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.answerReview(item, "ACCEPT") }) {
            Text(stringResource(if (item.kind == ReviewQueueEntity.TRANSPORT) R.string.review_confirm_type else R.string.review_confirm))
        }
        OutlinedButton(onClick = {
            if (item.kind == ReviewQueueEntity.TRANSPORT) {
                showOptions = true
            } else {
                viewModel.answerReview(item, "REJECT")
            }
        }) {
            Text(if (item.kind == ReviewQueueEntity.TRANSPORT) other else stringResource(R.string.review_keep_separate))
        }
        androidx.compose.material3.DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
            options.forEach { activity ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(activity.name.lowercase().replace('_', ' ')) },
                    onClick = {
                        viewModel.answerReview(item, "CORRECT", activity.name)
                        showOptions = false
                    },
                )
            }
        }
    }
    OutlinedButton(onClick = { viewModel.answerReview(item, "SKIP") }) {
        Text(stringResource(R.string.review_skip))
    }
}

@Composable
private fun reviewTitle(item: ReviewQueueEntity) = stringResource(
    when (item.kind) {
        ReviewQueueEntity.TRANSPORT -> R.string.review_kind_transport
        ReviewQueueEntity.MERGE_TRACKS -> R.string.review_kind_merge
        ReviewQueueEntity.SHORT_STOP -> R.string.review_kind_short_stop
        ReviewQueueEntity.VISIT_DRIFT -> R.string.review_kind_visit
        ReviewQueueEntity.PLACE_ASSIGNMENT -> R.string.review_kind_place
        ReviewQueueEntity.PLACE_DUPLICATE -> R.string.review_kind_duplicate_place
        else -> R.string.review_kind_generic
    },
)

@Composable
private fun reviewReason(code: String) = stringResource(
    when (code) {
        "personal_classifier_suggestion" -> R.string.review_reason_transport
        "short_stop_between_similar_tracks" -> R.string.review_reason_short_stop
        "nearby_same_type_tracks" -> R.string.review_reason_merge
        else -> R.string.review_reason_generic
    },
)

@Composable
private fun reviewSuggestion(item: ReviewQueueEntity) = stringResource(
    when (item.kind) {
        ReviewQueueEntity.MERGE_TRACKS -> R.string.review_suggestion_merge
        ReviewQueueEntity.SHORT_STOP -> R.string.review_suggestion_short_stop
        ReviewQueueEntity.VISIT_DRIFT -> R.string.review_suggestion_visit
        ReviewQueueEntity.PLACE_ASSIGNMENT -> R.string.review_suggestion_place
        ReviewQueueEntity.PLACE_DUPLICATE -> R.string.review_suggestion_duplicate
        else -> R.string.review_suggestion_generic
    },
)
