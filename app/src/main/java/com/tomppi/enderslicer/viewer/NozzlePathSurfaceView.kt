package com.tomppi.enderslicer.viewer

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.tomppi.enderslicer.engine.GcodeNozzlePath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Hue for an extrusion move: the print-speed ramp (cyan slow, orange fast).
 * Speed colors are the single nozzle-path color mode.
 */
internal fun extrusionHue(speedRatio: Float): Float {
    val clampedSpeed = speedRatio.coerceIn(0f, 1f)
    return 200f - 180f * clampedSpeed
}

/** Micro-segments (sub-0.05 mm) emit with zero width. */
internal const val RIBBON_MIN_SEGMENT_MM = 0.05f
internal const val FINE_LAYER_HEIGHT_MM = 0.12f

/**
 * Growable direct FloatBuffer: nozzle-path geometry is built straight into
 * native memory so a long print never OOMs the 512 MB Java heap (the original
 * boxed ArrayList<Float> build did exactly that - see the GLThread
 * OutOfMemoryError in buildPathBuffers/bufferOf).
 */
internal class DirectFloatSink(initialCapacity: Int = 4096) {
    private var buffer: FloatBuffer = alloc(max(initialCapacity, 16))
    private var count = 0

    private fun alloc(capacity: Int): FloatBuffer =
        ByteBuffer.allocateDirect(capacity * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    operator fun plusAssign(value: Float) {
        if (count >= buffer.capacity()) grow()
        buffer.put(count, value)
        count++
    }

    val size: Int get() = count

    fun isEmpty(): Boolean = count == 0

    /**
     * Zero-copy view of the accumulated data: callers build fully, then read.
     * Duplicating 60-180 MB of ribbon vertex data per path load pushes the
     * devices over the heap limit on big prints (intermittent OOM crashes).
     */
    fun toFloatBuffer(): FloatBuffer {
        buffer.position(0)
        buffer.limit(count)
        return buffer
    }

    fun toFloatArray(): FloatArray {
        val result = FloatArray(count)
        buffer.position(0)
        buffer.limit(count)
        buffer.get(result)
        return result
    }

    private fun grow() {
        val next = alloc(max(buffer.capacity() * 2, 16))
        buffer.position(0)
        buffer.limit(count)
        next.put(buffer)
        next.position(0)
        buffer = next
    }
}

/**
 * Resolves the physical bead width for one extrusion move, shared by the
 * renderer (geometry) and the UI (inspector readout) so they can never
 * disagree: width = deltaE x filament area / length / layer height, clamped
 * to [lineWidth] x [0.4, 4.0]; sub-0.05 mm segments collapse to zero width
 * so micro-segments do not render as dark specks.
 */
internal fun resolveBeadWidthMm(
    lengthMm: Float,
    deltaE: Float,
    parsedLayerHeight: Float,
    layerHeightFallback: Float,
    lineWidth: Float,
    filamentArea: Float,
): Float {
    val height = if (parsedLayerHeight > 0.02f && parsedLayerHeight <= 2.0f) {
        parsedLayerHeight
    } else {
        layerHeightFallback
    }
    val rawWidth = if (lengthMm > 1e-4f && deltaE > 0f) {
        val crossArea = deltaE * filamentArea / lengthMm
        crossArea / height
    } else {
        lineWidth
    }
    // Micro-segment jitter: on fine layers the E-per-length ratio on
    // sub-mm moves is dominated by coordinate quantization, inflating
    // widths to 0.6+ mm against a true 0.42-0.44 mm. Fine-layer beads keep a
    // tight band around the nominal line width so the noise collapses but
    // real per-segment variation (skirts, perimeters, infill) survives.
    val maxRatio = if (height <= FINE_LAYER_HEIGHT_MM) 1.15f else 4.0f
    val minRatio = if (height <= FINE_LAYER_HEIGHT_MM) 0.60f else 0.4f
    val bounded = rawWidth.coerceIn(lineWidth * minRatio, lineWidth * maxRatio)
    return if (lengthMm < RIBBON_MIN_SEGMENT_MM) 0f else bounded
}

class NozzlePathSurfaceView(context: Context) : GLSurfaceView(context) {
    private val pathRenderer = NozzlePathRenderer()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private var previousX = 0f
    private var previousY = 0f
    private var previousFocusX = 0f
    private var previousFocusY = 0f
    private var panning = false

    init {
        setEGLContextClientVersion(2)
        // Same proven EGL config as the model viewer. The custom MSAA chooser
        // was removed: it is the prime suspect for device-specific crashes
        // right after the path loads (GL init on the render thread).
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(pathRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = true
        // The ribbon build runs on the GL thread; hop progress/failure back
        // to the main thread so the UI can show where it got to.
        pathRenderer.onBuildProgress = { fraction -> post { onBuildProgress?.invoke(fraction) } }
        pathRenderer.onBuildFinished = { error -> post { onBuildFinished?.invoke(error) } }
    }

    fun setPath(
        path: GcodeNozzlePath,
        selectedMoveIndex: Int,
        beadHeightMm: Float,
        beadLineWidthMm: Float,
        filamentDiameterMm: Float,
    ) {
        queueEvent {
            pathRenderer.setPath(path, beadHeightMm, beadLineWidthMm, filamentDiameterMm)
            pathRenderer.setSelectedMove(selectedMoveIndex)
        }
        // setPath resets the camera on the GL thread.
        queueEvent { notifyOrientation() }
        requestRender()
    }

    /** When false, gray travel moves are not drawn. */
    var showTravels: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            queueEvent { pathRenderer.setShowTravels(value) }
            requestRender()
        }

    /**
     * Orthographic (true-width) camera for measuring the path; perspective is
     * the default for context. The orbit pivot, pan and fit are identical.
     */
    var orthographic: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            queueEvent { pathRenderer.orthographic = value }
            requestRender()
        }

    /** Invoked on the main thread whenever the turntable yaw/pitch changes. */
    var onOrientationChanged: ((ViewerOrientation) -> Unit)? = null

