package com.tomppi.enderslicer.ui.klipper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.tomppi.enderslicer.printer.KlipperMesh
import com.tomppi.enderslicer.viewer.DensityRamp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The mesh drawn as a surface, which is the shape a grid of numbers is describing.
 *
 * The two things this screen is read for - a corner that is low, and a bed that is tilted - are
 * angles, and a flat picture of a grid shows neither. Turned, both are visible as what they are.
 *
 * Drawn on a Canvas rather than through the OpenGL surface the plate and the nozzle path use: a
 * mesh is tens of points, not tens of thousands. The colours are the viewer's own ramp, so a
 * colour means here what it means everywhere else in the app.
 */
@Composable
internal fun KlipperMeshSurface(mesh: KlipperMesh, modifier: Modifier = Modifier) {
    var yaw by rememberSaveable(mesh.profileName) { mutableStateOf(MeshSurfaceGeometry.DEFAULT_YAW_DEG) }
    var pitch by rememberSaveable(mesh.profileName) { mutableStateOf(MeshSurfaceGeometry.DEFAULT_PITCH_DEG) }
    val (rows, columns) = mesh.shape
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .pointerInput(Unit) {
                    detectDragGestures { _, drag ->
                        yaw = MeshSurfaceGeometry.yawAfterDrag(yaw, drag.x)
                        // Free to turn right over: the underside is the view you want when a
                        // first layer will not stick, and it is drawn black so it cannot be
                        // mistaken for the bed. The bed follows the finger, not the camera.
                        pitch = MeshSurfaceGeometry.pitchAfterDrag(pitch, drag.y)
                    }
                },
        ) {
            MeshSurfaceGeometry.facets(mesh, size.width, size.height, yaw, pitch).forEach { facet ->
                val path = Path()
                facet.corners.forEachIndexed { index, corner ->
                    if (index == 0) path.moveTo(corner.x, corner.y) else path.lineTo(corner.x, corner.y)
                }
                path.close()
                drawPath(path, facet.color)
            }
        }
        Text(
            "$rows x $columns points" +
                (mesh.minimum?.let { min -> mesh.maximum?.let { max -> "\n%.3f to %.3f mm".format(min, max) } } ?: "") +
                " - drag to turn" +
                if (MeshSurfaceGeometry.isFromBelow(pitch)) ", underside" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One drawn quad: where it goes on the canvas, its colour, how far away it is, and which side of
 * the bed is facing the camera.
 */
internal data class MeshFacet(
    val corners: List<Offset>,
    val color: Color,
    val depth: Float,
    val underside: Boolean,
)

/**
 * A grid point after the turntable.
 *
 * [x] and [y] are in the unit box the mesh is normalised into, with +y the way the screen's up
 * is; [depth] grows with distance from the camera, so the far side sorts first.
 */
internal data class ProjectedPoint(val x: Double, val y: Double, val depth: Double)

/**
 * The projection, on its own so it can be reasoned about and tested without a screen.
 *
 * A turntable: the grid is normalised to a unit square, its heights are scaled so the range
 * takes a fixed fraction of that square, then yaw turns it about the vertical axis and pitch
 * tilts it towards the viewer, all the way over. Orthographic, because a mesh has no perspective
 * to speak of and parallel grid lines are easier to read.
 *
 * The camera sits in front of the bed and above it, looking at the middle. That is what fixes
 * the two axes: the way up the screen is the bed's Y times sin(pitch) plus the height times
 * cos(pitch), and the way away from the camera is Y times cos(pitch) minus the height times
 * sin(pitch). Swapping those two draws the bed upside down and paints it back to front at once,
 * so it reads as the underside - which is what the first version of this did.
 *
 * Heights are exaggerated rather than to scale: a few hundredths of a millimetre across a 160 mm
 * bed is invisible otherwise. The caption gives the range, so the shape is read as a shape.
 */
internal object MeshSurfaceGeometry {
    // The viewers' own opening yaw (ModelSurfaceView.DEFAULT_YAW), so the mesh opens over the
    // same corner of the bed as the plate does.
    const val DEFAULT_YAW_DEG = -28f

    /**
     * The opening tilt, as the camera elevation this screen solves for.
     *
     * The viewers rotate the object and hold the camera at a fixed 0.62 elevation - 31.8
     * degrees, looking from -Y with +Z up. This screen solves for the elevation directly, so
     * its angle is that 31.8 plus the object's; this value is the sum for a bed seen at a
     * readable slant rather than from straight above.
     */
    const val DEFAULT_PITCH_DEG = 58f

    /** How much of the drawing's box the height range may take. */
    private const val HEIGHT_FRACTION = 0.35

    /**
     * How far the drawing can reach from the middle, at any angle.
     *
     * Horizontally the half-diagonal of the unit square, and vertically that much again plus the
     * height field's own half-height. A fixed fit from these, rather than one computed from what
     * is on screen: a scale that changed with the angle made a drag that turns the bed look like
     * a zoom as well as a turn.
     */
    private const val REACH_X = 0.75f
    private const val REACH_Y = 0.78f

    /**
     * Degrees of turn per pixel of drag.
     *
     * The model, layer and nozzle-path viewers all use 0.35 and the same signs: see
     * ModelSurfaceView's gesture calling rotate(dx * 0.35f, dy * 0.35f), which adds both to
     * the object's own angles. This screen is a fourth turntable and behaves like the others,
     * so a drag learned in one of them is right in all of them.
     */
    const val DRAG_DEGREES_PER_PIXEL = 0.35f

    /**
     * The tilt after a vertical drag, which moves the bed rather than the camera: dragging
     * down lays it flat towards the reader and shows the top, dragging up lifts its near edge
     * and turns the underside towards them. Written the way the viewers write it - the angle
     * plus the drag, with down being the positive direction of a screen.
     *
     * Dragging up lifts the near edge, so the bed turns its underside towards you; dragging
     * down lowers it and shows the top. The other way round - what this did first - is the
     * camera moving instead: up means climbing above the bed and looking down on it, which
     * feels backwards to a finger that is holding the thing.
     *
     * A drag arrives in screen pixels, where down is positive, hence the addition.
     */
    fun pitchAfterDrag(pitchDeg: Float, dragY: Float): Float =
        wrapPitch(pitchDeg + dragY * DRAG_DEGREES_PER_PIXEL)

    /**
     * The turn after a horizontal drag. The bed follows the finger and the camera does not
     * move, which is the whole of the difference between dragging an object and dragging the
     * view of it - and is what the other viewers already do.
     */
    fun yawAfterDrag(yawDeg: Float, dragX: Float): Float =
        wrapYaw(yawDeg + dragX * DRAG_DEGREES_PER_PIXEL)

    /** Turns an angle back into (-180, 180], so a drag cannot walk it away. */
    fun wrapYaw(yaw: Float): Float = wrap(yaw)

    /** The same for the tilt, which is not clamped: the bed turns all the way over. */
    fun wrapPitch(pitch: Float): Float = wrap(pitch)

    /**
     * Whether the camera is under the bed.
     *
     * Straight above is a tilt of 90 degrees; past 0 and through the negative angles the camera
     * is below, which is the half of the turn where the surface is seen from behind, its heights
     * read mirrored, and every quad is drawn black.
     *
     * This is asked of the tilt rather than of each quad's own facing, and that distinction is
     * the whole of it: with the heights exaggerated some five hundredfold, the top of a real bed
     * is nothing but cliffs, and half the cliffs face away from the camera. Judging each quad by
     * its facing blackened half the bed when the camera was plainly above it.
     */
    fun isFromBelow(pitchDeg: Float): Boolean = sin(Math.toRadians(pitchDeg.toDouble())) < 0.0

    private fun wrap(angle: Float): Float {
        var wrapped = angle % 360f
        if (wrapped > 180f) wrapped -= 360f
        if (wrapped <= -180f) wrapped += 360f
        return wrapped
    }

    /** The grid after yaw and pitch, in the unit box. Rows are the bed's Y, columns its X. */
    fun project(mesh: KlipperMesh, yawDeg: Float, pitchDeg: Float): List<List<ProjectedPoint>> {
        val rows = mesh.points
        if (rows.isEmpty()) return emptyList()
        val columns = rows.minOf { it.size }
        if (columns < 2 || rows.size < 2) return emptyList()
        val minZ = mesh.minimum ?: return emptyList()
        val maxZ = mesh.maximum ?: return emptyList()
        // A mesh can come back perfectly flat, and that is a shape worth drawing: a card at the
        // middle of the ramp. Only the scale needs guarding, not the drawing.
        val spanZ = (maxZ - minZ).coerceAtLeast(1e-9)
        val middleZ = (minZ + maxZ) / 2.0
        val gain = HEIGHT_FRACTION / spanZ
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)
        val cosPitch = cos(pitch)
        val sinPitch = sin(pitch)
        return List(rows.size) { row ->
            List(columns) { column ->
                val u = column.toDouble() / (columns - 1) - 0.5
                val v = row.toDouble() / (rows.size - 1) - 0.5
                val height = (rows[row][column] - middleZ) * gain
                val x = u * cosYaw - v * sinYaw
                val y = u * sinYaw + v * cosYaw
                ProjectedPoint(
                    x = x,
                    y = y * sinPitch + height * cosPitch,
                    depth = y * cosPitch - height * sinPitch,
                )
            }
        }
    }

    fun facets(
        mesh: KlipperMesh,
        widthPx: Float,
        heightPx: Float,
        yawDeg: Float,
        pitchDeg: Float,
    ): List<MeshFacet> {
        if (widthPx <= 0f || heightPx <= 0f) return emptyList()
        val grid = project(mesh, yawDeg, pitchDeg)
        if (grid.isEmpty()) return emptyList()
        val columns = grid.first().size
        val rows = grid.size
        val minZ = mesh.minimum ?: return emptyList()
        val maxZ = mesh.maximum ?: return emptyList()
        val spanZ = (maxZ - minZ).coerceAtLeast(1e-9)
        // Blanked, not shaded: a mesh seen from below has its heights mirrored, and a coloured
        // surface there would be read as the bed rather than as the back of it.
        val fromBelow = isFromBelow(pitchDeg)
        val scale = minOf(widthPx / (2f * REACH_X), heightPx / (2f * REACH_Y)) * 0.95f
        val offsetX = widthPx / 2f
        val offsetY = heightPx / 2f
        // Screen y grows downward, so the projected up is negated here and only here.
        fun screen(point: ProjectedPoint) = Offset(
            (point.x * scale).toFloat() + offsetX,
            (-point.y * scale).toFloat() + offsetY,
        )

        val heights = mesh.points
        val facets = ArrayList<MeshFacet>((rows - 1) * (columns - 1))
        for (row in 0 until rows - 1) {
            for (column in 0 until columns - 1) {
                val a = grid[row][column]
                val b = grid[row][column + 1]
                val c = grid[row + 1][column + 1]
                val d = grid[row + 1][column]
                val height = (heights[row][column] + heights[row][column + 1] +
                    heights[row + 1][column + 1] + heights[row + 1][column]) / 4.0
                val rgb = DensityRamp.ramp((height - minZ) / spanZ)
                facets += MeshFacet(
                    corners = listOf(screen(a), screen(b), screen(c), screen(d)),
                    color = if (fromBelow) Color.Black else Color(rgb[0], rgb[1], rgb[2]),
                    depth = ((a.depth + b.depth + c.depth + d.depth) / 4.0).toFloat(),
                    underside = fromBelow,
                )
            }
        }
        // Farthest first: a Canvas has no depth buffer, so the painting order is the depth test.
        return facets.sortedByDescending { it.depth }
    }
}
