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
 * The Gaussian splatting menu: the phone's own capture-to-splat workflow, and the Aholo viewer
 * that opens what it trained.
 *
 * Two doors, one workspace. "New scan" is Splat Proto's workflow embedded from the :splatproto
 * module (Camera2 capture -> NPU object mask -> Parallax reconstruction -> Brush training); the
 * trained scene is written into [SplatLibrary]'s folder, so "Splat viewer" lists it on the next
 * open alongside any .sog/.ply imported from elsewhere. Nothing here hands a model to the plate
 * or the slicer: a splat is a visual reconstruction, not a toolpath, and turning it into a mesh
 * is a separate job for Blender.
 */
@Composable
internal fun GaussianSplattingSheet(
    onDismiss: () -> Unit,
    onNewScan: () -> Unit,
    onOpenViewer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Read once when the sheet opens: the count is the library's state at the moment the menu
    // was pressed, and a capture that finishes behind this sheet is picked up on the next open.
    val splatCount = remember { SplatLibrary.list(context).size }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = EnderSlicerDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(EnderSlicerDimens.Space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Gaussian splatting", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Capture an object, train a splat on the phone, and view it here",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
        HorizontalDivider()
        MoreRow(
            icon = AppIcons.Camera,
            title = "New scan",
            subtitle = "Capture 20-40 frames, mask the object, reconstruct and train",
            onClick = onNewScan,
        )
        HorizontalDivider()
        MoreRow(
            icon = AppIcons.Cube,
            title = "Splat viewer",
            subtitle = if (splatCount == 0) {
                "View a trained scene, or import a .sog/.ply from another pipeline"
            } else {
                splatCount.toString() + (if (splatCount == 1) " trained scene" else " trained scenes") +
                    " - view, import or delete"
            },
            onClick = onOpenViewer,
        )
        HorizontalDivider()
        Text(
            "Capture, masking, reconstruction and Brush training all run on this phone: the " +
                "frames and the trained model never leave it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Object masking runs U2Net on the NPU, and that needs root: the prebuilt " +
                "qnn-net-run loads the unsigned HTP skeleton, which the app sandbox cannot do. " +
                "Without root the scan still reconstructs and trains, just without a mask.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(EnderSlicerDimens.Space8))
    }
}
