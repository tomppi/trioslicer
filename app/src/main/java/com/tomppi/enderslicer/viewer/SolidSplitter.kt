package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement

/**
 * The two halves of an axis-aligned cut through a model, cut with the mesh
 * boolean where the engine will take the model and with [BedClipper] where it
 * will not.
 *
 * Clipping the triangles and capping the cross-section by hand is a lot of
 * bookkeeping to get exactly right: the cap's outline has to pair, edge for
 * edge, with the surface the cut opened, and where the plane passes through a
 * vertex the walk that builds the cap can revisit a vertex and leave a
 * non-manifold boundary behind. On the XYZ calibration cube every plane in the
 * model did that: the front half came back with "closed 1 hole (5 edges), left 1
 * open: a boundary that revisits a vertex" from [MeshRepair], and the snap gate
 * has to refuse a half in that state.
 *
 * A boolean has none of that to get wrong. The model is a closed solid, the
 * half-space box is a closed solid, and subtracting one from the other is a
 * well-posed operation: the engine closes the cut face itself, so each half is
 * manifold by construction and T-junctions, bowtie outlines and unchained loops
 * cannot arise. It also costs the engine one subtract per half, measured in
 * milliseconds on a real part.
 *
 * The clipper is still the fallback, and the fallback is the whole of today's
 * behaviour: a model the engine will not take - an open mesh, a mesh with
 * self-intersections, or a build without libmanifold_jni.so - is cut and capped
 * exactly as before, and [Split.path] tells the caller which way it went.
 *
 * [BedClipper] keeps the job it is good at: the below-bed clip in
 * `stagedMesh()`, which only feeds the slicer and needs no solid.
 */
object SolidSplitter {
    /** Which of the two ways a split produced its halves. */
    enum class Path { BOOLEAN, CLIPPER }

    /** Both halves of one split, and how they were cut. */
    data class Split(val low: StlMesh, val high: StlMesh, val path: Path)

    /**
     * The engine calls the boolean path makes. Behind an interface for the same
     * reason [SnapFitGate] puts Manifold behind one: the library is only
     * packaged for arm64-v8a, so the decision has to be testable on the JVM.
     */
    interface Engine {
        /** False when the library is not packaged for this device's ABI. */
        val available: Boolean

        fun isManifold(mesh: StlMesh): Boolean

        fun subtract(first: StlMesh, second: StlMesh): MeshBoolean.Result
    }

    /** What [MeshBoolean] answers with on a device. */
    object NativeEngine : Engine {
        override val available: Boolean get() = MeshBoolean.ensureLoaded() && MeshBoolean.isAvailable

        override fun isManifold(mesh: StlMesh): Boolean = MeshBoolean.isManifold(mesh)

        override fun subtract(first: StlMesh, second: StlMesh): MeshBoolean.Result =
            MeshBoolean.subtract(first, second)
    }

    /**
     * The material of [mesh] at or below [offsetMm] on [axis] as [Split.low],
     * and the material at or above it as [Split.high].
     *
     * A plane that does not pass strictly through the model is the clipper's own
     * degenerate case - one half is the same instance, the other is empty - and
     * is left to it, because those are the answers its callers already rely on.
     */
    fun split(
        mesh: StlMesh,
        axis: ModelPlacement.Axis,
        offsetMm: Float,
        engine: Engine = NativeEngine,
    ): Split {
        require(offsetMm.isFinite()) { "A cut plane offset must be finite" }
        val span = mesh.bounds.spanAlong(axis)
        val cutsThrough = span.start < offsetMm - PLANE_EPSILON_MM &&
            span.endInclusive > offsetMm + PLANE_EPSILON_MM
        if (!cutsThrough || mesh.triangleCount <= 0 || !engine.available || !engine.isManifold(mesh)) {
            return clipped(mesh, axis, offsetMm)
        }
        // The low half keeps what is at or below the plane, so the half-space
        // box that is removed from it is the one above the plane, and the high
        // half is the mirror of that.
        val low = subtract(mesh, halfSpaceBox(mesh.bounds, axis, offsetMm, removeAbove = true), engine)
        val high = subtract(mesh, halfSpaceBox(mesh.bounds, axis, offsetMm, removeAbove = false), engine)
        if (low == null || high == null) return clipped(mesh, axis, offsetMm)
        return Split(
            low.copy(displayName = mesh.displayName),
            high.copy(displayName = mesh.displayName),
            Path.BOOLEAN,
        )
    }

