package com.tomppi.enderslicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** How long a corner alert stays before it stops covering the model. */
private const val CORNER_ALERT_MILLIS = 30_000L

/** Amber, because a safety warning is not a failure but must not read as one either. */
private val WarningAmber = Color(0xFFFFB300)

/**
 * The corner of the model viewer, where a failure lands in red.
 *
 * It is deliberately short-lived: the log keeps the record, and a banner that
 * never leaves spends the rest of the session covering the model it is warning
 * about. Tapping it opens the log rather than dismissing it, because the reason
 * to touch a failure is to read the whole story.
 */
@Composable
internal fun DiagnosticsCorner(
    onOpenLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alert by Diagnostics.latestAlert.collectAsStateWithLifecycle()
    val entry = alert ?: return
    // Whether there is anywhere to tap through to. The alert is not the log.
    val logging by Diagnostics.enabled.collectAsStateWithLifecycle()
    LaunchedEffect(entry) {
        delay(CORNER_ALERT_MILLIS)
        Diagnostics.dismissAlert()
    }
    val failure = entry.level == Diagnostics.Level.FAILURE
    Column(
        modifier = modifier
            .widthIn(max = 460.dp)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onOpenLog)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = (if (failure) "FAILED · " else "WARNING · ") + entry.message,
            color = if (failure) MaterialTheme.colorScheme.error else WarningAmber,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = Diagnostics.formatTime(entry.atMillis) + " · " + entry.source +
                if (logging) " · tap for the app log" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * The app log: what the app did, and what it reported as a failure, with the
 * failures loud enough to find while scrolling.
 */
@Composable
internal fun DiagnosticsLogSheet(onDismiss: () -> Unit) {
    val entries by Diagnostics.entries.collectAsStateWithLifecycle()
    val enabled by Diagnostics.enabled.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // A log reads from the bottom: the newest line is the one being looked for.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("App log", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (enabled) {
                "Everything the app reported since the log was last cleared. Failures are red."
            } else {
                "Diagnostics are off, so nothing is being recorded. Turn them on under " +
                    "More > Diagnostics."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entries.isEmpty()) {
            Text(
                if (enabled) "Nothing logged yet." else "Nothing to show.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entries) { entry ->
                    val failure = entry.level == Diagnostics.Level.FAILURE
                    val warning = entry.level == Diagnostics.Level.WARNING
                    Text(
                        text = Diagnostics.formatTime(entry.atMillis) + "  " +
                            entry.level.name.padEnd(7) + "  " + entry.source + ": " + entry.message,
                        color = when {
                            failure -> MaterialTheme.colorScheme.error
                            warning -> WarningAmber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (failure) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = { Diagnostics.clear() }, enabled = entries.isNotEmpty()) {
                Text("Clear")
            }
            Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
        }
    }
}
