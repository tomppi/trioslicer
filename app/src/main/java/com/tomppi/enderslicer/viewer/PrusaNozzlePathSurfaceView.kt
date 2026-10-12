package com.tomppi.enderslicer.viewer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.tomppi.enderslicer.engine.PrusaNozzlePath
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * GPU-backed nozzle-path renderer for PrusaSlicer output.
 * Geometry comes from Prusa’s own ;WIDTH: / ;HEIGHT: markers (authoritative bead
 * width and layer height), never from extrusion-delta estimation. All geometry
 * lives in vertex buffer objects (GPU memory), uploaded once per path.
 * Features: turntable orbit, pinch zoom, two-finger pan, double-tap reset,
 * tap-to-pick, grid, nozzle crosshair, travel lines, speed colors, ortho mode.
 */
class PrusaNozzlePathSurfaceView(context: Context) : GLSurfaceView(context) {
    private val pathRenderer = PrusaNozzlePathRenderer()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private var previousX = 0f
    private var previousY = 0f
    private var previousFocusX = 0f
    private var previousFocusY = 0f
    private var panning = false

    init {
        setEGLContextClientVersion(2)
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

    private var lastQueuedPath: PrusaNozzlePath? = null
    private var lastQueuedMove = -1

    fun setPath(path: PrusaNozzlePath, selectedMoveIndex: Int) {
        // AndroidView.update fires on every recomposition (playback ticks
        // included); skip unchanged calls so the GL queue is not flooded.
        if (lastQueuedPath === path && lastQueuedMove == selectedMoveIndex) return
        lastQueuedPath = path
        lastQueuedMove = selectedMoveIndex
        queueEvent {
            pathRenderer.setPath(path)
            pathRenderer.setSelectedMove(selectedMoveIndex)
        }
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

    /** Orthographic (true-width) camera for measuring the path. */
    var orthographic: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            queueEvent { pathRenderer.orthographic = value }
            requestRender()
        }

    var onOrientationChanged: ((ViewerOrientation) -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null
    var onMovePicked: ((Int) -> Unit)? = null

    /** Invoked on the main thread with ribbon-build progress (0..1). */
    var onBuildProgress: ((Float) -> Unit)? = null

    /** Invoked on the main thread when the geometry build settles; null = success. */
    var onBuildFinished: ((String?) -> Unit)? = null

    fun currentOrientation(): ViewerOrientation = pathRenderer.orientation

    fun currentZoom(): Float = pathRenderer.zoomLevel

    /** Resets yaw, pitch, zoom, pan and the orbit pivot back to the model fit. */
    fun resetView() {
        queueEvent { pathRenderer.resetCamera() }
        queueEvent { notifyOrientation() }
        queueEvent { notifyZoom() }
        requestRender()
    }

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
                    if (panning) pathRenderer.panPixels(focusX - previousFocusX, focusY - previousFocusY)
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
}

/** GL renderer: geometry build, VBO upload, draw, camera, picking. */
internal class PrusaNozzlePathRenderer : NozzlePathRendererBase() {
    var orthographic = false

    private var path: PrusaNozzlePath? = null
    private var selectedMoveIndex = 0
    private var showTravels = true

    // The geometry is built on the first frame after a path is set: a queued
    // setPath can run before the surface (and so the GL context) exists, and
    // uploading needs one.
    private var buildPending = false
    private var gridPositions: FloatBuffer? = null
    private var gridVertexCount = 0
    private var markerPositions: FloatBuffer? = null
    private var markerGlowPositions: FloatBuffer? = null
    private var markerVertexCount = 0
    private var markerGlowVertexCount = 0
    internal var ribbonPrefix = IntArray(1)
    internal var travelPrefix = IntArray(1)

    private var modelMinX = -115f
    private var modelMaxX = 115f
    private var modelMinY = -115f
    private var modelMaxY = 115f
    private var modelMinZ = 0f
    private var modelMaxZ = 250f
    private var viewportWidth = 1
    private var viewportHeight = 1
    // Live build progress for the UI; reports every BUILD_PROGRESS_STRIDE moves.
    var onBuildProgress: ((Float) -> Unit)? = null
    var onBuildFinished: ((String?) -> Unit)? = null
    private var yaw = PrusaNozzlePathViewDefaults.DEFAULT_YAW
    private var pitch = PrusaNozzlePathViewDefaults.DEFAULT_PITCH
    private var zoom = PrusaNozzlePathViewDefaults.DEFAULT_ZOOM
    private var panX = 0f
    private var panY = 0f

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val modelView = FloatArray(16)

    val orientation: ViewerOrientation
        get() = ViewerOrientation(yaw, pitch)

    val zoomLevel: Float
        get() = zoom

    fun setPath(value: PrusaNozzlePath) {
        if (path === value) return
        path = value
        selectedMoveIndex = if (value.moveCount <= 0) 0 else selectedMoveIndex.coerceIn(0, value.moveCount - 1)
        onBuildProgress?.invoke(0f)
        buildPending = true
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

    fun setShowTravels(value: Boolean) { showTravels = value }

    fun rotate(deltaYaw: Float, deltaPitch: Float) {
        yaw = wrapDegrees(yaw + deltaYaw)
        pitch = wrapDegrees(pitch + deltaPitch)
    }

    fun zoom(scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        zoom = (zoom * scaleFactor).coerceIn(PrusaNozzlePathViewDefaults.MIN_ZOOM, PrusaNozzlePathViewDefaults.MAX_ZOOM)
    }

    fun panPixels(deltaX: Float, deltaY: Float) {
        if (!deltaX.isFinite() || !deltaY.isFinite()) return
        val visibleHeight = NozzlePathViewCamera.visibleHeightAtPivot(
            cameraDistance(),
            PrusaNozzlePathViewDefaults.FIELD_OF_VIEW,
        )
        val worldPerPixel = visibleHeight / max(viewportHeight, 1)
        panX += deltaX * worldPerPixel
        panY -= deltaY * worldPerPixel
    }

    fun resetCamera() {
        yaw = PrusaNozzlePathViewDefaults.DEFAULT_YAW
        pitch = PrusaNozzlePathViewDefaults.DEFAULT_PITCH
        zoom = PrusaNozzlePathViewDefaults.DEFAULT_ZOOM
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
        litProgram = createGlProgram(LIT_VERTEX_SHADER, LIT_FRAGMENT_SHADER)
        colorProgram = createGlProgram(COLOR_VERTEX_SHADER, COLOR_FRAGMENT_SHADER)
        solidProgram = createGlProgram(SOLID_VERTEX_SHADER, SOLID_FRAGMENT_SHADER)
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

    override fun onDrawFrame(gl: GL10?) {
        try {
            if (buildPending) buildGeometry()
            drawFrame()
        } catch (error: Throwable) {
            Log.e("PrusaNozzlePathView", "frame failed", error)
        }
    }

    private fun drawFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val current = path ?: return
        val aspect = viewportWidth.toFloat() / viewportHeight
        computeCamera(aspect)
        val upTo = (selectedMoveIndex + 1).coerceAtMost(ribbonPrefix.size - 1)
        val ribbonCount = ribbonPrefix[upTo]
        drawSolidLines(gridPositions, gridVertexCount, 1f, 0.24f, 0.30f, 0.40f, 0.48f)
        if (pathGeometry.ready) {
            // Hard clamp: never draw past the geometry that was uploaded. A
            // mismatch here previously produced "model disappears after a
            // certain point" and scrambled speed colors; the clamp degrades to
            // fewer ribbons and logs the mismatch for diagnosis.
            val requested = ribbonCount * RibbonPathGeometry.WINDOW_VERTICES
            val litVerts = if (requested <= pathGeometry.ribbonVertexCount) {
                requested
            } else {
                Log.e(
                    "PrusaNozzlePathView",
                    "ribbon overdraw: requested " + requested + " vertices, have " + pathGeometry.ribbonVertexCount,
                )
                pathGeometry.ribbonVertexCount
            }
            drawLitTrianglesVbo(litVerts)
            if (showTravels) {
                val travelRequested = travelPrefix[upTo] * 2
                val travelVerts = if (travelRequested <= pathGeometry.travelVertexCount) {
                    travelRequested
                } else {
                    Log.e(
                        "PrusaNozzlePathView",
                        "travel overdraw: requested " + travelRequested + " vertices, have " + pathGeometry.travelVertexCount,
                    )
                    pathGeometry.travelVertexCount
                }
                drawColoredLinesVbo(travelVerts, PrusaNozzlePathViewDefaults.TRAVEL_WIDTH)
            }
        }
        drawSolidLines(markerGlowPositions, markerGlowVertexCount, 8f, 0.85f, 0.55f, 0.30f, 0.20f)
        drawSolidLines(markerPositions, markerVertexCount, 4.5f, 1f, 1f, 1f, 1f)
    }
    /**
     * Builds the ribbon strips and uploads them to the GPU buffers, one bounded
     * chunk at a time.
     *
     * One window per move, always, sized by the marker-driven width of the run it
     * belongs to. What changed is where the geometry is allowed to exist: it
     * passes through the CPU a chunk at a time, so a million moves no longer asks
     * the Java heap for the 792 MB of ribbon they make - Android charges direct
     * ByteBuffers there, and the Path tab died on a single 160 MB allocation of
     * exactly this geometry.
     */
    private fun buildGeometry() {
        val value = path ?: return
        buildPending = false
        val startedAt = System.nanoTime()
        try {
            val ribbonWindows = RibbonPathGeometry.countKind(
                value.moves,
                value.moveCount,
                PrusaNozzlePath.Kind.EXTRUSION.code,
            )
            val travelBound = RibbonPathGeometry.countKind(
                value.moves,
                value.moveCount,
                PrusaNozzlePath.Kind.TRAVEL.code,
            )
            if (!pathGeometry.allocate(ribbonWindows, travelBound)) {
                throw IllegalStateException(
                    "The device refused the preview's vertex buffers for " + value.moveCount + " moves",
                )
            }
            val builder = PrusaRibbonBuilder(value) { fraction -> onBuildProgress?.invoke(fraction) }
            val staging = RibbonChunkStaging(pathGeometry)
            builder.build(staging)
            staging.finish()
            ribbonPrefix = builder.ribbonPrefix
            travelPrefix = builder.travelPrefix
            // Camera bounds from the printed part only (extrusion moves). A
            // travel-only path keeps the defaults.
            if (builder.modelMinX.isFinite()) {
                modelMinX = builder.modelMinX
                modelMaxX = builder.modelMaxX
                modelMinY = builder.modelMinY
                modelMaxY = builder.modelMaxY
                modelMinZ = builder.modelMinZ
                modelMaxZ = builder.modelMaxZ
            }
            buildGrid(value)
            buildMarker()
            onBuildProgress?.invoke(1f)
            onBuildFinished?.invoke(null)
            Log.i(
                "PrusaNozzlePathView",
                "geometry built: " + value.moveCount + " moves in " +
                    ((System.nanoTime() - startedAt) / 1_000_000) + " ms",
            )
        } catch (error: Throwable) {
            Log.e("PrusaNozzlePathView", "Unable to build prusa nozzle-path buffers", error)
            pathGeometry.release()
            onBuildFinished?.invoke(error.message ?: error.javaClass.simpleName)
        }
    }
    private fun buildGrid(value: PrusaNozzlePath) {
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
        val offset = selectedMoveIndex.coerceIn(0, current.moveCount - 1) * PrusaNozzlePath.VALUES_PER_MOVE
        val x = current.moves[offset + PrusaNozzlePath.X2]
        val y = current.moves[offset + PrusaNozzlePath.Y2]
        val z = current.moves[offset + PrusaNozzlePath.Z2]
        val size = max(sceneRadius(current) * 0.022f, 0.9f)
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

    fun pickNearestMove(screenX: Float, screenY: Float): Int {
        try {
            return pickNearestMoveUnsafe(screenX, screenY)
        } catch (error: Throwable) {
            Log.e("PrusaNozzlePathView", "pick failed", error)
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
        for (moveIndex in 0 until current.moveCount) {
            val offset = moveIndex * PrusaNozzlePath.VALUES_PER_MOVE
            project(source, offset + PrusaNozzlePath.X1, offset + PrusaNozzlePath.Y1, offset + PrusaNozzlePath.Z1)
            val ax = pickOut[0] / pickOut[3]
            val ay = pickOut[1] / pickOut[3]
            project(source, offset + PrusaNozzlePath.X2, offset + PrusaNozzlePath.Y2, offset + PrusaNozzlePath.Z2)
            val bx = pickOut[0] / pickOut[3]
            val by = pickOut[1] / pickOut[3]
            val sx1 = (ax + 1f) * 0.5f * viewportWidth
            val sy1 = (1f - ay) * 0.5f * viewportHeight
            val sx2 = (bx + 1f) * 0.5f * viewportWidth
            val sy2 = (1f - by) * 0.5f * viewportHeight
            val distanceSq = segmentDistanceSq(screenX, screenY, sx1, sy1, sx2, sy2)
            if (distanceSq < bestDistanceSq) {
                val behind = pickOut[3] <= 0f
                if (!behind) {
                    bestDistanceSq = distanceSq
                    bestIndex = moveIndex
                }
            }
        }
        return bestIndex
    }

    private fun computeCamera(aspect: Float): Float {
        val distance = cameraDistance()
        val radius = sceneRadius(requireNotNull(path))
        val nearPlane = max(0.05f, distance - radius * 1.6f)
        val farPlane = max(nearPlane + 100f, distance + radius * 2.8f + 100f)
        if (orthographic) {
            // Orthographic measurement mode: the half-height comes from the
            // eye distance the pan and the perspective view use, or the part
            // jumps in apparent size when the toggle flips.
            val halfHeight = NozzlePathViewCamera.orthographicHalfHeight(
                distance,
                PrusaNozzlePathViewDefaults.FIELD_OF_VIEW,
            )
            val halfWidth = halfHeight * aspect
            Matrix.orthoM(projection, 0, -halfWidth, halfWidth, -halfHeight, halfHeight, nearPlane, farPlane)
        } else {
            Matrix.perspectiveM(projection, 0, PrusaNozzlePathViewDefaults.FIELD_OF_VIEW, aspect, nearPlane, farPlane)
        }
        Matrix.setLookAtM(view, 0, 0f, -distance, distance * 0.62f, 0f, 0f, 0f, 0f, 0f, 1f)
        Matrix.translateM(view, 0, panX, panY, 0f)
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

    private fun cameraDistance(): Float {
        val current = path ?: return 300f
        return max(sceneRadius(current) * 3.4f / zoom, 2f)
    }

    private fun sceneRadius(value: PrusaNozzlePath): Float {
        val dx = max(modelMaxX - modelMinX, 1f)
        val dy = max(modelMaxY - modelMinY, 1f)
        val dz = max(modelMaxZ - modelMinZ, 1f)
        return sqrt(dx * dx + dy * dy + dz * dz) * 0.5f
    }

    private companion object {
        private val LIT_VERTEX_SHADER = """
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec4 aColor;
            attribute float aAmbient;
            uniform mat4 uMvpMatrix;
            uniform mat4 uSceneMatrix;
            uniform vec3 uKeyDir;
            uniform vec3 uFillDir;
            uniform vec3 uViewDir;
            varying vec4 vColor;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            varying float vAmbient;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vNormal = (uSceneMatrix * vec4(aNormal, 0.0)).xyz;
                vec3 world = (uSceneMatrix * vec4(aPosition, 1.0)).xyz;
                vWorldPos = world;
                vColor = aColor;
                vAmbient = aAmbient;
            }
        """
        private val LIT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;
            varying vec3 vNormal;
            varying vec3 vWorldPos;
            varying float vAmbient;
            uniform vec3 uKeyDir;
            uniform vec3 uFillDir;
            uniform vec3 uViewDir;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 view = normalize(uViewDir);
                vec3 key = normalize(uKeyDir);
                vec3 fill = normalize(uFillDir);
                vec3 halfDir = normalize(key + view);
                float keyDiff = max(dot(n, key), 0.0);
                float fillDiff = max(dot(n, fill), 0.0);
                float rim = pow(max(1.0 - max(dot(n, view), 0.0), 0.0), 3.0);
                float spec = pow(max(dot(n, halfDir), 0.0), 32.0) * 0.25;
                float light = vAmbient * (0.30 + 0.55 * keyDiff + 0.22 * fillDiff);
                vec3 color = vColor.rgb * light + vec3(rim * 0.10);
                gl_FragColor = vec4(color, vColor.a);
            }
        """
        private val COLOR_VERTEX_SHADER = """
            attribute vec3 aPosition;
            attribute vec4 aColor;
            uniform mat4 uMvpMatrix;
            varying vec4 vColor;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vColor = aColor;
            }
        """
        private val COLOR_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;
            void main() { gl_FragColor = vColor; }
        """
        private val SOLID_VERTEX_SHADER = """
            attribute vec3 aPosition;
            uniform mat4 uMvpMatrix;
            void main() { gl_Position = uMvpMatrix * vec4(aPosition, 1.0); }
        """
        private val SOLID_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """
    }
}

/** Constants shared by the Prusa nozzle-path surface and its renderer. */
internal object PrusaNozzlePathViewDefaults {
    const val DEFAULT_YAW = -32f
    const val DEFAULT_PITCH = 58f
    const val DEFAULT_ZOOM = 1f
    const val MIN_ZOOM = 0.25f
    const val MAX_ZOOM = 60f
    const val FIELD_OF_VIEW = 42f
    const val TRAVEL_WIDTH = 1.5f
    const val TOP_AMBIENT = 0.98f
    const val SIDE_TOP_AMBIENT = 0.94f
    const val SIDE_BASE_AMBIENT = 0.86f
}