    /** Today's cut, for a model the boolean engine will not take. */
    private fun clipped(mesh: StlMesh, axis: ModelPlacement.Axis, offsetMm: Float): Split = Split(
        low = BedClipper.clipClosed(mesh, axis, offsetMm, BedClipper.Half.LOW),
        high = BedClipper.clipClosed(mesh, axis, offsetMm, BedClipper.Half.HIGH),
        path = Path.CLIPPER,
    )

    /**
     * One half of the boolean split, or null when the engine would not produce
     * one. A result that is not a closed solid is refused here rather than
     * passed on: the whole point of the boolean path is that the half is
     * manifold, and a caller that got an open one would only fail later.
     */
    private fun subtract(mesh: StlMesh, box: StlMesh, engine: Engine): StlMesh? {
        val result = engine.subtract(mesh, box)
        if (result !is MeshBoolean.Result.Success) return null
        if (!result.closed || result.mesh.triangleCount <= 0) return null
        return result.mesh
    }

    /**
     * A closed box standing on the removed side of the plane: everything at or
     * above [offsetMm] when [removeAbove], everything at or below it otherwise.
     *
     * It reaches [BOX_MARGIN_MM] past the model's own bounds on every axis the
     * plane does not cut, and past the far side on the one it does, so the only
     * face of the box that meets the model is the one on the plane itself. That
     * face is exactly the cut: the engine intersects the two surfaces there and
     * the half comes out closed.
     */
    internal fun halfSpaceBox(
        bounds: MeshBounds,
        axis: ModelPlacement.Axis,
        offsetMm: Float,
        removeAbove: Boolean,
    ): StlMesh {
        val margin = maxOf(BOX_MARGIN_MM, bounds.spanAlong(axis).let { it.endInclusive - it.start } * BOX_MARGIN_FRACTION)
        var minX = bounds.minX - margin
        var maxX = bounds.maxX + margin
        var minY = bounds.minY - margin
        var maxY = bounds.maxY + margin
        var minZ = bounds.minZ - margin
        var maxZ = bounds.maxZ + margin
        when (axis) {
            ModelPlacement.Axis.X -> if (removeAbove) minX = offsetMm else maxX = offsetMm
            ModelPlacement.Axis.Y -> if (removeAbove) minY = offsetMm else maxY = offsetMm
            ModelPlacement.Axis.Z -> if (removeAbove) minZ = offsetMm else maxZ = offsetMm
        }
        return boxMesh("half space", minX, minY, minZ, maxX, maxY, maxZ)
    }

    /**
     * An axis-aligned box as twelve triangles wound outwards. Written out rather
     * than reached for through the engine's own primitive so the fallback path
     * builds the same box on a device without the library, and so the winding
     * is a thing a JVM test can measure.
     */
    private fun boxMesh(
        name: String,
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float,
    ): StlMesh {
        val builder = MeshSolidBuilder(name)
        fun corner(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        val a = corner(minX, minY, minZ)
        val b = corner(maxX, minY, minZ)
        val c = corner(maxX, maxY, minZ)
        val d = corner(minX, maxY, minZ)
        val e = corner(minX, minY, maxZ)
        val f = corner(maxX, minY, maxZ)
        val g = corner(maxX, maxY, maxZ)
        val h = corner(minX, maxY, maxZ)
        fun face(first: FloatArray, second: FloatArray, third: FloatArray, fourth: FloatArray) {
            builder.addTriangle(first[0], first[1], first[2], second[0], second[1], second[2], third[0], third[1], third[2])
            builder.addTriangle(first[0], first[1], first[2], third[0], third[1], third[2], fourth[0], fourth[1], fourth[2])
        }
        face(a, d, c, b) // bottom, -Z
        face(e, f, g, h) // top, +Z
        face(a, b, f, e) // front, -Y
        face(d, h, g, c) // back, +Y
        face(a, e, h, d) // left, -X
        face(b, c, g, f) // right, +X
        return builder.build()
    }

    /** No cut leaves the box narrower than this around the model. */
    private const val BOX_MARGIN_MM = 1f

    /** Nor narrower than this share of the model's own reach along the cut axis. */
    private const val BOX_MARGIN_FRACTION = 0.02f

    private const val PLANE_EPSILON_MM = 1e-6f
}
