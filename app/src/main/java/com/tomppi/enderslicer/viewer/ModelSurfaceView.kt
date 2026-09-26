package com.tomppi.enderslicer.viewer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import com.tomppi.enderslicer.annotation.AnnotationGesture
import com.tomppi.enderslicer.annotation.Point3
import com.tomppi.enderslicer.annotation.SegmentEnd
import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.smartinfill.SmartInfillOverlay
import com.tomppi.enderslicer.supportpaint.SupportPaintMode
import com.tomppi.enderslicer.supportpaint.SupportPaintState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

class ModelSurfaceView(
    context: Context,
    private val printer: PrinterDefinition,
) : GLSurfaceView(context) {
    private val modelRenderer = ModelRenderer(printer, resources.displayMetrics.density)
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private var previousX = 0f
    private var previousY = 0f
    private var previousFocusX = 0f
    private var previousFocusY = 0f
    private var panning = false
    private val paintPickExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pendingPaintCoordinates = java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
    private val paintPickLock = Any()
    private var paintPickScheduled = false

    /** When not [SupportPaintMode.NONE], a single-finger drag paints instead of rotating. */
    var paintMode: SupportPaintMode = SupportPaintMode.NONE
        set(value) {
            if (field == value) return
            field = value
            queueEvent { modelRenderer.setPaintActive(value != SupportPaintMode.NONE) }
            requestRender()
        }

    /**
     * When true, a single-finger drag moves the model across the build plate
     * instead of orbiting the camera. Two fingers still zoom and pan, and paint
     * mode still wins when it is on.
     */
    var dragMoveActive: Boolean = false

    /** Invoked on the UI thread with the plate movement in mm a finished drag asked for. */
    var onModelDragCommitted: ((Float, Float) -> Unit)? = null

    /** Accumulated plate movement of the drag in progress, in mm. */
    private var dragTotalX = 0f
    private var dragTotalY = 0f
    private var draggingModel = false

    /**
     * What the on-model gizmo is doing. NONE hides it, and MOVE also lets a plain
     * finger drag move the model, so the mode and the gesture agree.
     */
    var gizmoMode: TransformGizmoMode = TransformGizmoMode.NONE
        set(value) {
            if (field == value) return
            field = value
            draggingModel = false
            draggingRotate = false
            dragTotalX = 0f
            dragTotalY = 0f
            rotateDegrees = 0f
            modelRenderer.setDragOffset(0f, 0f)
            modelRenderer.setPreviewTransform(null, null, 0f, 1f)
            modelRenderer.setGizmo(gizmoFor(value))
            requestRender()
        }

    /** A long press that landed on the model: the app puts the three modes up. */
    var onTransformRequested: ((Float, Float) -> Unit)? = null

    /** Live rotation while the finger is down, for the hovering readout. */
    var onRotatePreview: ((ModelPlacement.Axis, Float) -> Unit)? = null

    /** The snapped rotation the finger let go at; the app commits it. */
    var onRotateCommitted: ((ModelPlacement.Axis, Float) -> Unit)? = null

    /** Live plate movement while a drag is in progress, for the readout. */
    var onModelDragPreview: ((Float, Float) -> Unit)? = null

    /**
     * A drag on a gizmo arrow: millimetres along that axis. Only the arrows start
     * one, which is the point of drawing them.
     */
    var onModelAxisMove: ((ModelPlacement.Axis, Float) -> Unit)? = null

    /** The same movement while the finger is still down, for the readout. */
    var onModelAxisMovePreview: ((ModelPlacement.Axis, Float) -> Unit)? = null

    /**
     * Uniform scale preview while the slider moves; 1 means none. The commit goes
     * through the view model once the finger lets go, exactly like a typed value.
     */
    var scalePreview: Float = 1f
        set(value) {
            val safe = if (value.isFinite() && value > 0f) value else 1f
            if (field == safe) return
            field = safe
            modelRenderer.setPreviewTransform(pivotFor(), null, 0f, safe)
            requestRender()
        }

    private var draggingRotate = false
    private var rotateDegrees = 0f
    private var rotateAxis: ModelPlacement.Axis = ModelPlacement.Axis.Z
    private var rotateGrabX = 0f
    private var rotateGrabY = 0f
    private var draggingAxisMove = false
    private var axisMoveAxis: ModelPlacement.Axis = ModelPlacement.Axis.X
    private var axisMoveMillimetres = 0f
    private var currentMesh: StlMesh? = null

    /** Invoked on the UI thread with the model triangle hit by a paint stroke. */
    var onPaintHit: ((MeshPicker.Hit) -> Unit)? = null

    /** Invoked on the UI thread with the model triangle a tap picked. */
    var onSurfacePick: ((MeshPicker.Hit) -> Unit)? = null

    /**
     * When true, a tap picks the surface under the finger.
     *
     * Deliberately separate from [paintMode]: a drag still orbits the camera and
     * only a tap inside the touch slop picks, so looking at a part never assigns
     * a surface by accident.
     */
    var surfacePickActive: Boolean = false

    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop
    private var surfacePickDownX = 0f
    private var surfacePickDownY = 0f

    /**
     * When true, taps place annotation points and drags may move a handle.
     *
     * A drag that does not begin on a handle still orbits the camera - that is
     * the whole point of the mode. Consuming every drag for placing is what made
     * the model impossible to turn while annotating.
     */
    var annotationActive: Boolean = false

    /** Invoked on the UI thread when a tap places an annotation point. */
    var onAnnotationTap: ((AnnotationGesture) -> Unit)? = null

    /** Invoked on the UI thread while a handle drag moves, to slide that handle. */
    var onAnnotationAdjust: ((SegmentEnd, AnnotationGesture) -> Unit)? = null

    /** Invoked once when holding a handle starts a height-only adjustment. */
    var onAnnotationZAdjustStart: ((SegmentEnd) -> Unit)? = null

    /** Invoked on the UI thread while a height adjustment drags. */
    var onAnnotationZAdjust: ((AnnotationGesture) -> Unit)? = null

    /** Invoked when a height adjustment ends. */
    var onAnnotationZAdjustEnd: (() -> Unit)? = null

    private val pendingAnnotationCoordinates =
        java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
    private var annotationScheduled = false

    private val pendingAnnotationProbe =
        java.util.concurrent.atomic.AtomicReference<FloatArray?>(null)
    private var annotationProbeScheduled = false

    /** True between ACTION_DOWN and the handle probe answering. */
    private var annotationDeciding = false
    private var annotationGrabbed: SegmentEnd? = null
    private var annotationDownX = 0f
    private var annotationDownY = 0f
    private var annotationMoved = false
    private var annotationAccumDx = 0f
    private var annotationAccumDy = 0f

    /** A tap whose UP arrived before the handle probe answered. */
    private var annotationPendingTap: FloatArray? = null

    /** True once a hold on a handle has switched to height-only adjustment. */
    private var annotationZMode = false

    /**
     * Switches a held handle to height adjustment.
     *
     * Deliberately a hold rather than a second control: height is changed
     * rarely and per point, so it does not deserve permanent screen space on a
     * phone. Cancelled by any movement, so it never fights an ordinary drag.
     */
    private val annotationHold = Runnable {
        val end = annotationGrabbed ?: return@Runnable
        if (annotationMoved) return@Runnable
        annotationZMode = true
        onAnnotationZAdjustStart?.invoke(end)
    }

    fun setAnnotationOverlay(overlay: AnnotationOverlay?) {
        queueEvent { modelRenderer.setAnnotationOverlay(overlay) }
        requestRender()
    }

    /** Invoked on the main thread whenever the turntable yaw/pitch changes. */
    var onOrientationChanged: ((ViewerOrientation) -> Unit)? = null

    /**
     * Whether the user may move the camera.
     *
     * The modelling screen shares one camera with the agent, and the agent owns
     * it by default: gestures are dropped outright while it does, rather than
     * being accepted and then overwritten by the next camera the agent sets.
     */
    var cameraInteractive: Boolean = true

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(modelRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = true
    }

    fun setMesh(mesh: StlMesh?) {
        currentMesh = mesh
        queueEvent { modelRenderer.setMesh(mesh) }
        // The gizmo is sized from the model, so a new model gets a new one.
        queueEvent { modelRenderer.setGizmo(gizmoFor(gizmoMode)) }
        // setMesh resets the camera on the GL thread for a new model.
        queueEvent { notifyOrientation() }
        requestRender()
    }

    fun currentOrientation(): ViewerOrientation = modelRenderer.orientation

    /**
     * Restores the turntable yaw/pitch after the surface view is recreated
     * (for example when the app moves away from the Plate tab and back).
     * Zoom and pan keep their defaults; only the orbit is restored.
     */
    fun restoreOrientation(orientation: ViewerOrientation) {
        queueEvent { modelRenderer.setOrientation(orientation) }
        requestRender()
    }

    /**
     * The camera's eye distance in world units (millimetres).
     *
     * This is the part of the view that can be handed to another renderer: yaw
     * and pitch alone say which way the model is turned, but not how close the
     * eye is, and distance is what makes a zoom-in on a problem mean the same
     * thing to both sides.
     */
    fun currentDistanceMm(): Float = modelRenderer.currentDistanceMm()

    /** Moves the camera to [distanceMm] by solving for the zoom that produces it. */
    fun restoreDistanceMm(distanceMm: Float) {
        queueEvent { modelRenderer.setDistanceMm(distanceMm) }
        requestRender()
    }

    fun setPaintState(paint: SupportPaintState) {
        queueEvent { modelRenderer.setPaintState(paint) }
        requestRender()
    }

    /**
     * Draws the picked Smart Infill boundary conditions on the model:
     * supports and loads in their own colours, the armed one highlighted.
     * Null clears the overlay.
     */
    fun setSmartInfillOverlay(overlay: SmartInfillOverlay?) {
        queueEvent { modelRenderer.setSmartInfillOverlay(overlay) }
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!cameraInteractive) return false
        val painting = paintMode != SupportPaintMode.NONE
        val annotating = annotationActive && !painting

        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
                panning = false
                if (surfacePickActive) {
                    surfacePickDownX = event.x
                    surfacePickDownY = event.y
                }
                if (painting) {
                    pendingPaintCoordinates.set(floatArrayOf(event.x, event.y))
                    schedulePaintPick()
                } else if (annotating) {
                    // Whether this becomes a handle drag or an orbit is decided
                    // by a hit test that runs off the UI thread, so hold the
                    // touch until it answers rather than guessing.
                    annotationDownX = event.x
                    annotationDownY = event.y
                    annotationMoved = false
                    annotationGrabbed = null
                    annotationDeciding = true
                    annotationAccumDx = 0f
                    annotationAccumDy = 0f
                    pendingAnnotationProbe.set(floatArrayOf(event.x, event.y))
                    scheduleAnnotationProbe()
                } else if (dragMoveActive) {
                    draggingModel = true
                    dragTotalX = 0f
                    dragTotalY = 0f
                } else if (gizmoMode != TransformGizmoMode.NONE) {
                    // Only a handle starts a transform: a drag that began on the
                    // model or the plate is the camera's, as it was before.
                    val handle = modelRenderer.gizmoHitAt(event.x, event.y, GizmoDrag.TOUCH_RADIUS_PX)
                    when (handle?.kind) {
                        GizmoHandleKind.RING -> {
                            draggingRotate = true
                            rotateDegrees = 0f
                            rotateAxis = handle.axis
                            rotateGrabX = event.x
                            rotateGrabY = event.y
                        }
                        GizmoHandleKind.ARROW -> {
                            draggingAxisMove = true
                            axisMoveAxis = handle.axis
                            axisMoveMillimetres = 0f
                        }
                        null -> Unit
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    previousFocusX = pointerFocusX(event)
                    previousFocusY = pointerFocusY(event)
                    panning = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val focusX = pointerFocusX(event)
                    val focusY = pointerFocusY(event)
                    if (panning) {
                        modelRenderer.panPixels(
                            deltaX = focusX - previousFocusX,
                            deltaY = focusY - previousFocusY,
                        )
                    }
                    previousFocusX = focusX
                    previousFocusY = focusY
                    panning = true
                    requestRender()
                } else if (!scaleDetector.isInProgress) {
                    if (painting) {
                        pendingPaintCoordinates.set(floatArrayOf(event.x, event.y))
                        schedulePaintPick()
                    } else if (annotating && annotationDeciding) {
                        // Accumulate until the probe answers; the movement is
                        // applied as an orbit if it turns out not to be a handle.
                        annotationAccumDx += event.x - previousX
                        annotationAccumDy += event.y - previousY
                        previousX = event.x
                        previousY = event.y
                    } else if (annotating && annotationGrabbed != null) {
                        if (!annotationZMode) {
                            // Movement means this is a placement drag, not a hold.
                            removeCallbacks(annotationHold)
                            annotationMoved = true
                        }
                        previousX = event.x
                        previousY = event.y
                        pendingAnnotationCoordinates.set(floatArrayOf(event.x, event.y))
                        scheduleAnnotationGesture()
                    } else if (draggingRotate) {
                        val dx = event.x - previousX
                        val dy = event.y - previousY
                        rotateDegrees += modelRenderer.ringDragDegrees(
                            axis = rotateAxis,
                            deltaXPx = dx,
                            deltaYPx = dy,
                            touchX = rotateGrabX,
                            touchY = rotateGrabY,
                        )
                        val snapped = snapDegrees(rotateDegrees)
                        modelRenderer.setPreviewTransform(pivotFor(), rotateAxis, snapped, 1f)
                        previousX = event.x
                        previousY = event.y
                        onRotatePreview?.invoke(rotateAxis, snapped)
                        requestRender()
                    } else if (draggingAxisMove) {
                        val dx = event.x - previousX
                        val dy = event.y - previousY
                        axisMoveMillimetres += modelRenderer.axisDragMillimetres(axisMoveAxis, dx, dy)
                        val (offsetX, offsetY, offsetZ) = when (axisMoveAxis) {
                            ModelPlacement.Axis.X -> Triple(axisMoveMillimetres, 0f, 0f)
                            ModelPlacement.Axis.Y -> Triple(0f, axisMoveMillimetres, 0f)
                            ModelPlacement.Axis.Z -> Triple(0f, 0f, axisMoveMillimetres)
                        }
                        modelRenderer.setDragOffset(offsetX, offsetY, offsetZ)
                        previousX = event.x
                        previousY = event.y
                        onModelAxisMovePreview?.invoke(axisMoveAxis, axisMoveMillimetres)
                        requestRender()
                    } else if (draggingModel) {
                        val dx = event.x - previousX
                        val dy = event.y - previousY
                        val delta = modelRenderer.dragDeltaMm(dx, dy)
                        dragTotalX += delta[0]
                        dragTotalY += delta[1]
                        modelRenderer.setDragOffset(dragTotalX, dragTotalY)
                        onModelDragPreview?.invoke(dragTotalX, dragTotalY)
                        previousX = event.x
                        previousY = event.y
                        requestRender()
                    } else {
                        val dx = event.x - previousX
                        val dy = event.y - previousY
                        modelRenderer.rotate(dx * 0.35f, dy * 0.35f)
                        previousX = event.x
                        previousY = event.y
                        notifyOrientation()
                        requestRender()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                panning = false
                if (event.pointerCount - 1 == 1) {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    previousX = event.getX(remainingIndex)
                    previousY = event.getY(remainingIndex)
                }
            }

            MotionEvent.ACTION_UP -> {
                performClick()
                panning = false
                if (annotationActive && !painting && !annotationMoved && annotationGrabbed == null) {
                    val coordinates = floatArrayOf(event.x, event.y)
                    if (annotationDeciding) {
                        // The probe has not answered yet; hold the tap so a touch
                        // that turns out to be on a handle does not also place a point.
                        annotationPendingTap = coordinates
                    } else {
                        pendingAnnotationCoordinates.set(coordinates)
                        scheduleAnnotationGesture()
                    }
                }
                if (surfacePickActive && !painting) {
                    // A tap, not a drag: the surface is only assigned when the
                    // finger stayed inside the slop.
                    val dx = event.x - surfacePickDownX
                    val dy = event.y - surfacePickDownY
                    if (dx * dx + dy * dy <= touchSlopPx * touchSlopPx) {
                        pendingPaintCoordinates.set(floatArrayOf(event.x, event.y))
                        schedulePaintPick()
                    }
                }
                if (draggingRotate) {
                    draggingRotate = false
                    val degrees = snapDegrees(rotateDegrees)
                    rotateDegrees = 0f
                    modelRenderer.setPreviewTransform(null, null, 0f, 1f)
                    requestRender()
                    if (degrees != 0f) onRotateCommitted?.invoke(rotateAxis, degrees)
                }
                if (draggingAxisMove) {
                    draggingAxisMove = false
                    val millimetres = axisMoveMillimetres
                    axisMoveMillimetres = 0f
                    modelRenderer.setDragOffset(0f, 0f, 0f)
                    requestRender()
                    if (millimetres != 0f) onModelAxisMove?.invoke(axisMoveAxis, millimetres)
                }
                if (draggingModel) {
                    draggingModel = false
                    modelRenderer.setDragOffset(0f, 0f)
                    requestRender()
                    val committedX = dragTotalX
                    val committedY = dragTotalY
                    dragTotalX = 0f
                    dragTotalY = 0f
                    if (committedX != 0f || committedY != 0f) {
                        onModelDragCommitted?.invoke(committedX, committedY)
                    }
                }
                removeCallbacks(annotationHold)
                if (annotationZMode) {
                    annotationZMode = false
                    onAnnotationZAdjustEnd?.invoke()
                }
                annotationDeciding = false
                annotationGrabbed = null
            }

            MotionEvent.ACTION_CANCEL -> {
                panning = false
                if (draggingRotate) {
                    draggingRotate = false
                    rotateDegrees = 0f
                    modelRenderer.setPreviewTransform(null, null, 0f, 1f)
                    requestRender()
                }
                if (draggingAxisMove) {
                    draggingAxisMove = false
                    axisMoveMillimetres = 0f
                    modelRenderer.setDragOffset(0f, 0f, 0f)
                    requestRender()
                }
                if (draggingModel) {
                    draggingModel = false
                    dragTotalX = 0f
                    dragTotalY = 0f
                    modelRenderer.setDragOffset(0f, 0f)
                    requestRender()
                }
            }
        }
        return true
    }

    /** The gizmo geometry for a mode, sized from the model on screen. */
    private fun gizmoFor(mode: TransformGizmoMode): GizmoOverlay? {
        val bounds = currentMesh?.bounds ?: return null
        val pivot = Point3(bounds.centerX, bounds.centerY, bounds.minZ)
        return when (mode) {
            TransformGizmoMode.ROTATE -> TransformGizmo.ringsOverlay(
                TransformGizmo.rings(pivot, TransformGizmo.ringRadiusMm(bounds)),
            )
            TransformGizmoMode.MOVE -> TransformGizmo.arrowsOverlay(
                TransformGizmo.arrows(pivot, TransformGizmo.arrowLengthMm(bounds)),
            )
            TransformGizmoMode.SCALE -> TransformGizmo.boxOutline(bounds)
            TransformGizmoMode.NONE -> null
        }
    }

    /** Rotation and scale turn about the model's base centre, as the placement does. */
    private fun pivotFor(): Point3? {
        val bounds = currentMesh?.bounds ?: return null
        return Point3(bounds.centerX, bounds.centerY, bounds.minZ)
    }

    private fun snapDegrees(degrees: Float): Float =
        Math.round(degrees / ROTATE_SNAP_DEGREES) * ROTATE_SNAP_DEGREES

    /**
     * Asks the model whether a long press landed on it.
     *
     * The pick runs off the UI thread, like the paint pick, and only a hit asks
     * for the gizmo: long-pressing the plate is not a request to transform
     * anything.
     */
    private fun scheduleTransformProbe(screenX: Float, screenY: Float) {
        paintPickExecutor.execute {
            val hit = modelRenderer.pickTriangle(screenX, screenY)
            if (hit != null) post { onTransformRequested?.invoke(screenX, screenY) }
        }
    }

    private fun notifyOrientation() {
        val listener = onOrientationChanged ?: return
        post { listener(modelRenderer.orientation) }
    }

    private fun schedulePaintPick() {
        synchronized(paintPickLock) {
            if (paintPickScheduled) return
            paintPickScheduled = true
        }
        paintPickExecutor.execute {
            try {
                while (true) {
                    val coordinates = pendingPaintCoordinates.getAndSet(null) ?: break
                    val hit = modelRenderer.pickTriangle(coordinates[0], coordinates[1]) ?: continue
                    post {
                        if (surfacePickActive) onSurfacePick?.invoke(hit) else onPaintHit?.invoke(hit)
                    }
                }
            } finally {
                synchronized(paintPickLock) { paintPickScheduled = false }
                if (pendingPaintCoordinates.get() != null) schedulePaintPick()
            }
        }
    }

    /**
     * Resolves pending annotation gestures off the UI thread.
     *
     * Same shape as [schedulePaintPick]: at most one run is in flight, and
     * coordinates that arrive during a run are picked up by the drain loop
     * rather than queued as separate tasks.
     */
    private fun scheduleAnnotationGesture() {
        synchronized(paintPickLock) {
            if (annotationScheduled) return
            annotationScheduled = true
        }
        paintPickExecutor.execute {
            try {
                while (true) {
                    val coordinates = pendingAnnotationCoordinates.getAndSet(null) ?: break
                    val gesture = modelRenderer.annotationGestureAt(coordinates[0], coordinates[1])
                        ?: continue
                    // Read the grab state on the UI thread, at delivery: the same
                    // queue carries taps and handle drags, and which one this is
                    // depends on whether a handle is held when it lands.
                    post {
                        val end = annotationGrabbed
                        when {
                            end == null -> onAnnotationTap?.invoke(gesture)
                            annotationZMode -> onAnnotationZAdjust?.invoke(gesture)
                            else -> onAnnotationAdjust?.invoke(end, gesture)
                        }
                    }
                }
            } finally {
                synchronized(paintPickLock) { annotationScheduled = false }
                if (pendingAnnotationCoordinates.get() != null) scheduleAnnotationGesture()
            }
        }
    }

    /**
     * Resolves whether a touch landed on an annotation handle.
     *
     * Same shape as [scheduleAnnotationGesture]: one run in flight, drained
     * rather than queued. The answer decides whether the touch becomes a handle
     * drag or an orbit, so movement is held until it arrives.
     */
    private fun scheduleAnnotationProbe() {
        synchronized(paintPickLock) {
            if (annotationProbeScheduled) return
            annotationProbeScheduled = true
        }
        paintPickExecutor.execute {
            try {
                while (true) {
                    val coordinates = pendingAnnotationProbe.getAndSet(null) ?: break
                    val handle = modelRenderer.annotationHandleAt(
                        coordinates[0],
                        coordinates[1],
                        HANDLE_TOUCH_RADIUS_PX,
                    )
                    post { applyAnnotationProbe(handle) }
                }
            } finally {
                synchronized(paintPickLock) { annotationProbeScheduled = false }
                if (pendingAnnotationProbe.get() != null) scheduleAnnotationProbe()
            }
        }
    }

    private fun applyAnnotationProbe(handle: SegmentEnd?) {
        annotationDeciding = false
        annotationGrabbed = handle
        if (handle != null) {
            // A handle is held, so the held movement is not an orbit. The first
            // MOVE delivers the ray that slides it, unless the finger stays put
            // long enough to mean height instead.
            annotationAccumDx = 0f
            annotationAccumDy = 0f
            postDelayed(annotationHold, HANDLE_LONG_PRESS_MILLIS)
            return
        }
        // Not a handle, so the movement the user already made becomes an orbit.
        if (annotationAccumDx != 0f || annotationAccumDy != 0f) {
            modelRenderer.rotate(annotationAccumDx * 0.35f, annotationAccumDy * 0.35f)
            annotationAccumDx = 0f
            annotationAccumDy = 0f
            notifyOrientation()
            requestRender()
        }
        // A tap that completed before this answer is still a tap.
        annotationPendingTap?.let { coordinates ->
            annotationPendingTap = null
            pendingAnnotationCoordinates.set(coordinates)
            scheduleAnnotationGesture()
        }
    }

    /**
     * Frees the model's GPU buffers when the surface goes away.
     *
     * GLSurfaceView's renderer interface has no destroy callback, and the viewer
     * keeps its EGL context across a pause, so without this a mesh-sized buffer
     * would stay pinned while the app sits in the background. Queued before
     * super: tearing the surface down is what eventually destroys the context,
     * and the ids only mean anything while it is current.
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        queueEvent { modelRenderer.releaseGpuBuffers() }
        super.surfaceDestroyed(holder)
    }

    override fun onDetachedFromWindow() {
        paintPickExecutor.shutdown()
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun pointerFocusX(event: MotionEvent): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) total += event.getX(index)
        return total / event.pointerCount
    }

    private fun pointerFocusY(event: MotionEvent): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) total += event.getY(index)
        return total / event.pointerCount
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            modelRenderer.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onLongPress(event: MotionEvent) {
            if (cameraInteractive && paintMode == SupportPaintMode.NONE &&
                !annotationActive && gizmoMode == TransformGizmoMode.NONE
            ) {
                scheduleTransformProbe(event.x, event.y)
            }
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            modelRenderer.resetCamera()
            notifyOrientation()
            requestRender()
            return true
        }
    }

    private companion object {
        /**
         * Touch slop for grabbing an end handle, in pixels.
         *
         * Comfortably larger than the drawn marker because a finger is wider
         * than a cross, and small enough that it cannot swallow a tap meant for
         * the model behind it.
         */
        const val HANDLE_TOUCH_RADIUS_PX = 44f

        /**
         * How long a handle must be held to switch to height adjustment.
         *
         * Long because it is a mode change rather than a tap, and it must not
         * fire while the user is simply resting a finger before dragging.
         */
        const val HANDLE_LONG_PRESS_MILLIS = 2_000L

        /** Cura's rotation snap, and the step the readout counts in. */
        const val ROTATE_SNAP_DEGREES = 15f

    }
}

