package com.tomppi.enderslicer.viewer

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Looper
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A cut the model view previews, in build-plate millimetres.
 *
 * Everything at or beyond [offsetMm] along [axis] is discarded by the model
 * shader, so the part reads as cut open without one vertex being rebuilt: the
 * slider only changes this value and the mesh on the GPU is untouched. The
 * cross-section is left open exactly as the CPU clipper [BedClipper] leaves it,
 * so what shows through the cut is the inside of the part - the same hollow
 * cross-section the layer view draws.
 *
 * It is a preview only. The geometry the engines are handed comes from
 * [BedClipper] when the user commits the split.
 */
data class ModelCutPlane(
    val axis: ModelPlacement.Axis,
    val offsetMm: Float,
) {
    /** The plane as the shader takes it: unit normal first, then the offset along it. */
    fun planeFloats(): FloatArray = when (axis) {
        ModelPlacement.Axis.X -> floatArrayOf(1f, 0f, 0f, offsetMm)
        ModelPlacement.Axis.Y -> floatArrayOf(0f, 1f, 0f, offsetMm)
        ModelPlacement.Axis.Z -> floatArrayOf(0f, 0f, 1f, offsetMm)
    }
}

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

    /** True once this gesture's face pick has been reported, so a drag reports only the first. */
    private var facePickReported = false

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
            // Through the GL thread, like every other renderer change: writing the
            // gizmo from the UI thread left the GL thread reading its own copy, so
            // Done closed the menu while the ribbons stayed on screen.
            refreshGizmo()
        }

    /**
     * The gizmo the snap tool draws while a joint is being moved: the plate's
     * own move arrows, standing on the hook.
     *
     * It takes precedence over [gizmoMode]'s while it is up, and the two are
     * never both wanted - one set of handles is aimed at at a time - so the
     * joint's own goes on top and its owner clears it when the move ends.
     */
    var jointGizmo: GizmoOverlay? = null
        set(value) {
            field = value
            refreshGizmo()
        }

    /**
     * True while the snap panel's Move is live: the arrows stand on the selected
     * joint and a finger that lands ON one of them moves the joint along that
     * axis alone.
     *
     * Exactly the model's own move gizmo: only a handle starts a move, so a drag
     * that began on the model or the plate is still the camera's and the part
     * never moves by accident. Two fingers orbit, pan and zoom as always.
     */
    var jointMoveActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                draggingJointAxis = null
                jointAxisMillimetres = 0f
                modelRenderer.setGizmoActiveAxis(null)
            }
        }

    /** An arrow of the joint's own gizmo went down: that axis is live from here. */
    var onJointAxisGrab: ((ModelPlacement.Axis) -> Unit)? = null

    /** The same arrow, held: millimetres along it since the grab. */
    var onJointAxisDrag: ((ModelPlacement.Axis, Float) -> Unit)? = null

    /** The arrow came up: the axis goes dark and the move is over. */
    var onJointAxisRelease: (() -> Unit)? = null

    /** The arrow being held, and how far its own axis has carried it. */
    private var draggingJointAxis: ModelPlacement.Axis? = null
    private var jointAxisMillimetres = 0f
    private var jointAxisPivotX = 0f
    private var jointAxisPivotY = 0f
    private var jointAxisPivotZ = 0f
    private var jointAxisLengthMm = 0f

    /** A long press that landed on the model: the app puts the three modes up. */
    var onTransformRequested: ((Float, Float) -> Unit)? = null

    /** A tap on a part: the index in the list the renderer was last given. */
    var onModelPicked: ((Int) -> Unit)? = null

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
     * True while a handle is being dragged. The menu steps aside for the duration:
     * a ring or an arrow is being aimed at, and the menu is in the way of the
     * thing it belongs to.
     */
    var onHandleDragActive: ((Boolean) -> Unit)? = null

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
    private var rotateTangentX = 0f
    private var rotateTangentY = 0f
    private var rotateRadiusPx = 0f
    private var draggingAxisMove = false
    private var axisMoveAxis: ModelPlacement.Axis = ModelPlacement.Axis.X
    private var axisMoveMillimetres = 0f
    /**
     * Every mesh on the plate, in the host's order.
     *
     * The plate held exactly one until multi-object printing: the tools, the
     * gizmo and the annotations still act on the selected one, while the camera
     * frames them all.
     */
    private var currentMeshes: List<StlMesh> = emptyList()

    /** Which object in [currentMeshes] the tools act on. */
    private var currentSelectedIndex = 0

    /** The mesh those tools act on, or null when the plate is empty. */
    private val currentMesh: StlMesh?
        get() = currentMeshes.getOrNull(currentSelectedIndex)

    /** Invoked on the UI thread with the model triangle hit by a paint stroke. */
    var onPaintHit: ((MeshPicker.Hit) -> Unit)? = null

    /** Invoked on the UI thread with the model triangle a tap picked. */
    var onSurfacePick: ((MeshPicker.Hit) -> Unit)? = null

    /**
     * When true, a tap on the model places a snap joint: the handler is given
     * the point it landed on, already in plate coordinates.
     *
     * Owns taps the way [annotationActive] does, and only taps: a drag still
     * orbits, because the joint's position is a point, not a stroke.
     */
    var jointPickActive: Boolean = false

    /** Invoked on the UI thread with the model point a joint placement tap hit. */
    var onJointPick: ((MeshPicker.Hit) -> Unit)? = null

    /**
     * When true, a DRAG that begins on the model is a cut stroke: one cut where
     * the finger went down, whose slit is perpendicular to the finger's own
     * travel, and a tap still cuts where it lands.
     *
     * Set beside [jointPickActive] and only while the panel has "Cut wall" armed.
     * The direction of the stroke is read on lift; the point is the pick the
     * touch went down with, so a stroke that began beside the model is handed
     * back to the camera rather than cutting anything.
     */
    var jointStrokeActive: Boolean = false

    /** Invoked on the UI thread with a cut stroke: the model point it began on and its direction. */
    var onJointStroke: ((MeshPicker.Hit, Vec3) -> Unit)? = null

    /** True between a cut stroke's touch-down and the picker's answer. */
    private var strokeActive = false
    private var strokeMoved = false
    private var strokeFirstHit: MeshPicker.Hit? = null
    private var strokePendingUp = false
    private var strokeDownX = 0f
    private var strokeDownY = 0f
    private var strokeUpX = 0f
    private var strokeUpY = 0f
    private var strokeAccumDx = 0f
    private var strokeAccumDy = 0f

    /**
     * When true, the finger paints the surface: a sample lands where it goes
     * down and another at every move, until it lifts. This is filaSim's brush,
     * and the same shape the support-paint brush already has.
     *
     * Deliberately separate from [paintMode]: they are two different paints, and
     * a drag under either one is a stroke rather than an orbit.
     */
    var surfacePickActive: Boolean = false

    /**
     * True when a surface pick is a face selection rather than a Smart Infill brush stroke.
     *
     * Both use [onSurfacePick], but they want different gestures: a stroke paints on every
     * sample it passes over, and a face pick is the one point the user tapped. With this set the
     * pick is reported once per finger-down, so a drag across the model cannot fire a selection
     * per sample at the engine.
     */
    var faceSelectActive: Boolean = false

    /**
     * The finger came up after a stroke. The host commits what the samples
     * collected here rather than on every one of them.
     */
    var onSurfacePickEnd: (() -> Unit)? = null

    /**
     * The stroke's first sample decides whether the gesture paints or moves the
     * camera: on the model it is a brush, beside it the camera, which is the
     * hover gate filaSim uses. The answer comes from a pick off the UI thread, so
     * the movement made while it is in flight is held and replayed as an orbit if
     * the answer is "not on the model".
     */
    private var brushDeciding = false
    private var brushDragging = false
    private var brushAccumDx = 0f
    private var brushAccumDy = 0f

    /** True while the queued coordinate is the stroke's deciding probe. */
    private val brushProbePending = java.util.concurrent.atomic.AtomicBoolean(false)

    private val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop

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

    /**
     * Hands the viewer the whole plate, and which object the tools act on.
     *
     * Every mesh arrives already placed - in bed coordinates - so drawing the
     * plate is drawing their vertices end to end and no per-object matrix is
     * involved. The selected object is the one the brush, the gizmo and the
     * annotations land on, and the only one drawn with the selection tint, so
     * which object is being worked on is never a guess.
     */
    fun setMeshes(meshes: List<StlMesh>, selectedIndex: Int) {
        val selected = selectedIndex.coerceIn(0, max(meshes.size - 1, 0))
        currentMeshes = meshes
        currentSelectedIndex = selected
        // The committed transform is in the new vertices, so the preview standing
        // in for it ends here - exactly when the geometry arrives. That timing is
        // what keeps the model from snapping back under the finger on release.
        modelRenderer.setDragOffset(0f, 0f, 0f)
        modelRenderer.setPreviewTransform(null, null, 0f, 1f)
        queueEvent { modelRenderer.setMeshes(meshes, selected) }
        // The gizmo is sized from the model, so a new model gets a new one.
        refreshGizmo()
        // setMeshes resets the camera on the GL thread for a new model.
        queueEvent { notifyOrientation() }
        requestRender()
    }

    /** One model on the plate: what every single-model caller still means. */
    fun setMesh(mesh: StlMesh?) {
        setMeshes(if (mesh == null) emptyList() else listOf(mesh), 0)
    }

    /**
     * Previews a cut on one object of the plate, or clears it with null.
     *
     * Only the object at [objectIndex] is cut: the plate's other parts are not
     * being split and must go on drawing whole.
     */
    fun setCutPlane(plane: ModelCutPlane?, objectIndex: Int) {
        modelRenderer.setCutPlane(plane, objectIndex)
        requestRender()
    }

    /**
     * Stands a tool's boolean result in for a plate object's own mesh, or
     * clears it with an empty map.
     *
     * The plate's model list is untouched: what is previewed is display only,
     * and the meshes the engines would be handed are still the ones on the
     * plate until the result is committed.
     */
    fun setPreviewObjects(previews: Map<Int, StlMesh>) {
        modelRenderer.setPreviewObjects(previews)
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

    fun setPaintStates(paint: List<SupportPaintState>) {
        queueEvent { modelRenderer.setPaintStates(paint) }
        requestRender()
    }

    /** One state for the whole plate; kept for callers that predate per-object paint. */
    fun setPaintState(paint: SupportPaintState) {
        setPaintStates(listOf(paint))
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

    /**
     * Tints one face of the selected CAD object, as scene triangle indices.
     *
     * The plate draws the tint itself, in the mesh shader, from a per-triangle buffer that is
     * separate from the support paint's: selecting writes nothing a paint stroke reads, and a
     * paint stroke tints nothing. Null or an empty array clears it. The camera is not touched -
     * a face is not a screen position, so orbiting keeps the tint on the face.
     */
    fun setFaceTint(triangles: IntArray?) {
        val copy = triangles?.copyOf()
        queueEvent { modelRenderer.setFaceTint(copy) }
        requestRender()
    }

    /** The view's current orientation, or the plate's default before the first gesture. */
    fun plateOrientation(): ViewerOrientation = modelRenderer.orientation

    /**
     * Renders the plate at the camera the user is looking through, into an offscreen frame.
     *
     * Blocking, on purpose: the caller is the agent's tool thread, and the picture that answers
     * has to be the scene the user sees now - the selected face's tint included - not whatever
     * the on-screen surface happened to hold on some frame (a resizing plate, a sheet over it, a
     * frame drawn before the tint arrived). The renderer draws the same scene through the same
     * camera into its own frame buffer, so nothing about the window can change what the agent
     * is shown. Never call it on the UI or GL thread - a blocking wait there would freeze the
     * frame that produces it. Returns null when there is no surface to render into (another
     * tab, the app in the background): there is no honest picture to send then.
     */
    fun capturePlate(timeoutMillis: Long = CAPTURE_TIMEOUT_MILLIS): Bitmap? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        if (!isAttachedToWindow || !isShown) return null
        val pending = CompletableFuture<Bitmap?>()
        post { queueEvent { pending.complete(modelRenderer.renderOffscreen()) } }
        return try {
            pending.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            null
        }
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
                if (jointMoveActive) {
                    // The joint's own mode: only one of its arrows starts a
                    // move, and only that axis moves. A grab that lands on no
                    // arrow is the camera's, exactly as it is under the model's
                    // own move gizmo - the part never moves by accident.
                    val handle = modelRenderer.gizmoHitAt(event.x, event.y, GizmoDrag.TOUCH_RADIUS_PX)
                    if (handle?.kind == GizmoHandleKind.ARROW) {
                        // The arrow's own shaft: its first sample is the pivot the
                        // drag is measured from and its last is the tip, so the
                        // finger's pixels turn into millimetres along THAT arrow.
                        val points = modelRenderer.gizmoArrowSamples(handle.axis)
                        val count = points?.size?.div(3) ?: 0
                        if (points != null && count >= 2) {
                            draggingJointAxis = handle.axis
                            jointAxisMillimetres = 0f
                            jointAxisPivotX = points[0]
                            jointAxisPivotY = points[1]
                            jointAxisPivotZ = points[2]
                            val last = (count - 1) * 3
                            jointAxisLengthMm = kotlin.math.sqrt(
                                (points[last] - points[0]) * (points[last] - points[0]) +
                                    (points[last + 1] - points[1]) * (points[last + 1] - points[1]) +
                                    (points[last + 2] - points[2]) * (points[last + 2] - points[2]),
                            )
                            modelRenderer.setGizmoActiveAxis(handle.axis)
                            onJointAxisGrab?.invoke(handle.axis)
                        }
                    }
                }
                if (draggingJointAxis == null && jointPickActive) {
                    // Where the joint goes is the point the tap lands on, and the
                    // picker runs off the UI thread like every other probe here:
                    // the coordinates are copied, not the event.
                    pendingPaintCoordinates.set(floatArrayOf(event.x, event.y))
                    schedulePaintPick()
                    if (jointStrokeActive) {
                        // A cut stroke: whether this is the tap that cuts one
                        // place or the drag that draws a direction is only known
                        // when it ends, so hold the movement until the pick has
                        // answered whether the gesture began on the model.
                        strokeActive = true
                        strokeMoved = false
                        strokeFirstHit = null
                        strokePendingUp = false
                        strokeDownX = event.x
                        strokeDownY = event.y
                        strokeUpX = event.x
                        strokeUpY = event.y
                        strokeAccumDx = 0f
                        strokeAccumDy = 0f
                    }
                }
                if (surfacePickActive) {
                    // Ask whether this gesture began on the model. Until the answer
                    // arrives the touch is held rather than guessed at.
                    facePickReported = false
                    brushDeciding = true
                    brushDragging = false
                    brushAccumDx = 0f
                    brushAccumDy = 0f
                    brushProbePending.set(true)
                    pendingPaintCoordinates.set(floatArrayOf(event.x, event.y))
                    schedulePaintPick()
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
                            val reference = modelRenderer.ringDragReference(handle.axis, event.x, event.y)
                            rotateTangentX = reference?.get(0) ?: 0f
                            rotateTangentY = reference?.get(1) ?: 0f
                            rotateRadiusPx = reference?.get(2) ?: 0f
                            modelRenderer.setGizmoActiveAxis(handle.axis)
                            onHandleDragActive?.invoke(true)
                        }
                        GizmoHandleKind.ARROW -> {
                            draggingAxisMove = true
                            axisMoveAxis = handle.axis
                            axisMoveMillimetres = 0f
                            modelRenderer.setGizmoActiveAxis(handle.axis)
                            onHandleDragActive?.invoke(true)
                        }
                        null -> Unit
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    // Two fingers are a pan, not a cut stroke.
                    if (strokeFirstHit == null) replayStrokeAsOrbit()
                    resetJointStroke()
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
                    if (draggingJointAxis != null) {
                        // The joint is being moved along the arrow it was
                        // grabbed by: the axis is the whole of the gesture, and
                        // the camera stays exactly where it is.
                        val axis = draggingJointAxis!!
                        jointAxisMillimetres += modelRenderer.axisDragMillimetres(
                            axis = axis,
                            deltaXPx = event.x - previousX,
                            deltaYPx = event.y - previousY,
                            pivotX = jointAxisPivotX,
                            pivotY = jointAxisPivotY,
                            pivotZ = jointAxisPivotZ,
                            lengthMm = jointAxisLengthMm,
                        )
                        previousX = event.x
                        previousY = event.y
                        onJointAxisDrag?.invoke(axis, jointAxisMillimetres)
                    } else if (jointStrokeActive && strokeActive) {
                        // A cut stroke owns the movement: the camera does not turn
                        // while the finger is drawing the slit's direction. Until
                        // the pick answers, hold what moved in case the gesture
                        // began beside the model and is the camera's after all.
                        strokeUpX = event.x
                        strokeUpY = event.y
                        if (kotlin.math.abs(event.x - strokeDownX) > touchSlopPx ||
                            kotlin.math.abs(event.y - strokeDownY) > touchSlopPx
                        ) {
                            strokeMoved = true
                        }
                        if (strokeFirstHit == null) {
                            strokeAccumDx += event.x - previousX
                            strokeAccumDy += event.y - previousY
                        }
                        previousX = event.x
                        previousY = event.y
                    } else if (painting || brushDragging) {
                        // A stroke sample. One slot, overwritten: the picker runs
                        // off the UI thread and a slow sample must not queue up a
                        // backlog of stale positions behind it.
                        pendingPaintCoordinates.set(floatArrayOf(event.x, event.y))
                        schedulePaintPick()
                    } else if (brushDeciding) {
                        // Held until the probe answers: replayed as an orbit if the
                        // gesture turned out to be beside the model.
                        brushAccumDx += event.x - previousX
                        brushAccumDy += event.y - previousY
                        previousX = event.x
                        previousY = event.y
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
                        rotateDegrees += GizmoDrag.ringDegrees(
                            deltaXPx = dx,
                            deltaYPx = dy,
                            tangentXPx = rotateTangentX,
                            tangentYPx = rotateTangentY,
                            radiusPx = rotateRadiusPx,
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
                if (draggingJointAxis != null) {
                    // The arrow is up; the mode stays live until the panel's
                    // Move takes it down, so another axis can be grabbed at once.
                    draggingJointAxis = null
                    jointAxisMillimetres = 0f
                    modelRenderer.setGizmoActiveAxis(null)
                    onJointAxisRelease?.invoke()
                }
                if (jointStrokeActive && strokeActive) {
                    // A tap cuts where it landed; a drag cuts one place with the
                    // slit across the finger's travel. The pick may still be in
                    // flight, so the answer settles it.
                    val hit = strokeFirstHit
                    if (hit != null) {
                        settleJointStroke(hit)
                    } else {
                        strokePendingUp = true
                    }
                }
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
                if (brushDragging && !painting) {
                    // The stroke is over: the host hands the finished selection to
                    // the engine once, here, instead of on every sample.
                    onSurfacePickEnd?.invoke()
                }
                brushDeciding = false
                brushDragging = false
                brushAccumDx = 0f
                brushAccumDy = 0f
                // The preview is deliberately left standing: the commit lands a
                // moment later, and dropping it here would snap the model back to
                // where it started in between. It goes when the new geometry
                // arrives, or through clearDragPreview when nothing arrives.
                if (draggingRotate) {
                    draggingRotate = false
                    val degrees = snapDegrees(rotateDegrees)
                    rotateDegrees = 0f
                    modelRenderer.setGizmoActiveAxis(null)
                    onHandleDragActive?.invoke(false)
                    if (degrees != 0f) onRotateCommitted?.invoke(rotateAxis, degrees)
                }
                if (draggingAxisMove) {
                    draggingAxisMove = false
                    val millimetres = axisMoveMillimetres
                    axisMoveMillimetres = 0f
                    modelRenderer.setGizmoActiveAxis(null)
                    onHandleDragActive?.invoke(false)
                    if (millimetres != 0f) onModelAxisMove?.invoke(axisMoveAxis, millimetres)
                }
                if (draggingModel) {
                    draggingModel = false
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
                if (draggingJointAxis != null) {
                    draggingJointAxis = null
                    jointAxisMillimetres = 0f
                    modelRenderer.setGizmoActiveAxis(null)
                    onJointAxisRelease?.invoke()
                }
                if (strokeFirstHit == null) replayStrokeAsOrbit()
                resetJointStroke()
                if (draggingRotate) {
                    draggingRotate = false
                    rotateDegrees = 0f
                    onHandleDragActive?.invoke(false)
                    modelRenderer.setPreviewTransform(null, null, 0f, 1f)
                    requestRender()
                }
                if (draggingAxisMove) {
                    draggingAxisMove = false
                    axisMoveMillimetres = 0f
                    onHandleDragActive?.invoke(false)
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

    /**
     * Hands the renderer the handles that belong on screen: the joint's own
     * while a joint is being moved, the mode's otherwise. One writer, so the
     * two can never both be drawn and a cleared joint gizmo always puts the
     * mode's own back.
     */
    private fun refreshGizmo() {
        val geometry = jointGizmo ?: gizmoFor(gizmoMode)
        queueEvent { modelRenderer.setGizmo(geometry) }
        requestRender()
    }

    /** The gizmo geometry for a mode, sized from the model on screen. */
    private fun gizmoFor(mode: TransformGizmoMode): GizmoOverlay? {
        val bounds = currentMesh?.bounds ?: return null
        val pivot = pivotFor() ?: return null
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

    /**
     * The point the gizmo is drawn around and the preview turns about: the middle
     * of the model, so rings sit on the part rather than at its feet. The
     * committed placement re-centres and re-bases the mesh after the linear part,
     * so a transform about the middle previews where it will land.
     */
    private fun pivotFor(): Point3? {
        val bounds = currentMesh?.bounds ?: return null
        return Point3(bounds.centerX, bounds.centerY, bounds.centerZ)
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
            // The gizmo belongs to the object being worked on, so long-pressing a
            // neighbour asks for nothing rather than putting handles on a part the
            // finger is not on.
            if (hit != null) post {
                if (hit.objectIndex == currentSelectedIndex) {
                    onTransformRequested?.invoke(screenX, screenY)
                }
            }
        }
    }

    private fun notifyOrientation() {
        val listener = onOrientationChanged ?: return
        post { listener(modelRenderer.orientation) }
    }

    /**
     * Settles what the stroke is: a brush on the model, or the camera beside it.
     *
     * This is filaSim's hover gate - over the part the gesture paints, beside it
     * orbit and pan stay live - and it is what makes a brush usable without a
     * mode to switch out of every time the view needs turning.
     */
    private fun applyBrushProbe(hit: MeshPicker.Hit?) {
        brushDeciding = false
        // The brush paints the part the finger is on, and the host makes that part the selected
        // one as it paints, so a stroke may begin anywhere on the plate. It used to require the
        // selected part, which handed every stroke that began on a neighbour to the camera and
        // left all but the highlighted model unpaintable. Smart Infill is the exception: its
        // report is about the selected object, so a neighbour is not its target.
        val paints = hit != null && (!surfacePickActive || hit.objectIndex == currentSelectedIndex)
        if (paints) {
            brushDragging = true
            // The sample that answered "brush" is a sample like any other: hand it
            // to the handler the later ones use, or the first touch of a stroke is
            // spent on the question and the paint only starts on the next sample.
            // The handler expands the brush off the UI thread, exactly as it does
            // for every later sample, so nothing here waits for that expansion.
            if (surfacePickActive) {
                if (!faceSelectActive || !facePickReported) {
                    facePickReported = true
                    onSurfacePick?.invoke(hit)
                }
            } else {
                onPaintHit?.invoke(hit)
            }
            return
        }
        brushDragging = false
        if (brushAccumDx != 0f || brushAccumDy != 0f) {
            // The movement made while the answer was in flight becomes the orbit
            // it would have been had the touch landed beside the model.
            modelRenderer.rotate(brushAccumDx * 0.35f, brushAccumDy * 0.35f)
            brushAccumDx = 0f
            brushAccumDy = 0f
            notifyOrientation()
            requestRender()
        }
    }

    /**
     * The picker's answer for a cut stroke's first sample: on the model it is the
     * cut's own place, and beside it the gesture was the camera's, so the
     * movement held while the probe ran becomes the orbit it would have been.
     *
     * The answer may arrive after the finger has lifted - the picker runs off the
     * UI thread - so [strokePendingUp] says the lift is waiting for it.
     */
    private fun acceptJointStroke(hit: MeshPicker.Hit?) {
        if (!jointStrokeActive || !strokeActive) return
        if (hit == null) {
            replayStrokeAsOrbit()
            resetJointStroke()
            return
        }
        strokeFirstHit = hit
        if (strokePendingUp) settleJointStroke(hit)
    }

    /** The stroke is over: one cut whose slit runs across the finger's travel, or one tap. */
    private fun settleJointStroke(hit: MeshPicker.Hit) {
        val direction = if (strokeMoved) jointStrokeDirection() else null
        if (direction != null) {
            onJointStroke?.invoke(hit, direction)
        } else {
            // A stroke with no direction left in it, or a tap: the cut lands at
            // the point, with the ring's own automatic direction.
            onJointPick?.invoke(hit)
        }
        resetJointStroke()
    }

    /**
     * The direction of the finger's own travel on the plate, in millimetres. The
     * bed-plane mapping the model drag already reads turns the screen's pixels
     * into plate millimetres, and only the direction of that is used: a slit is
     * perpendicular to the stroke, so its length does not matter.
     */
    private fun jointStrokeDirection(): Vec3? {
        val delta = modelRenderer.dragDeltaMm(strokeUpX - strokeDownX, strokeUpY - strokeDownY)
        val direction = Vec3(delta[0], delta[1], 0f)
        return direction.takeIf { it.isFinite() && it.length() > 1e-4f }
    }

    /** The movement a cut stroke held while the pick was in flight, as the camera's own. */
    private fun replayStrokeAsOrbit() {
        if (strokeAccumDx == 0f && strokeAccumDy == 0f) return
        modelRenderer.rotate(strokeAccumDx * 0.35f, strokeAccumDy * 0.35f)
        strokeAccumDx = 0f
        strokeAccumDy = 0f
        notifyOrientation()
        requestRender()
    }

    private fun resetJointStroke() {
        strokeActive = false
        strokeMoved = false
        strokeFirstHit = null
        strokePendingUp = false
        strokeAccumDx = 0f
        strokeAccumDy = 0f
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
                    val hit = modelRenderer.pickTriangle(coordinates[0], coordinates[1])
                    // The joint is placed where the ray meets the half the user
                    // can actually see, so the cut preview's clip is part of the
                    // pick. Without it the probe answered with a point on the
                    // half standing behind the visible one.
                    val jointPick = jointPickActive
                    val jointStroke = jointStrokeActive
                    // The stroke's first sample answers a question rather than
                    // painting: a miss is an answer here, not a reason to skip.
                    if (brushProbePending.getAndSet(false)) {
                        post { applyBrushProbe(hit) }
                        continue
                    }
                    if (jointPick && jointStroke) {
                        // A cut stroke reads a miss as well as a hit: beside the
                        // model the gesture is the camera's.
                        post { acceptJointStroke(hit) }
                        continue
                    }
                    if (hit == null) continue
                    if (jointPick) {
                        post { onJointPick?.invoke(hit) }
                        continue
                    }
                    // The object index is read here, on the UI thread, so the answer cannot race
                    // a selection change on the GL thread.
                    val picked = hit.objectIndex == currentSelectedIndex
                    post {
                        if (surfacePickActive) {
                            // Smart Infill reports on the selected object only; a face pick is
                            // the tap's own point, reported once however far the finger drags.
                            if (picked && (!faceSelectActive || !facePickReported)) {
                                facePickReported = true
                                onSurfacePick?.invoke(hit)
                            }
                        } else {
                            // Support paint follows the finger: the host paints the object this
                            // hit names and selects it, so one stroke can cross the plate.
                            onPaintHit?.invoke(hit)
                        }
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
        // The published frame names a viewport and matrices of a context that is
        // going away, so a pick that arrives before the next draw misses rather
        // than answering from a camera nobody is looking through any more.
        MeshPicker.clearFrame()
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

        /**
         * A tap on a part makes it the one the tools act on.
         *
         * Only while no other tool owns taps: painting, annotation and Smart Infill surface
         * picking all read a tap as their own, and with a gizmo on screen the tap is aimed at a
         * handle. The pick runs off the UI thread like the others, and a tap on the part that is
         * already selected asks for nothing.
         */
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            // A tap paints nothing, so a paint mode does not own it: tapping the part you want to
            // work on is exactly what a brush needs, and it was the only way to change the target
            // without leaving the paint screen.
            if (!cameraInteractive || annotationActive || jointPickActive ||
                surfacePickActive || gizmoMode != TransformGizmoMode.NONE
            ) {
                return false
            }
            val listener = onModelPicked ?: return false
            // The framework recycles the event as soon as this callback returns, so the probe is
            // given the coordinates rather than the event. Every other pick here copies them for
            // the same reason; reading the event on the executor raced the recycle.
            val screenX = event.x
            val screenY = event.y
            paintPickExecutor.execute {
                val hit = modelRenderer.pickTriangle(screenX, screenY) ?: return@execute
                if (hit.objectIndex != currentSelectedIndex) {
                    post { listener(hit.objectIndex) }
                }
            }
            return false
        }

        override fun onLongPress(event: MotionEvent) {
            // The joint's move owns the handles while it is up: a long press on
            // the model would put the model's own card behind arrows that stand
            // on the hook, which is two gizmos for one finger.
            if (cameraInteractive && paintMode == SupportPaintMode.NONE &&
                !annotationActive && !jointMoveActive && gizmoMode == TransformGizmoMode.NONE
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

    /** Drops a preview the committed transform never replaced, such as a refused move. */
    fun clearDragPreview() {
        dragTotalX = 0f
        dragTotalY = 0f
        axisMoveMillimetres = 0f
        rotateDegrees = 0f
        modelRenderer.setDragOffset(0f, 0f, 0f)
        modelRenderer.setPreviewTransform(null, null, 0f, 1f)
        requestRender()
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

        /** How long a plate capture waits for the frame it asked for, before saying none came. */
        const val CAPTURE_TIMEOUT_MILLIS = 5_000L


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
    /**
     * The whole plate as one mesh: every object's vertices end to end.
     *
     * The meshes are handed in already placed - in bed coordinates - so the
     * renderer needs no per-object matrix, only where each object starts. Written
     * last, after the bookkeeping beside it, so a pick on the executor thread
     * never sees a scene without its owners.
     */
    @Volatile private var mesh: StlMesh? = null

    /** The objects the scene was built from, in the same order. */
    private var objectMeshes: List<StlMesh> = emptyList()

    /** Which object the tools act on: an index into [objectMeshes]. */
    @Volatile private var selectedObject = 0

    /**
     * The cut previewed on one object, or null when the plate is not being cut.
     *
     * A preview only: the shader discards what stands beyond the plane, and the
     * committed geometry is [BedClipper]'s work. Volatile because the UI thread
     * moves the plane while the GL thread draws it.
     */
    @Volatile private var cutPlane: ModelCutPlane? = null

    /** Which object [cutPlane] cuts. */
    @Volatile private var cutObject = -1

    /** Previews a cut on one object of the plate, or clears it with null. */
    fun setCutPlane(plane: ModelCutPlane?, objectIndex: Int) {
        cutPlane = plane
        cutObject = objectIndex
    }

    /**
     * One entry per scene triangle: the object that owns it.
     *
     * Empty for a plate of one object, whose every triangle belongs to object
     * zero: four bytes per triangle on the Java heap is worth more than a lookup
     * that can only answer one thing.
     */
    private var triangleOwners: IntArray = IntArray(0)

    /** Where each object's triangles start inside the scene. */
    private var objectFirstTriangle: IntArray = IntArray(0)

    private var meshBuffer: FloatBuffer? = null

    /**
     * One colour buffer for the whole plate: every object's triangles live in
     * their own slice of it, so paint on a neighbour is drawn too.
     */
    private var paintColors: PaintColorBuffer? = null

    /** The palette slot the selected object's unpainted triangles draw in. */
    private var tintSlot = PaintColorBuffer.BASE_SLOT

    /** The state and base slot each object's slice of [paintColors] was last resynced with. */
    private var syncedStates: Array<SupportPaintState?> = emptyArray()
    private var syncedBaseSlots: IntArray = IntArray(0)

    /** The overlay [paintColors] already carries, so an unchanged one is not rewritten. */
    private var syncedOverlay: SmartInfillOverlay? = null
    private var annotationOverlay: AnnotationOverlay? = null
    private var annotationBuffer: FloatBuffer? = null
    @Volatile private var gizmoOverlay: GizmoOverlay? = null

    // The gizmo is drawn as screen-space ribbons rather than GL lines: glLineWidth
    // is capped at one pixel on many Android drivers, so a handle thick enough to
    // aim at has to be geometry. One scratch buffer is reused for every group.
    @Volatile private var gizmoRibbon: FloatBuffer? = null
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // Preview of a transform being dragged. Rotation and scale happen about the
    // pivot the committed placement uses - the model's base centre - so letting go
    // does not shift the model the finger just positioned.
    @Volatile private var previewPivotX = 0f
    @Volatile private var previewPivotY = 0f
    @Volatile private var previewPivotZ = 0f
    @Volatile private var previewAxis: ModelPlacement.Axis? = null
    @Volatile private var previewDegrees = 0f
    @Volatile private var previewScale = 1f
    @Volatile private var gizmoActiveAxis: ModelPlacement.Axis? = null
    // GPU-side (VBO) copies of the mesh; the model renders from VRAM after a
    // single upload, like a game, instead of re-reading CPU memory per frame.
    private var meshVbo = 0
    private var colorVbo = 0
    private var uploadedMesh: StlMesh? = null
    private var colorUploaded = false

    /**
     * The selected face's tint, one byte per scene vertex: the paint buffer's shape for a
     * different fact. It is a buffer of its own on purpose - selecting a face writes nothing the
     * paint path reads and a paint stroke never tints - and the mesh shader mixes it over the
     * shaded colour, so the tint rides the face through an orbit.
     */
    private var faceTint: SelectionTintBuffer? = null
    private var faceTintVbo = 0
    private var faceTintUploaded: SelectionTintBuffer? = null


    /** One state per object, in the same order as the plate's meshes. */
    private var paintStates: List<SupportPaintState> = emptyList()
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

    // Plate-space preview offset while a finger drags one object: the placement
    // itself is only changed once, when the finger lifts, because a real move
    // re-transforms the whole mesh and writes the workspace snapshot. It belongs
    // to the object the tools act on, and only its vertices carry it - see
    // placeObject.
    @Volatile private var dragOffsetX = 0f
    @Volatile private var dragOffsetY = 0f
    @Volatile private var dragOffsetZ = 0f

    /**
     * Meshes a tool is previewing in place of a plate object's own.
     *
     * The snap fit's live preview: a boolean result replaces the half it was
     * computed from, without the plate's model list changing at all - and
     * because the joint moves both halves, it is a map and not one mesh. The
     * scene's single concatenated buffer cannot carry these: they arrive after
     * the scene was built, and re-concatenating a plate of millions of
     * triangles for a preview is exactly the copy a preview exists to avoid, so
     * each gets a buffer of its own, uploaded once per result.
     */
    @Volatile private var previewObjects: Map<Int, StlMesh> = emptyMap()

    private var previewUploaded: Map<Int, StlMesh> = emptyMap()
    private var previewVbo = 0
    private var previewBuffers: Array<FloatBuffer?> = emptyArray()
    private var previewIndices: IntArray = IntArray(0)

    /** Where each previewed mesh starts inside [previewVbo], in bytes. */
    private var previewSlots: IntArray = IntArray(0)

    /** Replaces the previewed objects; an empty map returns the plate to its own meshes. */
    fun setPreviewObjects(previews: Map<Int, StlMesh>) {
        previewObjects = previews
    }

    /**
     * Fills the face tint from scene triangle indices, or clears it with null.
     *
     * Runs on the GL thread. The buffer is sized to the scene the plate currently draws; a scene
     * that changed since (a repaint) has already cleared it in [setMeshes], so a stale index can
     * never tint a triangle that now belongs to something else.
     */
    fun setFaceTint(triangles: IntArray?) {
        val count = sceneTriangleCount()
        faceTint = if (triangles == null || triangles.isEmpty() || count <= 0) {
            null
        } else {
            SelectionTintBuffer(count).also { it.select(triangles) }
        }
        releaseFaceTintBuffer()
    }

    /** How many triangles the plate's scene holds, or 0 when it is empty. */
    private fun sceneTriangleCount(): Int {
        if (objectMeshes.isEmpty()) return 0
        val last = objectMeshes.size - 1
        return objectFirstTriangle.getOrElse(last) { 0 } + objectMeshes[last].triangleCount
    }

    /**
     * Renders the plate into an offscreen frame buffer at the camera the user is looking
     * through, and returns it as a bitmap.
     *
     * This is a real offscreen render, not a read of the on-screen frame: the same scene is
     * drawn by the same shaders (the selection tint included) through the same camera into a
     * texture of [PLATE_CAPTURE_LONG_SIDE] pixels on its long side, whatever size the surface
     * happens to be. That is what makes the picture the user's view rather than whatever the
     * window looked like on some frame, and it means the plate render cannot depend on the
     * on-screen surface being resized, covered or mid-animation.
     *
     * Runs on the GL thread; returns null when there is no GL context to draw into yet.
     */
    fun renderOffscreen(): Bitmap? {
        if (meshProgram == 0) return null
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        if (!aspect.isFinite() || aspect <= 0f) return null
        val captureWidth: Int
        val captureHeight: Int
        if (aspect >= 1f) {
            captureWidth = PLATE_CAPTURE_LONG_SIDE
            captureHeight = (PLATE_CAPTURE_LONG_SIDE / aspect).toInt().coerceAtLeast(1)
        } else {
            captureHeight = PLATE_CAPTURE_LONG_SIDE
            captureWidth = (PLATE_CAPTURE_LONG_SIDE * aspect).toInt().coerceAtLeast(1)
        }
        val frameBufferIds = IntArray(1)
        val textureIds = IntArray(1)
        val renderBufferIds = IntArray(1)
        GLES20.glGenFramebuffers(1, frameBufferIds, 0)
        if (frameBufferIds[0] == 0) return null
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBufferIds[0])
        GLES20.glGenTextures(1, textureIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            captureWidth,
            captureHeight,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            textureIds[0],
            0,
        )
        GLES20.glGenRenderbuffers(1, renderBufferIds, 0)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, renderBufferIds[0])
        GLES20.glRenderbufferStorage(
            GLES20.GL_RENDERBUFFER,
            GLES20.GL_DEPTH_COMPONENT16,
            captureWidth,
            captureHeight,
        )
        GLES20.glFramebufferRenderbuffer(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_DEPTH_ATTACHMENT,
            GLES20.GL_RENDERBUFFER,
            renderBufferIds[0],
        )
        val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
            GLES20.GL_FRAMEBUFFER_COMPLETE
        if (!complete) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteRenderbuffers(1, renderBufferIds, 0)
            GLES20.glDeleteTextures(1, textureIds, 0)
            GLES20.glDeleteFramebuffers(1, frameBufferIds, 0)
            return null
        }
        // The screen's own viewport and size are put back at the end: the drawing code sizes a
        // few things from them, and none of it may change because a picture was taken.
        val screenWidth = viewportWidth
        val screenHeight = viewportHeight
        viewportWidth = captureWidth
        viewportHeight = captureHeight
        GLES20.glViewport(0, 0, captureWidth, captureHeight)
        GLES20.glClearColor(PLATE_BACKGROUND[0], PLATE_BACKGROUND[1], PLATE_BACKGROUND[2], 1f)
        drawScene(captureWidth, captureHeight, publishForPicker = false)
        val bitmap = readPixels(captureWidth, captureHeight)
        viewportWidth = screenWidth
        viewportHeight = screenHeight
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, screenWidth, screenHeight)
        GLES20.glDeleteRenderbuffers(1, renderBufferIds, 0)
        GLES20.glDeleteTextures(1, textureIds, 0)
        GLES20.glDeleteFramebuffers(1, frameBufferIds, 0)
        // The screen keeps the plate's own background, which the next frame also clears with.
        GLES20.glClearColor(PLATE_BACKGROUND[0], PLATE_BACKGROUND[1], PLATE_BACKGROUND[2], 1f)
        return bitmap
    }

    /**
     * Uploads the preview meshes, once per set.
     *
     * Runs on the GL thread from the draw, so the context is current and a
     * result that arrived between frames is uploaded before it is drawn.
     */
    private fun ensurePreviewUpload() {
        val previews = previewObjects
        if (previewUploaded === previews && previewBuffers.size == previews.size) return
        releasePreviewBuffers()
        previewUploaded = previews
        if (previews.isEmpty()) return
        val indices = previews.keys.sorted()
        previewIndices = indices.toIntArray()
        val buffers = arrayOfNulls<FloatBuffer>(indices.size)
        var totalFloats = 0
        previewBuffers = buffers
        indices.forEachIndexed { slot, index ->
            val preview = previews.getValue(index)
            val floats = preview.interleavedVertices
            val direct = floats.directOrNull()
            val buffer = direct ?: floats.arrayOrNull()?.let(::floatBuffer)
            buffers[slot] = buffer
            if (buffer != null) {
                buffer.position(0)
                totalFloats += buffer.remaining()
            }
        }
        if (totalFloats == 0) return
        // One buffer holds every previewed mesh: it is allocated once at its
        // full size and each mesh is written at its own byte offset, so a set of
        // results costs one id and one allocation rather than one per half.
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        previewVbo = ids[0]
        if (previewVbo == 0) return
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, previewVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            totalFloats * Float.SIZE_BYTES,
            null,
            GLES20.GL_DYNAMIC_DRAW,
        )
        var accepted = GLES20.glGetError() == GLES20.GL_NO_ERROR
        previewSlots = IntArray(buffers.size)
        var offsetBytes = 0
        buffers.forEachIndexed { slot, buffer ->
            previewSlots[slot] = offsetBytes
            if (buffer == null) return@forEachIndexed
            buffer.position(0)
            GLES20.glBufferSubData(
                GLES20.GL_ARRAY_BUFFER,
                offsetBytes,
                buffer.remaining() * Float.SIZE_BYTES,
                buffer,
            )
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) accepted = false
            offsetBytes += buffer.remaining() * Float.SIZE_BYTES
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        if (!accepted) releasePreviewBuffers()
    }

    /** Deletes the preview buffers and forgets which meshes they held. */
    private fun releasePreviewBuffers() {
        if (previewVbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(previewVbo), 0)
            previewVbo = 0
        }
        previewUploaded = emptyMap()
        previewBuffers = emptyArray()
        previewIndices = IntArray(0)
        previewSlots = IntArray(0)
    }

    /**
     * The trait pointers for one buffer.
     *
     * The colour array is left bound exactly as the caller set it: it is
     * scene-wide and read from element zero, and a preview mesh has no slice of
     * it, so the preview draws in the object's constant colour.
     */
    private fun bindTraits(
        buffer: FloatBuffer?,
        vbo: Int,
        position: Int,
        normal: Int,
        byteOffset: Int,
    ) {
        if (vbo != 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, FLOATS_PER_VERTEX_BYTES, byteOffset)
            GLES20.glEnableVertexAttribArray(normal)
            GLES20.glVertexAttribPointer(
                normal,
                3,
                GLES20.GL_FLOAT,
                false,
                FLOATS_PER_VERTEX_BYTES,
                byteOffset + 12,
            )
        } else if (buffer != null) {
            // No VBO in play at all: the pointer has to be a client address,
            // which means nothing may be bound when the call is made.
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glEnableVertexAttribArray(position)
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, FLOATS_PER_VERTEX_BYTES, buffer)
            GLES20.glEnableVertexAttribArray(normal)
            buffer.position(3)
            GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, FLOATS_PER_VERTEX_BYTES, buffer)
        }
    }

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val scene = FloatArray(16)
    private val modelLocal = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    /**
     * The whole plate, exactly as the host lists it, and which object the tools
     * act on.
     *
     * A scene that is the same objects in the same order is left alone: every
     * recomposition hands in a fresh list, and re-concatenating a plate of
     * millions of triangles for one would make it stutter. Selecting a different
     * object is not a new scene either - its vertices are already in the buffer,
     * and only the tint and the paint move.
     */
    fun setMeshes(values: List<StlMesh>, selectedIndex: Int) {
        val selected = selectedIndex.coerceIn(0, max(values.size - 1, 0))
        if (selected == selectedObject && sameObjects(values)) return
        val next = values.toList()
        val rebuilt = !sameObjects(next)
        val isNewModel = next.size != objectMeshes.size ||
            next.indices.any { next[it].displayName != objectMeshes[it].displayName }
        // The paint in hand is one state per object now, so a different selection
        // needs no reset: every object keeps its own colours and only the tint
        // moves. The host hands the states over again in the same breath anyway.
        objectMeshes = next
        selectedObject = selected
        if (rebuilt) {
            val scene = sceneOf(next)
            triangleOwners = scene?.owners ?: IntArray(0)
            objectFirstTriangle = scene?.firstTriangle ?: IntArray(0)
            meshBuffer = scene?.mesh?.let { built ->
                built.interleavedVertices.directOrNull()
                    ?: built.interleavedVertices.arrayOrNull()?.let(::floatBuffer)
            }
            // A rebuilt scene renumbers every triangle, so the selection's tint is dropped
            // with it: the caller re-applies a surviving face by name. The buffer is a GL
            // object of the old scene and goes with it.
            faceTint = null
            releaseFaceTintBuffer()
            // Written last: the volatile field is what publishes the scene and the
            // bookkeeping beside it to a pick running on the executor thread.
            mesh = scene?.mesh
            // Every placement change (rotate, scale, move, lay flat, drop) hands in
            // fresh meshes, and the previous VBO is unreachable from here on: left
            // behind, one scene-sized buffer would leak per tap until the driver ran
            // out of memory and the plate silently stopped drawing.
            releaseGpuBuffers()
            dropColorBuffer()
        }
        rebuildColorBuffer()
        if (isNewModel) resetCamera()
    }

    /** True when [values] is the very same objects, in the same order, as the scene in hand. */
    private fun sameObjects(values: List<StlMesh>): Boolean {
        if (values.size != objectMeshes.size) return false
        for (index in values.indices) {
            if (values[index] !== objectMeshes[index]) return false
        }
        return true
    }

    /** The object the tools act on, or null when the plate is empty. */
    private fun selectedMesh(): StlMesh? = objectMeshes.getOrNull(selectedObject)

    /**
     * Concatenates the objects into the one buffer the plate is drawn from.
     *
     * The meshes are already in bed coordinates, so a scene is their vertices end
     * to end: one upload, one draw per object, and an object's place in the
     * buffer as its only per-object state. A plate of one borrows that mesh's
     * vertex data instead of copying it, so a single model keeps exactly the
     * memory and the upload it had before multi-object printing; a plate that
     * outgrows the Java heap moves to a direct buffer by the same rule
     * VertexData applies to a single mesh, so concatenating cannot defeat it.
     */
    private fun sceneOf(values: List<StlMesh>): Scene? {
        if (values.isEmpty()) return null
        val only = values.singleOrNull()
        if (only != null) {
            // One object is its own scene. The wrapper shares its vertex data, so
            // this costs an object header and nothing else.
            return Scene(
                mesh = StlMesh(
                    displayName = only.displayName,
                    interleavedVertices = only.interleavedVertices,
                    triangleCount = only.triangleCount,
                    bounds = only.bounds,
                ),
                owners = IntArray(0),
                firstTriangle = intArrayOf(0),
            )
        }
        val totalTriangles = values.sumOf { it.triangleCount }
        val totalFloats = totalTriangles * FLOATS_PER_TRIANGLE
        val owners = IntArray(totalTriangles)
        val firstTriangle = IntArray(values.size)
        val heap = totalTriangles < OFF_HEAP_MIN_TRIANGLES
        val array = if (heap) FloatArray(totalFloats) else null
        val direct = if (heap) {
            null
        } else {
            ByteBuffer.allocateDirect(totalFloats * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
        var triangle = 0
        var at = 0
        values.forEachIndexed { index, objectMesh ->
            firstTriangle[index] = triangle
            val floats = objectMesh.triangleCount * FLOATS_PER_TRIANGLE
            val source = objectMesh.interleavedVertices
            val sourceArray = source.arrayOrNull()
            if (array != null) {
                if (sourceArray != null) {
                    System.arraycopy(sourceArray, 0, array, at, floats)
                } else {
                    source.directOrNull()!!.duplicate().apply {
                        position(0)
                        limit(floats)
                    }.get(array, at, floats)
                }
            } else {
                val target = direct!!
                target.position(at)
                if (sourceArray != null) {
                    target.put(sourceArray, 0, floats)
                } else {
                    // A duplicate, never the mesh's own buffer: the picker reads
                    // its contents from another thread, and its position is not mine
                    // to move.
                    target.put(
                        source.directOrNull()!!.duplicate().apply {
                            position(0)
                            limit(floats)
                        },
                    )
                }
            }
            var owned = 0
            while (owned < objectMesh.triangleCount) {
                owners[triangle + owned] = index
                owned++
            }
            triangle += objectMesh.triangleCount
            at += floats
        }
        return Scene(
            mesh = StlMesh(
                displayName = SCENE_NAME,
                interleavedVertices = if (array != null) {
                    VertexData.fromArray(array)
                } else {
                    VertexData.fromDirect(direct!!)
                },
                triangleCount = totalTriangles,
                bounds = values.map { it.bounds }.unionOrNull() ?: values[0].bounds,
            ),
            owners = owners,
            firstTriangle = firstTriangle,
        )
    }

    /** The plate as one mesh, with the per-object bookkeeping the draw and the pick need. */
    private class Scene(
        val mesh: StlMesh,
        /** One entry per scene triangle: the object that owns it; empty for one object. */
        val owners: IntArray,
        /** Where each object's triangles start inside [mesh]. */
        val firstTriangle: IntArray,
    )

    /**
     * One paint state per object, in the same order as the plate's meshes.
     *
     * Every object's paint is drawn, not just the selected one's, so the caller
     * hands the whole plate's states over rather than the selection's.
     */
    fun setPaintStates(values: List<SupportPaintState>) {
        if (paintStates == values) return
        paintStates = values
        rebuildColorBuffer()
    }

    /** One state for the whole plate; kept for callers that predate per-object paint. */
    fun setPaintState(value: SupportPaintState) {
        setPaintStates(listOf(value))
    }

    /**
     * Applies a single paint edit to one object, rewriting only the triangles in
     * [changed].
     *
     * [changed] and [value] are in [objectIndex]'s own triangle indices, and a
     * stroke expands to a bounded set of them, so this keeps the update O(brush)
     * instead of rebuilding a colour buffer sized by the whole plate. Falls back
     * to a full rebuild when the buffer is missing or the scene changed
     * underneath it.
     */
    fun applyPaintEdit(objectIndex: Int, value: SupportPaintState, changed: Set<Int>) {
        if (objectIndex !in objectMeshes.indices) return
        if (paintStates.getOrElse(objectIndex) { SupportPaintState() } == value) return
        val updated = paintStates.toMutableList()
        while (updated.size <= objectIndex) updated += SupportPaintState()
        updated[objectIndex] = value
        paintStates = updated
        val buffer = paintColors
        if (buffer == null || buffer.triangleCount != mesh?.triangleCount) {
            rebuildColorBuffer()
            return
        }
        val baseSlot = baseSlotOf(objectIndex)
        buffer.apply(
            paint = value,
            changed = changed,
            offsetTriangles = objectFirstTriangle.getOrElse(objectIndex) { 0 },
            countTriangles = objectMeshes[objectIndex].triangleCount,
            baseSlot = baseSlot,
        )
        markSynced(objectIndex, value, baseSlot)
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

    /**
     * Picks against the plate as drawn.
     *
     * The cut preview is a shader clip plane on one object, so a picking ray has
     * to leave the discarded half out of the test: without that, a tap on the
     * open cut face of the visible half landed on the half standing behind it -
     * the phase-one gap this feature is the first to suffer from. The clip
     * applies to the object being cut and to no other, exactly as the shader
     * flag is set per draw.
     */
    fun pickTriangle(screenX: Float, screenY: Float): MeshPicker.Hit? {
        val scene = mesh ?: return null
        val cut = cutPlane
        val clip = if (cut != null) MeshPicker.PickClip(cut.axis, cut.offsetMm) else null
        val hit = MeshPicker.pick(
            mesh = scene,
            screenX = screenX,
            screenY = screenY,
            clip = clip,
            clipObject = cutObject,
            owners = triangleOwners,
        ) ?: return null
        return objectHit(hit)
    }

    /**
     * Re-frames a scene hit as a hit on the object that owns the triangle.
     *
     * The picker only ever sees the plate as one mesh, so the index it answers
     * with is a scene index. Every caller outside wants the object - the brush,
     * the gizmo and the annotations all act on one - together with the index
     * inside that object's own mesh, which is the index its paint is stored by.
     */
    private fun objectHit(hit: MeshPicker.Hit): MeshPicker.Hit {
        val owner = if (hit.triangleIndex in triangleOwners.indices) {
            triangleOwners[hit.triangleIndex]
        } else {
            0
        }
        return hit.copy(
            triangleIndex = hit.triangleIndex - objectFirstTriangle.getOrElse(owner) { 0 },
            objectIndex = owner,
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
        val scene = mesh ?: return null
        val ray = MeshPicker.ray(screenX, screenY) ?: return null
        // An annotation describes the selected object - the session is cleared when
        // another model is loaded - so a surface point on a neighbour is not a face
        // of it. It counts as a miss and lands on the plane, exactly like a tap
        // beside the part, rather than exporting another object's triangle index.
        val hit = MeshPicker.pick(scene, screenX, screenY)
            ?.let(::objectHit)
            ?.takeIf { it.objectIndex == selectedObject }
        val bounds = selectedMesh()?.bounds ?: scene.bounds
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

    /**
     * Moves the drawn vertices of the object the tools act on without touching the
     * placement; zeroed when a drag ends. Every other object stays exactly where
     * it is for the whole gesture, because the commit that follows moves this one
     * alone.
     */
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
        val scene = mesh ?: return null
        if (overlay.handles.isEmpty()) return null
        val hits = ArrayList<GizmoHit>(overlay.handles.size * 8)
        val candidates = ArrayList<GizmoDrag.HandleCandidate>(overlay.handles.size * 8)
        overlay.handles.forEach { handle ->
            val points = handle.points.size / 3
            for (index in 0 until points) {
                val x = handle.points[index * 3]
                val y = handle.points[index * 3 + 1]
                val z = handle.points[index * 3 + 2]
                val screen = MeshPicker.project(x, y, z) ?: continue
                val dx = screen[0] - screenX
                val dy = screen[1] - screenY
                hits.add(GizmoHit(handle.axis, handle.kind))
                candidates.add(
                    GizmoDrag.HandleCandidate(
                        screenDistancePx = kotlin.math.sqrt(dx * dx + dy * dy),
                        depthMm = MeshPicker.depthOf(x, y, z) ?: 0f,
                    ),
                )
            }
        }
        // What the model is doing under the finger: a handle further away than
        // that surface is behind the model, and reaching through the part to grab a
        // ring on its far side is exactly what felt wrong.
        val surface = MeshPicker.pick(scene, screenX, screenY)
        val surfaceDepth = surface?.let { MeshPicker.depthOf(it.x, it.y, it.z) }
        val chosen = GizmoDrag.chooseHandle(candidates, tolerancePx, surfaceDepth)
        return if (chosen >= 0) hits[chosen] else null
    }

    /** The shaft samples of one arrow of the gizmo on screen, or null when it is not up. */
    fun gizmoArrowSamples(axis: ModelPlacement.Axis): FloatArray? =
        gizmoOverlay?.handles?.firstOrNull { it.axis == axis && it.kind == GizmoHandleKind.ARROW }?.points

    /** Millimetres along [axis] for a screen drag on its arrow. */
    fun axisDragMillimetres(axis: ModelPlacement.Axis, deltaXPx: Float, deltaYPx: Float): Float {
        // The arrows are sized from the object they move, not from the plate.
        val bounds = selectedMesh()?.bounds ?: return 0f
        val pivot = Point3(bounds.centerX, bounds.centerY, bounds.centerZ)
        return axisDragMillimetres(
            axis = axis,
            deltaXPx = deltaXPx,
            deltaYPx = deltaYPx,
            pivotX = pivot.x,
            pivotY = pivot.y,
            pivotZ = pivot.z,
            lengthMm = TransformGizmo.arrowLengthMm(bounds),
        )
    }

    /**
     * The same, for an arrow whose own pivot and length are known: the joint's
     * gizmo stands on a hook rather than on the selected model, so it is that
     * arrow which has to be projected to turn the finger's pixels into
     * millimetres along the axis it holds.
     */
    fun axisDragMillimetres(
        axis: ModelPlacement.Axis,
        deltaXPx: Float,
        deltaYPx: Float,
        pivotX: Float,
        pivotY: Float,
        pivotZ: Float,
        lengthMm: Float,
    ): Float {
        if (!lengthMm.isFinite() || lengthMm <= 0f) return 0f
        val tip = when (axis) {
            ModelPlacement.Axis.X -> Point3(pivotX + lengthMm, pivotY, pivotZ)
            ModelPlacement.Axis.Y -> Point3(pivotX, pivotY + lengthMm, pivotZ)
            ModelPlacement.Axis.Z -> Point3(pivotX, pivotY, pivotZ + lengthMm)
        }
        val from = MeshPicker.project(pivotX, pivotY, pivotZ) ?: return 0f
        val to = MeshPicker.project(tip.x, tip.y, tip.z) ?: return 0f
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
            lengthMm = lengthMm,
            fallbackMillimetresPerPixel = fallback,
        )
    }

    /**
     * The screen direction a ring turns where it was grabbed, and its size on
     * screen, or null when the ring is not on screen. Worked out once, when the
     * finger lands, because it does not change while the drag lasts.
     */
    fun ringDragReference(axis: ModelPlacement.Axis, touchX: Float, touchY: Float): FloatArray? {
        val overlay = gizmoOverlay ?: return null
        val handle = overlay.handles.firstOrNull { it.axis == axis && it.kind == GizmoHandleKind.RING } ?: return null
        if (mesh == null) return null
        val count = handle.points.size / 3
        if (count < 4) return null
        val projected = FloatArray(count * 2)
        for (index in 0 until count) {
            val screen = MeshPicker.project(
                handle.points[index * 3],
                handle.points[index * 3 + 1],
                handle.points[index * 3 + 2],
            ) ?: return null
            projected[index * 2] = screen[0]
            projected[index * 2 + 1] = screen[1]
        }
        return GizmoDrag.ringReference(projected, touchX, touchY)
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
        GLES20.glClearColor(PLATE_BACKGROUND[0], PLATE_BACKGROUND[1], PLATE_BACKGROUND[2], 1f)
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
        // The tint's VBO died with the old context too. The CPU-side selection survives (the
        // renderer keeps the buffer), so the next drawn frame re-uploads it - but only if the
        // stale id is forgotten here, or the draw would ask for a buffer that no longer exists
        // and silently draw no tint at all.
        faceTintVbo = 0
        faceTintUploaded = null
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
        drawScene(viewportWidth, viewportHeight, publishForPicker = true)
    }

    /**
     * Draws the whole plate: the frame the screen shows, and the same scene an offscreen plate
     * render is taken from.
     *
     * [width] and [height] are the frame buffer being drawn into, so the projection and the fit
     * follow the size of the picture rather than the size of the window; [publishForPicker] is
     * true only for the on-screen frame, because the picker's camera is the surface's own and an
     * offscreen pass must not move it.
     */
    private fun drawScene(width: Int, height: Int, publishForPicker: Boolean) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val aspect = width.toFloat() / height.toFloat()
        val fit = sceneFit(aspect)
        val distance = fit.distance
        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW_DEGREES, aspect, fit.nearPlane, fit.farPlane)
        Matrix.setLookAtM(view, 0, 0f, -distance, distance * 0.62f, 0f, 0f, 0f, 0f, 0f, 1f)
        Matrix.translateM(view, 0, panX, panY, 0f)

        Matrix.setIdentityM(scene, 0)
        Matrix.rotateM(scene, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(scene, 0, yaw, 0f, 0f, 1f)
        // In plate coordinates, so the offset turns with the model it moves.
        Matrix.translateM(scene, 0, -fit.centerX, -fit.centerY, -fit.centerZ)

        // The frame's own matrix, published for the picker: an object is drawn
        // through it plus its own preview transform below, and the plate as a
        // whole through exactly this. Copying it out here, before any draw
        // overwrites the scratch matrices, is what makes every pick, projection
        // and depth test in the viewer answer about the geometry actually on
        // screen. It used to be recomputed in the picker, and the two fits came
        // out different: taps landed past the model and selected nothing.
        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        if (publishForPicker) MeshPicker.publish(mvp, width, height)

        // The preview belongs to one object: the one the tools act on, which is
        // the one every commit transforms. Putting it in the scene moved the
        // grid, the bed and the axis triad along with the model, which reads as
        // moving the camera and then having the model catch up when the finger
        // lifts; drawing it through the whole plate did the same to every
        // neighbour, while the commit moved a single object. That object's
        // vertices, its gizmo and its annotations go through this matrix - see
        // placeObject for the per-object part - and the plate does not.
        Matrix.setIdentityM(modelLocal, 0)
        Matrix.translateM(modelLocal, 0, dragOffsetX, dragOffsetY, dragOffsetZ)
        Matrix.translateM(modelLocal, 0, previewPivotX, previewPivotY, previewPivotZ)
        previewAxis?.let { axis ->
            when (axis) {
                ModelPlacement.Axis.X -> Matrix.rotateM(modelLocal, 0, previewDegrees, 1f, 0f, 0f)
                ModelPlacement.Axis.Y -> Matrix.rotateM(modelLocal, 0, previewDegrees, 0f, 1f, 0f)
                ModelPlacement.Axis.Z -> Matrix.rotateM(modelLocal, 0, previewDegrees, 0f, 0f, 1f)
            }
        }
        if (previewScale != 1f) Matrix.scaleM(modelLocal, 0, previewScale, previewScale, previewScale)
        Matrix.translateM(modelLocal, 0, -previewPivotX, -previewPivotY, -previewPivotZ)

        drawGrid()
        drawMesh()
        drawAxisTriad()
        drawAnnotation()
        drawGizmo()
    }

    /**
     * Reads one frame buffer of the plate into a bitmap.
     *
     * The frame is RGBA bytes from the bottom-left corner up, while a bitmap is ARGB from the
     * top-left down: both are undone here, and the swap is also where R and B are put back (GL
     * hands over R,G,B,A bytes and an ARGB int wants B,G,R,A in memory order).
     */
    private fun readPixels(width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        // A direct buffer, because glReadPixels writes through the native address; it is read
        // with absolute gets, so the whole-resolution copy never becomes a Java array.
        val raw = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, raw)
        val target = IntArray(width * height)
        for (y in 0 until height) {
            // Bitmap row 0 is the TOP of the picture; the frame buffer row 0 is its bottom.
            val frameRow = height - 1 - y
            for (x in 0 until width) {
                val at = (frameRow * width + x) * 4
                val red = raw.get(at).toInt() and 0xFF
                val green = raw.get(at + 1).toInt() and 0xFF
                val blue = raw.get(at + 2).toInt() and 0xFF
                val alpha = raw.get(at + 3).toInt() and 0xFF
                target[y * width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(target, width, height, Bitmap.Config.ARGB_8888)
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

        // Annotations are placed on the model's surface, so they ride with it.
        Matrix.multiplyMM(modelMatrix, 0, scene, 0, modelLocal, 0)
        Matrix.multiplyMM(modelView, 0, view, 0, modelMatrix, 0)
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
        val overlay = annotationOverlay ?: return null
        if (overlay.handles.isEmpty() || mesh == null) return null
        var best: SegmentEnd? = null
        var bestDistance = radiusPx
        for ((end, position) in overlay.handles) {
            val screen = MeshPicker.project(position.x, position.y, position.z) ?: continue
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

    /** Marks which handle is being held, so it can be drawn as the live one. */
    fun setGizmoActiveAxis(axis: ModelPlacement.Axis?) {
        gizmoActiveAxis = axis
    }

    /** Replaces the transform gizmo geometry; null clears it. */
    fun setGizmo(value: GizmoOverlay?) {
        gizmoOverlay = value
        val longest = value?.groups.orEmpty().maxOfOrNull { it.vertices.size } ?: 0
        val bytes = GizmoRibbon.capacityBytes(longest)
        gizmoRibbon = if (bytes <= 0) {
            null
        } else {
            java.nio.ByteBuffer
                .allocateDirect(bytes)
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

        // The gizmo rides with the model it moves.
        Matrix.multiplyMM(modelMatrix, 0, scene, 0, modelLocal, 0)
        Matrix.multiplyMM(modelView, 0, view, 0, modelMatrix, 0)
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
            // The handle being held is drawn fatter and lighter: with three rings
            // on screen, the one under the finger needs to be obvious.
            val active = group.axis != null && group.axis == gizmoActiveAxis
            val written = GizmoRibbon.expand(
                vertices = group.vertices,
                mvp = mvp,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                halfWidthPx = (if (active) GIZMO_ACTIVE_LINE_WIDTH_PX else GIZMO_LINE_WIDTH_PX) / 2f,
                target = ribbon,
            )
            if (written == 0) return@forEach
            if (active) {
                GLES20.glUniform4f(
                    color,
                    group.color[0] + (1f - group.color[0]) * 0.55f,
                    group.color[1] + (1f - group.color[1]) * 0.55f,
                    group.color[2] + (1f - group.color[2]) * 0.55f,
                    1f,
                )
            } else if (gizmoActiveAxis != null) {
                // Another arrow is held: this one steps back, so which axis is
                // live reads at a glance rather than by comparing colours.
                GLES20.glUniform4f(
                    color,
                    group.color[0] * GIZMO_IDLE_DIM,
                    group.color[1] * GIZMO_IDLE_DIM,
                    group.color[2] * GIZMO_IDLE_DIM,
                    1f,
                )
            } else {
                GLES20.glUniform4f(color, group.color[0], group.color[1], group.color[2], 1f)
            }
            ribbon.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 3 * 4, ribbon)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, written)
        }
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /**
     * Brings the scene-wide colour buffer in line with every object's paint, the
     * selected object's tint and the Smart Infill overlay.
     *
     * One buffer holds the whole plate, and every object owns a slice of it: its
     * own triangle indices are resynced at its own first scene triangle, in its
     * own base slot. That is what makes paint on a neighbour visible - the
     * previous shape held only the selected object's colours, and every other
     * object drew through the constant-colour path with its paint invisible.
     *
     * Only the objects whose state or base slot actually changed are resynced, so
     * a stroke costs the object it paints rather than the whole plate.
     */
    private fun rebuildColorBuffer() {
        val totalTriangles = mesh?.triangleCount ?: 0
        if (objectMeshes.isEmpty() || totalTriangles == 0) {
            dropColorBuffer()
            return
        }
        // A uniform base colour needs no buffer: the shader constant path in
        // drawMesh covers it, keeping dense meshes off the direct-memory heap
        // until painting, a boundary condition or a result tint needs colours.
        // The selection tint is one of those constants too, so a plate with
        // nothing painted pays nothing for it either.
        val overlay = smartInfillOverlay
        val overlayEmpty = overlay == null || overlay.isEmpty
        if (!paintActive && overlayEmpty && paintStates.all { it.isEmpty }) {
            dropColorBuffer()
            return
        }
        val palette = colorPalette(overlay)
        val existing = paintColors
        // A new palette, a new scene size or no buffer at all means a fresh
        // buffer; contents matter, not just the slot count, because a new run
        // with the same number of bins still repaints every region.
        val fresh = existing == null ||
            existing.triangleCount != totalTriangles ||
            !existing.hasPalette(palette.colors)
        val buffer = if (fresh) {
            PaintColorBuffer(triangleCount = totalTriangles, palette = palette.colors)
        } else {
            requireNotNull(existing)
        }
        if (fresh) {
            paintColors = buffer
            // A fresh buffer has no GPU-side copy yet, so force a full upload.
            colorUploaded = false
            syncedStates = emptyArray()
            syncedBaseSlots = IntArray(0)
            syncedOverlay = null
        }
        tintSlot = palette.selectedBaseSlot
        objectMeshes.forEachIndexed { index, objectMesh ->
            val state = paintStates.getOrElse(index) { SupportPaintState() }
            val baseSlot = baseSlotOf(index)
            if (!fresh && index < syncedStates.size &&
                syncedStates[index] === state && syncedBaseSlots[index] == baseSlot
            ) {
                return@forEachIndexed
            }
            buffer.resync(
                paint = state,
                offsetTriangles = objectFirstTriangle.getOrElse(index) { 0 },
                countTriangles = objectMesh.triangleCount,
                baseSlot = baseSlot,
            )
            markSynced(index, state, baseSlot)
        }
        if (fresh || overlay !== syncedOverlay) {
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
                offsetTriangles = objectFirstTriangle.getOrElse(selectedObject) { 0 },
                countTriangles = objectMeshes.getOrNull(selectedObject)?.triangleCount ?: 0,
            )
            syncedOverlay = overlay
        }
    }

    /** Frees the colour buffer and forgets what it carried. */
    private fun dropColorBuffer() {
        paintColors = null
        colorUploaded = false
        syncedStates = emptyArray()
        syncedBaseSlots = IntArray(0)
        syncedOverlay = null
    }

    /** The palette slot an object's unpainted triangles draw in. */
    private fun baseSlotOf(index: Int): Int =
        if (selectionTinted() && index == selectedObject) tintSlot else PaintColorBuffer.BASE_SLOT

    /** Records what an object's slice of [paintColors] was last resynced with. */
    private fun markSynced(index: Int, state: SupportPaintState, baseSlot: Int) {
        if (index >= syncedStates.size) {
            syncedStates = syncedStates.copyOf(index + 1)
            syncedBaseSlots = syncedBaseSlots.copyOf(index + 1)
        }
        syncedStates[index] = state
        syncedBaseSlots[index] = baseSlot
    }

    /**
     * The colour of every buffer slot: the fixed roles, then one per density bin
     * of the result tint, from the viewer's own ramp, and the selection tint as
     * one extra slot at the end.
     *
     * Appending the tint leaves every existing index alone - slot 0 stays the
     * plain base colour and the bins keep their 6+ slots - while still letting the
     * selected object's unpainted triangles draw in the tint and a neighbour's in
     * [BASE_COLOR]. The index the tint lands on comes back with the palette.
     */
    private fun colorPalette(overlay: SmartInfillOverlay?): Palette {
        val bins = overlay?.binDensities ?: EMPTY_DENSITIES
        val palette = ArrayList<FloatArray>(FIXED_SLOTS + bins.size + 1)
        palette += BASE_COLOR
        palette += ENFORCER_COLOR
        palette += BLOCKER_COLOR
        palette += SUPPORT_COLOR
        palette += LOAD_COLOR
        palette += ACTIVE_PICK_COLOR
        for (density in bins) palette += DensityRamp.color(density)
        val selectedBaseSlot = palette.size
        palette += SELECTED_BASE_COLOR
        return Palette(palette.toTypedArray(), selectedBaseSlot)
    }

    /** A palette and the slot that carries the selection tint. */
    private class Palette(
        val colors: Array<FloatArray>,
        /** The slot the selected object's unpainted triangles draw in. */
        val selectedBaseSlot: Int,
    )

    /** True when there is more than one object, so the selected one has to stand out. */
    private fun selectionTinted(): Boolean = objectMeshes.size > 1

    /** The colour an object with no colour buffer of its own is drawn in. */
    private fun objectColor(index: Int): FloatArray =
        if (selectionTinted() && index == selectedObject) SELECTED_BASE_COLOR else BASE_COLOR

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
        releasePreviewBuffers()
        releaseFaceTintBuffer()
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

    /** Deletes the tint buffer and forgets which selection it held; the shape is kept. */
    private fun releaseFaceTintBuffer() {
        if (faceTintVbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(faceTintVbo), 0)
            faceTintVbo = 0
        }
        faceTintUploaded = null
    }

    /** Deletes the mesh buffer and forgets which model it held. */
    private fun releaseMeshBuffer() {
        if (meshVbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(meshVbo), 0)
            meshVbo = 0
        }
        uploadedMesh = null
    }

    /**
     * Fills [modelMatrix], [modelView] and [mvp] for one object of the plate.
     *
     * The transform previewed under the finger belongs to the object the tools act
     * on - the one every commit transforms - so it is that object's vertices alone
     * that carry it. Drawing the preview for the whole plate slid every neighbour
     * along with the finger while the commit that followed moved one object, which
     * is a preview that lies about what is happening. An object's vertices arrive
     * in bed coordinates, so the identity is what holds it exactly still.
     */
    private fun placeObject(index: Int) {
        val local = if (index == selectedObject) modelLocal else identityMatrix
        Matrix.multiplyMM(modelMatrix, 0, scene, 0, local, 0)
        Matrix.multiplyMM(modelView, 0, view, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
    }

    private fun drawMesh() {
        if (mesh == null) return
        val buffer = meshBuffer ?: return

        // ModelPlacement has already written the mesh vertices into final
        // build-plate coordinates; modelLocal carries only the transform being
        // previewed under the finger, and placeObject hands it to the one object
        // being transformed rather than to the whole plate.

        GLES20.glUseProgram(meshProgram)
        val position = GLES20.glGetAttribLocation(meshProgram, "aPosition")
        val normal = GLES20.glGetAttribLocation(meshProgram, "aNormal")
        val color = GLES20.glGetAttribLocation(meshProgram, "aColor")
        val mvpLocation = GLES20.glGetUniformLocation(meshProgram, "uMvpMatrix")
        val modelLocation = GLES20.glGetUniformLocation(meshProgram, "uModelMatrix")
        val plateMatrixLocation = GLES20.glGetUniformLocation(meshProgram, "uPlateMatrix")
        val clipPlaneLocation = GLES20.glGetUniformLocation(meshProgram, "uClipPlane")
        val clipEnabledLocation = GLES20.glGetUniformLocation(meshProgram, "uClipEnabled")
        // The plane is the same for every object; only which objects it applies to
        // changes, and that is the per-object flag below.
        val cut = cutPlane
        val cutFloats = cut?.planeFloats()

        buffer.position(0)
        ensureMeshUpload(buffer)
        val previews = previewObjects
        ensurePreviewUpload()
        val vbo = meshVbo
        val colors = paintColors
        val data = colors?.buffer
        if (colors != null && data != null) {
            if (colorVbo == 0) {
                val ids = IntArray(1)
                GLES20.glGenBuffers(1, ids, 0)
                colorVbo = ids[0]
            }
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
                    // A stroke rewrites the painted object's floats, so that is all
                    // the GPU is sent - never the whole plate's colours.
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
            }
        }

        // Position, normal and colour arrays are bound once, at element zero, and
        // each object is drawn starting at its own first vertex. A draw that starts
        // at element F reads *every* enabled array from element F, and the colour
        // buffer is scene-wide, so colour element first+i belongs to the same vertex
        // as mesh element first+i: a vertex always fetches its own colour.
        if (vbo != 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 6 * 4, 0)
            GLES20.glEnableVertexAttribArray(normal)
            GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 6 * 4, 12)
        } else {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glEnableVertexAttribArray(position)
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
            GLES20.glEnableVertexAttribArray(normal)
            buffer.position(3)
            GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 6 * 4, buffer)
        }
        if (colors != null && data != null) {
            if (colorVbo != 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, colorVbo)
                GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 3 * 4, 0)
            } else {
                // No colour VBO (glGenBuffers failed): the pointer has to be a real
                // client address, and GLES reads it as a byte offset while another
                // buffer is bound, so unbind first.
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                data.position(0)
                GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 3 * 4, data)
            }
            GLES20.glEnableVertexAttribArray(color)
        } else {
            GLES20.glDisableVertexAttribArray(color)
        }

        // The selected face's tint: an attribute of its own, one byte per scene vertex, so it is
        // mixed over whatever colour the paint path above already chose instead of replacing it.
        // With no selection the array is disabled and its constant value is 0, which the shader
        // reads as "no tint".
        val tintLocation = GLES20.glGetAttribLocation(meshProgram, "aFaceTint")
        val tint = faceTint
        if (tint != null && tintLocation >= 0 && tint.byteCount > 0) {
            if (faceTintVbo == 0) {
                val ids = IntArray(1)
                GLES20.glGenBuffers(1, ids, 0)
                faceTintVbo = ids[0]
            }
            if (faceTintVbo != 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, faceTintVbo)
                if (faceTintUploaded !== tint) {
                    tint.buffer.position(0)
                    GLES20.glBufferData(
                        GLES20.GL_ARRAY_BUFFER,
                        tint.byteCount,
                        tint.buffer,
                        GLES20.GL_DYNAMIC_DRAW,
                    )
                    faceTintUploaded = tint
                }
                GLES20.glEnableVertexAttribArray(tintLocation)
                GLES20.glVertexAttribPointer(
                    tintLocation,
                    1,
                    GLES20.GL_UNSIGNED_BYTE,
                    true,
                    0,
                    0,
                )
            }
        } else if (tintLocation >= 0) {
            GLES20.glDisableVertexAttribArray(tintLocation)
            GLES20.glVertexAttrib1f(tintLocation, 0f)
        }

        // One draw per object, each starting at its own first scene vertex and
        // through its own model matrix: only the object being transformed carries
        // the preview, so a neighbour cannot even appear to move before the commit
        // reaches it. With no colour buffer at all every object still draws in its
        // own constant - the selected one in the tint, the rest in the plain base
        // colour.
        for (index in objectMeshes.indices) {
            placeObject(index)
            GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(modelLocation, 1, false, modelMatrix, 0)
            // The vertices are already in build-plate coordinates, so the object's
            // own matrix is the plate position a cut is measured against - not
            // uModelMatrix, which carries the camera for the lighting.
            GLES20.glUniformMatrix4fv(
                plateMatrixLocation,
                1,
                false,
                if (index == selectedObject) modelLocal else identityMatrix,
                0,
            )
            // The cut is a preview of one object's split, so the flag is set per
            // draw and a neighbour on the plate is never clipped with it.
            val cutting = cutFloats != null && index == cutObject
            GLES20.glUniform1f(clipEnabledLocation, if (cutting) 1f else 0f)
            if (cutting) {
                GLES20.glUniform4f(
                    clipPlaneLocation,
                    cutFloats[0],
                    cutFloats[1],
                    cutFloats[2],
                    cutFloats[3],
                )
            }
            val shown = previews[index]
            if (shown != null) {
                // A tool is previewing this object: the boolean result stands in
                // for it, drawn where it is - the preview is in plate
                // coordinates, exactly as the mesh it replaces is.
                val slot = previewIndices.indexOf(index)
                if (slot >= 0) {
                    // The palette is the scene's own, one colour per scene
                    // vertex: the preview's vertices are boolean output and
                    // have no slice of it, so the constant branch draws them.
                    // The object's paint still stands for support paint and
                    // blocker volumes - it is a property of the plate object,
                    // not of the triangles underneath it.
                    val rgb = objectColor(index)
                    GLES20.glVertexAttrib3f(color, rgb[0], rgb[1], rgb[2])
                    if (colors != null && data != null) GLES20.glDisableVertexAttribArray(color)
                    // A preview mesh has no slice of the scene's tint buffer: its vertices are
                    // boolean output, so they draw untinted.
                    if (tintLocation >= 0) {
                        GLES20.glDisableVertexAttribArray(tintLocation)
                        GLES20.glVertexAttrib1f(tintLocation, 0f)
                    }
                    bindTraits(
                        buffer = previewBuffers.getOrNull(slot),
                        vbo = previewVbo,
                        position = position,
                        normal = normal,
                        byteOffset = previewSlots.getOrElse(slot) { 0 },
                    )
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, shown.triangleCount * VERTICES_PER_TRIANGLE)
                    // The scene-wide traits go back as they were for the objects
                    // after this one: the mesh buffer's pointers, and the colour
                    // array when the plate has one.
                    if (colors != null && data != null) {
                        GLES20.glEnableVertexAttribArray(color)
                        if (colorVbo != 0) {
                            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, colorVbo)
                            GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 3 * 4, 0)
                        } else {
                            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                            data.position(0)
                            GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT, false, 3 * 4, data)
                        }
                    }
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
                    GLES20.glEnableVertexAttribArray(position)
                    GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 6 * 4, 0)
                    GLES20.glEnableVertexAttribArray(normal)
                    GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 6 * 4, 12)
                    if (tint != null && tintLocation >= 0 && faceTintVbo != 0) {
                        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, faceTintVbo)
                        GLES20.glEnableVertexAttribArray(tintLocation)
                        GLES20.glVertexAttribPointer(tintLocation, 1, GLES20.GL_UNSIGNED_BYTE, true, 0, 0)
                    }
                    continue
                }
            }
            val firstVertex = objectFirstTriangle.getOrElse(index) { 0 } * VERTICES_PER_TRIANGLE
            val vertexCount = objectMeshes[index].triangleCount * VERTICES_PER_TRIANGLE
            if (colors == null) {
                val rgb = objectColor(index)
                GLES20.glVertexAttrib3f(color, rgb[0], rgb[1], rgb[2])
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, firstVertex, vertexCount)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glDisableVertexAttribArray(color)
        if (tintLocation >= 0) GLES20.glDisableVertexAttribArray(tintLocation)

        // The optimized volumes are the selected object's own meshes and draw
        // through the matrix the loop left behind - the last object's, which is
        // not necessarily that object's.
        placeObject(selectedObject)

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

        /** And fatter still while it is the one being dragged. */
        const val GIZMO_ACTIVE_LINE_WIDTH_PX = 13f

        /**
         * What an arrow's colour is multiplied by while ANOTHER arrow is held:
         * the held one brightens and widens, and the rest step back.
         */
        const val GIZMO_IDLE_DIM = 0.45f
        const val GRID_Z = -0.08f

        /** Long side of a plate render, in pixels: the size the agent's picture is drawn at. */
        const val PLATE_CAPTURE_LONG_SIDE = 768

        /** The plate's own background, so an offscreen render looks like the screen. */
        val PLATE_BACKGROUND = floatArrayOf(0.055f, 0.065f, 0.08f)

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

        /** Interleaved vertex: three position floats, then three normal ones. */
        const val FLOATS_PER_VERTEX = 6

        /** The same vertex in bytes, which is what a GL stride and offset take. */
        const val FLOATS_PER_VERTEX_BYTES = FLOATS_PER_VERTEX * 4

        /** Three vertices to a triangle, so eighteen floats to a scene triangle. */
        const val VERTICES_PER_TRIANGLE = 3
        const val FLOATS_PER_TRIANGLE = FLOATS_PER_VERTEX * VERTICES_PER_TRIANGLE

        /** The scene mesh's own name; nothing displays it. */
        const val SCENE_NAME = "Build plate"

        /**
         * Base colour of the object being worked on while the plate holds more than
         * one. The same family as [BASE_COLOR] and clearly lighter, so the object a
         * brush, a gizmo drag or an annotation lands on stands out without reading
         * as a different material.
         */
        val SELECTED_BASE_COLOR = floatArrayOf(0.52f, 0.78f, 0.98f)
