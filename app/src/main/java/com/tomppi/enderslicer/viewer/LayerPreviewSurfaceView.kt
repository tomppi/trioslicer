package com.tomppi.enderslicer.viewer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
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
    }

    fun setPreview(
        preview: GcodeLayerPreview,
        selectedLayerIndex: Int,
        style: LayerPreviewStyle,
    ) {
        queueEvent {
            layerRenderer.setPreview(preview)
            layerRenderer.setStyle(style)
            layerRenderer.setSelectedLayer(selectedLayerIndex)
        }
        requestRender()
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
    private var pathPositionBuffer: FloatBuffer? = null
    private var pathColorBuffer: FloatBuffer? = null
    private var cumulativePathVertices = IntArray(0)
    private var maximumLayerZ = 0f
    private var style = LayerPreviewStyle.CURRENT_LAYER
    private var currentRibbonPositionBuffer: FloatBuffer? = null
    private var currentRibbonColorBuffer: FloatBuffer? = null
    private var currentHaloPositionBuffer: FloatBuffer? = null
    private var currentRibbonVertexCount = 0

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

    fun setPreview(value: GcodeLayerPreview) {
        if (preview === value) return
        preview = value
        buildPathBuffers(value)
        buildGrid(value)
        selectedLayerIndex = if (value.layers.indices.isEmpty()) 0 else selectedLayerIndex.coerceIn(value.layers.indices)
        buildCurrentLayerRibbons()
        resetCamera()
    }

    fun setSelectedLayer(value: Int) {
        val current = preview ?: return
        val bounded = if (current.layers.indices.isEmpty()) 0 else value.coerceIn(current.layers.indices)
        val changed = selectedLayerIndex != bounded
        selectedLayerIndex = bounded
        if (changed) buildCurrentLayerRibbons()
    }

    fun setStyle(value: LayerPreviewStyle) {
        style = value
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
        val previousPathVertices = if (selectedLayerIndex > 0) cumulativePathVertices[selectedLayerIndex - 1] else 0
        if (style == LayerPreviewStyle.BUILD_UP && previousPathVertices > 0) {
            drawColoredPaths(firstVertex = 0, vertexCount = previousPathVertices, alpha = 0.15f, mode = GLES20.GL_LINES)
        }
        drawSolidTriangles(currentHaloPositionBuffer, currentRibbonVertexCount, 0.0f, 0.0f, 0.0f, 0.96f)
        drawColoredTriangles(currentRibbonPositionBuffer, currentRibbonColorBuffer, currentRibbonVertexCount)
        val currentStart = previousPathVertices
        val currentEnd = cumulativeVertexCount(cumulativePathVertices)
        drawColoredPaths(currentStart, currentEnd - currentStart, 1f, GLES20.GL_LINES)
    }

    private fun cumulativeVertexCount(counts: IntArray): Int {
        if (counts.isEmpty()) return 0
        return counts[selectedLayerIndex.coerceIn(counts.indices)]
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
        val positions = pathPositionBuffer ?: return
        val colors = pathColorBuffer ?: return
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

    private fun buildPathBuffers(value: GcodeLayerPreview) {
        pathPositionBuffer = null
        pathColorBuffer = null
        cumulativePathVertices = IntArray(0)

        val totalSegments = value.layers.sumOf { it.segmentCount }
        val plan = PreviewMemoryBudget.plan(totalSegments, selectedLayerSegments = 0)
        val positions = allocateFloatBuffer(
            PreviewMemoryBudget.checkedFloatCount(plan.baseSegmentLimit, BASE_POSITION_FLOATS_PER_SEGMENT),
        )
        val colors = allocateFloatBuffer(
            PreviewMemoryBudget.checkedFloatCount(plan.baseSegmentLimit, BASE_COLOR_FLOATS_PER_SEGMENT),
        )
        val pathCounts = IntArray(value.layers.size)
        var sourceIndex = 0
        var renderedSegments = 0

        value.layers.forEachIndexed { layerIndex, layer ->
            val values = layer.segments
            var offset = 0
            while (offset + 5 < values.size) {
                val retain = PreviewMemoryBudget.shouldRetain(
                    sourceIndex = sourceIndex++,
                    sourceCount = totalSegments,
                    retainedLimit = plan.baseSegmentLimit,
                )
                if (retain) {
                    val x1 = values[offset]
                    val y1 = values[offset + 1]
                    val x2 = values[offset + 2]
                    val y2 = values[offset + 3]
                    val speed = values[offset + 4]
                    val feature = GcodeLayerPreview.Feature.fromCode(values[offset + 5].toInt())
                    putLine(positions, x1, y1, layer.z, x2, y2, layer.z)
                    putPreviewColor(
                        buffer = colors,
                        feature = feature,
                        speed = speed,
                        minimum = value.minSpeedMmPerSecond,
                        maximum = value.maxSpeedMmPerSecond,
                        copies = 2,
                    )
                    renderedSegments++
                }
                offset += GcodeLayerPreview.VALUES_PER_SEGMENT
            }
            pathCounts[layerIndex] = renderedSegments * 2
        }

        positions.position(0)
        colors.position(0)
        pathPositionBuffer = positions
        pathColorBuffer = colors
        cumulativePathVertices = pathCounts
        maximumLayerZ = value.layers.maxOfOrNull { it.z } ?: 0f
    }

    private fun buildCurrentLayerRibbons() {
        currentRibbonPositionBuffer = null
        currentHaloPositionBuffer = null
        currentRibbonColorBuffer = null
        currentRibbonVertexCount = 0

        val current = preview ?: return
        if (current.layers.isEmpty()) return
        val layer = current.layers[selectedLayerIndex.coerceIn(current.layers.indices)]
        val totalSegments = current.layers.sumOf { it.segmentCount }
        val plan = PreviewMemoryBudget.plan(totalSegments, layer.segmentCount)
        if (plan.ribbonSegmentLimit <= 0) return

        val core = allocateFloatBuffer(
            PreviewMemoryBudget.checkedFloatCount(plan.ribbonSegmentLimit, RIBBON_POSITION_FLOATS_PER_SEGMENT),
        )
        val halo = allocateFloatBuffer(
            PreviewMemoryBudget.checkedFloatCount(plan.ribbonSegmentLimit, RIBBON_POSITION_FLOATS_PER_SEGMENT),
        )
        val colors = allocateFloatBuffer(
            PreviewMemoryBudget.checkedFloatCount(plan.ribbonSegmentLimit, RIBBON_COLOR_FLOATS_PER_SEGMENT),
        )
        val values = layer.segments
        var offset = 0
        var sourceIndex = 0
        var renderedSegments = 0
        while (offset + 5 < values.size) {
            val retain = PreviewMemoryBudget.shouldRetain(
                sourceIndex = sourceIndex++,
                sourceCount = layer.segmentCount,
                retainedLimit = plan.ribbonSegmentLimit,
            )
            if (retain) {
                val x1 = values[offset]
                val y1 = values[offset + 1]
                val x2 = values[offset + 2]
                val y2 = values[offset + 3]
                val speed = values[offset + 4]
                val feature = GcodeLayerPreview.Feature.fromCode(values[offset + 5].toInt())
                putRibbon(core, x1, y1, x2, y2, layer.z + 0.035f, CORE_RIBBON_WIDTH)
                putRibbon(halo, x1, y1, x2, y2, layer.z + 0.025f, HALO_RIBBON_WIDTH)
                putPreviewColor(
                    buffer = colors,
                    feature = feature,
                    speed = speed,
                    minimum = current.minSpeedMmPerSecond,
                    maximum = current.maxSpeedMmPerSecond,
                    copies = 6,
                )
                renderedSegments++
            }
            offset += GcodeLayerPreview.VALUES_PER_SEGMENT
        }
        core.position(0)
        halo.position(0)
        colors.position(0)
        currentRibbonPositionBuffer = core
        currentHaloPositionBuffer = halo
        currentRibbonColorBuffer = colors
        currentRibbonVertexCount = renderedSegments * 6
    }

    private fun putRibbon(
        buffer: FloatBuffer,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        z: Float,
        width: Float,
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
        val nx = -dy / length * width * 0.5f
        val ny = dx / length * width * 0.5f
        val ax = x1 + nx
        val ay = y1 + ny
        val bx = x1 - nx
        val by = y1 - ny
        val cx = x2 - nx
        val cy = y2 - ny
        val dx2 = x2 + nx
        val dy2 = y2 + ny
        buffer.put(ax).put(ay).put(z)
        buffer.put(bx).put(by).put(z)
        buffer.put(cx).put(cy).put(z)
        buffer.put(ax).put(ay).put(z)
        buffer.put(cx).put(cy).put(z)
        buffer.put(dx2).put(dy2).put(z)
    }

    private fun putLine(
        buffer: FloatBuffer,
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float,
    ) {
        buffer.put(x1).put(y1).put(z1)
        buffer.put(x2).put(y2).put(z2)
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

    private fun putPreviewColor(
        buffer: FloatBuffer,
        feature: GcodeLayerPreview.Feature,
        speed: Float,
        minimum: Float,
        maximum: Float,
        copies: Int,
    ) {
        val red: Float
        val green: Float
        val blue: Float
        when (feature) {
            GcodeLayerPreview.Feature.SUPPORT -> {
                red = 0.15f; green = 0.95f; blue = 1f
            }
            GcodeLayerPreview.Feature.SUPPORT_INTERFACE -> {
                red = 1f; green = 0.25f; blue = 0.9f
            }
            GcodeLayerPreview.Feature.ADHESION -> {
                red = 1f; green = 0.62f; blue = 0.08f
            }
            GcodeLayerPreview.Feature.ARC_OVERHANG -> {
                red = 0.72f; green = 0.38f; blue = 1f
            }
            GcodeLayerPreview.Feature.WAVE_OVERHANG -> {
                red = 0.12f; green = 0.92f; blue = 0.82f
            }
            else -> {
                val range = max(maximum - minimum, 0.001f)
                val normalized = ((speed - minimum) / range).coerceIn(0f, 1f)
                val hue = 240f * (1f - normalized)
                val saturation = 0.95f
                val value = 1f
                val chroma = value * saturation
                val hueSection = (hue / 60f) % 6f
                val x = chroma * (1f - abs(hueSection % 2f - 1f))
                val match = value - chroma
                when {
                    hueSection < 1f -> {
                        red = chroma + match; green = x + match; blue = match
                    }
                    hueSection < 2f -> {
                        red = x + match; green = chroma + match; blue = match
                    }
                    hueSection < 3f -> {
                        red = match; green = chroma + match; blue = x + match
                    }
                    hueSection < 4f -> {
                        red = match; green = x + match; blue = chroma + match
                    }
                    hueSection < 5f -> {
                        red = x + match; green = match; blue = chroma + match
                    }
                    else -> {
                        red = chroma + match; green = match; blue = x + match
                    }
                }
            }
        }
        repeat(copies) {
            buffer.put(red).put(green).put(blue).put(1f)
        }
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
        const val CORE_RIBBON_WIDTH = 0.38f
        const val HALO_RIBBON_WIDTH = 0.96f
        const val BASE_POSITION_FLOATS_PER_SEGMENT = 6
        const val BASE_COLOR_FLOATS_PER_SEGMENT = 8
        const val RIBBON_POSITION_FLOATS_PER_SEGMENT = 18
        const val RIBBON_COLOR_FLOATS_PER_SEGMENT = 24

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
