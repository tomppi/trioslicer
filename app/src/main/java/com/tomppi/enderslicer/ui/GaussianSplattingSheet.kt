package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tomppi.enderslicer.splat.SplatLibrary

/**
 * The Splat/PLY files menu: opens the viewer library populated with EasyScanner captures,
 * trained Gaussian splat scenes, and imported .ply/.sog files.
 */
@Composable
internal fun GaussianSplattingSheet(
    onDismiss: () -> Unit,
    onOpenViewer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val splatCount = remember { SplatLibrary.listEntries(context).size }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EnderSlicerDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Splat/PLY files", style = MaterialTheme.typography.titleMedium)
                Text(
                    "View trained splats and EasyScanner PLY point clouds on the phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
        HorizontalDivider()
        MoreRow(
            icon = AppIcons.Cube,
            title = "Splat viewer",
            subtitle = if (splatCount == 0) {
                "View trained scenes and EasyScanner PLY clouds, or import a .sog/.ply"
            } else {
                splatCount.toString() + (if (splatCount == 1) " file" else " files") +
                    " - view, import or delete"
            },
            onClick = onOpenViewer,
        )
        HorizontalDivider()
        Text(
            "All captured EasyScanner scans and trained Cross3DGS scenes appear here automatically " +
                "and render offline on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(EnderSlicerDimens.Space8))
    }
}