private class ModelRenderer(
    private val printer: PrinterDefinition,
    /** Device pixels per dp, for the marker line widths that GL takes in pixels. */
    private val density: Float,
) : GLSurfaceView.Renderer {
    @Volatile private var mesh: StlMesh? = null
    private var meshBuffer: FloatBuffer? = null
    private var paintColors: PaintColorBuffer? = null
    private var annotationOverlay: AnnotationOverlay? = null
    private var annotationBuffer: FloatBuffer? = null
    private var gizmoOverlay: GizmoOverlay? = null

    // The gizmo is drawn as screen-space ribbons rather than GL lines: glLineWidth
    // is capped at one pixel on many Android drivers, so a handle thick enough to
    // aim at has to be geometry. One scratch buffer is reused for every group.
    private var gizmoRibbon: FloatBuffer? = null
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val ndcStart = FloatArray(3)
    private val ndcEnd = FloatArray(3)

    // Preview of a transform being dragged. Rotation and scale happen about the
    // pivot the committed placement uses - the model's base centre - so letting go
    // does not shift the model the finger just positioned.
    @Volatile private var previewPivotX = 0f
    @Volatile private var previewPivotY = 0f
    @Volatile private var previewPivotZ = 0f
    @Volatile private var previewAxis: ModelPlacement.Axis? = null
    @Volatile private var previewDegrees = 0f
    @Volatile private var previewScale = 1f
    // GPU-side (VBO) copies of the mesh; the model renders from VRAM after a
    // single upload, like a game, instead of re-reading CPU memory per frame.
    private var meshVbo = 0
    private var colorVbo = 0
    private var uploadedMesh: StlMesh? = null
    private var colorUploaded = false
    private var paintState: SupportPaintState = SupportPaintState()
    private var paintActive = false
    private var smartInfillOverlay: SmartInfillOverlay? = null
    private var meshProgram = 0
    private var lineProgram = 0
    private var regionProgram = 0
    // The optimized regions as translucent surfaces: one buffer pair, built when a
    // result arrives and blended over the shaded model from then on.
    private var regionPositions: FloatBuffer? = null
    private var regionColors: FloatBuffer? = null
    private var regionVertexCount = 0
    private var regionVbo = 0
    private var regionColorVbo = 0
    private var regionUploaded = false

    /**
     * Widest line this driver will actually draw.
     *
     * GLES only guarantees 1px, so a requested annotation thickness has to be
     * clamped or it silently draws at 1px on some devices and not others.
     */
    private var maxLineWidth = 1f
    private var gridBuffer: FloatBuffer? = null
    private var gridVertexCount = 0
    private var viewportWidth = 1
    private var viewportHeight = 1

    // The plate's own XYZ marker, on the bed's front-left corner.
    private var triadBuffer: FloatBuffer? = null
    private val triadOrigin = floatArrayOf(0f, 0f, GRID_Z)
    private val triadPoint = FloatArray(4)
    private val triadEye = FloatArray(3)
    private val triadInverse = FloatArray(16)
    private val triadCorner = FloatArray(4)
    @Volatile private var yaw = DEFAULT_YAW
    @Volatile private var pitch = DEFAULT_PITCH
    @Volatile private var zoom = DEFAULT_ZOOM
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f

    // Plate-space preview offset while a finger drags the model: the placement
    // itself is only changed once, when the finger lifts, because a real move
    // re-transforms the whole mesh and writes the workspace snapshot.
    @Volatile private var dragOffsetX = 0f
    @Volatile private var dragOffsetY = 0f
    @Volatile private var dragOffsetZ = 0f

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val scene = FloatArray(16)
    private val modelLocal = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    fun setMesh(value: StlMesh?) {
        if (mesh === value) return
        val isNewModel = value?.displayName != mesh?.displayName
        mesh = value
        meshBuffer = value?.let { mesh ->
            mesh.interleavedVertices.directOrNull()
                ?: mesh.interleavedVertices.arrayOrNull()?.let(::floatBuffer)
        }
        // Every placement change (rotate, scale, move, lay flat, drop) hands in a
        // fresh StlMesh, and the previous VBO is unreachable from here on: left
        // behind, one mesh-sized buffer would leak per tap until the driver ran
        // out of memory and the model silently stopped drawing.
        releaseGpuBuffers()
        paintColors = null
        rebuildColorBuffer()
        if (isNewModel) resetCamera()
    }

    fun setPaintState(value: SupportPaintState) {
        if (paintState == value) return
        paintState = value
        rebuildColorBuffer()
    }

    /**
     * Applies a single paint edit, rewriting only the triangles in [changed].
     *
     * A stroke expands to a bounded set of triangles, so this keeps the update
     * O(brush) instead of rebuilding a colour buffer sized by the whole mesh.
     * Falls back to a full rebuild when the buffer is missing or the mesh
     * changed underneath it.
     */
    fun applyPaintEdit(value: SupportPaintState, changed: Set<Int>) {
        if (paintState == value) return
        paintState = value
        val buffer = paintColors
        if (buffer == null || buffer.triangleCount != mesh?.triangleCount) {
            rebuildColorBuffer()
            return
        }
        buffer.apply(value, changed)
    }

    fun setPaintActive(value: Boolean) {
        if (paintActive == value) return
        paintActive = value
        rebuildColorBuffer()
    }

    /**
     * The Smart Infill boundary conditions drawn on the part.
     *
     * Identity-compared: the host hands in a new instance only when the
     * selection changed, and an identical one must not cost a full colour
     * rewrite on every recomposition.
     */
    fun setSmartInfillOverlay(value: SmartInfillOverlay?) {
        if (value === smartInfillOverlay) return
        smartInfillOverlay = value
        rebuildColorBuffer()
        rebuildRegions()
    }

    private fun cameraSnapshot(currentMesh: StlMesh) = MeshPicker.CameraSnapshot(
        viewportWidth = viewportWidth.toFloat(),
        viewportHeight = viewportHeight.toFloat(),
        yaw = yaw,
        pitch = pitch,
        zoom = zoom,
        panX = panX,
        panY = panY,
        meshBounds = currentMesh.bounds,
    )

    fun pickTriangle(screenX: Float, screenY: Float): MeshPicker.Hit? {
        val currentMesh = mesh ?: return null
        return MeshPicker.pick(
            mesh = currentMesh,
            printer = printer,
            camera = cameraSnapshot(currentMesh),
            screenX = screenX,
            screenY = screenY,
        )
    }

    /**
     * Resolves a screen position into an annotation placement.
     *
     * A hit gives an exact surface point and its triangle. A miss is not
     * discarded: the ray is intersected with the plane through the model's
     * centre that faces the camera, so a gesture in empty space still produces
     * a real 3D position at a predictable depth. Either way the ray travels
     * with the result, because a later depth-preserving move needs it.
     */
    fun annotationGestureAt(screenX: Float, screenY: Float): AnnotationGesture? {
        val currentMesh = mesh ?: return null
        val camera = cameraSnapshot(currentMesh)
        val ray = MeshPicker.ray(printer, camera, screenX, screenY) ?: return null
        val hit = MeshPicker.pick(currentMesh, printer, camera, screenX, screenY)
        val bounds = currentMesh.bounds
        val position = if (hit != null) {
            Point3(hit.x, hit.y, hit.z)
        } else {
            val t = (bounds.centerX - ray.originX) * ray.dirX +
                (bounds.centerY - ray.originY) * ray.dirY +
                (bounds.centerZ - ray.originZ) * ray.dirZ
            Point3(
                ray.originX + ray.dirX * t,
                ray.originY + ray.dirY * t,
                ray.originZ + ray.dirZ * t,
            )
        }
        return AnnotationGesture(
            position = position,
            faceIndex = hit?.triangleIndex,
            rayOrigin = Point3(ray.originX, ray.originY, ray.originZ),
            rayDirection = Point3(ray.dirX, ray.dirY, ray.dirZ),
            screenX = screenX,
            screenY = screenY,
        )
    }

    val orientation: ViewerOrientation
        get() = ViewerOrientation(yaw, pitch)

    fun rotate(deltaYaw: Float, deltaPitch: Float) {
        yaw = wrapDegrees(yaw + deltaYaw)
        pitch = wrapDegrees(pitch + deltaPitch)
    }

    fun zoom(scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        zoom = (zoom * scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun panPixels(deltaX: Float, deltaY: Float) {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return
        val distance = cameraDistance()
        val eyeDistance = distance * CAMERA_EYE_DISTANCE_SCALE
        val visibleHeight = 2f * eyeDistance * tan(Math.toRadians(FIELD_OF_VIEW_DEGREES / 2.0)).toFloat()
        val worldPerPixel = visibleHeight / max(viewportHeight, 1).toFloat()
        panX += deltaX * worldPerPixel
        panY -= deltaY * worldPerPixel
    }

    /**
     * The plate movement a screen drag is asking for, in millimetres.
     *
     * Read from the touch handler, like [panPixels], so it converts against the
     * camera that is on screen.
     */
    fun dragDeltaMm(deltaXPx: Float, deltaYPx: Float): FloatArray {
        val millimetresPerPixel = BedPlaneDrag.millimetresPerPixel(
            distanceMm = cameraDistance(),
            viewportHeightPx = viewportHeight,
            fieldOfViewDegrees = FIELD_OF_VIEW_DEGREES,
            eyeDistanceScale = CAMERA_EYE_DISTANCE_SCALE,
        )
        return BedPlaneDrag.plateDeltaMm(
            deltaXPx = deltaXPx,
            deltaYPx = deltaYPx,
            millimetresPerPixel = millimetresPerPixel,
            yawDegrees = yaw,
            pitchDegrees = pitch,
        )
    }

    /** Moves the drawn model without touching the placement; zeroed when a drag ends. */
    fun setDragOffset(xMm: Float, yMm: Float) = setDragOffset(xMm, yMm, 0f)

    fun setDragOffset(xMm: Float, yMm: Float, zMm: Float) {
        dragOffsetX = xMm
        dragOffsetY = yMm
        dragOffsetZ = zMm
    }

    /**
     * Which gizmo handle a touch landed on, if any.
     *
     * Handles are points in plate coordinates, so the test is how close each one
     * projects to the finger: the same approach the annotation handles use, and
     * the only one that stays honest while the camera orbits.
     */
    fun gizmoHitAt(screenX: Float, screenY: Float, tolerancePx: Float): GizmoHit? {
        val overlay = gizmoOverlay ?: return null
        val currentMesh = mesh ?: return null
        if (overlay.handles.isEmpty()) return null
        val camera = cameraSnapshot(currentMesh)
        var best: GizmoHit? = null
        var bestDistance = tolerancePx
        overlay.handles.forEach { handle ->
            val points = handle.points.size / 3
            for (index in 0 until points) {
                val screen = MeshPicker.project(
                    printer,
                    camera,
                    handle.points[index * 3],
                    handle.points[index * 3 + 1],
                    handle.points[index * 3 + 2],
                ) ?: continue
                val dx = screen[0] - screenX
                val dy = screen[1] - screenY
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                if (distance <= bestDistance) {
                    bestDistance = distance
                    best = GizmoHit(handle.axis, handle.kind)
                }
            }
        }
        return best
    }

    /** Millimetres along [axis] for a screen drag on its arrow. */
    fun axisDragMillimetres(axis: ModelPlacement.Axis, deltaXPx: Float, deltaYPx: Float): Float {
        val currentMesh = mesh ?: return 0f
        val bounds = currentMesh.bounds
        val pivot = Point3(bounds.centerX, bounds.centerY, bounds.minZ)
        val length = TransformGizmo.arrowLengthMm(bounds)
        val tip = when (axis) {
            ModelPlacement.Axis.X -> Point3(pivot.x + length, pivot.y, pivot.z)
            ModelPlacement.Axis.Y -> Point3(pivot.x, pivot.y + length, pivot.z)
            ModelPlacement.Axis.Z -> Point3(pivot.x, pivot.y, pivot.z + length)
        }
        val camera = cameraSnapshot(currentMesh)
        val from = MeshPicker.project(printer, camera, pivot.x, pivot.y, pivot.z) ?: return 0f
        val to = MeshPicker.project(printer, camera, tip.x, tip.y, tip.z) ?: return 0f
        val fallback = BedPlaneDrag.millimetresPerPixel(
            distanceMm = cameraDistance(),
            viewportHeightPx = viewportHeight,
            fieldOfViewDegrees = FIELD_OF_VIEW_DEGREES,
            eyeDistanceScale = CAMERA_EYE_DISTANCE_SCALE,
        )
        return GizmoDrag.axisMillimetres(
            deltaXPx = deltaXPx,
            deltaYPx = deltaYPx,
            fromX = from[0],
            fromY = from[1],
            toX = to[0],
            toY = to[1],
            lengthMm = length,
            fallbackMillimetresPerPixel = fallback,
        )
    }

    /** Degrees for a screen drag on the ring of [axis]. */
    fun ringDragDegrees(axis: ModelPlacement.Axis, deltaXPx: Float, deltaYPx: Float, touchX: Float, touchY: Float): Float {
        val overlay = gizmoOverlay ?: return 0f
        val handle = overlay.handles.firstOrNull { it.axis == axis && it.kind == GizmoHandleKind.RING } ?: return 0f
        val currentMesh = mesh ?: return 0f
        val camera = cameraSnapshot(currentMesh)
        val count = handle.points.size / 3
        if (count < 2) return 0f
        val first = MeshPicker.project(printer, camera, handle.points[0], handle.points[1], handle.points[2]) ?: return 0f
        val oppositeIndex = count / 2
        val opposite = MeshPicker.project(
            printer,
            camera,
            handle.points[oppositeIndex * 3],
            handle.points[oppositeIndex * 3 + 1],
            handle.points[oppositeIndex * 3 + 2],
        ) ?: return 0f
        val centreX = (first[0] + opposite[0]) / 2f
        val centreY = (first[1] + opposite[1]) / 2f
        val radiusPx = kotlin.math.sqrt(
            (opposite[0] - first[0]) * (opposite[0] - first[0]) +
                (opposite[1] - first[1]) * (opposite[1] - first[1]),
        ) / 2f
        return GizmoDrag.ringDegrees(
            deltaXPx = deltaXPx,
            deltaYPx = deltaYPx,
            centreX = centreX,
            centreY = centreY,
            touchX = touchX,
            touchY = touchY,
            radiusPx = radiusPx,
        )
    }

    fun resetCamera() {
        yaw = DEFAULT_YAW
        pitch = DEFAULT_PITCH
        zoom = DEFAULT_ZOOM
        panX = 0f
        panY = 0f
    }

    fun setOrientation(orientation: ViewerOrientation) {
        yaw = wrapDegrees(orientation.yawDegrees)
        pitch = wrapDegrees(orientation.pitchDegrees)
    }

    /** `cameraDistance()` re-derives the fit, so expose it as the shared value. */
    fun currentDistanceMm(): Float = cameraDistance()

    /**
     * Solves for the zoom that puts the eye at [target] millimetres out.
     *
     * Distance falls as zoom rises, so a bisection over the legal zoom range
     * finds it without anyone having to mirror `SceneCameraFit`'s arithmetic
     * here - a duplicated formula would drift the moment that one changed.
     */
    fun setDistanceMm(target: Float) {
        if (!target.isFinite() || target <= 0f) return
        var low = MIN_ZOOM
        var high = MAX_ZOOM
        repeat(24) {
            val mid = (low + high) / 2f
            zoom = mid
            if (cameraDistance() > target) low = mid else high = mid
        }
        zoom = ((low + high) / 2f).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.055f, 0.065f, 0.08f, 1f)
        GLES20.glClearDepthf(1f)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDepthFunc(GLES20.GL_LESS)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        meshProgram = createGlProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
        lineProgram = createGlProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        val widthRange = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, widthRange, 0)
        maxLineWidth = widthRange[1].takeIf { it.isFinite() && it > 0f } ?: 1f
        // A new GL context invalidates old VBO ids: those buffers died with the
        // previous context, so the ids are forgotten rather than deleted - the
        // names are free to be handed to unrelated objects in this one.
        meshVbo = 0
        colorVbo = 0
        uploadedMesh = null
        colorUploaded = false
        regionProgram = 0
        regionVbo = 0
        regionColorVbo = 0
        regionUploaded = false
        buildGrid()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = max(width, 1)
        viewportHeight = max(height, 1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val fit = sceneFit(aspect)
        val distance = fit.distance

        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW_DEGREES, aspect, fit.nearPlane, fit.farPlane)
        Matrix.setLookAtM(view, 0, 0f, -distance, distance * 0.62f, 0f, 0f, 0f, 0f, 0f, 1f)
        Matrix.translateM(view, 0, panX, panY, 0f)

        Matrix.setIdentityM(scene, 0)
        Matrix.rotateM(scene, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(scene, 0, yaw, 0f, 0f, 1f)
        // In plate coordinates, so the offset turns with the model it moves.
        Matrix.translateM(scene, 0, dragOffsetX, dragOffsetY, dragOffsetZ)
        // The dragged transform, previewed about the model's base centre.
        Matrix.translateM(scene, 0, previewPivotX, previewPivotY, previewPivotZ)
        previewAxis?.let { axis ->
            when (axis) {
                ModelPlacement.Axis.X -> Matrix.rotateM(scene, 0, previewDegrees, 1f, 0f, 0f)
                ModelPlacement.Axis.Y -> Matrix.rotateM(scene, 0, previewDegrees, 0f, 1f, 0f)
                ModelPlacement.Axis.Z -> Matrix.rotateM(scene, 0, previewDegrees, 0f, 0f, 1f)
            }
        }
        if (previewScale != 1f) Matrix.scaleM(scene, 0, previewScale, previewScale, previewScale)
        Matrix.translateM(scene, 0, -previewPivotX, -previewPivotY, -previewPivotZ)
        Matrix.translateM(scene, 0, -fit.centerX, -fit.centerY, -fit.centerZ)

        drawGrid()
        drawMesh()
        drawAxisTriad()
        drawAnnotation()
        drawGizmo()
    }

    /**
     * Draws the annotation overlay without depth testing.
     *
     * Annotation is an overlay on the model, not part of it: a point the user
     * placed on the far side of the mesh must still be visible, otherwise
     * orbiting to judge its depth would make it disappear.
     */
    private fun drawAnnotation() {
        val overlay = annotationOverlay ?: return
        val buffer = annotationBuffer ?: return
        if (overlay.isEmpty) return

        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        GLES20.glUseProgram(lineProgram)
        val position = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        val matrix = GLES20.glGetUniformLocation(lineProgram, "uMvpMatrix")
        val color = GLES20.glGetUniformLocation(lineProgram, "uColor")
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 3 * 4, buffer)
        GLES20.glLineWidth(overlay.thicknessPx.coerceIn(1f, maxLineWidth))
        if (overlay.lineVertexCount > 0) {
            GLES20.glUniform4f(color, ANNOTATION_COLOR[0], ANNOTATION_COLOR[1], ANNOTATION_COLOR[2], 1f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, overlay.lineVertexCount)
        }
        if (overlay.markerVertexCount > 0) {
            GLES20.glUniform4f(color, ANNOTATION_MARKER_COLOR[0], ANNOTATION_MARKER_COLOR[1], ANNOTATION_MARKER_COLOR[2], 1f)
            GLES20.glDrawArrays(GLES20.GL_LINES, overlay.lineVertexCount, overlay.markerVertexCount)
        }
        GLES20.glLineWidth(1f)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /**
     * Which annotation handle a screen point is on, if any.
     *
     * Runs on the GL thread where the camera is consistent. Handles are drawn at
     * a fixed world size, so their touch target is a fixed pixel radius rather
     * than a projected size - a marker far from the camera is small on screen
     * but must still be grabbable.
     */
    fun annotationHandleAt(screenX: Float, screenY: Float, radiusPx: Float): SegmentEnd? {
        val currentMesh = mesh ?: return null
        val overlay = annotationOverlay ?: return null
        if (overlay.handles.isEmpty()) return null
        val camera = cameraSnapshot(currentMesh)
        var best: SegmentEnd? = null
        var bestDistance = radiusPx
        for ((end, position) in overlay.handles) {
            val screen = MeshPicker.project(printer, camera, position.x, position.y, position.z)
                ?: continue
            val dx = screen[0] - screenX
            val dy = screen[1] - screenY
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = end
            }
        }
        return best
    }

    /** Replaces the overlay geometry; null clears it. */
    fun setAnnotationOverlay(value: AnnotationOverlay?) {
        annotationOverlay = value
        val vertices = value?.vertices
        if (vertices == null || vertices.isEmpty()) {
            annotationBuffer = null
            return
        }
        val direct = java.nio.ByteBuffer
            .allocateDirect(vertices.size * Float.SIZE_BYTES)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        direct.put(vertices)
        direct.position(0)
        annotationBuffer = direct
    }

    /** Replaces the transform gizmo geometry; null clears it. */
    fun setGizmo(value: GizmoOverlay?) {
        gizmoOverlay = value
        val longest = value?.groups.orEmpty().maxOfOrNull { it.vertices.size } ?: 0
        gizmoRibbon = if (longest == 0) {
            null
        } else {
            java.nio.ByteBuffer
                .allocateDirect(longest * 2 * Float.SIZE_BYTES)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
    }

    /**
     * The transform the finger is dragging, previewed without touching the model:
     * [degrees] about [axis] and a uniform [scale], both about [pivot].
     */
    fun setPreviewTransform(pivot: Point3?, axis: ModelPlacement.Axis?, degrees: Float, scale: Float) {
        previewPivotX = pivot?.x ?: 0f
        previewPivotY = pivot?.y ?: 0f
        previewPivotZ = pivot?.z ?: 0f
        previewAxis = axis
        previewDegrees = if (degrees.isFinite()) degrees else 0f
        previewScale = if (scale.isFinite() && scale > 0f) scale else 1f
    }

    private fun drawGizmo() {
        val overlay = gizmoOverlay ?: return
        val ribbon = gizmoRibbon ?: return
        if (overlay.isEmpty) return

        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        GLES20.glUseProgram(lineProgram)
        val position = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        val matrix = GLES20.glGetUniformLocation(lineProgram, "uMvpMatrix")
        val color = GLES20.glGetUniformLocation(lineProgram, "uColor")
        // The ribbon is built in NDC, so it goes through the identity.
        GLES20.glUniformMatrix4fv(matrix, 1, false, identityMatrix, 0)

        // Over the model, not inside it: a handle on the far side still has to be
        // visible and grabbable, which is the whole point of showing it.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnableVertexAttribArray(position)
        overlay.groups.forEach { group ->
            val written = expandRibbon(group.vertices, ribbon)
            if (written == 0) return@forEach
            GLES20.glUniform4f(color, group.color[0], group.color[1], group.color[2], 1f)
            ribbon.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 3 * 4, ribbon)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, written)
        }
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /**
     * Turns a group's line pairs into quads of a fixed pixel width.
     *
     * Widths are measured in pixels and the terms are built in NDC, so a handle
     * looks the same width on any screen and at any zoom. Returns the vertex
     * count written, or 0 when nothing was in front of the camera.
     */
    private fun expandRibbon(vertices: FloatArray, target: FloatBuffer): Int {
        val halfWidthPx = GIZMO_LINE_WIDTH_PX / 2f
        val halfWidthNdcX = halfWidthPx / (viewportWidth / 2f)
        val halfWidthNdcY = halfWidthPx / (viewportHeight / 2f)
        target.clear()
        var written = 0
        var index = 0
        while (index + 5 < vertices.size) {
            val hasStart = projectInto(vertices[index], vertices[index + 1], vertices[index + 2], ndcStart)
            val hasEnd = projectInto(vertices[index + 3], vertices[index + 4], vertices[index + 5], ndcEnd)
            index += 6
            if (!hasStart || !hasEnd) continue
            // Direction in pixels, so the ribbon is square whatever the aspect is.
            val directionX = (ndcEnd[0] - ndcStart[0]) * viewportWidth / 2f
            val directionY = (ndcEnd[1] - ndcStart[1]) * viewportHeight / 2f
            val length = kotlin.math.sqrt(directionX * directionX + directionY * directionY)
            if (!length.isFinite() || length < 1e-3f) continue
            val offsetX = -directionY / length * halfWidthNdcX
            val offsetY = directionX / length * halfWidthNdcY
            target.put(ndcStart[0] + offsetX); target.put(ndcStart[1] + offsetY); target.put(ndcStart[2])
            target.put(ndcEnd[0] + offsetX); target.put(ndcEnd[1] + offsetY); target.put(ndcEnd[2])
            target.put(ndcEnd[0] - offsetX); target.put(ndcEnd[1] - offsetY); target.put(ndcEnd[2])
            target.put(ndcStart[0] + offsetX); target.put(ndcStart[1] + offsetY); target.put(ndcStart[2])
            target.put(ndcEnd[0] - offsetX); target.put(ndcEnd[1] - offsetY); target.put(ndcEnd[2])
            target.put(ndcStart[0] - offsetX); target.put(ndcStart[1] - offsetY); target.put(ndcStart[2])
            written += 6
        }
        return written
    }

    /** Plate point to NDC through the current matrix; false when behind the eye. */
    private fun projectInto(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val clipX = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
        val clipY = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
        val clipZ = mvp[2] * x + mvp[6] * y + mvp[10] * z + mvp[14]
        val clipW = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
        if (!clipW.isFinite() || clipW <= 1e-4f) return false
        out[0] = clipX / clipW
        out[1] = clipY / clipW
        out[2] = clipZ / clipW
        return true
    }

    private fun rebuildColorBuffer() {
        val currentMesh = mesh ?: run {
            paintColors = null
            colorUploaded = false
            return
        }
        // A uniform base colour needs no buffer: the shader constant path in
        // drawMesh covers it, keeping dense meshes off the direct-memory heap
        // until painting, a boundary condition or a result tint needs colours.
        val overlay = smartInfillOverlay
        val overlayEmpty = overlay == null || overlay.isEmpty
        if (!paintActive && paintState.isEmpty && overlayEmpty) {
            paintColors = null
            colorUploaded = false
            return
        }
        val palette = colorPalette(overlay)
        var buffer = paintColors
        if (buffer == null ||
            buffer.triangleCount != currentMesh.triangleCount ||
            // Contents, not just the slot count: a new run with the same number of
            // bins still repaints every region.
            !buffer.hasPalette(palette)
        ) {
            buffer = PaintColorBuffer(
                triangleCount = currentMesh.triangleCount,
                palette = palette,
            )
            paintColors = buffer
            // A fresh buffer has no GPU-side copy yet, so force a full upload.
            colorUploaded = false
        }
        // Only triangles whose classification changed are rewritten.
        buffer.resync(paintState)
        buffer.resyncOverlay(
            supports = overlay?.supports ?: EMPTY_TRIANGLES,
            loads = overlay?.loads ?: EMPTY_TRIANGLES,
            active = overlay?.active ?: EMPTY_TRIANGLES,
            // Once the region shells are drawn they carry the densities, and a
            // surface tint underneath them reads as a blotchy mess on top of the
            // shading. The picked supports, loads and armed surface stay tinted.
            regions = if (overlay != null && overlay.volumes.isNotEmpty()) {
                EMPTY_TRIANGLES
            } else {
                overlay?.regions ?: EMPTY_TRIANGLES
            },
        )
    }

    /**
     * The colour of every buffer slot: the fixed roles, then one per density bin
     * of the result tint, from the viewer's own ramp.
     */
    private fun colorPalette(overlay: SmartInfillOverlay?): Array<FloatArray> {
        val bins = overlay?.binDensities ?: EMPTY_DENSITIES
        val palette = ArrayList<FloatArray>(FIXED_SLOTS + bins.size)
        palette += BASE_COLOR
        palette += ENFORCER_COLOR
        palette += BLOCKER_COLOR
        palette += SUPPORT_COLOR
        palette += LOAD_COLOR
        palette += ACTIVE_PICK_COLOR
        for (density in bins) palette += DensityRamp.color(density)
        return palette.toTypedArray()
    }

    private fun cameraDistance(): Float = sceneFit(
        viewportWidth.toFloat() / max(viewportHeight, 1).toFloat(),
    ).distance

    private fun sceneFit(aspect: Float): SceneCameraFit.Fit = SceneCameraFit.calculate(
        printer = printer,
        meshBounds = mesh?.bounds,
        aspect = aspect.coerceAtLeast(0.01f),
        zoom = zoom,
        verticalFieldOfViewDegrees = FIELD_OF_VIEW_DEGREES,
    )

    private fun drawGrid() {
        val buffer = gridBuffer ?: return
        GLES20.glUseProgram(lineProgram)
        val position = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        val matrix = GLES20.glGetUniformLocation(lineProgram, "uMvpMatrix")
        val color = GLES20.glGetUniformLocation(lineProgram, "uColor")

        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        buffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 3 * 4, buffer)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glUniform4f(color, 0.31f, 0.36f, 0.43f, 1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridVertexCount)
        GLES20.glDisableVertexAttribArray(position)
    }

    /**
     * Uploads the interleaved mesh to a vertex buffer object exactly once per
     * model: afterwards the triangles live in GPU memory and every frame is a
     * pure GPU draw (the same way a game renders a static mesh), instead of
     * re-reading CPU memory through client-side pointers per frame.
     *
     * Whatever the previous model left behind is deleted first, so a placement
     * change costs one mesh-sized buffer at a time rather than one per change.
     */
    private fun ensureMeshUpload(buffer: FloatBuffer) {
        if (meshVbo != 0 && uploadedMesh === mesh) return
        releaseMeshBuffer()
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        meshVbo = ids[0]
        if (meshVbo == 0) return
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshVbo)
        val accepted = uploadMeshBuffer(buffer)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        if (!accepted) {
            // The driver refused the allocation, so the buffer holds nothing:
            // drawing from it would blank the model. Dropping the id sends the
            // next frame back to the CPU-side copy, which still renders, and the
            // upload is retried from there.
            releaseMeshBuffer()
            return
        }
        uploadedMesh = mesh
    }

    /**
     * Uploads [buffer] and reports whether the driver accepted it.
     *
     * glBufferData is the call that fails once a mesh outgrows the memory the
     * driver will hand out, and a failed call is invisible in the id alone, so
     * the error flag has to be read back.
     */
    private fun uploadMeshBuffer(buffer: FloatBuffer): Boolean {
        // Draining what earlier frame work left behind keeps the check below
        // about this upload alone.
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            // The error flag is a queue: one glGetError clears one entry.
        }
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            buffer.remaining() * Float.SIZE_BYTES,
            buffer,
            GLES20.GL_STATIC_DRAW,
        )
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    /**
     * Deletes every buffer this renderer owns.
     *
     * The ids name objects inside one GL context, so this has to run on the GL
     * thread while that context is current; see [ModelSurfaceView.surfaceDestroyed]
     * for the teardown path.
     */
    fun releaseGpuBuffers() {
        releaseMeshBuffer()
        if (colorVbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(colorVbo), 0)
            colorVbo = 0
        }
        colorUploaded = false
        if (regionVbo != 0 || regionColorVbo != 0) {
            GLES20.glDeleteBuffers(2, intArrayOf(regionVbo, regionColorVbo), 0)
            regionVbo = 0
            regionColorVbo = 0
        }
        regionUploaded = false
    }

    /** Deletes the mesh buffer and forgets which model it held. */
    private fun releaseMeshBuffer() {
        if (meshVbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(meshVbo), 0)
            meshVbo = 0
        }
        uploadedMesh = null
    }

    private fun drawMesh() {
        val currentMesh = mesh ?: return
        val buffer = meshBuffer ?: return

        // ModelPlacement has already written the mesh vertices into final
        // build-plate coordinates. Preserve those coordinates so the viewer,
        // CuraEngine input and exported G-code all show the same placement.
        Matrix.setIdentityM(modelLocal, 0)
        Matrix.multiplyMM(modelMatrix, 0, scene, 0, modelLocal, 0)
        Matrix.multiplyMM(modelView, 0, view, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        GLES20.glUseProgram(meshProgram)
        val position = GLES20.glGetAttribLocation(meshProgram, "aPosition")
        val normal = GLES20.glGetAttribLocation(meshProgram, "aNormal")
        val color = GLES20.glGetAttribLocation(meshProgram, "aColor")
        val mvpLocation = GLES20.glGetUniformLocation(meshProgram, "uMvpMatrix")
        val modelLocation = GLES20.glGetUniformLocation(meshProgram, "uModelMatrix")

        buffer.position(0)
        ensureMeshUpload(buffer)
        val vbo = meshVbo
        if (vbo != 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 6 * 4, 0)
            GLES20.glEnableVertexAttribArray(normal)
            GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 6 * 4, 12)
        } else {
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
            buffer.position(3)
            GLES20.glEnableVertexAttribArray(normal)
            GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
        }

        val colors = paintColors
        if (colors != null) {
            if (colorVbo == 0) {
                val ids = IntArray(1)
                GLES20.glGenBuffers(1, ids, 0)
                colorVbo = ids[0]
            }
            val data = colors.buffer
            if (colorVbo != 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, colorVbo)
                if (!colorUploaded) {
                    // First upload after the buffer was built: send everything once.
                    data.position(0)
                    data.limit(data.capacity())
                    GLES20.glBufferData(
                        GLES20.GL_ARRAY_BUFFER,
                        data.capacity() * Float.SIZE_BYTES,
                        data,
                        GLES20.GL_DYNAMIC_DRAW,
                    )
                    colorUploaded = true
                    colors.takeDirtyRange()
                } else {
                    // Steady state: send only the float range the last edit touched.
                    val dirty = colors.takeDirtyRange()
                    if (dirty != null) {
                        val from = dirty.first
                        val length = dirty.last - dirty.first + 1
                        data.position(from)
                        data.limit(from + length)
                        GLES20.glBufferSubData(
                            GLES20.GL_ARRAY_BUFFER,
                            from * Float.SIZE_BYTES,
                            length * Float.SIZE_BYTES,
                            data,
                        )
                        data.limit(data.capacity())
                    }
                }
                GLES20.glEnableVertexAttribArray(color)
                GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 3 * 4, 0)
            } else {
                // No colour VBO (glGenBuffers failed): the pointer has to be a real
                // client address, and GLES reads it as a byte offset while the mesh
                // VBO is still bound, so unbind first.
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                data.position(0)
                GLES20.glEnableVertexAttribArray(color)
                GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 3 * 4, data)
            }
        } else {
            GLES20.glDisableVertexAttribArray(color)
            GLES20.glVertexAttrib3f(color, BASE_COLOR[0], BASE_COLOR[1], BASE_COLOR[2])
        }

        GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(modelLocation, 1, false, modelMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, currentMesh.triangleCount * 3)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glDisableVertexAttribArray(color)

        // The optimized volumes are blended over the model: the part keeps its
        // shading, the density regions read as translucent volumes inside it, and
        // their colours come from the same ramp as the legend.
        drawResultRegions()
    }

    /**
     * Builds the translucent surfaces for the optimized volumes: one triangle
     * buffer per region, each vertex carrying that region's colour from the same
     * ramp the legend uses, so a volume and its legend row always agree. The
     * volumes are the engine's own meshes in the model's coordinates, so the same
     * model matrix places them.
     *
     * Called on the GL thread with the rest of the overlay updates, once per
     * result rather than per frame.
     */
    private fun rebuildRegions() {
        regionPositions = null
        regionColors = null
        regionVertexCount = 0
        regionUploaded = false
        val volumes = smartInfillOverlay?.volumes.orEmpty()
        if (volumes.isEmpty()) return

        val parts = volumes.map { volume ->
            val rgb = DensityRamp.color(volume.densityPercent / 100.0)
            val colors = FloatArray(volume.triangleCount * 3)
            for (triangle in 0 until volume.triangleCount) {
                colors[triangle * 3] = rgb[0]
                colors[triangle * 3 + 1] = rgb[1]
                colors[triangle * 3 + 2] = rgb[2]
            }
            WireframeBuffer.triangles(volume.positions, volume.indices, colors)
        }
        val regions = WireframeBuffer.concat(parts)
        if (regions.isEmpty) return

        regionPositions = floatBuffer(regions.positions)
        regionColors = floatBuffer(regions.colors)
        regionVertexCount = regions.vertexCount
    }

    /**
     * Blends the region volumes over the shaded model.
     *
     * No depth test and no depth writes: the regions of a part are inside it, so a
     * depth-tested draw would hide them behind the model's own surface (that is
     * what a wireframe attempt did on the device). Blending without depth ordering
     * is inexact for overlapping volumes, but it is what makes a nested density
     * view readable, and it is how filaSim's own viewer shows them.
     */
    private fun drawResultRegions() {
        val positions = regionPositions ?: return
        val colors = regionColors ?: return
        if (regionVertexCount == 0) return
        if (regionProgram == 0) {
            regionProgram = createGlProgram(REGION_VERTEX_SHADER, REGION_FRAGMENT_SHADER)
        }

        GLES20.glUseProgram(regionProgram)
        val position = GLES20.glGetAttribLocation(regionProgram, "aPosition")
        val color = GLES20.glGetAttribLocation(regionProgram, "aColor")
        val mvpLocation = GLES20.glGetUniformLocation(regionProgram, "uMvpMatrix")
        val alphaLocation = GLES20.glGetUniformLocation(regionProgram, "uAlpha")

        if (!regionUploaded) {
            if (regionVbo == 0 || regionColorVbo == 0) {
                val ids = IntArray(2)
                GLES20.glGenBuffers(2, ids, 0)
                regionVbo = ids[0]
                regionColorVbo = ids[1]
            }
            positions.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, regionVbo)
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                positions.capacity() * Float.SIZE_BYTES,
                positions,
                GLES20.GL_STATIC_DRAW,
            )
            colors.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, regionColorVbo)
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                colors.capacity() * Float.SIZE_BYTES,
                colors,
                GLES20.GL_STATIC_DRAW,
            )
            regionUploaded = true
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, regionVbo)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 3 * 4, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, regionColorVbo)
        GLES20.glEnableVertexAttribArray(color)
        GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 3 * 4, 0)

        GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
        GLES20.glUniform1f(alphaLocation, REGION_ALPHA)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, regionVertexCount)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
    }

    /**
     * Draws the plate's XYZ marker on the bed's front-left corner.
     *
     * The marker belongs to the scene, not to the screen: depth testing stays on,
     * so the model hides it when the corner is behind the part, and only its arm
     * length is recomputed per frame - from the corner's own depth, so the three
     * arrows keep the same size on screen however far the plate is zoomed.
     */
    private fun drawAxisTriad() {
        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        triadPoint[0] = triadOrigin[0]
        triadPoint[1] = triadOrigin[1]
        triadPoint[2] = triadOrigin[2]
        triadPoint[3] = 1f
        Matrix.multiplyMV(triadCorner, 0, modelView, 0, triadPoint, 0)
        // Behind the camera: nothing to draw, and nothing to divide by.
        if (triadCorner[2] >= -0.01f) return
        if (!Matrix.invertM(triadInverse, 0, modelView, 0)) return
        val camera = floatArrayOf(0f, 0f, 0f, 1f)
        val eyeInPlate = FloatArray(4)
        Matrix.multiplyMV(eyeInPlate, 0, triadInverse, 0, camera, 0)
        triadEye[0] = eyeInPlate[0]
        triadEye[1] = eyeInPlate[1]
        triadEye[2] = eyeInPlate[2]

        val armMm = AxisTriad.armMm(
            viewDepthMm = -triadCorner[2],
            viewportHeightPx = viewportHeight,
            fieldOfViewDegrees = FIELD_OF_VIEW_DEGREES,
            armPx = TRIAD_ARM_DP * density,
        )
        // The camera's up direction in plate coordinates - the row of the
        // plate-to-view matrix that view-space up comes from - so the letters can
        // be squared to the camera rather than to the bed.
        val cameraUp = floatArrayOf(modelView[1], modelView[5], modelView[9])
        val vertices = AxisTriad.vertices(triadOrigin, armMm, triadEye, cameraUp)
        val buffer = triadBuffer ?: floatBuffer(vertices).also { triadBuffer = it }
        buffer.clear()
        buffer.put(vertices)
        buffer.position(0)

        GLES20.glUseProgram(lineProgram)
        val position = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        val matrix = GLES20.glGetUniformLocation(lineProgram, "uMvpMatrix")
        val color = GLES20.glGetUniformLocation(lineProgram, "uColor")
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glLineWidth((TRIAD_STROKE_DP * density).coerceIn(1f, maxLineWidth))
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 3 * 4, buffer)
        AxisTriad.COLORS.forEachIndexed { axis, rgb ->
            GLES20.glUniform4f(color, rgb[0], rgb[1], rgb[2], 1f)
            GLES20.glDrawArrays(
                GLES20.GL_LINES,
                axis * AxisTriad.VERTICES_PER_AXIS,
                AxisTriad.VERTICES_PER_AXIS,
            )
        }
        GLES20.glLineWidth(1f)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun buildGrid() {
        val values = ArrayList<Float>()
        val width = printer.widthMm.toFloat()
        val depth = printer.depthMm.toFloat()
        var x = 0f
        while (x <= width + 0.01f) {
            values += x; values += 0f; values += GRID_Z
            values += x; values += depth; values += GRID_Z
            x += 10f
        }
        var y = 0f
        while (y <= depth + 0.01f) {
            values += 0f; values += y; values += GRID_Z
            values += width; values += y; values += GRID_Z
            y += 10f
        }
        val array = FloatArray(values.size) { values[it] }
        gridBuffer = floatBuffer(array)
        gridVertexCount = array.size / 3
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); position(0) }
    }

    private fun wrapDegrees(value: Float): Float {
        var wrapped = value % 360f
        if (wrapped < -180f) wrapped += 360f
        if (wrapped >= 180f) wrapped -= 360f
        return wrapped
    }

    private companion object {
        const val DEFAULT_YAW = -28f
        const val DEFAULT_PITCH = 58f
        const val DEFAULT_ZOOM = 1f
        const val MIN_ZOOM = 0.08f
        const val MAX_ZOOM = 40f
        const val FIELD_OF_VIEW_DEGREES = 42f

        /** Fat enough to aim a finger at, like the handles it stands for. */
        const val GIZMO_LINE_WIDTH_PX = 8f
        const val GRID_Z = -0.08f

        /** The plate marker: its arm on screen, in dp, and how thick it draws. */
        const val TRIAD_ARM_DP = 27f
        const val TRIAD_STROKE_DP = 3f

        // Eye sits at (0, -distance, 0.62*distance); its true distance to the
        // target is distance * sqrt(1 + 0.62^2).
        const val CAMERA_EYE_DISTANCE_SCALE = 1.17666f

        val BASE_COLOR = floatArrayOf(0.14f, 0.58f, 0.86f)
        val ENFORCER_COLOR = floatArrayOf(0.20f, 0.85f, 0.32f)
        val BLOCKER_COLOR = floatArrayOf(0.90f, 0.25f, 0.22f)

        // Smart Infill boundary conditions: the part rests on the green
        // surfaces and carries the load on the orange ones, and the surface
        // waiting for a tap is yellow so the user can see what is armed.
        val SUPPORT_COLOR = floatArrayOf(0.16f, 0.72f, 0.38f)
        val LOAD_COLOR = floatArrayOf(0.95f, 0.45f, 0.10f)
        val ACTIVE_PICK_COLOR = floatArrayOf(1.00f, 0.84f, 0.16f)

        val EMPTY_TRIANGLES = IntArray(0)

        val EMPTY_DENSITIES = DoubleArray(0)

        /** The fixed palette slots: base, enforcer, blocker, support, load, armed. */
        const val FIXED_SLOTS = 6
val ANNOTATION_COLOR = floatArrayOf(1.00f, 0.76f, 0.22f)
val ANNOTATION_MARKER_COLOR = floatArrayOf(1.00f, 1.00f, 1.00f)

        const val MESH_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uModelMatrix;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vNormal;
            varying vec3 vColor;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vNormal = normalize(mat3(uModelMatrix) * aNormal);
                vColor = aColor;
            }
        """
        const val MESH_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vNormal;
            varying vec3 vColor;
            void main() {
                vec3 normal = normalize(vNormal);
                if (!gl_FrontFacing) {
                    normal = -normal;
                }
                vec3 keyLight = normalize(vec3(0.35, -0.70, 0.62));
                vec3 fillLight = normalize(vec3(-0.55, 0.30, 0.72));
                float key = max(dot(normal, keyLight), 0.0);
                float fill = max(dot(normal, fillLight), 0.0);
                float lighting = 0.28 + key * 0.62 + fill * 0.22;
                gl_FragColor = vec4(vColor * min(lighting, 1.12), 1.0);
            }
        """
        const val LINE_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            attribute vec3 aPosition;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
            }
        """
        const val LINE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """

        // The optimized volumes as translucent surfaces: per-vertex colour and one
        // alpha for the whole draw, blended over the model with depth testing off.
        const val REGION_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            attribute vec3 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vColor = aColor;
            }
        """
        const val REGION_FRAGMENT_SHADER = """
            precision mediump float;
            uniform float uAlpha;
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, uAlpha);
            }
        """

        /** How strongly a region volume tints the part; the panel's legend matches. */
        const val REGION_ALPHA = 0.40f
    }
}
