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
 * Growable direct FloatBuffer, for the small buffers the preview needs whole
 * (the plate grid, the nozzle marker, the camera-fit bounds). The ribbon itself
 * is staged a chunk at a time through [RibbonChunkStaging]: Android charges
 * direct ByteBuffers against the Java heap, so one buffer per path is what used
 * to OOM the Path tab.
 */
internal class DirectFloatSink(initialCapacity: Int = 4096) : FloatSink {
    private var buffer: FloatBuffer = alloc(max(initialCapacity, 16))
    private var count = 0

    private fun alloc(capacity: Int): FloatBuffer =
        ByteBuffer.allocateDirect(capacity * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    override operator fun plusAssign(value: Float) {
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
        pathRenderer.onPreviewStatus = { status -> post { onPreviewStatus?.invoke(status) } }
    }

    fun setPath(
        path: GcodeNozzlePath,
        selectedMoveIndex: Int,
        beadHeightMm: Float,
        beadLineWidthMm: Float,
        filamentDiameterMm: Float,
        previewCapacity: PreviewCapacity,
    ) {
        queueEvent {
            pathRenderer.setPath(path, beadHeightMm, beadLineWidthMm, filamentDiameterMm, previewCapacity)
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

    /** Invoked on the main thread with what the preview is holding: whole path, or a band. */
    var onPreviewStatus: ((String) -> Unit)? = null

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
    // Physical bead parameters from the current slice settings (fallbacks
    // when the parsed per-move flow cannot be trusted).
    private var beadHeight = 0.20f
    private var beadLineWidth = 0.40f
    private var filamentArea = Math.PI.toFloat() * 0.875f * 0.875f
    // The band of the path the vertex buffers currently hold. The buffers are allocated once
    // per path at the capacity the device granted, and a new band is uploaded when the scrub
    // leaves the middle of the one in view - see RibbonBand.
    private var bandFirst = 0
    private var bandLast = 0
    private var bandWindows = 0
    private var bandTravels = 0
    // The band's own prefix up to the scrub: the picture still builds up as the slider moves,
    // and the part of the band below the anchor is the context around the current layer. The
    // prefixes come from the build, so moving the scrub inside the band costs nothing.
    private var bandDrawWindows = 0
    private var bandDrawTravels = 0
    private var bandWindowPrefix = IntArray(0)
    private var bandTravelPrefix = IntArray(0)
    private var bandPending = false
    private var bandCapacityWindows = 0
    private var bandCapacityTravels = 0
    private var pathWindows = 0
    private var grantedWindows = 0
    private var capacity = PreviewCapacity.Default
    // The geometry is built on the first frame after a path is set: a queued
    // setPath can run before the surface (and so the GL context) exists, and
    // uploading needs one.
    private var buildPending = false
    @Volatile private var showTravels = true
    @Volatile var orthographic = false
    private var gridPositions: FloatBuffer? = null
    private var gridVertexCount = 0
    private var markerPositions: FloatBuffer? = null
    private var markerVertexCount = 0
    private var markerGlowPositions: FloatBuffer? = null
    private var markerGlowVertexCount = 0

    private var viewportWidth = 1
    private var viewportHeight = 1
    // Live build progress for the UI; reports every BUILD_PROGRESS_STRIDE moves.
    var onBuildProgress: ((Float) -> Unit)? = null
    var onBuildFinished: ((String?) -> Unit)? = null
    /** What the preview is holding now: the whole path, or the band the device granted. */
    var onPreviewStatus: ((String) -> Unit)? = null
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
        previewCapacity: PreviewCapacity,
    ) {
        val area = (Math.PI.toFloat() * (filamentDiameterMm.coerceIn(0.5f, 4.0f) / 2f).let { it * it })
        // Line width and filament diameter change the ribbon as much as the bead height does:
        // comparing only the first two let the geometry and the readout disagree.
        if (path === value && beadHeight == beadHeightMm && beadLineWidth == beadLineWidthMm &&
            filamentArea == area && capacity == previewCapacity
        ) {
            return
        }
        val capacityChanged = capacity != previewCapacity
        capacity = previewCapacity
        path = value
        beadHeight = beadHeightMm.coerceIn(0.02f, 2.0f)
        beadLineWidth = beadLineWidthMm.coerceIn(0.10f, 2.0f)
        filamentArea = area
        selectedMoveIndex = if (value.moveCount <= 0) 0 else selectedMoveIndex.coerceIn(0, value.moveCount - 1)
        if (capacityChanged || buildPending) onBuildProgress?.invoke(0f)
        buildPending = true
        if (capacityChanged) resetCamera()
    }

    /**
     * Prepares a path: the camera bounds, the grid, the marker, and the vertex buffers at the
     * capacity the device granted. The geometry itself is built a band at a time.
     *
     * One window per move inside the band, always: the picture is the file. The staging
     * buffers are filled a chunk at a time, copied into VRAM and reused, so the Java heap
     * never holds the ribbon - and the GPU only ever holds the band in view.
     */
    private fun preparePath() {
        val value = path ?: return
        buildPending = false
        val startedAt = System.nanoTime()
        try {
            val totalWindows = RibbonPathGeometry.countKind(
                value.moves,
                value.moveCount,
                GcodeNozzlePath.Kind.EXTRUSION.code,
            )
            val totalTravels = RibbonPathGeometry.countKind(
                value.moves,
                value.moveCount,
                GcodeNozzlePath.Kind.TRAVEL.code,
            )
            pathWindows = totalWindows
            val askedWindows = min(capacity.windows, totalWindows).coerceAtLeast(1)
            val askedTravels = min(capacity.travels, totalTravels).coerceAtLeast(1)
            val granted = allocateAdaptiveWindows(askedWindows, askedTravels, RibbonBand.MINIMUM_WINDOWS)
            if (granted <= 0) {
                throw IllegalStateException(BandAllocator.refusal(askedWindows, RibbonBand.MINIMUM_WINDOWS))
            }
            bandCapacityWindows = granted
            bandCapacityTravels = scaledTravels(askedTravels, askedWindows, granted)
            grantedWindows = granted
            applyCameraBounds(extrusionBoundsVertices(value), value)
            buildGrid(value)
            buildMarker()
            bandPending = true
            Log.i(
                TAG,
                "nozzle-path buffers: asked " + askedWindows + " windows, granted " + granted +
                    " in " + ((System.nanoTime() - startedAt) / 1_000_000) + " ms",
            )
        } catch (error: Throwable) {
            // Keep the render thread alive and surface the cause in logcat
            // instead of taking the app down.
            Log.e(TAG, "Unable to build nozzle-path buffers", error)
            pathGeometry.release()
            onBuildFinished?.invoke(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Builds the band of moves around the scrub into the vertex buffers, every move in the band
     * drawn, and reports what the preview is holding.
     */
    private fun buildBand() {
        val value = path ?: return
        bandPending = false
        if (!pathGeometry.ready || value.moveCount <= 0) return
        val startedAt = System.nanoTime()
        try {
            val anchor = selectedMoveIndex.coerceIn(0, value.moveCount - 1)
            val range = RibbonBand.plan(
                value.moves,
                value.moveCount,
                anchor,
                bandCapacityWindows,
                bandCapacityTravels,
            )
            pathGeometry.reuse()
            val builder = CuraRibbonBuilder(value, beadHeight, beadLineWidth, filamentArea) { fraction ->
                onBuildProgress?.invoke(fraction)
            }
            val staging = RibbonChunkStaging(pathGeometry)
            builder.build(staging, range.first, range.last + 1)
            staging.finish()
            bandFirst = range.first
            bandLast = range.last + 1
            bandWindows = builder.extrusionWindows
            bandTravels = builder.travelSegments
            bandWindowPrefix = builder.extrusionPrefix
            bandTravelPrefix = builder.travelPrefix
            updateBandDraw(anchor)
            buildMarker()
            onBuildProgress?.invoke(1f)
            onBuildFinished?.invoke(null)
            onPreviewStatus?.invoke(
                if (bandFirst == 0 && bandLast == value.moveCount) {
                    "Whole path · " + bandWindows + " windows in the vertex buffers · " + capacity.note
                } else {
                    "Banded · the device granted " + grantedWindows + " windows of the " + pathWindows +
                        " this path needs · moves " + bandFirst + "–" + (bandLast - 1) + " of " +
                        value.moveCount + " · " + capacity.note
                },
            )
            Log.i(
                TAG,
                "nozzle-path band built: moves " + bandFirst + ".." + bandLast + ", windows " + bandWindows +
                    ", travels " + bandTravels + " in " + ((System.nanoTime() - startedAt) / 1_000_000) + " ms",
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to build the nozzle-path band", error)
            onBuildFinished?.invoke(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Camera bounds from the printed part only. Stray extrusion moves (purge
     * line at the plate corner, distant skirt loops) are trimmed with per-axis
     * percentiles so the orbit pivots on the model, not the plate; the fallback
     * keeps the view valid for travel-only paths.
     */
    private fun applyCameraBounds(boundsVertices: FloatArray, value: GcodeNozzlePath) {
        val bounds = NozzlePathBounds.printedBounds(boundsVertices)
        if (bounds != null) {
            modelMinX = bounds[0]; modelMinY = bounds[1]; modelMinZ = bounds[2]
            modelMaxX = bounds[3]; modelMaxY = bounds[4]; modelMaxZ = bounds[5]
        } else {
            modelMinX = value.minX; modelMinY = value.minY; modelMinZ = value.minZ
            modelMaxX = value.maxX; modelMaxY = value.maxY; modelMaxZ = value.maxZ
        }
    }

    fun setSelectedMove(value: Int) {
        val current = path ?: return
        if (current.moveCount <= 0) return
        val safe = value.coerceIn(0, current.moveCount - 1)
        if (safe == selectedMoveIndex) return
        selectedMoveIndex = safe
        buildMarker()
        if (bandNeedsRebuild(safe, current.moveCount)) bandPending = true else updateBandDraw(safe)
    }

    private fun bandNeedsRebuild(anchor: Int, moveCount: Int): Boolean =
        RibbonBand.needsRebuild(bandFirst, bandLast, moveCount, anchor)

    /** Points the draw at the band's prefix up to [anchor], which is what the scrub reveals. */
    private fun updateBandDraw(anchor: Int) {
        val move = anchor.coerceIn(bandFirst, (bandLast - 1).coerceAtLeast(bandFirst))
        bandDrawWindows = bandWindowPrefix.getOrElse(move + 1) { bandWindows }.coerceIn(0, bandWindows)
        bandDrawTravels = bandTravelPrefix.getOrElse(move + 1) { bandTravels }.coerceIn(0, bandTravels)
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
        // A new GL context invalidates the old buffer ids: the geometry has to
        // be built into this one again.
        pathGeometry.forget()
        if (path != null) buildPending = true
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
            if (buildPending) preparePath()
            if (bandPending) buildBand()
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

        drawSolidLines(gridPositions, gridVertexCount, 1f, 0.24f, 0.30f, 0.40f, 0.48f)
        if (pathGeometry.ready) {
            // Physical beads in one lit pass: per-face analytic normals with a
            // fixed 3-light rig (key + fill + rim) so the bead facets read as a
            // solid printed part from every orbit angle without the zoomed-out
            // moire that interpolated normals caused. The clamp is the same
            // guard the Prusa renderer carries: never draw past what was built.
            val litVertices = min(
                bandDrawWindows * RibbonPathGeometry.WINDOW_VERTICES,
                pathGeometry.ribbonVertexCount,
            )
            drawLitTrianglesVbo(litVertices)
            if (showTravels) {
                val travelVertices = min(bandDrawTravels * 2, pathGeometry.travelVertexCount)
                drawColoredLinesVbo(travelVertices, TRAVEL_WIDTH)
            }
        }
        drawSolidLines(markerGlowPositions, markerGlowVertexCount, 8f, 0.85f, 0.55f, 0.30f, 0.20f)
        drawSolidLines(markerPositions, markerVertexCount, 4.5f, 1f, 1f, 1f, 1f)
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
        // Fixed WORLD-space studio rig: the bead path rotates under the lights
        // while orbiting, so faces shade consistently from every angle. These
        // are directions TOWARD the light.
        // Eye sits at (0, -distance, 0.62*distance) in view space. The ambient
        // terms and the layer tints live with the geometry that uses them
        // (RibbonPathBuilders.kt).

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
