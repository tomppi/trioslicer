package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomppi.enderslicer.scanruler.engine.ScanRulerEmbeddedSession
import com.tomppi.enderslicer.scanruler.engine.ScanRulerPhase

/**
 * The plate workspace's two workspaces.
 *
 * Plate is the slicer's own plate; ScanRuler is the embedded CNC Kitchen engine's live page.
 * The switch is the agent-context signal too: the in-app agent works with whichever workspace
 * is selected, exactly as the top-bar menus choose the engine.
 */
@Composable
internal fun PlateWorkspaceSwitch(
    activeView: PlateView,
    onViewChange: (PlateView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = PLATE_CARD_ALPHA),
        ),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "View",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            PlateView.entries.forEach { view ->
                val selected = activeView == view
                val content = @Composable {
                    Text(view.label, style = MaterialTheme.typography.labelMedium)
                }
                if (selected) {
                    Button(
                        onClick = { onViewChange(view) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) { content() }
                } else {
                    OutlinedButton(
                        onClick = { onViewChange(view) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) { content() }
                }
            }
        }
    }
}

/**
 * The embedded ScanRuler viewport.
 *
 * One AndroidView for the session's whole life: when Plate is selected the host is a 1dp,
 * fully transparent box (the page stays alive and is told setVisible(false)), and when
 * ScanRuler is selected it fills the plate content. The engine owns the WebView, the loopback
 * servers, the chrome-hiding adapter and the agent host; this composable only hosts it and
 * shows the loading/error/empty-plate states.
 */
@Composable
internal fun ScanRulerEmbeddedHost(
    session: ScanRulerEmbeddedSession?,
    active: Boolean,
    loading: Boolean,
    error: String?,
    scanName: String?,
    hasMesh: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val engineState = session?.let { it.state.collectAsStateWithLifecycle().value }
    LaunchedEffect(session, active) { session?.setVisible(active) }
    Box(
        modifier = if (active) {
            modifier.fillMaxSize()
        } else {
            modifier.size(1.dp).alpha(0f)
        },
    ) {
        if (session != null && engineState != null && engineState.phase != ScanRulerPhase.IDLE) {
            key(session) {
                AndroidView(
                    factory = { session.webView },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (!active) return@Box
        when {
            loading -> ScanRulerStateOverlay(
                title = "Preparing ScanRuler…",
                detail = engineState?.message
                    ?: "Starting the on-device page and handing it the plate mesh.",
                busy = true,
                onRetry = null,
            )
            error != null -> ScanRulerStateOverlay(
                title = "ScanRuler needs a retry",
                detail = error,
                busy = false,
                onRetry = onRetry,
            )
            engineState?.phase == ScanRulerPhase.FAILED -> ScanRulerStateOverlay(
                title = "ScanRuler could not start",
                detail = engineState.message ?: "The page did not become ready.",
                busy = false,
                onRetry = onRetry,
            )
            !hasMesh -> ScanRulerStateOverlay(
                title = "The plate has no mesh yet",
                detail = "ScanRuler shows the plate's mesh. Import a model, select it, " +
                    "then come back to this view.",
                busy = false,
                onRetry = null,
            )
            else -> ScanRulerAgentChip(
                scanName = scanName,
                toolCount = engineState?.toolCount ?: 0,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun ScanRulerStateOverlay(
    title: String,
    detail: String?,
    busy: Boolean,
    onRetry: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                detail?.let { sentence ->
                    Text(
                        sentence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                onRetry?.let { retry ->
                    Button(onClick = retry) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun ScanRulerAgentChip(
    scanName: String?,
    toolCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Text(
            "ScanRuler agent · " + (scanName ?: "no scan") + " · " + toolCount + " tools",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