    /** Invoked on the main thread with the current zoom multiplier. */
    var onZoomChanged: ((Float) -> Unit)? = null

    /** Invoked on the main thread when the user taps a move to inspect it. */
    var onMovePicked: ((Int) -> Unit)? = null

    /** Invoked on the main thread with ribbon-build progress (0..1). */
    var onBuildProgress: ((Float) -> Unit)? = null

    /** Invoked on the main thread when the geometry build settles; null = success. */
    var onBuildFinished: ((String?) -> Unit)? = null

    fun currentOrientation(): ViewerOrientation = pathRenderer.orientation

    fun currentZoom(): Float = pathRenderer.zoomLevel

    private fun notifyOrientation() {
        val listener = onOrientationChanged ?: return
        post { listener(pathRenderer.orientation) }
    }

    private fun notifyZoom() {
        val listener = onZoomChanged ?: return
        post { listener(pathRenderer.zoomLevel) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
                panning = false
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
                        pathRenderer.panPixels(focusX - previousFocusX, focusY - previousFocusY)
                    }
                    previousFocusX = focusX
                    previousFocusY = focusY
                    panning = true
                    requestRender()
                } else if (!scaleDetector.isInProgress) {
                    pathRenderer.rotate((event.x - previousX) * 0.35f, (event.y - previousY) * 0.35f)
                    previousX = event.x
                    previousY = event.y
                    notifyOrientation()
                    requestRender()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                panning = false
                if (event.pointerCount - 1 == 1) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    previousX = event.getX(remaining)
                    previousY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                panning = false
            }
            MotionEvent.ACTION_CANCEL -> panning = false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pathRenderer.zoom(detector.scaleFactor)
            notifyZoom()
            requestRender()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            queueEvent {
                val index = pathRenderer.pickNearestMove(event.x, event.y)
                if (index >= 0) post { onMovePicked?.invoke(index) }
            }
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            pathRenderer.resetCamera()
            notifyOrientation()
            notifyZoom()
            requestRender()
            return true
        }
    }

    /** Resets yaw, pitch, zoom, pan and the orbit pivot back to the model fit. */
    fun resetView() {
        queueEvent { pathRenderer.resetCamera() }
        queueEvent { notifyOrientation() }
        queueEvent { notifyZoom() }
        requestRender()
    }
}

private class NozzlePathRenderer : NozzlePathRendererBase() {
    private var path: GcodeNozzlePath? = null
    private var selectedMoveIndex = 0
    private var ribbonPositions: FloatBuffer? = null
    private var ribbonNormals: FloatBuffer? = null
    private var ribbonColors: FloatBuffer? = null
    private var ribbonAmbient: FloatBuffer? = null
    private var travelPositions: FloatBuffer? = null
    private var travelColors: FloatBuffer? = null
    // Physical bead parameters from the current slice settings (fallbacks
    // when the parsed per-move flow cannot be trusted).
    private var beadHeight = 0.20f
    private var beadLineWidth = 0.40f
    private var filamentArea = Math.PI.toFloat() * 0.875f * 0.875f
    // extensionPrefix[m] = extrusion moves stored with index < m (same for travel).
    private var extrusionPrefix = IntArray(1)
    private var travelPrefix = IntArray(1)
    @Volatile private var showTravels = true
    @Volatile var orthographic = false
    private var gridPositions: FloatBuffer? = null
    private var gridVertexCount = 0
    // GPU-side (VBO) copies of the ribbon/travel geometry: uploaded once per
    // path, so the path renders from GPU memory like a game renders static
    // geometry.
    private var uploadedPath: GcodeNozzlePath? = null
    private var markerPositions: FloatBuffer? = null
    private var markerVertexCount = 0
    private var markerGlowPositions: FloatBuffer? = null
    private var markerGlowVertexCount = 0

    private var viewportWidth = 1
    private var viewportHeight = 1
    // Live build progress for the UI; reports every BUILD_PROGRESS_STRIDE moves.
    var onBuildProgress: ((Float) -> Unit)? = null
    var onBuildFinished: ((String?) -> Unit)? = null
    private var lastReportedMove = 0
    // Camera pivot and fit use the EXTRUSION bounds (the printed model), not
    // the whole path: travel moves to the prime line or skirt sit far from the
    // part and would anchor the orbit to the plate instead of the model.
    private var modelMinX = 0f
    private var modelMinY = 0f
    private var modelMinZ = 0f
    private var modelMaxX = 0f
    private var modelMaxY = 0f
    private var modelMaxZ = 0f
    @Volatile private var yaw = DEFAULT_YAW
    @Volatile private var pitch = DEFAULT_PITCH
    @Volatile private var zoom = DEFAULT_ZOOM
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val modelView = FloatArray(16)
    // Scratch for projection during picking.

    fun setPath(
        value: GcodeNozzlePath,
        beadHeightMm: Float,
        beadLineWidthMm: Float,
        filamentDiameterMm: Float,
    ) {
        if (path === value && beadHeight == beadHeightMm) return
        path = value
        beadHeight = beadHeightMm.coerceIn(0.02f, 2.0f)
        beadLineWidth = beadLineWidthMm.coerceIn(0.10f, 2.0f)
        filamentArea = (Math.PI.toFloat() * (filamentDiameterMm.coerceIn(0.5f, 4.0f) / 2f).let { it * it })
        selectedMoveIndex = if (value.moveCount <= 0) 0 else selectedMoveIndex.coerceIn(0, value.moveCount - 1)
        lastReportedMove = 0
        onBuildProgress?.invoke(0f)
        val startedAt = System.nanoTime()
        try {
            buildPathBuffers(value)
            buildGrid(value)
            buildMarker()
            onBuildProgress?.invoke(1f)
            onBuildFinished?.invoke(null)
            Log.i(
                TAG,
                "nozzle-path geometry built: " + value.moveCount + " moves in " +
                    ((System.nanoTime() - startedAt) / 1_000_000) + " ms",
            )
        } catch (error: Throwable) {
            // Keep the render thread alive and surface the cause in logcat
            // instead of taking the app down.
            Log.e(TAG, "Unable to build nozzle-path buffers", error)
            ribbonPositions = null
            ribbonNormals = null
            ribbonColors = null
            ribbonAmbient = null
            travelPositions = null
            travelColors = null
            onBuildFinished?.invoke(error.message ?: error.javaClass.simpleName)
        }
        resetCamera()
    }

