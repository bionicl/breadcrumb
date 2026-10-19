package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.ReviewQueueEntity
import io.github.valeronm.breadcrumb.domain.ActivityType
import java.util.Date

@Composable
internal fun ReviewTab(viewModel: TrackListViewModel, bucket: ReviewBucket) {
    LaunchedEffect(Unit) { viewModel.reconcileStaleReviewItems() }
    val all by viewModel.reviewQueue.collectAsStateWithLifecycle()
    val places by viewModel.storedPlaces.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable(bucket) { mutableStateOf<String?>(null) }
    var selectedIndex by rememberSaveable(bucket) { mutableStateOf(0) }
    val visible = all.filter {
        when (bucket) {
            ReviewBucket.PENDING -> it.state == ReviewQueueEntity.PENDING
            ReviewBucket.SNOOZED -> it.state == ReviewQueueEntity.SNOOZED
            ReviewBucket.RESOLVED -> it.state !in setOf(ReviewQueueEntity.PENDING, ReviewQueueEntity.SNOOZED)
        }
    }.sortedByDescending { it.startWallTimeMs }
    val index = visible.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
        ?: selectedIndex.coerceIn(0, visible.lastIndex.coerceAtLeast(0))
    val item = visible.getOrNull(index)
    LaunchedEffect(item?.id) {
        selectedId = item?.id
        selectedIndex = index
    }
    val previousLabel = stringResource(R.string.review_previous)
    val nextLabel = stringResource(R.string.review_next)
    // Browse by swiping the header/timeline. The native map keeps all of its pan gestures.
    val navigation = Modifier.pointerInput(item?.id, index, visible.size) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onHorizontalDrag = { change, amount ->
                change.consume()
                distance += amount
            },
            onDragEnd = {
                val target = when {
                    distance < -64.dp.toPx() -> index + 1
                    distance > 64.dp.toPx() -> index - 1
                    else -> index
                }
                visible.getOrNull(target)?.let { selectedId = it.id }
            },
        )
    }.semantics {
        customActions = buildList {
            if (index > 0) {
                add(
                    CustomAccessibilityAction(previousLabel) {
                        selectedId = visible[index - 1].id
                        true
                    },
                )
            }
            if (index < visible.lastIndex) {
                add(
                    CustomAccessibilityAction(nextLabel) {
                        selectedId = visible[index + 1].id
                        true
                    },
                )
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 620.dp).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item == null) {
                Column(
                    Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(if (bucket == ReviewBucket.RESOLVED) R.string.review_empty_resolved else R.string.review_empty),
                        Modifier.padding(24.dp), textAlign = TextAlign.Center,
                    )
                }
            } else {
                key(item.id) {
                    ReviewQuestion(item, viewModel, places, Modifier.weight(1f), navigation)
                }
            }
        }
    }
}

@Composable
private fun ReviewQuestion(
    item: ReviewQueueEntity,
    viewModel: TrackListViewModel,
    places: List<Place>,
    modifier: Modifier,
    navigation: Modifier,
) {
    var showReason by remember { mutableStateOf(false) }
    var timelineExpanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var context by remember(item.id, item.inputRevision) { mutableStateOf<ReviewContext?>(null) }
    LaunchedEffect(item.id, item.inputRevision) { context = viewModel.reviewContext(item) }
    val nearest = places.firstOrNull { it.id == item.suggestedValue?.toLongOrNull() }
    val isTrip = item.kind == ReviewQueueEntity.TRANSPORT
    val focusLabel = stringResource(if (item.kind == ReviewQueueEntity.MISSING_RECORDING) R.string.review_event_gap else R.string.review_event_stop)
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().then(navigation).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    reviewTitle(item), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    relativeDay(item.startWallTimeMs).standaloneCase(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
                IconButton(onClick = { showReason = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Info, stringResource(R.string.review_why), Modifier.size(17.dp))
                }
            }
            val loaded = context
            if (loaded == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            } else {
                key(loaded) {
                    ReviewMap(
                        loaded, isTrip, item.kind == ReviewQueueEntity.MISSING_RECORDING, focusLabel, nearest,
                        Modifier.fillMaxWidth().weight(1f),
                    )
                }
                TextButton(
                    onClick = { timelineExpanded = !timelineExpanded },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Text(stringResource(if (timelineExpanded) R.string.review_hide_timeline else R.string.review_show_timeline))
                    Icon(
                        if (timelineExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        null, Modifier.padding(start = 4.dp).size(18.dp),
                    )
                }
                if (timelineExpanded) ReviewMiniTimeline(item, loaded, navigation)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ReviewDecisions(item, viewModel, places)
            }
        }
    }
    if (showReason) {
        AlertDialog(
            onDismissRequest = { showReason = false }, title = { Text(reviewTitle(item)) },
            text = { Text(reviewReason(item.reasonCode)) },
            confirmButton = { TextButton(onClick = { showReason = false }) { Text(stringResource(R.string.review_close)) } },
        )
    }
}