val ANNOTATION_COLOR = floatArrayOf(1.00f, 0.76f, 0.22f)
val ANNOTATION_MARKER_COLOR = floatArrayOf(1.00f, 1.00f, 1.00f)

        // The split preview is a clip plane, not a second mesh: the vertex
        // shader carries each fragment's build-plate position across, and the
        // fragment shader throws away what stands beyond the plane. Nothing is
        // rebuilt while the slider moves, and the mesh on the GPU never changes.
        const val MESH_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uModelMatrix;
            uniform mat4 uPlateMatrix;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            attribute float aFaceTint;
            varying vec3 vNormal;
            varying vec3 vColor;
            varying vec3 vPlatePosition;
            varying float vFaceTint;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vPlatePosition = (uPlateMatrix * vec4(aPosition, 1.0)).xyz;
                vNormal = normalize(mat3(uModelMatrix) * aNormal);
                vColor = aColor;
                vFaceTint = aFaceTint;
            }
        """
        const val MESH_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uClipPlane;
            uniform float uClipEnabled;
            varying vec3 vNormal;
            varying vec3 vColor;
            varying vec3 vPlatePosition;
            varying float vFaceTint;
            // The selected face: warm amber, deliberately outside the support palette (its
            // interface is cyan and its supports magenta) and the layer view's speed ramp.
            const vec3 FACE_TINT_COLOR = vec3(1.0, 0.78, 0.22);
            void main() {
                if (uClipEnabled > 0.5 &&
                    dot(uClipPlane.xyz, vPlatePosition) > uClipPlane.w) {
                    discard;
                }
                vec3 normal = normalize(vNormal);
                if (!gl_FrontFacing) {
                    normal = -normal;
                }
                vec3 keyLight = normalize(vec3(0.35, -0.70, 0.62));
                vec3 fillLight = normalize(vec3(-0.55, 0.30, 0.72));
                float key = max(dot(normal, keyLight), 0.0);
                float fill = max(dot(normal, fillLight), 0.0);
                float lighting = 0.28 + key * 0.62 + fill * 0.22;
                vec3 shaded = vColor * min(lighting, 1.12);
                // The tint is mixed over the shaded colour rather than replacing it, so the
                // face keeps its shape while it reads warm; support paint underneath it stays
                // where it was and simply shades through.
                vec3 color = mix(shaded, FACE_TINT_COLOR, vFaceTint * 0.85);
                gl_FragColor = vec4(color, 1.0);
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
