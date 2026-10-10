package com.tomppi.enderslicer.viewer

/**
 * The whole-seam pad, contoured to the material it actually sits on.
 *
 * The ladder's pad starts as a plain box across the seam. On a solid part that
 * is the right shape and this leaves it alone. On a hollow or thin-walled part
 * it is not: a box across a boat hull's cross-section is a wall standing in the
 * empty middle between the hull and the cabin, which is what a forced full
 * joint used to place. So the box is intersected with the half's own solid, and
 * what survives is the material's own cross-section - a rim that follows the
 * hull and the cabin walls.
 *
 * The contour is then inset a little, so the tongue does not break out through
 * the outline and the mating face keeps a rim around it, and the matching
 * recess is that same contoured shape plus the clearance, so the two still
 * mate. The result is a proper contoured tongue and groove. On a solid cube
 * the inset falls outside the pad's own box - which is already a rim inside
 * the face - so nothing about the pad changes there.
 *
 * The engine is behind [Engine], so the operation can be described without the
 * native library; on the JVM it runs against the host build staged by
 * scripts/build-manifold-host.sh.
 */
object SnapPad {
    /**
     * What the panel says when the material cannot carry a pad and the ladder
     * has to drop a rung: the reason travels with the joint that was placed.
     */
    const val TOO_THIN_REASON = "the wall here is too thin for a pad with a rim"

    /** The booleans the contour needs; [MeshBoolean] answers them on a device. */
    interface Engine {
        fun union(first: StlMesh, second: StlMesh): MeshBoolean.Result
        fun intersect(first: StlMesh, second: StlMesh): MeshBoolean.Result
    }

    /** What [MeshBoolean] answers with on a device. */
    object NativeEngine : Engine {
        override fun union(first: StlMesh, second: StlMesh): MeshBoolean.Result =
            MeshBoolean.union(first, second)

        override fun intersect(first: StlMesh, second: StlMesh): MeshBoolean.Result =
            MeshBoolean.intersect(first, second)
    }

    /**
     * [joint] with its pad and recess contoured to [beamHalf]'s material, or
     * null when the engine would not answer one of the booleans.
     *
     * Everything is in plate coordinates: the joint's solids are placed, the
     * half is placed, and the frame's own axis, side and rise are the
     * directions the contour moves in.
     */
    fun contour(
        joint: SnapFitJoint,
        beamHalf: SnapFitHalf,
        socketHalf: SnapFitHalf,
        engine: Engine = NativeEngine,
    ): SnapFitJoint? {
        val frame = joint.frame
        val dimensions = joint.dimensions
        val rect = faceRect(joint.registrationSolid, frame) ?: return null

        // Only the material near the pad is needed, and clipping to it first
        // keeps the erosion off the whole part: a million-triangle half does
        // not have to be intersected with itself four times for a pad that is
        // a centimetre across.
        val margin = INSET_MM + MARGIN_MM
        val region = framedBox(
            frame,
            LocalRect(rect.minX - margin, rect.maxX + margin, rect.minY - margin, rect.maxY + margin),
            -dimensions.stepRootMm - margin,
            dimensions.stepDepthMm + margin,
        )
        val piece = engine.intersect(beamHalf.mesh, region).meshOrNull() ?: return null

        // The inset is a share of the local material, not a fixed number: a
        // 1 mm hull wall cannot lose half a millimetre from each side and
        // still have a rim. The full inset is tried first, then halves of it,
        // and the first that leaves a rim worth having is the one used.
        val plain = engine.intersect(joint.registrationSolid, piece).meshOrNull() ?: return null
        val plainVolume = MeshVolume.of(plain)
        if (plainVolume <= 0.0) return null
        var root: StlMesh? = null
        for (inset in INSET_STEPS_MM) {
            val eroded = erode(piece, frame, inset, engine) ?: continue
            val candidate = engine.intersect(joint.registrationSolid, eroded).meshOrNull() ?: continue
            if (MeshVolume.of(candidate) >= MIN_RIM_SHARE * plainVolume) {
                root = candidate
                break
            }
        }
        // No inset small enough leaves a rim: there is nothing here for a pad
        // to hold on to, and the ladder's lesser rung is the honest answer.
        val shaped = root ?: return null

        // Stack the contour proud of the face, in steps no deeper than its own
        // root, so the tongue fills the recess it will be given.
        val tongue = stack(shaped, frame, dimensions, engine) ?: return null

        // The recess is the MATE's half of the same shape. The contour was
        // read off the beam half, so it is moved into the halves' shared own
        // coordinates and placed through the mate's own placement: built in
        // the beam's frame it would sit in the gap and cut nothing, which is
        // exactly how a one-sided joint was shipped once.
        val local = beamHalf.unplace(tongue, RECESS_NAME) ?: return null
        val localAxis = beamHalf.toLocalDirection(frame.axis)
        val localSide = beamHalf.toLocalDirection(frame.side)
        val localRise = beamHalf.toLocalDirection(frame.rise)
        val clearanced = dilate(local, localSide, localRise, dimensions.stepClearanceMm, engine) ?: return null
        val lifted = translate(clearanced, localAxis * dimensions.stepClearanceMm)
        val localRecess = engine.union(clearanced, lifted).meshOrNull() ?: return null
        val recess = socketHalf.place(localRecess, RECESS_NAME)
        return joint.copy(registrationSolid = tongue, registrationRecess = recess)
    }

