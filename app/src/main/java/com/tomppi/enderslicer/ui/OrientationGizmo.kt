package com.tomppi.enderslicer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tomppi.enderslicer.viewer.ViewerOrientationMath
import kotlin.math.sqrt

private val AXIS_COLORS = listOf(Color(0xFFE57373), Color(0xFF81C784), Color(0xFF64B5F6))
private val AXIS_LABELS = listOf("X", "Y", "Z")
private val CHIP_COLOR = Color(0xD914181D)

private const val GIZMO_SIZE_DP = 84
private const val ARM_DP = 27f
private const val HEAD_LENGTH_DP = 7f
private const val HEAD_HALF_DP = 4f
private const val AXIS_STROKE_DP = 3f
private const val RING_RADIUS_DP = 4.5f
private const val RING_STROKE_DP = 2f
private const val LABEL_GAP_DP = 3f
private const val LABEL_PAD_DP = 3f
private const val LABEL_TEXT_SP = 12f
private const val TINY_AXIS = 0.22f

/**
 * Corner XYZ orientation helper: three labelled arrows that rotate with the
 * model so the build-plate X direction (the reference for top-layer skin
 * angles) stays identifiable while orienting the model. An axis that points
 * nearly straight at the camera is drawn as a ring at the origin.
 */
@Composable
internal fun OrientationGizmo(
    yawDegrees: Float,
    pitchDegrees: Float,
    cameraElevation: Float,
    modifier: Modifier = Modifier,
) {
    val vectors = remember(yawDegrees, pitchDegrees, cameraElevation) {
        ViewerOrientationMath.axisScreenVectors(yawDegrees, pitchDegrees, cameraElevation)
    }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = LABEL_TEXT_SP.sp, fontWeight = FontWeight.Bold)

    Canvas(modifier = modifier.size(GIZMO_SIZE_DP.dp)) {
        val arm = ARM_DP.dp.toPx()
        val headLength = HEAD_LENGTH_DP.dp.toPx()
        val headHalf = HEAD_HALF_DP.dp.toPx()
        val stroke = AXIS_STROKE_DP.dp.toPx()
        val ringRadius = RING_RADIUS_DP.dp.toPx()
        val ringStroke = RING_STROKE_DP.dp.toPx()
        val labelGap = LABEL_GAP_DP.dp.toPx()
        val labelPad = LABEL_PAD_DP.dp.toPx()
        val origin = center

        for (axis in 0 until 3) {
            val vx = vectors[axis * 2]
            val vy = vectors[axis * 2 + 1]
            val length = sqrt(vx * vx + vy * vy)
            val color = AXIS_COLORS[axis]
            val label = AXIS_LABELS[axis]
            val measured = textMeasurer.measure(label, labelStyle.copy(color = color))
            val chipSize = Size(
                measured.size.width + labelPad * 2,
                measured.size.height + labelPad * 2,
            )

            if (length < TINY_AXIS) {
                // Axis points nearly straight at the camera: ring at the origin.
                drawCircle(color, radius = ringRadius, center = origin, style = Stroke(width = ringStroke))
                val labelOrigin = Offset(
                    origin.x + ringRadius + labelGap,
                    origin.y - measured.size.height / 2f,
                )
                drawRoundRect(
                    CHIP_COLOR,
                    topLeft = labelOrigin - Offset(labelPad, labelPad),
                    size = chipSize,
                    cornerRadius = CornerRadius(labelPad, labelPad),
                )
                drawText(measured, topLeft = labelOrigin)
            } else {
                val tip = Offset(origin.x + vx * arm, origin.y + vy * arm)
                drawLine(color, origin, tip, strokeWidth = stroke, cap = StrokeCap.Round)
                val backX = -vx / length
                val backY = -vy / length
                val perpX = -backY
                val perpY = backX
                drawLine(
                    color,
                    tip,
                    Offset(
                        tip.x + backX * headLength + perpX * headHalf,
                        tip.y + backY * headLength + perpY * headHalf,
                    ),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color,
                    tip,
                    Offset(
                        tip.x + backX * headLength - perpX * headHalf,
                        tip.y + backY * headLength - perpY * headHalf,
                    ),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                val labelOrigin = Offset(
                    tip.x + (vx / length) * labelGap - measured.size.width / 2f,
                    tip.y + (vy / length) * labelGap - measured.size.height / 2f,
                )
                drawRoundRect(
                    CHIP_COLOR,
                    topLeft = labelOrigin - Offset(labelPad, labelPad),
                    size = chipSize,
                    cornerRadius = CornerRadius(labelPad, labelPad),
                )
                drawText(measured, topLeft = labelOrigin)
            }
        }
    }
}
