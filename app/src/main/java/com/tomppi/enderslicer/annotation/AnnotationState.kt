package com.tomppi.enderslicer.annotation

import kotlin.math.sqrt

/** A position in model space, in millimetres. */
data class Point3(val x: Float, val y: Float, val z: Float) {
    fun distanceTo(other: Point3): Float {
        val dx = other.x - x
        val dy = other.y - y
        val dz = other.z - z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/**
 * How a point's depth was established.
 *
 * A point placed where the ray hit the model is [SURFACE] and remembers the
 * triangle. A point dropped where the ray missed is [PLANE] - it has a depth,
 * but that depth is a convention rather than something the geometry confirms.
 */
enum class AnnotationAnchor { SURFACE, PLANE }

/** What a series is for. */
enum class AnnotationKind {
    /** Two points; the distance between them is the point of the annotation. */
    MEASURE,

    /** An open path the user traced. */
    PATH,

    /** A closed loop - a region to act on. */
    REGION,
}

data class AnnotationPoint(
    val position: Point3,
    val anchor: AnnotationAnchor,
    val faceIndex: Int? = null,
)

data class AnnotationChain(
    val id: Int,
    val kind: AnnotationKind,
    val points: List<AnnotationPoint>,
    val closed: Boolean = false,
) {
    /** Total length: the path length, or the perimeter when closed. */
    fun lengthMm(): Float {
        if (points.size < 2) return 0f
        var total = 0f
        for (i in 0 until points.size - 1) {
            total += points[i].position.distanceTo(points[i + 1].position)
        }
        if (closed) total += points.last().position.distanceTo(points.first().position)
        return total
    }

    /** True when the chain has enough points to mean anything. */
    fun isComplete(): Boolean = when (kind) {
        AnnotationKind.MEASURE -> points.size >= 2
        AnnotationKind.PATH -> points.size >= 2
        AnnotationKind.REGION -> points.size >= 3
    }
}

/** Which end of the segment on screen is being addressed. */
enum class SegmentEnd { START, END }

/**
 * Point-to-point annotation, edited a segment at a time.
 *
 * The interaction is tap-to-place and drag-to-adjust, not drag-to-place: a
 * drag across the model is how the camera orbits, so consuming it for placing
 * would make the model impossible to turn while annotating. Only a drag that
 * begins on an end handle moves geometry; every other drag belongs to the
 * camera.
 *
 * A **series** is an ordered run of segments. Placing a point fills the start
 * of the first segment, then its end; locking a segment commits both and lets
 * the next one begin at the point just committed, so a run of segments costs
 * one tap each after the first. Locking the series ends it, and the next point
 * placed starts a new one from scratch.
 *
 * This type is pure: it holds no camera and does no picking. The caller
 * resolves screen positions to rays and 3D points, which keeps every rule here
 * unit-testable.
 */
class AnnotationState {

    private val chainList = mutableListOf<AnnotationChain>()
    private var nextChainId = 1

    /** Locked series, in creation order. This is what gets serialised. */
    val chains: List<AnnotationChain> get() = chainList.toList()

    /** Points already locked into the series being drawn. */
    private val seriesPoints = mutableListOf<AnnotationPoint>()

    /** Locked points of the series in progress, oldest first. */
    val currentSeries: List<AnnotationPoint> get() = seriesPoints.toList()

    /** Start of the segment being placed; only set for the first segment of a series. */
    var pendingStart: AnnotationPoint? = null
        private set

    /** End of the segment being placed, once the user has tapped it. */
    var pendingEnd: AnnotationPoint? = null
        private set

    /**
     * Rendered line width in **screen pixels**, not model millimetres.
     *
     * This is presentation only - the geometry sent on for modelling is the
     * centreline. Pixels because it is drawn with glLineWidth, which takes
     * pixels and is clamped to the driver's supported range at draw time.
     */
    var thicknessPx: Float = DEFAULT_THICKNESS_PX
        set(value) {
            field = value.coerceIn(MIN_THICKNESS_PX, MAX_THICKNESS_PX)
        }

    var kind: AnnotationKind = AnnotationKind.PATH

    /**
     * Distance from the camera captured when a handle drag began.
     *
     * Holding this for the duration of the drag is what makes orbiting safe: a
     * point keeps its depth while the camera moves, so a lateral correction
     * never silently changes how far away the point is.
     */
    private var capturedDepth: Float? = null

    /** Which end a drag in progress is moving, if any. */
    var adjusting: SegmentEnd? = null
        private set

    /**
     * Height of the plane points are placed on, in model millimetres.
     *
     * Tapping resolves to a ray, and a ray does not say where along itself the
     * point belongs. Pinning the height removes that ambiguity: the point lands
     * where the ray crosses this plane, which is exactly under the finger.
     */
    var workPlaneZ: Float = 0f

    /** Which end is having its height adjusted on its own, if any. */
    var zAdjusting: SegmentEnd? = null
        private set

    /**
     * Starts a height-only adjustment of [end].
     *
     * X and Y are left exactly as they are, so raising a point never nudges it
     * sideways - the reason this is a separate mode from a normal drag.
     */
    fun beginZAdjust(end: SegmentEnd): Boolean {
        if (handle(end) == null) return false
        zAdjusting = end
        adjusting = null
        capturedDepth = null
        return true
    }

    fun endZAdjust() {
        zAdjusting = null
    }

    /** Moves [end] to an exact model position, as chosen by the working plane. */
    fun moveHandleTo(end: SegmentEnd, position: Point3): Boolean {
        if (handle(end) == null) return false
        if (!position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite()) return false
        put(end, AnnotationPoint(position, AnnotationAnchor.PLANE, faceIndex = null))
        return true
    }

    /** Moves [end] to [z], keeping its X and Y. */
    fun setHandleZ(end: SegmentEnd, z: Float): Boolean {
        val current = handle(end) ?: return false
        if (!z.isFinite()) return false
        put(
            end,
            AnnotationPoint(
                position = Point3(current.position.x, current.position.y, z),
                anchor = AnnotationAnchor.PLANE,
                faceIndex = null,
            ),
        )
        return true
    }

    val isEmpty: Boolean get() = chainList.isEmpty() && seriesPoints.isEmpty() &&
        pendingStart == null && pendingEnd == null

    /** True when a segment is complete but not yet committed. */
    val canLockSegment: Boolean get() = pendingEnd != null

    /** True when there is a series worth committing. */
    val canLockSeries: Boolean get() = seriesPoints.size >= 2

    /**
     * Where the segment being placed begins, for drawing.
     *
     * This is the user's own first point, or the committed point the series has
     * reached. It is not necessarily grabbable - see [startHandle].
     */
    val segmentStart: AnnotationPoint?
        get() = pendingStart ?: seriesPoints.lastOrNull()

    /**
     * The start of the segment that the user may actually drag, or null.
     *
     * Only the first segment of a series owns its start. Once a segment is
     * locked, its start belongs to the series and is no longer adjustable, which
     * is what "adjustable until we lock them" means.
     */
    val startHandle: AnnotationPoint? get() = pendingStart

    /** The end handle, once placed. */
    val endHandle: AnnotationPoint? get() = pendingEnd

    /** Both addresses that a drag may grab, in the order the UI draws them. */
    fun handles(): List<Pair<SegmentEnd, AnnotationPoint>> = buildList {
        startHandle?.let { add(SegmentEnd.START to it) }
        endHandle?.let { add(SegmentEnd.END to it) }
    }

    /**
     * Places one tap.
     *
     * The first tap of a series sets the segment start; every later tap sets
     * its end. Returns true when the tap was consumed, false when the segment
     * already has both ends and the user must lock before placing another.
     */
    fun tap(position: Point3, anchor: AnnotationAnchor, faceIndex: Int? = null): Boolean {
        val point = AnnotationPoint(position, anchor, faceIndex)
        if (pendingEnd != null) return false
        if (seriesPoints.isEmpty() && pendingStart == null) {
            pendingStart = point
            return true
        }
        pendingEnd = point
        return true
    }

    /**
     * Captures the depth of the handle being dragged from [camera], so the drag
     * slides the point at constant distance rather than through the model.
     */
    fun beginAdjust(end: SegmentEnd, camera: Point3) {
        val current = handle(end) ?: return
        adjusting = end
        capturedDepth = camera.distanceTo(current.position)
    }

    /**
     * Slides the dragged handle along a screen ray at the captured depth.
     *
     * Sliding is a lateral correction, so the point detaches from any surface
     * triangle it was on. Use [snap] to put it back on the model.
     */
    fun moveAlongRay(origin: Point3, direction: Point3): Boolean {
        val depth = capturedDepth ?: return false
        val end = adjusting ?: return false
        val length = sqrt(
            direction.x * direction.x + direction.y * direction.y + direction.z * direction.z,
        )
        if (length <= 1e-6f) return false
        val unit = Point3(direction.x / length, direction.y / length, direction.z / length)
        val moved = AnnotationPoint(
            position = Point3(
                origin.x + unit.x * depth,
                origin.y + unit.y * depth,
                origin.z + unit.z * depth,
            ),
            anchor = AnnotationAnchor.PLANE,
            faceIndex = null,
        )
        put(end, moved)
        return true
    }

    /** Ends a handle drag; the captured depth no longer applies. */
    fun endAdjust() {
        capturedDepth = null
        adjusting = null
        zAdjusting = null
    }

    /** Re-anchors a handle onto the model surface. */
    fun snap(end: SegmentEnd, position: Point3, faceIndex: Int) {
        if (handle(end) == null) return
        put(end, AnnotationPoint(position, AnnotationAnchor.SURFACE, faceIndex))
        // A snap fixes the depth deliberately, so a later orbit must not undo it.
        capturedDepth = null
    }

    /**
     * Commits the segment being placed and starts the next one from its end.
     *
     * Returns true when something was committed.
     */
    fun lockSegment(): Boolean {
        val end = pendingEnd ?: return false
        pendingStart?.let { seriesPoints += it }
        seriesPoints += end
        pendingStart = null
        pendingEnd = null
        endAdjust()
        return true
    }

    /**
     * Ends the series in progress, so the next point placed starts a new one.
     *
     * A series of fewer than two points has no geometry, so it is discarded
     * rather than stored as a degenerate chain.
     */
    fun lockSeries(): AnnotationChain? {
        lockSegment()
        if (seriesPoints.size < 2) {
            // A series of fewer than two points has no geometry, so it is
            // discarded completely - including a start that was never paired.
            seriesPoints.clear()
            pendingStart = null
            pendingEnd = null
            endAdjust()
            return null
        }
        val chain = AnnotationChain(
            id = nextChainId++,
            kind = kind,
            points = seriesPoints.toList(),
        )
        chainList += chain
        seriesPoints.clear()
        endAdjust()
        return chain
    }

    /** Removes the most recent uncommitted point, then the last committed one. */
    fun undo(): Boolean {
        endAdjust()
        when {
            pendingEnd != null -> {
                pendingEnd = null
                return true
            }
            pendingStart != null -> {
                pendingStart = null
                return true
            }
            seriesPoints.isNotEmpty() -> {
                seriesPoints.removeAt(seriesPoints.size - 1)
                return true
            }
            chainList.isNotEmpty() -> {
                val last = chainList.removeAt(chainList.size - 1)
                seriesPoints += last.points
                return true
            }
        }
        return false
    }

    fun clear() {
        chainList.clear()
        seriesPoints.clear()
        pendingStart = null
        pendingEnd = null
        endAdjust()
    }

    /**
     * Replaces everything with [chains] from a saved document.
     *
     * Ids are kept as given so a re-save is stable, and the next id continues
     * past the highest restored one rather than colliding with it.
     */
    fun restore(chains: List<AnnotationChain>) {
        clear()
        chainList += chains
        nextChainId = (chains.maxOfOrNull { it.id } ?: 0) + 1
    }

    private fun handle(end: SegmentEnd): AnnotationPoint? = when (end) {
        SegmentEnd.START -> startHandle
        SegmentEnd.END -> endHandle
    }

    private fun put(end: SegmentEnd, point: AnnotationPoint) {
        when (end) {
            // A start handle only exists before the first segment is committed;
            // afterwards the start is a locked series point and not movable.
            SegmentEnd.START -> if (pendingStart != null) pendingStart = point
            SegmentEnd.END -> if (pendingEnd != null) pendingEnd = point
        }
    }

    companion object {
        const val DEFAULT_THICKNESS_PX = 6f
        const val MIN_THICKNESS_PX = 2f
        const val MAX_THICKNESS_PX = 24f
    }
}
