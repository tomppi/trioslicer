package com.tomppi.enderslicer.annotation

/**
 * A screen gesture resolved into model space by the viewer.
 *
 * Both the resolved point and the ray that produced it travel together: the
 * point is where the gesture landed, and the ray is what a later depth
 * preserving move needs in order to slide the point without changing its
 * distance from the camera.
 */
data class AnnotationGesture(
    /** Where the gesture landed, in model millimetres. */
    val position: Point3,
    /** The triangle under the gesture, or null when the ray missed the model. */
    val faceIndex: Int?,
    /** Origin of the screen ray, in model space. */
    val rayOrigin: Point3,
    /** Unit direction of the screen ray. */
    val rayDirection: Point3,
    val screenX: Float,
    val screenY: Float,
) {
    val isOnSurface: Boolean get() = faceIndex != null

    val anchor: AnnotationAnchor get() = if (faceIndex != null) AnnotationAnchor.SURFACE else AnnotationAnchor.PLANE
}