    /** The tongue: [root] repeated upwards until it reaches the pad's depth. */
    private fun stack(
        root: StlMesh,
        frame: SnapFitFrame,
        dimensions: SnapFitDimensions,
        engine: Engine,
    ): StlMesh? {
        var tongue = root
        var top = 0f
        var guard = 0
        while (top + DEPTH_EPSILON_MM < dimensions.stepDepthMm && guard < MAX_STEPS) {
            guard++
            val step = minOf(dimensions.stepRootMm, dimensions.stepDepthMm - top)
            if (step <= DEPTH_EPSILON_MM) break
            val lifted = translate(tongue, frame.axis * step)
            tongue = engine.union(tongue, lifted).meshOrNull() ?: return null
            top += step
        }
        return tongue
    }

    /** [solid] grown by [clearance] across the seam's plane, never along it. */
    private fun dilate(
        solid: StlMesh,
        side: Vec3,
        rise: Vec3,
        clearance: Float,
        engine: Engine,
    ): StlMesh? {
        if (clearance <= 0f) return solid
        var grown = solid
        for (direction in listOf(side, side * -1f, rise, rise * -1f)) {
            val shifted = translate(solid, direction * clearance)
            grown = engine.union(grown, shifted).meshOrNull() ?: return null
        }
        return grown
    }

    /** The in-plane rectangle [solid] covers in [frame]'s own plane, or null. */
    private fun faceRect(solid: StlMesh, frame: SnapFitFrame): LocalRect? {
        if (solid.triangleCount <= 0) return null
        val vertices = solid.interleavedVertices
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (vertex in 0 until solid.triangleCount * 3) {
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val local = frame.local(Vec3(vertices[base], vertices[base + 1], vertices[base + 2]))
            if (local.x < minX) minX = local.x
            if (local.x > maxX) maxX = local.x
            if (local.y < minY) minY = local.y
            if (local.y > maxY) maxY = local.y
        }
        return LocalRect(minX, maxX, minY, maxY)
    }

    /** A box in [frame]'s own coordinates, wound outwards. */
    private fun framedBox(frame: SnapFitFrame, face: LocalRect, low: Float, high: Float): StlMesh {
        val builder = MeshSolidBuilder("pad region")
        val corners = arrayOf(
            Vec3(face.minX, face.minY, low), Vec3(face.maxX, face.minY, low),
            Vec3(face.maxX, face.maxY, low), Vec3(face.minX, face.maxY, low),
            Vec3(face.minX, face.minY, high), Vec3(face.maxX, face.minY, high),
            Vec3(face.maxX, face.maxY, high), Vec3(face.minX, face.maxY, high),
        ).map { frame.model(it) }
        fun face(a: Int, b: Int, c: Int, d: Int) {
            builder.addTriangle(corners[a].x, corners[a].y, corners[a].z, corners[b].x, corners[b].y, corners[b].z, corners[c].x, corners[c].y, corners[c].z)
            builder.addTriangle(corners[a].x, corners[a].y, corners[a].z, corners[c].x, corners[c].y, corners[c].z, corners[d].x, corners[d].y, corners[d].z)
        }
        face(0, 3, 2, 1) // bottom, -z
        face(4, 5, 6, 7) // top, +z
        face(0, 1, 5, 4) // -y
        face(3, 7, 6, 2) // +y
        face(0, 4, 7, 3) // -x
        face(1, 2, 6, 5) // +x
        return builder.build()
    }

    /** [mesh] moved by [by], keeping the winding and the triangle count. */
    private fun translate(mesh: StlMesh, by: Vec3): StlMesh {
        val builder = MeshSolidBuilder(mesh.displayName)
        val vertices = mesh.interleavedVertices
        val points = FloatArray(9)
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            for (corner in 0 until 3) {
                val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                points[corner * 3] = vertices[at] + by.x
                points[corner * 3 + 1] = vertices[at + 1] + by.y
                points[corner * 3 + 2] = vertices[at + 2] + by.z
            }
            builder.addTriangle(
                points[0], points[1], points[2],
                points[3], points[4], points[5],
                points[6], points[7], points[8],
            )
        }
        return builder.build()
    }

    private fun MeshBoolean.Result.meshOrNull(): StlMesh? = (this as? MeshBoolean.Result.Success)?.mesh

    /** A rectangle in the frame's plane, in frame-local millimetres. */
    private data class LocalRect(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

    /** The half is eroded in the plane of the seam - never along the assembly
     * axis, so the pad keeps its depth - and the pad follows what is left. */
    private fun erode(piece: StlMesh, frame: SnapFitFrame, inset: Float, engine: Engine): StlMesh? {
        var eroded = piece
        for (direction in listOf(frame.side, frame.side * -1f, frame.rise, frame.rise * -1f)) {
            val shifted = translate(eroded, direction * inset)
            eroded = engine.intersect(eroded, shifted).meshOrNull() ?: return null
        }
        return eroded
    }

    private const val RECESS_NAME = "snap-fit contoured recess"

    /** How far the tongue's contour pulls back from the part's own surface. */
    private const val INSET_MM = 0.5f

    /**
     * The insets tried, largest first. The first that leaves at least
     * [MIN_RIM_SHARE] of the plain contour is the local material's share: a
     * thin wall lands on one of the smaller steps, and a wall too thin for any
     * rim falls off the end and the rung is refused.
     */
    private val INSET_STEPS_MM = listOf(INSET_MM, INSET_MM / 2f, INSET_MM / 4f, INSET_MM / 8f)

    /** The share of the contour that has to survive an inset to be a rim. */
    private const val MIN_RIM_SHARE = 0.4

    /** How much material around the pad the contour is allowed to look at. */
    private const val MARGIN_MM = 1.5f

    private const val DEPTH_EPSILON_MM = 1e-3f
    private const val MAX_STEPS = 8
}
