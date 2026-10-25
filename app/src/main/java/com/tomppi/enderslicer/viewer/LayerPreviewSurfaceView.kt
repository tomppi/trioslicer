package com.tomppi.enderslicer.viewer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

public enum class LayerPreviewStyle { CURRENT_LAYER, BUILD_UP }

class LayerPreviewSurfaceView(
    context: Context,
) : GLSurfaceView(context) {
    private val layerRenderer = LayerPreviewRenderer()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private var previousX = 0f
    private var previousY = 0f
    private var previousFocusX = 0f
    private var previousFocusY = 0f
    private var panning = false

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(layerRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = true
        layerRenderer.onPreviewStatus = { status -> post { onPreviewStatus?.invoke(status) } }
    }

    fun setPreview(
        preview: GcodeLayerPreview,
        selectedLayerIndex: Int,
        style: LayerPreviewStyle,
        capacity: PreviewCapacity,
    ) {
        if (queuedPreview === preview && queuedLayerIndex == selectedLayerIndex &&
            queuedStyle == style && queuedCapacity == capacity
        ) {
            return
        }
        queuedPreview = preview
        queuedLayerIndex = selectedLayerIndex
        queuedStyle = style
        queuedCapacity = capacity
        queueEvent {
            layerRenderer.setPreview(preview, capacity, style)
            layerRenderer.setSelectedLayer(selectedLayerIndex)
        }
        requestRender()
    }

    /** What the buffers hold: the layers in view and the profile that sized them. */
    var onPreviewStatus: ((String) -> Unit)? = null

    private var queuedPreview: GcodeLayerPreview? = null
    private var queuedLayerIndex = -1
    private var queuedStyle: LayerPreviewStyle? = null
    private var queuedCapacity: PreviewCapacity? = null

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
                        layerRenderer.panPixels(
                            deltaX = focusX - previousFocusX,
                            deltaY = focusY - previousFocusY,
                        )
                    }
                    previousFocusX = focusX
                    previousFocusY = focusY
                    panning = true
                    requestRender()
                } else if (!scaleDetector.isInProgress) {
                    val dx = event.x - previousX
                    val dy = event.y - previousY
                    layerRenderer.rotate(dx * 0.35f, dy * 0.35f)
                    previousX = event.x
                    previousY = event.y
                    requestRender()
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
            }

            MotionEvent.ACTION_CANCEL -> panning = false
        }
        return true
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
            layerRenderer.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onDoubleTap(event: MotionEvent): Boolean {
            layerRenderer.resetCamera()
            requestRender()
            return true
        }
    }
}

private class LayerPreviewRenderer : GLSurfaceView.Renderer {
    private var preview: GcodeLayerPreview? = null
    private var selectedLayerIndex = 0
    private var capacity = PreviewCapacity.Default
    /**
     * The geometry of the layers in view: the selected layer's context for the current-layer
     * style, the whole stack to the scrub for build-up. It is rebuilt when the selection leaves
     * the window or the style changes, and the build-up stack is appended to as it grows; the
     * paths of every layer stay in the parser's storage.
     */
    private var geometry: LayerPreviewGeometry? = null
    private var maximumLayerZ = 0f
    private var style = LayerPreviewStyle.CURRENT_LAYER

    /** What the buffers hold, for the footer. */
    var onPreviewStatus: ((String) -> Unit)? = null

    private var colorProgram = 0
    private var solidProgram = 0
    private var maxLineWidth = 1f
    private var gridBuffer: FloatBuffer? = null
    private var gridVertexCount = 0
    private var viewportWidth = 1
    private var viewportHeight = 1
    @Volatile private var yaw = DEFAULT_YAW
    @Volatile private var pitch = DEFAULT_PITCH
    @Volatile private var zoom = DEFAULT_ZOOM
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val scene = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    fun setPreview(
        value: GcodeLayerPreview,
        previewCapacity: PreviewCapacity,
        previewStyle: LayerPreviewStyle,
    ) {
        if (preview === value && capacity == previewCapacity && style == previewStyle) return
        val newScene = preview !== value || capacity != previewCapacity
        preview = value
        capacity = previewCapacity
        style = previewStyle
        selectedLayerIndex = if (value.layers.indices.isEmpty()) 0 else selectedLayerIndex.coerceIn(value.layers.indices)
        val started = System.nanoTime()
        geometry = LayerPreviewGeometry(value, previewCapacity, previewStyle).also { it.select(selectedLayerIndex) }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        if (newScene) {
            buildGrid(value)
            resetCamera()
        }
        Log.i(
            "LayerPreviewView",
            "layer preview " + previewStyle.name + ": " + (geometry?.status ?: "") +
                " · window built in " + elapsed + " ms",
        )
        onPreviewStatus?.invoke(geometry?.status ?: "")
    }

