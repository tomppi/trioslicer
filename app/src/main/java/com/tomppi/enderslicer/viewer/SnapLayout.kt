package com.tomppi.enderslicer.viewer

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The seam's own layout guard: which joints are too close together to both be
 * built, and in what way.
 *
 * Two joints on one seam are two pockets in the same half of the same wall. The
 * failure this refuses is the quiet one: the second pocket breaks into the
 * first, the boolean answers with a closed mesh, and the joint that was asked
 * for is simply not there any more. So the room each joint takes across the
 * seam is measured from the solids it was really built as, and every pair is
 * checked before a boolean runs. It reports; it never drops a joint.
 *
 * The pad is deliberately not part of the footprint: a whole-seam pad IS the
 * seam's own cross-section, so two full joints on one seam share one pad rather
 * than competing for it, and what has to stay apart is the beam, the barb and
 * the key.
 *
 * Pure: mesh arithmetic, no engine, no state, no I/O.
 */
object SnapLayout {
    /** A rectangle in the seam's plane, in millimetres about a joint's own anchor. */
    data class SeamRect(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float) {
        val width: Float get() = maxX - minX
        val height: Float get() = maxY - minY
    }

    /** One joint's place on the seam: the anchor, and the room it takes around it. */
    data class Footprint(val anchor: Vec3, val side: Vec3, val rise: Vec3, val rect: SeamRect)

    /**
     * How much room the beam, its teeth and the key take about the joint's anchor.
     *
     * The beam is measured in the beam half's own frame and the pocket in the
     * mate's: the two frames sit on the same seam through the same anchor, so
     * the two rectangles are the same place physically - but the plate's packer
     * moves the halves apart after a split, and a pocket read in the beam half's
     * frame would carry the whole separation into the joint's own size.
     */
    fun footprintOf(joint: SnapFitJoint): Footprint {
        val beam = rectOf(joint.unionSolid, joint.frame)
        val pocket = rectOf(joint.subtractSolid, joint.socketFrame)
        return Footprint(
            anchor = joint.frame.originMm,
            side = joint.frame.side,
            rise = joint.frame.rise,
            rect = SeamRect(
                minOf(beam.minX, pocket.minX),
                maxOf(beam.maxX, pocket.maxX),
                minOf(beam.minY, pocket.minY),
                maxOf(beam.maxY, pocket.maxY),
            ),
        )
    }

    /** How far [mesh] reaches across the seam, either side of [frame]'s anchor. */
    private fun rectOf(mesh: StlMesh, frame: SnapFitFrame): SeamRect {
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        val vertices = mesh.interleavedVertices
        for (vertex in 0 until mesh.triangleCount * 3) {
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val local = frame.local(Vec3(vertices[base], vertices[base + 1], vertices[base + 2]))
            minX = minOf(minX, local.x)
            maxX = maxOf(maxX, local.x)
            minY = minOf(minY, local.y)
            maxY = maxOf(maxY, local.y)
        }
        if (minX > maxX || minY > maxY) return SeamRect(0f, 0f, 0f, 0f)
        return SeamRect(minX, maxX, minY, maxY)
    }

    /**
     * Every pair of joints whose footprints are closer than [wallMm] apart, as
     * one sentence each, naming both joints by their one-based place in the
     * list. The wall between two pockets has to be material, not a clearance:
     * two pockets that touch leave nothing to print between them.
     */
    fun conflicts(
        footprints: List<Footprint>,
        wallMm: Float,
        names: List<String> = emptyList(),
    ): List<String> {
        if (footprints.size < 2) return emptyList()
        // Every rectangle is carried into the FIRST joint's own two axes before
        // it is compared. The frames are one seam seen from either side of it,
        // so they can be turned round from each other: a joint whose beam goes
        // into the other half - which is where a joint the chosen half refused
        // is built - walks the assembly direction the other way, and its side
        // axis turns round with it. Comparing its own rectangle without carrying
        // it over read the joint as mirrored, and the guard reported "no
        // conflict" for a pair whose real pockets crossed by 4 mm.
        val reference = footprints.first()
        val rects = footprints.map { rectIn(it, reference) }
        val reasons = ArrayList<String>()
        for (first in rects.indices) {
            for (second in first + 1 until rects.size) {
                val a = rects[first]
                val b = rects[second]
                val gapX = gap(a.minX, a.maxX, b.minX, b.maxX)
                val gapY = gap(a.minY, a.maxY, b.minY, b.maxY)
                // The rects cross when neither axis separates them; otherwise
                // how far apart they really are, in both axes together.
                val separation = hypot(maxOf(gapX, 0f).toDouble(), maxOf(gapY, 0f).toDouble()).toFloat()
                val named = (names.getOrNull(first) ?: "") + (names.getOrNull(second)?.let { " and " + it } ?: "")
                if (gapX <= 0f && gapY <= 0f) {
                    reasons += "joints " + (first + 1) + " and " + (second + 1) + named +
                        " overlap on the seam: their pockets cross by " +
                        millimetres(-maxOf(gapX, gapY)) + " mm and a joint would be cut away by its neighbour"
                } else if (separation < wallMm) {
                    reasons += "joints " + (first + 1) + " and " + (second + 1) + named +
                        " leave only " + millimetres(separation) +
                        " mm of wall between their pockets and a printable wall is " +
                        millimetres(wallMm) + " mm; move one, or make them smaller"
                }
            }
        }
        return reasons
    }

    /**
     * [footprint]'s rectangle read in [reference]'s own two axes: the four
     * corners are carried into the reference's frame the way each joint's own
     * geometry is, so two rectangles facing opposite ways are compared where
     * they really are.
     */
    private fun rectIn(footprint: Footprint, reference: Footprint): SeamRect {
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (column in 0..1) {
            for (row in 0..1) {
                val x = if (column == 0) footprint.rect.minX else footprint.rect.maxX
                val y = if (row == 0) footprint.rect.minY else footprint.rect.maxY
                val point = footprint.anchor + footprint.side * x + footprint.rise * y
                val delta = point - reference.anchor
                val u = delta.dot(reference.side)
                val v = delta.dot(reference.rise)
                minX = minOf(minX, u)
                maxX = maxOf(maxX, u)
                minY = minOf(minY, v)
                maxY = maxOf(maxY, v)
            }
        }
        return SeamRect(minX, maxX, minY, maxY)
    }

    /** The clear distance between two spans on one axis: negative when they cross. */
    private fun gap(firstLow: Float, firstHigh: Float, secondLow: Float, secondHigh: Float): Float =
        maxOf(firstLow, secondLow) - minOf(firstHigh, secondHigh)

    private fun millimetres(value: Float): String = String.format(Locale.ROOT, "%.2f", abs(value))
}
