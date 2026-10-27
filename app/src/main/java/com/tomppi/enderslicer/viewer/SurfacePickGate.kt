package com.tomppi.enderslicer.viewer

/**
 * Which finger-down the surface picker may report.
 *
 * The viewer reports a hit to one of two tools - the support brush and the surface picker - and
 * they do not ask the same question. The support brush paints the part under the finger and the
 * host makes that part the selected one as it paints, so any object is its answer. Smart Infill's
 * boundary condition belongs to the object the tools act on, so a neighbour is not its target. A
 * face selection is the face under the finger, wherever it is: the ViewModel resolves which body
 * of the CAD document the tapped plate object draws, and names a tap on a non-document object.
 *
 * The rule lives here rather than inline in the touch handler so it can be tested without a GL
 * surface - the face pick was gated on the selected object for every picker mode, so on a
 * two-part document only the first part's faces could be selected at all.
 */
internal object SurfacePickGate {

    /**
     * True when the pick belongs to the tool that owns the plate right now.
     *
     * [selectedObjectIndex] is the plate object the tools act on; [surfacePickActive] covers both
     * surface tools and [faceSelectActive] says which of the two is armed.
     */
    fun reports(
        hit: MeshPicker.Hit?,
        faceSelectActive: Boolean,
        surfacePickActive: Boolean,
        selectedObjectIndex: Int,
    ): Boolean {
        if (hit == null) return false
        // A face is a face wherever it is: the report names the plate object the finger hit, and
        // the host resolves the CAD body from that object rather than from the selection.
        if (faceSelectActive) return true
        // Smart Infill paints a boundary condition on the selected object only; a neighbour is
        // not its target.
        if (surfacePickActive) return hit.objectIndex == selectedObjectIndex
        // The support brush paints the part under the finger; the host selects it as it paints.
        return true
    }
}
