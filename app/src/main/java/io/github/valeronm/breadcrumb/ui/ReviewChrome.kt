package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R

internal enum class ReviewBucket(@StringRes val titleRes: Int) {
    PENDING(R.string.review_title_pending),
    SNOOZED(R.string.review_title_later),
    RESOLVED(R.string.review_title_resolved),
}

@Composable
internal fun ReviewTitle(bucket: ReviewBucket, onSelect: (ReviewBucket) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clickable(role = Role.Button) { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(bucket.titleRes), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(24.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReviewBucket.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(mode.titleRes)) },
                    trailingIcon = {
                        if (mode == bucket) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp))
                    },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun ReviewAnalysisAction(viewModel: TrackListViewModel) {
    val analysis by viewModel.analysisQueueStatus.collectAsStateWithLifecycle()
    val enabled = viewModel.analysisEnabled
    val upToDate = analysis.running + analysis.queued + analysis.failed + analysis.unprocessed == 0
    var showDetails by remember { mutableStateOf(false) }
    val detail = when {
        !enabled -> stringResource(R.string.review_analysis_disabled)
        upToDate -> stringResource(R.string.review_analysis_current)
        else -> stringResource(R.string.review_analysis_status, analysis.running, analysis.queued, analysis.unprocessed, analysis.failed)
    }
    IconButton(onClick = { showDetails = true }) {
        Icon(
            if (upToDate && enabled) Icons.Filled.CheckCircle else Icons.Filled.Schedule,
            contentDescription = detail,
            modifier = Modifier.size(20.dp),
            tint = if (analysis.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            text = { Text(detail) },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.review_close)) } },
            dismissButton = {
                if (enabled && !upToDate) {
                    TextButton(onClick = {
                        viewModel.runRouteAnalysisNow()
                        showDetails = false
                    }) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                        Text(stringResource(R.string.review_process_queue_now), Modifier.padding(start = 6.dp))
                    }
                }
            },
        )
    }
}
