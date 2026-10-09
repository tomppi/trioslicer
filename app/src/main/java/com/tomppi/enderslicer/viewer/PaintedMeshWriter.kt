package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.supportpaint.SupportPaintState
import java.io.File

/**
 * Stages one displayed mesh as a 3MF that carries painted support enforcers and
 * blockers, because an STL cannot express "this facet is an enforcer, that one
 * is a blocker": PrusaSlicer and OrcaSlicer read paint from the model file, so a
 * painted model handed over as STL arrives unpainted and the paint is silently
 * ignored.
 *
 * Paint travels as a `slic3rpe:custom_supports` attribute on each painted
 * triangle, holding that triangle's serialised TriangleSelector state. The
 * state of an unsplit triangle is four bits — two bits of split-side count
 * (always zero here) followed by two bits of state, with NONE = 0,
 * ENFORCER = 1 and BLOCKER = 2 — packed into one hex digit, so an enforcer is
 * `4` and a blocker is `8`. Only whole, unsplit triangles are ever painted in
 * this app, so every attribute is that single digit and the digit-reversal the
 * multi-nibble encoding uses does not come into play.
 *
 * The file itself is written by [PlateThreeMfWriter]; this entry point is that
 * writer's one-object case, in the dialect this app has always handed both
 * engines.
 */
object PaintedMeshWriter {
    /** The attribute PrusaSlicer and OrcaSlicer read painted supports from. */
    const val SUPPORT_PAINT_ATTRIBUTE = "slic3rpe:custom_supports"

    /** Serialised state of an unsplit support-enforcer triangle. */
    const val ENFORCER_CODE = "4"

    /** Serialised state of an unsplit support-blocker triangle. */
    const val BLOCKER_CODE = "8"

    /** Writes [mesh] with [paint] as the only object of a one-object plate. */
    fun write(mesh: StlMesh, paint: SupportPaintState, destination: File) {
        PlateThreeMfWriter.write(
            file = destination,
            entries = listOf(PlateThreeMfWriter.Entry(name = mesh.displayName, mesh = mesh, paint = paint)),
            dialect = PlateThreeMfWriter.Dialect.ORCA,
        )
    }
}