    fun setSelectedMove(value: Int) {
        val current = path ?: return
        if (current.moveCount <= 0) return
        val safe = value.coerceIn(0, current.moveCount - 1)
        if (safe == selectedMoveIndex) return
        selectedMoveIndex = safe
        buildMarker()
    }

    fun setShowTravels(value: Boolean) {
        showTravels = value
    }

    val orientation: ViewerOrientation
        get() = ViewerOrientation(yaw, pitch)

    val zoomLevel: Float
        get() = zoom

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
        val visibleHeight = NozzlePathViewCamera.visibleHeightAtPivot(cameraDistance(), FIELD_OF_VIEW)
        val worldPerPixel = visibleHeight / max(viewportHeight, 1)
        panX += deltaX * worldPerPixel
        panY -= deltaY * worldPerPixel
    }

    fun resetCamera() {
        yaw = DEFAULT_YAW
        pitch = DEFAULT_PITCH
        zoom = DEFAULT_ZOOM
        panX = 0f
        panY = 0f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.055f, 0.065f, 0.08f, 1f)
        GLES20.glClearDepthf(1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        Log.i(TAG, "nozzle-path GL surface created; compiling shaders")
        litProgram = createGlProgram(LIT_VERTEX_SHADER, LIT_FRAGMENT_SHADER)
        colorProgram = createGlProgram(COLOR_VERTEX_SHADER, COLOR_FRAGMENT_SHADER)
        solidProgram = createGlProgram(SOLID_VERTEX_SHADER, SOLID_FRAGMENT_SHADER)
        Log.i(TAG, "nozzle-path shaders compiled")
        // A new GL context invalidates old VBO ids.
        pathVbos = IntArray(0)
        uploadedPath = null
        maxLineWidth = queryMaxLineWidth()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = max(width, 1)
        viewportHeight = max(height, 1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    private fun computeCamera(aspect: Float): Float {
        val distance = cameraDistance()
        val radius = sceneRadius(requireNotNull(path))
        val nearPlane = max(0.05f, distance - radius * 1.6f)
        val farPlane = max(nearPlane + 100f, distance + radius * 2.8f + 100f)
        if (orthographic) {
            // True-width measurement mode: the projection is orthographic so
            // bead widths on screen match the physical path regardless of
            // perspective foreshortening. The half-height comes from the same
            // eye distance as the pan and the perspective view, so flipping
            // the toggle does not change the apparent size of the part.
            val halfHeight = NozzlePathViewCamera.orthographicHalfHeight(distance, FIELD_OF_VIEW)
            val halfWidth = halfHeight * aspect
            Matrix.orthoM(projection, 0, -halfWidth, halfWidth, -halfHeight, halfHeight, nearPlane, farPlane)
        } else {
            Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW, aspect, nearPlane, farPlane)
        }
        Matrix.setLookAtM(view, 0, 0f, -distance, distance * 0.62f, 0f, 0f, 0f, 0f, 0f, 1f)
        Matrix.translateM(view, 0, panX, panY, 0f)
        // Turntable around the printed-part centre, exactly like the model
        // viewer: rotate first, then bring the part centre to the origin.
        Matrix.setIdentityM(scene, 0)
        Matrix.rotateM(scene, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(scene, 0, yaw, 0f, 0f, 1f)
        Matrix.translateM(
            scene, 0,
            -(modelMinX + modelMaxX) * 0.5f,
            -(modelMinY + modelMaxY) * 0.5f,
            -(modelMinZ + modelMaxZ) * 0.5f,
        )
        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
        return distance
    }

    override fun onDrawFrame(gl: GL10?) {
        try {
            drawFrame()
        } catch (error: Throwable) {
            // Render-thread failures must not take the app down.
            Log.e(TAG, "Nozzle-path frame failed", error)
        }
    }

    private fun drawFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val current = path ?: return
        val aspect = viewportWidth.toFloat() / viewportHeight
        computeCamera(aspect)
        val upTo = (selectedMoveIndex + 1).coerceAtMost(extrusionPrefix.size - 1)
        val ribbonMoveCount = extrusionPrefix[upTo]

        drawSolidLines(gridPositions, gridVertexCount, 1f, 0.24f, 0.30f, 0.40f, 0.48f)
        // Physical beads in one lit pass: per-face analytic normals with a
        // fixed 3-light rig (key + fill + rim) so the bead facets read as a
        // solid printed part from every orbit angle without the zoomed-out
        // moire that interpolated normals caused.
        ensureUploads(current)
        if (pathVbos.size == 6 && pathVbos[0] != 0) {
            drawLitTrianglesVbo(ribbonMoveCount * RibbonPathGeometry.WINDOW_VERTICES)
        } else {
            drawLitTriangles(
                ribbonPositions,
                ribbonNormals,
                ribbonColors,
                ribbonAmbient,
                ribbonMoveCount * RibbonPathGeometry.WINDOW_VERTICES,
            )
        }
        if (showTravels) {
            if (pathVbos.size == 6 && pathVbos[4] != 0) {
                drawColoredLinesVbo(travelPrefix[upTo] * 2, TRAVEL_WIDTH)
            } else {
                drawColoredLines(travelPositions, travelColors, travelPrefix[upTo] * 2, TRAVEL_WIDTH)
            }
        }
        drawSolidLines(markerGlowPositions, markerGlowVertexCount, 8f, 0.85f, 0.55f, 0.30f, 0.20f)
        drawSolidLines(markerPositions, markerVertexCount, 4.5f, 1f, 1f, 1f, 1f)
    }

        private fun buildPathBuffers(value: GcodeNozzlePath) {
        val source = value.moves
        // One window per move, always: full fidelity (no memory cap - if a
        // journey cannot fit, the system kills the process rather than us
        // degrading the preview with coarser windows).
        val stride = 1
        // Geometry is built into direct native buffers: the boxed-list build
        // this replaced OOM'd the Java heap on long prints (boxed Float ~16 B
        // vs 4 B in the final buffer, and 4 arrays per move with lighting).
        // Exact pre-size from the parsed path counts: one window per move and
        // one allocation per sink, so a big path never grows with doubling
        // copies (the 128 MB grow step is what OOM'd at ~500k+ moves).
        val extrusionCount = value.extrusionMoveCount
        val travelRunsUpperBound = value.travelMoveCount
        val ribbonVertex = DirectFloatSink(extrusionCount * RibbonPathGeometry.WINDOW_VERTICES * 3)
        val ribbonNormal = DirectFloatSink(extrusionCount * RibbonPathGeometry.WINDOW_VERTICES * 3)
        val ribbonColor = DirectFloatSink(extrusionCount * RibbonPathGeometry.WINDOW_VERTICES * 4)
        val ribbonAmbientValues = DirectFloatSink(extrusionCount * RibbonPathGeometry.WINDOW_VERTICES * 1)
        val travelVertex = DirectFloatSink(travelRunsUpperBound * 2 * 3)
        val travelColor = DirectFloatSink(travelRunsUpperBound * 2 * 4)
        // Full-range extrusion points for the camera fit (percentile-trimmed
        // later), independent of the ribbon stride sampling.
        val boundsVertex = DirectFloatSink(extrusionCount * 6)
        var minSpeed = Float.POSITIVE_INFINITY
        var maxSpeed = Float.NEGATIVE_INFINITY
        for (moveIndex in 0 until value.moveCount) {
            val moveOffset = moveIndex * GcodeNozzlePath.VALUES_PER_MOVE
            if (source[moveOffset + GcodeNozzlePath.KIND] == GcodeNozzlePath.Kind.EXTRUSION.code) {
                val speed = source[moveOffset + GcodeNozzlePath.SPEED]
                minSpeed = min(minSpeed, speed)
                maxSpeed = max(maxSpeed, speed)
            }
        }
        val speedSpan = maxSpeed - minSpeed
        var offset = 0
        var extrusionMoves = 0
        var travelMoves = 0
        extrusionPrefix = IntArray(value.moveCount + 1)
        travelPrefix = IntArray(value.moveCount + 1)
        // Paint in contiguous same-kind windows of STALE-STRIDE moves: with
        // stride > 1 the sampled move spans the WHOLE window (start point of
        // the first move, end point of the last, summed deltaE), so long
        // prints stay continuous instead of alternating gaps.
        var moveIndex = 0
        while (moveIndex < value.moveCount) {
            if (moveIndex - lastReportedMove >= RibbonPathGeometry.BUILD_PROGRESS_STRIDE) {
                lastReportedMove = moveIndex
                onBuildProgress?.invoke((moveIndex.toFloat() / max(value.moveCount, 1)).coerceIn(0f, 1f))
            }
            val windowOffset = moveIndex * GcodeNozzlePath.VALUES_PER_MOVE
            val kind = source[windowOffset + GcodeNozzlePath.KIND]
            val boundsX1 = source[windowOffset + GcodeNozzlePath.X1]
            val boundsY1 = source[windowOffset + GcodeNozzlePath.Y1]
            val boundsZ1 = source[windowOffset + GcodeNozzlePath.Z1]
            val boundsX2 = source[windowOffset + GcodeNozzlePath.X2]
            val boundsY2 = source[windowOffset + GcodeNozzlePath.Y2]
            val boundsZ2 = source[windowOffset + GcodeNozzlePath.Z2]
            // Natural run: consecutive same-kind moves that stay collinear
            // (turn-split at sharp reversals), WITHOUT the stride bound.
            var runEnd = moveIndex + 1
            var firstDx = boundsX2 - boundsX1
            var firstDy = boundsY2 - boundsY1
            val firstLen = sqrt(firstDx * firstDx + firstDy * firstDy)
            if (firstLen > 1e-7f) { firstDx /= firstLen; firstDy /= firstLen }
            var runChord = 0.0f
            var runDeltaE = 0f
            var runArc = 0f
            while (runEnd < value.moveCount &&
                source[runEnd * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.KIND] == kind) {
                val ro = runEnd * GcodeNozzlePath.VALUES_PER_MOVE
                var ndx = source[ro + GcodeNozzlePath.X2] - source[ro + GcodeNozzlePath.X1]
                var ndy = source[ro + GcodeNozzlePath.Y2] - source[ro + GcodeNozzlePath.Y1]
                val nlen = sqrt(ndx * ndx + ndy * ndy)
                if (nlen > 1e-7f && firstDx * ndx / nlen + firstDy * ndy / nlen < RibbonPathGeometry.TURN_SPLIT_DOT) break
                runEnd++
            }
            for (m in moveIndex until runEnd) {
                val o = m * GcodeNozzlePath.VALUES_PER_MOVE
                extrusionPrefix[m + 1] = extrusionMoves
                travelPrefix[m + 1] = travelMoves
                if (kind == GcodeNozzlePath.Kind.EXTRUSION.code) {
                    boundsVertex += source[o + GcodeNozzlePath.X1]
                    boundsVertex += source[o + GcodeNozzlePath.Y1]
                    boundsVertex += source[o + GcodeNozzlePath.Z1]
                    boundsVertex += source[o + GcodeNozzlePath.X2]
                    boundsVertex += source[o + GcodeNozzlePath.Y2]
                    boundsVertex += source[o + GcodeNozzlePath.Z2]
                }
            }
            val lastOffset = (runEnd - 1) * GcodeNozzlePath.VALUES_PER_MOVE
            val sx = source[windowOffset + GcodeNozzlePath.X1]
            val sy = source[windowOffset + GcodeNozzlePath.Y1]
            val sz = source[windowOffset + GcodeNozzlePath.Z1]
            val ex = source[lastOffset + GcodeNozzlePath.X2]
            val ey = source[lastOffset + GcodeNozzlePath.Y2]
            val ez = source[lastOffset + GcodeNozzlePath.Z2]
            // Run-wide totals: one width for the WHOLE natural run so every
            // stride window of the same line shares identical geometry -
            // micro-layer borders never alternate width/height.
            runChord = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy))
            runDeltaE = 0f
            runArc = 0f
            for (m in moveIndex until runEnd) {
                val mo = m * GcodeNozzlePath.VALUES_PER_MOVE
                runDeltaE += source[mo + GcodeNozzlePath.DELTA_E]
                val ax = source[mo + GcodeNozzlePath.X1]; val ay = source[mo + GcodeNozzlePath.Y1]
                val bx = source[mo + GcodeNozzlePath.X2]; val by = source[mo + GcodeNozzlePath.Y2]
                runArc += sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            }
            val runHeight = source[lastOffset + GcodeNozzlePath.LAYER_HEIGHT]
            val speedRatio = if (kind == GcodeNozzlePath.Kind.EXTRUSION.code && speedSpan > 0f) {
                ((source[windowOffset + GcodeNozzlePath.SPEED] - minSpeed) / speedSpan).coerceIn(0f, 1f)
            } else 0f
            val color = if (kind == GcodeNozzlePath.Kind.EXTRUSION.code) {
                hsv(extrusionHue(speedRatio), RIBBON_SATURATION, RIBBON_VALUE, 1f)
            } else floatArrayOf(0.50f, 0.54f, 0.64f, 0.20f)
            if (kind == GcodeNozzlePath.Kind.EXTRUSION.code) {
                // Emit stride-sized windows inside the run. Each window uses
                // the run deltaE scaled to ITS OWN chord, so the resolved
                // width equals the run width exactly (same numerator/denominator ratio).
                var windowStart = moveIndex
                while (windowStart < runEnd) {
                    val windowLast = min(windowStart + stride, runEnd) - 1
                    val wo = windowStart * GcodeNozzlePath.VALUES_PER_MOVE
                    val wlo = windowLast * GcodeNozzlePath.VALUES_PER_MOVE
                    val wsx = source[wo + GcodeNozzlePath.X1]
                    val wsy = source[wo + GcodeNozzlePath.Y1]
                    val wsz = source[wo + GcodeNozzlePath.Z1]
                    val wex = source[wlo + GcodeNozzlePath.X2]
                    val wey = source[wlo + GcodeNozzlePath.Y2]
                    val wez = source[wlo + GcodeNozzlePath.Z2]
                    val wChord = sqrt((wex - wsx) * (wex - wsx) + (wey - wsy) * (wey - wsy))
                    val windowDeltaE = if (runChord > 1e-6f) runDeltaE * (wChord / runChord) else runDeltaE
                    // Window boundaries are joints of the run: miter inside the
                    // run so the closing and opening windows share their corner
                    // coordinates, and a perpendicular cap where the run ends so
                    // bead ends are never skewed by a neighbour.
                    val currentDir = RibbonPathGeometry.unitDirection(wex - wsx, wey - wsy)
                    val previousDir = if (windowStart > moveIndex) {
                        val po = (windowStart - 1) * GcodeNozzlePath.VALUES_PER_MOVE
                        RibbonPathGeometry.unitDirection(
                            source[po + GcodeNozzlePath.X2] - source[po + GcodeNozzlePath.X1],
                            source[po + GcodeNozzlePath.Y2] - source[po + GcodeNozzlePath.Y1],
                        )
                    } else null
                    val nextDir = if (windowLast + 1 < runEnd) {
                        val no = (windowLast + 1) * GcodeNozzlePath.VALUES_PER_MOVE
                        RibbonPathGeometry.unitDirection(
                            source[no + GcodeNozzlePath.X2] - source[no + GcodeNozzlePath.X1],
                            source[no + GcodeNozzlePath.Y2] - source[no + GcodeNozzlePath.Y1],
                        )
                    } else null
                    val startNormal = RibbonPathGeometry.jointNormal(previousDir, currentDir)
                    val endNormal = RibbonPathGeometry.jointNormal(currentDir, nextDir)
                    addRibbonMove(
                        ribbonVertex, ribbonNormal, ribbonColor, ribbonAmbientValues,
                        wsx, wsy, wsz, wex, wey, wez,
                        windowDeltaE,
                        runHeight,
                        startNormal.first, startNormal.second,
                        endNormal.first, endNormal.second,
                        color,
                    )
                    extrusionMoves++
                    for (m in windowStart until windowLast) {
                        extrusionPrefix[m + 1] = extrusionMoves
                        travelPrefix[m + 1] = travelMoves
                    }
                    extrusionPrefix[windowLast + 1] = extrusionMoves
                    windowStart = windowLast + 1
                }
                extrusionPrefix[runEnd] = extrusionMoves
                travelPrefix[runEnd] = travelMoves
            } else {
                // Pure-Z layer transitions are not meaningful travel paths;
                // 599 of them at 0.08 mm would paint vertical scratches over
                // the whole part.
                val travelDx = ex - sx
                val travelDy = ey - sy
                val travelDz = ez - sz
                val travelLen = sqrt(travelDx * travelDx + travelDy * travelDy + travelDz * travelDz)
                val verticalOnly = travelLen > 1e-7f && sqrt(travelDx * travelDx + travelDy * travelDy) < travelLen * 0.25f
                if (!verticalOnly) {
                    travelVertex += sx; travelVertex += sy; travelVertex += sz
                    travelVertex += ex; travelVertex += ey; travelVertex += ez
                    repeat(2) { color.forEach { travelColor += it } }
                    travelMoves++
                }
                for (m in moveIndex until runEnd) {
                    travelPrefix[m + 1] = travelMoves
                    extrusionPrefix[m + 1] = extrusionMoves
                }
            }
            moveIndex = runEnd
        }
        ribbonPositions = ribbonVertex.toFloatBuffer()
        ribbonNormals = ribbonNormal.toFloatBuffer()
        ribbonColors = ribbonColor.toFloatBuffer()
        ribbonAmbient = ribbonAmbientValues.toFloatBuffer()
        travelPositions = travelVertex.toFloatBuffer()
        travelColors = travelColor.toFloatBuffer()

        // Camera bounds from the printed part only. Stray extrusion moves
        // (purge line at the plate corner, distant skirt loops) are trimmed
        // with per-axis percentiles so the orbit pivots on the model, not the
        // plate; the fallback keeps the view valid for travel-only paths.
        val bounds = NozzlePathBounds.printedBounds(boundsVertex.toFloatArray())
        if (bounds != null) {
            modelMinX = bounds[0]; modelMinY = bounds[1]; modelMinZ = bounds[2]
            modelMaxX = bounds[3]; modelMaxY = bounds[4]; modelMaxZ = bounds[5]
        } else {
            modelMinX = value.minX; modelMinY = value.minY; modelMinZ = value.minZ
            modelMaxX = value.maxX; modelMaxY = value.maxY; modelMaxZ = value.maxZ
        }
    }

    /**
     * Emits the physical bead for one extrusion move: a colored top face at
     * z + layer height plus shaded left/right side faces from z to z + height.
     * The width comes from the actual flow (deltaE * filament area / length /
     * layer height) bounded by the settings line width, so the geometry follows
     * the sliced settings (layer height, line width, flow).
     *
     * Lighting: each face carries ONE analytic normal (top = +Z, sides = the
     * perpendicular of the move direction). Constant normals per face mean a
     * constant luminance per facet - no interpolated gradients, so the render
     * stays crisp at every zoom and cannot produce corduroy moire. The
     * [ambient] channel carries the occlusion term: side faces get darker
     * toward the base (contact shadow) and odd layers keep the parity tint.
     */
    private fun addRibbonMove(
        vertex: DirectFloatSink,
        normals: DirectFloatSink,
        colors: DirectFloatSink,
        ambient: DirectFloatSink,
        sx: Float, sy: Float, sz: Float,
        ex: Float, ey: Float, ez: Float,
        deltaE: Float,
        parsedLayerHeight: Float,
        startNx: Float, startNy: Float,
        endNx: Float, endNy: Float,
        color: FloatArray,
    ) {
        val dx = ex - sx
        val dy = ey - sy
        val length = sqrt(dx * dx + dy * dy)
        val width = resolveBeadWidthMm(
            lengthMm = length,
            deltaE = deltaE,
            parsedLayerHeight = parsedLayerHeight,
            layerHeightFallback = beadHeight,
            lineWidth = beadLineWidth,
            filamentArea = filamentArea,
        )
        val height = if (parsedLayerHeight > 0.02f && parsedLayerHeight <= 2.0f) parsedLayerHeight else beadHeight
        // A degenerate (sub-cutoff) window would leave a hole in the wall; draw
        // it as a hairline bead so micro-layer prints stay solid. The inspector
        // keeps the true zero via its own readout path.
        val render = if (width <= 0f) beadLineWidth * HAIRLINE_WIDTH_RATIO else width
        // Subtle per-layer tint: odd layers are a touch darker so stacked beads
        // read as separate layers (the hue still follows speed/z). On fine-layer
        // prints the parity tint would band every micro-layer, so fade it out.
        val level = if (height > 0f) (ez / height).roundToInt() else 0
        val parity = if (level and 1 == 0) 1f else {
            if (height < FINE_LAYER_HEIGHT_MM) RIBBON_THIN_LAYER_TINT else RIBBON_LAYER_TINT
        }
        // On fine layers the side/top contrast at micro-layer scale creates
        // scalloped "teeth" along curved rims; soften it so micro-beads read as
        // one continuous surface.
        val fine = height <= FINE_LAYER_HEIGHT_MM
        // Geometry is shared with the Prusa renderer: corners are offset by the
        // boundary normals of the joint, so this window and its neighbours share
        // their corner coordinates exactly and walls stay seamless.
        RibbonPathGeometry.addStrip(
            vertex, normals, colors, ambient,
            sx, sy, sz, ex, ey, ez,
            render, height,
            startNx, startNy, endNx, endNy,
            color,
            parity * TOP_AMBIENT,
            parity * (if (fine) FINE_SIDE_BASE_AMBIENT else SIDE_BASE_AMBIENT),
            parity * (if (fine) FINE_SIDE_TOP_AMBIENT else SIDE_TOP_AMBIENT),
        )
    }

    private fun buildGrid(value: GcodeNozzlePath) {
        val width = max(modelMaxX - modelMinX, 1f)
        val depth = max(modelMaxY - modelMinY, 1f)
        val step = gridStep(max(width, depth))
        val minX = floor(modelMinX / step) * step
        val maxX = kotlin.math.ceil(modelMaxX / step) * step
        val minY = floor(modelMinY / step) * step
        val maxY = kotlin.math.ceil(modelMaxY / step) * step
        val xLines = ((maxX - minX) / step).toInt() + 1
        val yLines = ((maxY - minY) / step).toInt() + 1
        val buffer = allocate((xLines + yLines) * 2 * 3)
        val z = modelMinZ
        for (line in 0 until xLines) {
            val x = minX + line * step
            buffer.put(x); buffer.put(minY); buffer.put(z)
            buffer.put(x); buffer.put(maxY); buffer.put(z)
        }
        for (line in 0 until yLines) {
            val y = minY + line * step
            buffer.put(minX); buffer.put(y); buffer.put(z)
            buffer.put(maxX); buffer.put(y); buffer.put(z)
        }
        buffer.position(0)
        gridPositions = buffer
        gridVertexCount = (xLines + yLines) * 2
    }

    private fun buildMarker() {
        val current = path ?: return
        val offset = selectedMoveIndex.coerceIn(0, current.moveCount - 1) * GcodeNozzlePath.VALUES_PER_MOVE
        val x = current.moves[offset + GcodeNozzlePath.X2]
        val y = current.moves[offset + GcodeNozzlePath.Y2]
        val z = current.moves[offset + GcodeNozzlePath.Z2]
        val size = max(sceneRadius(current) * 0.022f, 0.9f)
        // Crosshair: three axis-aligned line pairs centred on the nozzle tip.
        val buffer = allocate(18)
        buffer.put(x - size); buffer.put(y); buffer.put(z)
        buffer.put(x + size); buffer.put(y); buffer.put(z)
        buffer.put(x); buffer.put(y - size); buffer.put(z)
        buffer.put(x); buffer.put(y + size); buffer.put(z)
        buffer.put(x); buffer.put(y); buffer.put(z - size)
        buffer.put(x); buffer.put(y); buffer.put(z + size)
        buffer.position(0)
        markerPositions = buffer
        markerVertexCount = 6
        // Soft amber halo behind the crosshair so the nozzle reads clearly
        // against the blue-to-red path colours.
        val glow = allocate(18)
        val glowSize = size * 1.7f
        glow.put(x - glowSize); glow.put(y); glow.put(z)
        glow.put(x + glowSize); glow.put(y); glow.put(z)
        glow.put(x); glow.put(y - glowSize); glow.put(z)
        glow.put(x); glow.put(y + glowSize); glow.put(z)
        glow.put(x); glow.put(y); glow.put(z - glowSize)
        glow.put(x); glow.put(y); glow.put(z + glowSize)
        glow.position(0)
        markerGlowPositions = glow
        markerGlowVertexCount = 6
    }

    /** Returns the full-path move index nearest to a tap (screen px), or -1. */
    fun pickNearestMove(screenX: Float, screenY: Float): Int {
        try {
            return pickNearestMoveUnsafe(screenX, screenY)
        } catch (error: Throwable) {
            Log.e(TAG, "Nozzle-path pick failed", error)
            return -1
        }
    }

    private fun pickNearestMoveUnsafe(screenX: Float, screenY: Float): Int {
        val current = path ?: return -1
        if (current.moveCount <= 0) return -1
        val aspect = viewportWidth.toFloat() / max(viewportHeight, 1)
        computeCamera(aspect)
        val source = current.moves
        var bestDistanceSq = Float.POSITIVE_INFINITY
        var bestIndex = -1
        var offset = 0
        for (moveIndex in 0 until current.moveCount) {
            project(source, offset + GcodeNozzlePath.X1, offset + GcodeNozzlePath.Y1, offset + GcodeNozzlePath.Z1)
            val ax = pickOut[0] / pickOut[3]
            val ay = pickOut[1] / pickOut[3]
            project(source, offset + GcodeNozzlePath.X2, offset + GcodeNozzlePath.Y2, offset + GcodeNozzlePath.Z2)
            val bx = pickOut[0] / pickOut[3]
            val by = pickOut[1] / pickOut[3]
            // NDC (y up) to screen pixels (y down).
            val sx1 = (ax + 1f) * 0.5f * viewportWidth
            val sy1 = (1f - ay) * 0.5f * viewportHeight
            val sx2 = (bx + 1f) * 0.5f * viewportWidth
            val sy2 = (1f - by) * 0.5f * viewportHeight
            val distanceSq = segmentDistanceSq(screenX, screenY, sx1, sy1, sx2, sy2)
            if (distanceSq < bestDistanceSq) {
                // Ignore points behind the camera.
                val behind = pickOut[3] <= 0f
                if (!behind) {
                    bestDistanceSq = distanceSq
                    bestIndex = moveIndex
                }
            }
            offset += GcodeNozzlePath.VALUES_PER_MOVE
        }
        return bestIndex
    }

    /**
     * Uploads the ribbon/travel geometry into vertex buffer objects (GPU
     * memory) once per path; color-mode flips re-upload. Falls back silently
     * to client-side pointers if the driver allocates no buffer ids.
     */
    private fun ensureUploads(current: GcodeNozzlePath) {
        if (uploadedPath === current && pathVbos.size == 6) return
        if (pathVbos.size != 6) {
            val ids = IntArray(6)
            GLES20.glGenBuffers(6, ids, 0)
            pathVbos = ids
        }
        val buffers = listOf(ribbonPositions, ribbonNormals, ribbonColors, ribbonAmbient, travelPositions, travelColors)
        for (index in buffers.indices) {
            val data = buffers[index] ?: continue
            data.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pathVbos[index])
            GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER,
                data.remaining() * Float.SIZE_BYTES,
                data,
                GLES20.GL_STATIC_DRAW,
            )
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        uploadedPath = current
    }

    private fun drawLitTriangles(
        positions: FloatBuffer?,
        normals: FloatBuffer?,
        colors: FloatBuffer?,
        ambient: FloatBuffer?,
        vertexCount: Int,
    ) {
        if (positions == null || normals == null || colors == null || ambient == null || vertexCount <= 0) return
        GLES20.glUseProgram(litProgram)
        val position = GLES20.glGetAttribLocation(litProgram, "aPosition")
        val normal = GLES20.glGetAttribLocation(litProgram, "aNormal")
        val color = GLES20.glGetAttribLocation(litProgram, "aColor")
        val ambientLoc = GLES20.glGetAttribLocation(litProgram, "aAmbient")
        val matrix = GLES20.glGetUniformLocation(litProgram, "uMvpMatrix")
        val sceneMatrix = GLES20.glGetUniformLocation(litProgram, "uSceneMatrix")
        val keyDir = GLES20.glGetUniformLocation(litProgram, "uKeyDir")
        val fillDir = GLES20.glGetUniformLocation(litProgram, "uFillDir")
        val viewDir = GLES20.glGetUniformLocation(litProgram, "uViewDir")
        positions.position(0)
        normals.position(0)
        colors.position(0)
        ambient.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glEnableVertexAttribArray(color)
        GLES20.glEnableVertexAttribArray(ambientLoc)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, positions)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 12, normals)
        GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, 16, colors)
        GLES20.glVertexAttribPointer(ambientLoc, 1, GLES20.GL_FLOAT, false, 4, ambient)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(sceneMatrix, 1, false, scene, 0)
        GLES20.glUniform3f(keyDir, KEY_LIGHT[0], KEY_LIGHT[1], KEY_LIGHT[2])
        GLES20.glUniform3f(fillDir, FILL_LIGHT[0], FILL_LIGHT[1], FILL_LIGHT[2])
        GLES20.glUniform3f(viewDir, 0f, -VIEW_EYE_Y, VIEW_EYE_Z)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glDisableVertexAttribArray(color)
        GLES20.glDisableVertexAttribArray(ambientLoc)
    }

    private fun drawColoredLines(positions: FloatBuffer?, colors: FloatBuffer?, vertexCount: Int, width: Float) {
        if (positions == null || colors == null || vertexCount <= 0) return
        GLES20.glUseProgram(colorProgram)
        val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
        val color = GLES20.glGetAttribLocation(colorProgram, "aColor")
        val matrix = GLES20.glGetUniformLocation(colorProgram, "uMvpMatrix")
        positions.position(0)
        colors.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(color)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, positions)
        GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, 16, colors)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        lineWidth(width)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)
        GLES20.glLineWidth(1f)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
    }

    /** Moves the orbit pivot to the pending touch point (ray-plane intersection). */
    private fun cameraDistance(): Float {
        val current = path ?: return 300f
        return max(sceneRadius(current) * 3.4f / zoom, 2f)
    }

    private fun sceneRadius(value: GcodeNozzlePath): Float {
        val dx = max(modelMaxX - modelMinX, 1f)
        val dy = max(modelMaxY - modelMinY, 1f)
        val dz = max(modelMaxZ - modelMinZ, 1f)
        return sqrt(dx * dx + dy * dy + dz * dz) * 0.5f
    }

    companion object {
        private const val TAG = "NozzlePathView"
        private const val FIELD_OF_VIEW = 42f
        private const val DEFAULT_YAW = -32f
        private const val DEFAULT_PITCH = 58f
        private const val DEFAULT_ZOOM = 1f
        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 60f
        private const val TRAVEL_WIDTH = 1.5f
        private const val HAIRLINE_WIDTH_RATIO = 0.5f
        private const val RIBBON_THIN_LAYER_TINT = 0.995f
        // Fixed WORLD-space studio rig: the bead path rotates under the lights
        // while orbiting, so faces shade consistently from every angle. These
        // are directions TOWARD the light.
        // Eye sits at (0, -distance, 0.62*distance) in view space.
        // Ambient terms: the top face catches the key light head-on; side faces
        // darken toward the base so beads look seated on the layer below, and
        // odd layers keep a subtle tint so stacked layers separate.
        private const val TOP_AMBIENT = 0.97f
        private const val SIDE_TOP_AMBIENT = 0.92f
        private const val SIDE_BASE_AMBIENT = 0.74f
        private const val FINE_SIDE_BASE_AMBIENT = 0.88f
        private const val FINE_SIDE_TOP_AMBIENT = 0.95f
        // Odd layers render a touch darker so layers separate visually.
        private const val RIBBON_LAYER_TINT = 0.96f

        private const val LIT_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uSceneMatrix;
            attribute vec4 aPosition;
            attribute vec3 aNormal;
            attribute vec4 aColor;
            attribute float aAmbient;
            varying vec4 vColor;
            varying vec3 vWorldNormal;
            varying float vAmbient;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vColor = aColor;
                vWorldNormal = normalize((uSceneMatrix * vec4(aNormal, 0.0)).xyz);
                vAmbient = aAmbient;
            }
        """
        private const val LIT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;
            varying vec3 vWorldNormal;
            varying float vAmbient;
            uniform vec3 uKeyDir;
            uniform vec3 uFillDir;
            uniform vec3 uViewDir;
            void main() {
                vec3 n = normalize(vWorldNormal);
                vec3 key = normalize(uKeyDir);
                vec3 fill = normalize(uFillDir);
                vec3 v = normalize(uViewDir);
                float kd = max(dot(n, key), 0.0);
                float fd = max(dot(n, fill), 0.0);
                float rim = pow(1.0 - max(dot(n, v), 0.0), 2.0);
                vec3 halfV = normalize(key + v);
                float spec = pow(max(dot(n, halfV), 0.0), 32.0);
                float light = vAmbient * (0.40 + 0.62 * kd + 0.26 * fd + 0.14 * rim);
                vec3 lit = vColor.rgb * light + vec3(0.10 * spec);
                gl_FragColor = vec4(lit, vColor.a);
            }
        """
        private const val COLOR_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            attribute vec4 aPosition;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vColor = aColor;
            }
        """
        private const val COLOR_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;
            void main() {
                gl_FragColor = vColor;
            }
        """
        private const val SOLID_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            attribute vec4 aPosition;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
            }
        """
        private const val SOLID_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}

/**
 * Viewport scale shared by both nozzle-path renderers.
 *
 * The cameras look from (0, -distance, 0.62 * distance), so the eye sits
 * [CAMERA_EYE_DISTANCE_SCALE] times the fitted distance away from the orbit
 * pivot, and it is that eye distance - not the fitted distance - that sets both
 * the perspective view's apparent size and the two-finger pan step. An
 * orthographic projection has no eye of its own, so its half-height has to come
 * from the same value: deriving it from the fitted distance alone drew the part
 * 17.7% larger than the perspective view and moved it 17.7% further than the
 * finger on a drag.
 */
internal object NozzlePathViewCamera {
    /** Eye distance from the pivot when the look-at places the eye at [distance]. */
    const val CAMERA_EYE_DISTANCE_SCALE = 1.17666f

    /** World height the viewport spans at the orbit pivot; the pan step is this per viewport height. */
    fun visibleHeightAtPivot(distance: Float, fieldOfViewDegrees: Float): Float =
        2f * distance * CAMERA_EYE_DISTANCE_SCALE *
            tan(Math.toRadians(fieldOfViewDegrees / 2.0)).toFloat()

    /** Half-height of the orthographic projection: exactly half of [visibleHeightAtPivot]. */
    fun orthographicHalfHeight(distance: Float, fieldOfViewDegrees: Float): Float =
        0.5f * visibleHeightAtPivot(distance, fieldOfViewDegrees)
}