    fun setSelectedLayer(value: Int) {
        val current = preview ?: return
        val bounded = if (current.layers.indices.isEmpty()) 0 else value.coerceIn(current.layers.indices)
        selectedLayerIndex = bounded
        val started = System.nanoTime()
        val changed = geometry?.select(bounded) ?: false
        val elapsed = (System.nanoTime() - started) / 1_000_000
        if (changed) {
            Log.i(
                "LayerPreviewView",
                "layer preview step to " + bounded + ": " + (geometry?.status ?: "") +
                    " · selection handled in " + elapsed + " ms",
            )
        }
        onPreviewStatus?.invoke(geometry?.status ?: "")
    }

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
        val visibleHeight = 2f * distance * CAMERA_EYE_DISTANCE_SCALE * tan(Math.toRadians(FIELD_OF_VIEW_DEGREES / 2.0)).toFloat()
        val worldPerPixel = visibleHeight / max(viewportHeight, 1).toFloat()
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
        colorProgram = createGlProgram(COLOR_VERTEX_SHADER, COLOR_FRAGMENT_SHADER)
        solidProgram = createGlProgram(SOLID_VERTEX_SHADER, SOLID_FRAGMENT_SHADER)
        maxLineWidth = queryMaxLineWidth()
    }

    private fun lineWidth(width: Float) {
        GLES20.glLineWidth(width.coerceAtMost(maxLineWidth))
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = max(width, 1)
        viewportHeight = max(height, 1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val distance = cameraDistance()
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val current = preview
        val sceneWidth = max((current?.maxX ?: 0f) - (current?.minX ?: 0f), MIN_SCENE_SIZE)
        val sceneDepth = max((current?.maxY ?: 0f) - (current?.minY ?: 0f), MIN_SCENE_SIZE)
        val sceneRadius = sqrt(
            sceneWidth * sceneWidth +
                sceneDepth * sceneDepth +
                maximumLayerZ * maximumLayerZ,
        ) * 0.5f
        val nearPlane = max(0.1f, distance - sceneRadius * 1.4f)
        val farPlane = max(nearPlane + 100f, distance + sceneRadius * 2.5f + 100f)

        Matrix.perspectiveM(projection, 0, FIELD_OF_VIEW_DEGREES, aspect, nearPlane, farPlane)
        Matrix.setLookAtM(view, 0, 0f, -distance, distance * 0.62f, 0f, 0f, 0f, 0f, 0f, 1f)
        Matrix.translateM(view, 0, panX, panY, 0f)

        Matrix.setIdentityM(scene, 0)
        Matrix.rotateM(scene, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(scene, 0, yaw, 0f, 0f, 1f)
        val centerX = ((current?.minX ?: 0f) + (current?.maxX ?: 0f)) * 0.5f
        val centerY = ((current?.minY ?: 0f) + (current?.maxY ?: 0f)) * 0.5f
        Matrix.translateM(scene, 0, -centerX, -centerY, -maximumLayerZ * 0.5f)
        Matrix.multiplyMM(modelView, 0, view, 0, scene, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        drawSolidLines(gridBuffer, gridVertexCount, 1f, 0.26f, 0.32f, 0.42f, 0.50f)
        val built = geometry ?: return
        // Build-up draws the whole stack the window holds under the selection, from the bed up,
        // at the same faint alpha as before; the current layer is drawn in full over its own
        // ribbons, and the current-layer style draws only that layer.
        val stack = LayerPreviewDraw.buildUp(built, style)
        if (!stack.isEmpty) {
            drawColoredPaths(stack.firstVertex, stack.vertexCount, BUILD_UP_ALPHA, GLES20.GL_LINES)
        }
        drawSolidTriangles(built.ribbonHalo, built.ribbonVertexCount, 0.0f, 0.0f, 0.0f, 0.96f)
        drawColoredTriangles(built.ribbonPositions, built.ribbonColors, built.ribbonVertexCount)
        val selectedPaths = LayerPreviewDraw.selected(built)
        if (!selectedPaths.isEmpty) {
            drawColoredPaths(selectedPaths.firstVertex, selectedPaths.vertexCount, 1f, GLES20.GL_LINES)
        }
    }

    private fun drawSolidLines(
        buffer: FloatBuffer?,
        vertexCount: Int,
        width: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        if (buffer == null || vertexCount <= 0) return
        GLES20.glUseProgram(solidProgram)
        val position = GLES20.glGetAttribLocation(solidProgram, "aPosition")
        val matrix = GLES20.glGetUniformLocation(solidProgram, "uMvpMatrix")
        val color = GLES20.glGetUniformLocation(solidProgram, "uColor")

        buffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 3 * 4, buffer)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glUniform4f(color, red, green, blue, alpha)
        lineWidth(width)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)
        GLES20.glLineWidth(1f)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun drawColoredPaths(firstVertex: Int, vertexCount: Int, alpha: Float, mode: Int) {
        val positions = geometry?.pathPositions ?: return
        val colors = geometry?.pathColors ?: return
        if (vertexCount <= 0) return
        drawColoredBuffer(positions, colors, firstVertex, vertexCount, alpha, mode)
    }

    private fun drawColoredTriangles(
        positions: FloatBuffer?,
        colors: FloatBuffer?,
        vertexCount: Int,
    ) {
        if (positions == null || colors == null || vertexCount <= 0) return
        drawColoredBuffer(positions, colors, 0, vertexCount, 1f, GLES20.GL_TRIANGLES)
    }

    private fun drawColoredBuffer(
        positions: FloatBuffer,
        colors: FloatBuffer,
        firstVertex: Int,
        vertexCount: Int,
        alpha: Float,
        mode: Int,
    ) {
        GLES20.glUseProgram(colorProgram)
        val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
        val color = GLES20.glGetAttribLocation(colorProgram, "aColor")
        val matrix = GLES20.glGetUniformLocation(colorProgram, "uMvpMatrix")
        val opacity = GLES20.glGetUniformLocation(colorProgram, "uAlpha")
        positions.position(0)
        colors.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(color)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 3 * 4, positions)
        GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, 4 * 4, colors)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glUniform1f(opacity, alpha)
        if (mode == GLES20.GL_LINES) lineWidth(PATH_WIDTH)
        GLES20.glDrawArrays(mode, firstVertex, vertexCount)
        GLES20.glLineWidth(1f)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
    }

    private fun drawSolidTriangles(
        buffer: FloatBuffer?,
        vertexCount: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        if (buffer == null || vertexCount <= 0) return
        GLES20.glUseProgram(solidProgram)
        val position = GLES20.glGetAttribLocation(solidProgram, "aPosition")
        val matrix = GLES20.glGetUniformLocation(solidProgram, "uMvpMatrix")
        val color = GLES20.glGetUniformLocation(solidProgram, "uColor")
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 3 * 4, buffer)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glUniform4f(color, red, green, blue, alpha)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun cameraDistance(): Float {
        val current = preview
        val width = max((current?.maxX ?: 0f) - (current?.minX ?: 0f), MIN_SCENE_SIZE)
        val depth = max((current?.maxY ?: 0f) - (current?.minY ?: 0f), MIN_SCENE_SIZE)
        val radius = max(
            sqrt(width * width + depth * depth + maximumLayerZ * maximumLayerZ) * 0.5f,
            MIN_SCENE_SIZE * 0.5f,
        )
        val aspect = max(viewportWidth.toFloat() / max(viewportHeight, 1).toFloat(), 0.01f)
        val verticalHalfFov = Math.toRadians(FIELD_OF_VIEW_DEGREES / 2.0).toFloat()
        val horizontalHalfFov = atan(tan(verticalHalfFov) * aspect)
        val limitingHalfFov = min(verticalHalfFov, horizontalHalfFov).coerceAtLeast(0.05f)
        val fitted = radius / sin(limitingHalfFov) * CAMERA_MARGIN
        return max((fitted + radius * 0.35f) / zoom, radius + 4f)
    }

    private fun buildGrid(value: GcodeLayerPreview) {
        val array = LayerGridBuilder.build(
            minX = value.minX,
            maxX = value.maxX,
            minY = value.minY,
            maxY = value.maxY,
            spacing = GRID_SPACING.toDouble(),
            padding = GRID_PADDING.toDouble(),
            z = GRID_Z,
            maxLines = MAX_GRID_LINES,
        )
        gridBuffer = floatBuffer(array)
        gridVertexCount = array.size / 3
    }

    private fun allocateFloatBuffer(size: Int): FloatBuffer {
        require(size >= 0) { "Float buffer size cannot be negative" }
        val bytes = Math.multiplyExact(size, Float.SIZE_BYTES)
        return ByteBuffer.allocateDirect(bytes)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer {
        return allocateFloatBuffer(values.size).apply { put(values); position(0) }
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
        const val CAMERA_MARGIN = 1.15f
        const val GRID_Z = -0.08f
        // Eye sits at (0, -distance, 0.62*distance); true eye distance is distance * sqrt(1 + 0.62^2).
        const val CAMERA_EYE_DISTANCE_SCALE = 1.17666f
        const val GRID_SPACING = 10f
        const val GRID_PADDING = 10f
        const val MAX_GRID_LINES = 512
        const val MIN_SCENE_SIZE = 20f
        const val PATH_WIDTH = 3.8f
        const val BUILD_UP_ALPHA = 0.15f

        const val COLOR_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            attribute vec3 aPosition;
            attribute vec4 aColor;
            uniform float uAlpha;
            varying vec4 vColor;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vColor = vec4(aColor.rgb, aColor.a * uAlpha);
            }
        """
        const val COLOR_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;
            void main() {
                gl_FragColor = vColor;
            }
        """
        const val SOLID_VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            attribute vec3 aPosition;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
            }
        """
        const val SOLID_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
