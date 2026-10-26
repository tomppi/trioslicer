package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.supportpaint.SupportPaintMode

/**
 * Which tool owns a touch on the plate: a face pick, a support-paint stroke, or the camera's.
 *
 * The two tools exist for the same finger, so they are mutually exclusive by construction rather
 * than by a check spread over the UI: **arming either one disarms the other**, and the two
 * predicates below are the only place the question is answered. Selecting a face can therefore
 * never paint - there is no paint mode to paint with - and a paint stroke can never pick a face.
 */
data class PlateModes(
    val paint: SupportPaintMode = SupportPaintMode.NONE,
    val faceSelect: Boolean = false,
) {
    /** A paint mode was chosen: arming one ends face selection. */
    fun withPaint(mode: SupportPaintMode): PlateModes =
        if (mode == SupportPaintMode.NONE) {
            copy(paint = mode)
        } else {
            PlateModes(paint = mode, faceSelect = false)
        }

    /** Face selection was armed or ended: arming it ends any paint mode. */
    fun withFaceSelect(armed: Boolean): PlateModes =
        if (!armed) {
            copy(faceSelect = false)
        } else {
            PlateModes(paint = SupportPaintMode.NONE, faceSelect = true)
        }

    /** True when a tap on the plate is a face pick. */
    val picksFaces: Boolean get() = faceSelect && paint == SupportPaintMode.NONE

    /** True when a stroke on the plate paints. */
    val paints: Boolean get() = paint != SupportPaintMode.NONE && !faceSelect
}
