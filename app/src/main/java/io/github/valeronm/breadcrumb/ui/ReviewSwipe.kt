package io.github.valeronm.breadcrumb.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.db.ReviewQueueEntity
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch

internal enum class ReviewPrimaryAction {
    ASSIGN_PLACE,
    CHOOSE_PLACE,
    ACCEPT,
}

/** Mirrors the first visible action, including the place picker when no place is suggested. */
internal fun reviewPrimaryAction(item: ReviewQueueEntity, hasSuggestedPlace: Boolean): ReviewPrimaryAction {
    val offersPlace = item.kind == ReviewQueueEntity.PLACE_ASSIGNMENT ||
        (item.kind == ReviewQueueEntity.MISSING_RECORDING && item.reasonCode.endsWith("_with_place_evidence"))
    return when {
        offersPlace && hasSuggestedPlace -> ReviewPrimaryAction.ASSIGN_PLACE
        offersPlace -> ReviewPrimaryAction.CHOOSE_PLACE
        else -> ReviewPrimaryAction.ACCEPT
    }
}

@Composable
internal fun ReviewSwipeCard(
    enabled: Boolean,
    primaryLabel: String,
    primaryOpensPicker: Boolean,
    onPrimary: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    var settling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val primary by rememberUpdatedState(onPrimary)
    val later by rememberUpdatedState(onLater)
    val opensPicker by rememberUpdatedState(primaryOpensPicker)
    val threshold = with(LocalDensity.current) { 112.dp.toPx() }.coerceAtMost(width * 0.28f)
    val laterLabel = stringResource(R.string.review_action_later)
    val drag = Modifier.pointerInput(enabled, threshold) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (settling) return@awaitEachGesture
            var dx = 0f
            var dy = 0f
            var dragging = false
            var canceled = false
            var previous = down.position
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pointer = event.changes.firstOrNull { it.id == down.id }
                if (pointer == null || event.changes.count { it.pressed } > 1) {
                    canceled = true
                    break
                }
                dx += pointer.position.x - previous.x
                dy += pointer.position.y - previous.y
                previous = pointer.position
                if (!dragging && abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) {
                    canceled = true
                    break
                }
                if (!dragging && abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy)) dragging = true
                if (dragging) {
                    // Claim horizontal motion before the embedded Android map or child buttons.
                    pointer.consume()
                    offset = dx
                }
                if (!pointer.pressed) break
            }
            if (dragging) {
                val commit = !canceled && abs(offset) >= threshold
                val right = offset > 0f
                settling = true
                scope.launch {
                    try {
                        if (commit && !(right && opensPicker)) {
                            animate(offset, sign(offset) * width * 1.2f, animationSpec = tween(180)) { value, _ ->
                                offset = value
                            }
                            if (right) primary() else later()
                            offset = 0f
                        } else {
                            animate(offset, 0f, animationSpec = spring()) { value, _ -> offset = value }
                            if (commit) primary()
                        }
                    } finally {
                        settling = false
                    }
                }
            }
        }
    }
    Box(
        modifier.onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }.then(drag),
    ) {
        if (enabled && abs(offset) > 1f) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(24.dp),
                color = if (offset > 0f) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(
                    Modifier.fillMaxSize().padding(12.dp),
                    contentAlignment = if (offset > 0f) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    Column(
                        Modifier.widthIn(max = 72.dp).graphicsLayer { alpha = (abs(offset) / threshold).coerceIn(0f, 1f) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(if (offset > 0f) Icons.Filled.Check else Icons.Filled.Schedule, null, Modifier.size(32.dp))
                        Text(
                            if (offset > 0f) primaryLabel else laterLabel,
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = offset
                rotationZ = (offset / width * 8f).coerceIn(-8f, 8f)
            },
        ) {
            content()
        }
    }
}