@Composable
private fun ReviewMiniTimeline(item: ReviewQueueEntity, data: ReviewContext, navigation: Modifier) {
    val context = LocalContext.current
    val trip = item.kind == ReviewQueueEntity.TRANSPORT
    val first = data.primary
    val second = data.secondary
    val leftLabel = if (trip) {
        data.beforePlace ?: stringResource(R.string.review_event_unnamed)
    } else {
        first?.activityType?.let { activityLabel(context, it) } ?: stringResource(R.string.review_before)
    }
    val rightLabel = if (trip) {
        data.afterPlace ?: stringResource(R.string.review_event_unnamed)
    } else {
        second?.activityType?.let { activityLabel(context, it) } ?: stringResource(R.string.review_after)
    }
    val middleLabel = if (trip) {
        first?.activityType?.let { activityLabel(context, it) } ?: stringResource(R.string.review_event_trip)
    } else {
        stringResource(if (item.kind == ReviewQueueEntity.MISSING_RECORDING) R.string.review_event_gap else R.string.review_event_stop)
    }
    val centerIcon = if (trip) {
        activityIcon(first?.activityType?.let(ActivityType::ofName))
    } else if (item.kind == ReviewQueueEntity.MISSING_RECORDING) {
        Icons.Filled.MoreHoriz
    } else {
        Icons.Filled.LocationOn
    }
    Row(Modifier.fillMaxWidth().then(navigation).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TimelineCell(
            leftLabel,
            if (trip) clock(first?.startedAt) else clockRange(first?.startedAt, first?.endedAt),
            if (trip) Icons.Filled.LocationOn else activityIcon(first?.activityType?.let(ActivityType::ofName)),
            false, Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(top = 14.dp).size(12.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        TimelineCell(middleLabel, durationText(item.startWallTimeMs, item.endWallTimeMs), centerIcon, true, Modifier.weight(1.2f))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(top = 14.dp).size(12.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        TimelineCell(
            rightLabel,
            if (trip) clock(first?.endedAt) else clockRange(second?.startedAt, second?.endedAt),
            if (trip) Icons.Filled.LocationOn else activityIcon(second?.activityType?.let(ActivityType::ofName)),
            false, Modifier.weight(1f),
        )
    }
}

@Composable
private fun TimelineCell(label: String, time: String, icon: ImageVector, highlighted: Boolean, modifier: Modifier) {
    Column(
        modifier.background(
            if (highlighted) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f) else Color.Transparent,
            RoundedCornerShape(12.dp),
        ).padding(horizontal = 5.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            icon, null, Modifier.size(if (highlighted) 22.dp else 18.dp),
            tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (highlighted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            time, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun clock(time: Long?): String {
    val format = android.text.format.DateFormat.getTimeFormat(LocalContext.current)
    return time?.let { format.format(Date(it)) } ?: "—"
}

@Composable
private fun clockRange(start: Long?, end: Long?): String = "${clock(start)}–${clock(end)}"

@Composable
private fun ReviewDecisions(item: ReviewQueueEntity, viewModel: TrackListViewModel, places: List<Place>) {
    val nearest = places.firstOrNull { it.id == item.suggestedValue?.toLongOrNull() }
    var createPlace by remember { mutableStateOf(false) }
    var choosePlace by remember { mutableStateOf(false) }
    var chooseTransport by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val pending = item.state == ReviewQueueEntity.PENDING
    val context = LocalContext.current
    val question = when (item.kind) {
        ReviewQueueEntity.PLACE_ASSIGNMENT -> if (nearest == null) {
            stringResource(R.string.review_question_new_place)
        } else {
            stringResource(R.string.review_question_known_place, nearest.label.orEmpty())
        }
        ReviewQueueEntity.MISSING_RECORDING -> stringResource(R.string.review_question_gap)
        ReviewQueueEntity.TRANSPORT -> stringResource(
            R.string.review_question_transport,
            item.suggestedValue?.let { activityLabel(context, it) }.orEmpty(),
        )
        else -> reviewSuggestion(item)
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (pending) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { viewModel.answerReview(item, "SKIP") },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp))
                    Text(stringResource(R.string.review_action_later), Modifier.padding(start = 4.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (item.kind) {
                    ReviewQueueEntity.PLACE_ASSIGNMENT, ReviewQueueEntity.MISSING_RECORDING -> {
                        val canPlace = item.kind == ReviewQueueEntity.PLACE_ASSIGNMENT || item.reasonCode.endsWith("_with_place_evidence")
                        if (canPlace && nearest != null) {
                            Decision(Icons.Filled.Check, nearest.label.orEmpty(), 0, Modifier.weight(1f)) {
                                viewModel.assignReviewPlace(item, placeId = nearest.id)
                            }
                        }
                        if (canPlace) {
                            Decision(Icons.Filled.AddLocationAlt, stringResource(R.string.review_different_place), 1, Modifier.weight(1f)) {
                                choosePlace = true
                            }
                        }
                        if (item.kind == ReviewQueueEntity.MISSING_RECORDING) {
                            Decision(Icons.Filled.Route, stringResource(R.string.review_action_trip), 3, Modifier.weight(1f)) {
                                viewModel.answerReview(item, "ACCEPT")
                            }
                        }
                        if (item.kind == ReviewQueueEntity.PLACE_ASSIGNMENT) {
                            Decision(Icons.Filled.DeleteOutline, stringResource(R.string.review_delete_stop), 2, Modifier.weight(1f)) {
                                viewModel.deleteReviewStop(item)
                            }
                        } else {
                            Decision(Icons.Filled.Close, stringResource(R.string.review_keep_separate), 2, Modifier.weight(1f)) {
                                viewModel.answerReview(item, "REJECT")
                            }
                        }
                    }
                    else -> {
                        Decision(Icons.Filled.Check, stringResource(R.string.review_confirm), 0, Modifier.weight(1f)) {
                            viewModel.answerReview(item, "ACCEPT")
                        }
                        if (item.kind == ReviewQueueEntity.TRANSPORT) {
                            Decision(Icons.Filled.Tune, stringResource(R.string.review_other_type), 1, Modifier.weight(1f)) { chooseTransport = true }
                        }
                        Decision(
                            Icons.Filled.Close,
                            stringResource(
                                if (item.kind == ReviewQueueEntity.TRANSPORT) {
                                    R.string.review_keep_current
                                } else {
                                    R.string.review_keep_separate
                                },
                            ),
                            2, Modifier.weight(1f),
                        ) {
                            viewModel.answerReview(item, "REJECT")
                        }
                    }
                }
            }
            if (item.kind == ReviewQueueEntity.SHORT_STOP) {
                TextButton(onClick = { viewModel.deleteReviewStop(item) }) {
                    Icon(Icons.Filled.DeleteOutline, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.review_delete_stop), Modifier.padding(start = 6.dp))
                }
            }
        } else {
            Text(item.state.lowercase().replaceFirstChar(Char::titlecase), style = MaterialTheme.typography.labelMedium)
            when {
                item.state == ReviewQueueEntity.ACCEPTED && item.decision == "DELETE_STOP" ->
                    TextButton(onClick = { viewModel.undoReviewStop(item) }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null)
                        Text(stringResource(R.string.review_undo_delete_stop))
                    }
                item.state == ReviewQueueEntity.SNOOZED -> TextButton(onClick = { viewModel.restoreReview(item) }) {
                    Text(stringResource(R.string.review_restore))
                }
                item.state in setOf(ReviewQueueEntity.ACCEPTED, ReviewQueueEntity.CORRECTED) &&
                    item.decision in setOf("ASSIGN_PLACE", "CREATE_PLACE") -> TextButton(onClick = { viewModel.undoReviewPlace(item) }) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null)
                    Text(stringResource(R.string.review_undo_place))
                }
                item.state == ReviewQueueEntity.ACCEPTED &&
                    item.decision == "ACCEPT" &&
                    item.kind in setOf(ReviewQueueEntity.MERGE_TRACKS, ReviewQueueEntity.SHORT_STOP, ReviewQueueEntity.MISSING_RECORDING) ->
                    TextButton(onClick = { viewModel.undoReviewMerge(item) }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, null)
                        Text(stringResource(R.string.review_undo_merge))
                    }
            }
        }
    }
    if (choosePlace) {
        AlertDialog(
            onDismissRequest = { choosePlace = false },
            title = { Text(stringResource(R.string.review_different_place)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        choosePlace = false
                        createPlace = true
                    }) {
                        Icon(Icons.Filled.AddLocationAlt, null, Modifier.size(20.dp))
                        Text(stringResource(R.string.review_create_new_place), Modifier.padding(start = 12.dp))
                    }
                    places.filter { !it.label.isNullOrBlank() }.sortedBy { it.label.orEmpty().lowercase() }.forEach { place ->
                        TextButton(onClick = {
                            viewModel.assignReviewPlace(item, placeId = place.id)
                            choosePlace = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(place.label.orEmpty(), Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosePlace = false }) { Text(stringResource(R.string.review_cancel)) } },
        )
    }
    if (createPlace) {
        AlertDialog(
            onDismissRequest = { createPlace = false }, title = { Text(stringResource(R.string.review_create_new_place)) },
            text = { OutlinedTextField(name, { name = it.replace('\n', ' ') }, label = { Text(stringResource(R.string.review_place_name)) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    viewModel.assignReviewPlace(item, newPlaceName = name)
                    createPlace = false
                }) { Text(stringResource(R.string.review_create_new_place)) }
            },
            dismissButton = { TextButton(onClick = { createPlace = false }) { Text(stringResource(R.string.review_cancel)) } },
        )
    }
    if (chooseTransport) {
        AlertDialog(
            onDismissRequest = { chooseTransport = false }, title = { Text(stringResource(R.string.review_other_type)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ActivityType.entries.filter { it.recording && it != ActivityType.UNKNOWN }.forEach { activity ->
                        TextButton(onClick = {
                            viewModel.answerReview(item, "CORRECT", activity.name)
                            chooseTransport = false
                        }) {
                            Icon(activityIcon(activity), null, Modifier.size(20.dp))
                            Text(activityLabel(context, activity.name), Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { chooseTransport = false }) { Text(stringResource(R.string.review_cancel)) } },
        )
    }
}

@Composable
private fun Decision(icon: ImageVector, label: String, tone: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = when (tone) {
        0 -> colors.primary
        1 -> colors.secondaryContainer
        3 -> colors.tertiaryContainer
        else -> colors.surfaceContainerHighest
    }
    val foreground = when (tone) {
        0 -> colors.onPrimary
        1 -> colors.onSecondaryContainer
        3 -> colors.onTertiaryContainer
        else -> colors.onSurfaceVariant
    }
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(modifier = Modifier.size(50.dp), shape = CircleShape, color = background, contentColor = foreground) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(25.dp)) }
        }
        Text(
            label, Modifier.heightIn(min = 32.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (tone == 0) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun reviewTitle(item: ReviewQueueEntity) = stringResource(
    when (item.kind) {
        ReviewQueueEntity.TRANSPORT -> R.string.review_kind_transport
        ReviewQueueEntity.MISSING_RECORDING -> R.string.review_kind_missing_recording
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
        "unnamed_visit_near_place", "unnamed_visit_new_place" -> R.string.review_reason_unnamed_visit
        "moved_unrecorded_gap", "moved_unrecorded_gap_with_place_evidence" -> R.string.review_reason_missing_recording
        else -> R.string.review_reason_generic
    },
)

@Composable
private fun reviewSuggestion(item: ReviewQueueEntity) = stringResource(
    when (item.kind) {
        ReviewQueueEntity.MERGE_TRACKS -> R.string.review_suggestion_merge
        ReviewQueueEntity.SHORT_STOP -> R.string.review_suggestion_short_stop
        ReviewQueueEntity.MISSING_RECORDING -> R.string.review_suggestion_missing_recording
        ReviewQueueEntity.VISIT_DRIFT -> R.string.review_suggestion_visit
        ReviewQueueEntity.PLACE_ASSIGNMENT -> R.string.review_suggestion_place
        ReviewQueueEntity.PLACE_DUPLICATE -> R.string.review_suggestion_duplicate
        else -> R.string.review_suggestion_generic
    },
)
