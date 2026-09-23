package com.tomppi.enderslicer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tomppi.enderslicer.nonplanar.NonPlanarSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Full-window 3D view of the hot-end collision volume: the nozzle cone, the
 * heating block frustum, the whole-plate cutoff level and the build plate,
 * with an XYZ guide at the nozzle tip. Drag to orbit; nothing here is
 * editable - measurements stay in the sheet fields.
 */
@Composable
internal fun HotendVolumeDialog(
    nozzleAngleDegrees: Double?,
    protrusionMm: Double?,
    blockWidthMm: Double?,
    blockDepthMm: Double?,
    offsetXmm: Double?,
    offsetYmm: Double?,
    clearanceAngleDegrees: Double?,
    holderHeightMm: Double?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Hot-end collision model", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Drag to orbit · nothing here is editable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            HotendVolumeViewer(
                nozzleAngleDegrees = nozzleAngleDegrees,
                protrusionMm = protrusionMm,
                blockWidthMm = blockWidthMm,
                blockDepthMm = blockDepthMm,
                offsetXmm = offsetXmm,
                offsetYmm = offsetYmm,
                clearanceAngleDegrees = clearanceAngleDegrees,
                holderHeightMm = holderHeightMm,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LegendDot(Color(0xFFE57373), "X")
                LegendDot(Color(0xFF81C784), "Y")
                LegendDot(Color(0xFF64B5F6), "Z")
                LegendDot(MaterialTheme.colorScheme.primary, "Block frustum")
                LegendDot(MaterialTheme.colorScheme.secondary, "Nozzle cone")
                LegendDot(MaterialTheme.colorScheme.error, "No-go cutoff")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.padding(end = 4.dp)) {
            drawCircle(color, radius = 5f, center = center)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun HotendVolumeViewer(
    nozzleAngleDegrees: Double?,
    protrusionMm: Double?,
    blockWidthMm: Double?,
    blockDepthMm: Double?,
    offsetXmm: Double?,
    offsetYmm: Double?,
    clearanceAngleDegrees: Double?,
    holderHeightMm: Double?,
    modifier: Modifier = Modifier,
) {
    val angle = clearanceAngleDegrees ?: 45.0
    val holder = holderHeightMm ?: 50.0
    val protrusion = protrusionMm ?: 5.0
    val nozzleAngle = nozzleAngleDegrees ?: 30.0
    val width = blockWidthMm ?: 20.0
    val depth = blockDepthMm ?: 16.0
    val offsetX = offsetXmm ?: 0.0
    val offsetY = offsetYmm ?: 0.0

    var yaw by remember { mutableStateOf(42.0) }
    var pitch by remember { mutableStateOf(26.0) }

    val plateColor = MaterialTheme.colorScheme.outlineVariant
    val blockColor = MaterialTheme.colorScheme.primary
    val nozzleColor = MaterialTheme.colorScheme.secondary
    val cutoffColor = MaterialTheme.colorScheme.error
    val axisX = Color(0xFFE57373)
    val axisY = Color(0xFF81C784)
    val axisZ = Color(0xFF64B5F6)
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier.pointerInput(angle, holder, protrusion, nozzleAngle, width, depth, offsetX, offsetY) {
            detectDragGestures { change, drag ->
                change.consume()
                // Same feel as the GL model/path viewers: dragging up tips the
                // top toward you (pitch decreases), dragging down tips it away.
                yaw = (yaw + drag.x * 0.45).coerceIn(-180.0, 180.0)
                pitch = (pitch + drag.y * 0.45).coerceIn(5.0, 88.0)
            }
        },
    ) {
        val worldExtent = max(
            holder,
            max(width, depth) + max(abs(offsetX), abs(offsetY)) * 2.0 +
                max(0.0, holder - protrusion) * tan(Math.toRadians(90.0 - angle)) * 2.0,
        )
        val scale = (min(size.width * 0.42f, size.height * 0.40f) / worldExtent.toFloat()).toDouble()
        val center = Offset(size.width / 2f, size.height * 0.55f)
        val yawR = Math.toRadians(yaw)
        val pitchR = Math.toRadians(pitch)
        val cosYaw = cos(yawR)
        val sinYaw = sin(yawR)
        val cosPitch = cos(pitchR)
        val sinPitch = sin(pitchR)

        fun project(x: Double, y: Double, z: Double): Offset {
            val x1 = x * cosYaw - y * sinYaw
            val y1 = x * sinYaw + y * cosYaw
            val y2 = y1 * cosPitch - z * sinPitch
            val z2 = y1 * sinPitch + z * cosPitch
            return Offset(
                (x1 * scale + center.x).toFloat(),
                (-z2 * scale + center.y).toFloat(),
            )
        }

        fun line(a: Offset, b: Offset, color: Color, strokeWidth: Float = 2f) {
            drawLine(color, a, b, strokeWidth = strokeWidth)
        }

        // Build plate (schematic, not to scale).
        val plateHalf = max(width, depth) * 1.5
        val plate = listOf(
            project(-plateHalf, -plateHalf, 0.0),
            project(plateHalf, -plateHalf, 0.0),
            project(plateHalf, plateHalf, 0.0),
            project(-plateHalf, plateHalf, 0.0),
        )
        val platePath = Path().apply {
            moveTo(plate[0].x, plate[0].y)
            for (i in 1..3) lineTo(plate[i].x, plate[i].y)
            close()
        }
        drawPath(platePath, plateColor.copy(alpha = 0.15f))
        drawPath(platePath, plateColor, style = Stroke(width = 1.5f))

        // XYZ guide at the nozzle tip.
        val axisLength = max(width, depth) * 1.1
        val tip = project(0.0, 0.0, 0.0)
        fun axis(from: Offset, to: Offset, color: Color, label: String) {
            line(from, to, color, 2f)
            drawCircle(color, radius = 5f, center = to)
            val measured = textMeasurer.measure(label, TextStyle(fontSize = 13.sp, color = color))
            drawText(
                measured,
                topLeft = Offset(to.x + 4f, to.y - measured.size.height / 2f),
            )
        }
        axis(tip, project(axisLength, 0.0, 0.0), axisX, "X")
        axis(tip, project(0.0, axisLength, 0.0), axisY, "Y")
        axis(tip, project(0.0, 0.0, axisLength), axisZ, "Z")
        drawCircle(nozzleColor, radius = 6f, center = tip)
        drawCircle(Color.White, radius = 2.2f, center = tip)

        // Nozzle cone from the tip up to the junction. The taper angle is
        // measured from horizontal, so the cone widens at the complementary
        // angle: 75 degrees from horizontal = a thin cone.
        val junctionRadius = protrusion * tan(Math.toRadians(90.0 - nozzleAngle))
        val nozzleBase = (0 until 12).map { i ->
            val a = 2.0 * PI * i / 12.0
            project(cos(a) * junctionRadius, sin(a) * junctionRadius, protrusion)
        }
        for (vertex in nozzleBase) line(tip, vertex, nozzleColor.copy(alpha = 0.6f), 1.2f)
        for (i in nozzleBase.indices) {
            line(nozzleBase[i], nozzleBase[(i + 1) % nozzleBase.size], nozzleColor, 1.6f)
        }

        // Heating block frustum: footprint at the junction (offset from the
        // tip axis), widening at the clearance angle measured from horizontal.
        val rise = max(0.0, holder - protrusion)
        val topHalfW = width / 2.0 + rise * tan(Math.toRadians(90.0 - angle))
        val topHalfD = depth / 2.0 + rise * tan(Math.toRadians(90.0 - angle))
        val baseCorners = listOf(
            project(offsetX - width / 2.0, offsetY - depth / 2.0, protrusion),
            project(offsetX + width / 2.0, offsetY - depth / 2.0, protrusion),
            project(offsetX + width / 2.0, offsetY + depth / 2.0, protrusion),
            project(offsetX - width / 2.0, offsetY + depth / 2.0, protrusion),
        )
        val topCorners = listOf(
            project(offsetX - topHalfW, offsetY - topHalfD, holder),
            project(offsetX + topHalfW, offsetY - topHalfD, holder),
            project(offsetX + topHalfW, offsetY + topHalfD, holder),
            project(offsetX - topHalfW, offsetY + topHalfD, holder),
        )
        for (i in 0..3) {
            line(baseCorners[i], baseCorners[(i + 1) % 4], blockColor, 2f)
            line(topCorners[i], topCorners[(i + 1) % 4], blockColor, 2f)
            line(baseCorners[i], topCorners[i], blockColor.copy(alpha = 0.8f), 1.5f)
        }

        // Cutoff level: the whole-plate no-go plane above the holder.
        val cutoffHalf = plateHalf * 1.15
        val cutoff = listOf(
            project(-cutoffHalf, -cutoffHalf, holder),
            project(cutoffHalf, -cutoffHalf, holder),
            project(cutoffHalf, cutoffHalf, holder),
            project(-cutoffHalf, cutoffHalf, holder),
        )
        val cutoffPath = Path().apply {
            moveTo(cutoff[0].x, cutoff[0].y)
            for (i in 1..3) lineTo(cutoff[i].x, cutoff[i].y)
            close()
        }
        drawPath(cutoffPath, cutoffColor.copy(alpha = 0.10f))
        drawPath(cutoffPath, cutoffColor, style = Stroke(width = 1.5f))
    }
}
