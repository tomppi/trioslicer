package com.rawmechatronics.easyscanner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin geometry/bounds tracker for the live Fusion 3D coverage preview (unit-testable on JVM).
 */
internal class FusionPreviewStats {
    var totalLaserPoints: Int = 0
        private set
    var keyframeCount: Int = 0
        private set

    // Running bounding box of captured laser points (world metres).
    var minX = Float.POSITIVE_INFINITY; private set
    var minY = Float.POSITIVE_INFINITY; private set
    var minZ = Float.POSITIVE_INFINITY; private set
    var maxX = Float.NEGATIVE_INFINITY; private set
    var maxY = Float.NEGATIVE_INFINITY; private set
    var maxZ = Float.NEGATIVE_INFINITY; private set

    // Fallback bounding box of camera positions when no laser points exist yet.
    private var camMinX = Float.POSITIVE_INFINITY
    private var camMinY = Float.POSITIVE_INFINITY
    private var camMinZ = Float.POSITIVE_INFINITY
    private var camMaxX = Float.NEGATIVE_INFINITY
    private var camMaxY = Float.NEGATIVE_INFINITY
    private var camMaxZ = Float.NEGATIVE_INFINITY
    private var camCount = 0

    // Incremental robust centroid (clamped against single far outliers).
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumZ = 0.0
    private var sumN = 0

    fun reset() {
        totalLaserPoints = 0
        keyframeCount = 0
        minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY; minZ = Float.POSITIVE_INFINITY
        maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY; maxZ = Float.NEGATIVE_INFINITY
        camMinX = Float.POSITIVE_INFINITY; camMinY = Float.POSITIVE_INFINITY; camMinZ = Float.POSITIVE_INFINITY
        camMaxX = Float.NEGATIVE_INFINITY; camMaxY = Float.NEGATIVE_INFINITY; camMaxZ = Float.NEGATIVE_INFINITY
        camCount = 0
        sumX = 0.0; sumY = 0.0; sumZ = 0.0; sumN = 0
    }

    fun recordLaserPoints(worldPts: FloatArray, countFloats: Int = worldPts.size) {
        var i = 0
        while (i + 2 < countFloats) {
            val x = worldPts[i]; val y = worldPts[i + 1]; val z = worldPts[i + 2]
            i += 3
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            totalLaserPoints++
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            sumX += x; sumY += y; sumZ += z; sumN++
        }
    }

    fun recordCameraPosition(cx: Float, cy: Float, cz: Float, isKeyframe: Boolean) {
        if (!cx.isFinite() || !cy.isFinite() || !cz.isFinite()) return
        if (isKeyframe) keyframeCount++
        camCount++
        if (cx < camMinX) camMinX = cx; if (cx > camMaxX) camMaxX = cx
        if (cy < camMinY) camMinY = cy; if (cy > camMaxY) camMaxY = cy
        if (cz < camMinZ) camMinZ = cz; if (cz > camMaxZ) camMaxZ = cz
    }

    /** Returns `[cx, cy, cz, radius]` framing either the captured laser cloud or the camera path. */
    fun computeFraming(): FloatArray {
        if (sumN >= 10) {
            val cx = (sumX / sumN).toFloat()
            val cy = (sumY / sumN).toFloat()
            val cz = (sumZ / sumN).toFloat()
            val dx = (maxX - minX).coerceAtMost(3.0f)
            val dy = (maxY - minY).coerceAtMost(3.0f)
            val dz = (maxZ - minZ).coerceAtMost(3.0f)
            val r = (sqrt(dx * dx + dy * dy + dz * dz) * 0.55f).coerceIn(0.08f, 2.0f)
            return floatArrayOf(cx, cy, cz, r)
        }
        if (camCount >= 1) {
            val cx = (camMinX + camMaxX) * 0.5f
            val cy = (camMinY + camMaxY) * 0.5f
            val cz = (camMinZ + camMaxZ) * 0.5f
            val dx = camMaxX - camMinX; val dy = camMaxY - camMinY; val dz = camMaxZ - camMinZ
            val r = (sqrt(dx * dx + dy * dy + dz * dz) * 0.65f).coerceIn(0.18f, 2.5f)
            return floatArrayOf(cx, cy, cz, r)
        }
        return floatArrayOf(0f, 0f, 0f, 0.35f)
    }
}

/**
 * Lightweight OpenGL ES 3.0 live 3D coverage preview for Fusion Capture.
 *
 * Displays:
 * - Every captured metric laser point accumulated so far (height-colored cyan -> emerald -> gold)
 *   uploaded incrementally into a pre-allocated GPU VBO via tiny 16 KB staging chunks.
 * - Live active laser stripe in bright magenta on top.
 * - Recorded photo keyframe camera ring + cyan trajectory + live yellow camera frustum.
 */
class FusionPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private class PendingBatch(
        val capturedLaser: FloatArray?, // non-null only for actually recorded frames
        val liveLaser: FloatArray?,     // live active beam preview
        val pose: RigidPose?,
        val isKeyframe: Boolean,
    )

    private val queue = ConcurrentLinkedQueue<PendingBatch>()
    private val renderer = PreviewRenderer(queue)

    @Volatile var totalCapturedPoints: Int = 0
        private set
    @Volatile var totalKeyframes: Int = 0
        private set

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /**
     * Pushes a frame from `FrameProcessor` (lock-free, safe on 30 fps camera thread).
     * Pass [capturedWorldLaser] only when the frame was actually recorded into the Fusion session;
     * pass [liveWorldLaser] for live aiming feedback even while paused.
     */
    fun pushFrame(
        capturedWorldLaser: FloatArray?,
        liveWorldLaser: FloatArray?,
        vioPose: RigidPose?,
        isKeyframe: Boolean,
    ) {
        if (capturedWorldLaser != null) totalCapturedPoints += capturedWorldLaser.size / 3
        if (isKeyframe && vioPose != null) totalKeyframes++
        // Bound pending live-only updates if GL is paused, never dropping captured batches.
        if (capturedWorldLaser == null && !isKeyframe && queue.size > 4) return
        queue.add(PendingBatch(capturedWorldLaser, liveWorldLaser, vioPose, isKeyframe))
        requestRender()
    }

    fun clearPreview() {
        queue.clear()
        totalCapturedPoints = 0
        totalKeyframes = 0
        queueEvent {
            renderer.clear()
            requestRender()
        }
    }

    fun setFullscreenPointScale(fullscreen: Boolean) {
        queueEvent {
            renderer.fullscreenMode = fullscreen
            requestRender()
        }
    }

    // --- interactive orbit / pinch zoom / pan / double-tap reset ---
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            queueEvent { renderer.resetCamera() }
            requestRender()
            return true
        }
    })
    private var lastX = 0f
    private var lastY = 0f
    private var orbiting = false
    private var lastSpan = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private fun span(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; orbiting = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                orbiting = false
                lastSpan = span(e)
                lastFocusX = (e.getX(0) + e.getX(1)) / 2f
                lastFocusY = (e.getY(0) + e.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val sp = span(e)
                    val fx = (e.getX(0) + e.getX(1)) / 2f
                    val fy = (e.getY(0) + e.getY(1)) / 2f
                    if (lastSpan > 0f && sp > 0f) {
                        val factor = sp / lastSpan
                        val dx = (fx - lastFocusX) / width.coerceAtLeast(1)
                        val dy = (fy - lastFocusY) / height.coerceAtLeast(1)
                        queueEvent {
                            renderer.zoom(factor)
                            renderer.pan(dx, dy)
                        }
                        requestRender()
                    }
                    lastSpan = sp; lastFocusX = fx; lastFocusY = fy
                } else if (orbiting) {
                    val dx = (e.x - lastX) / width.coerceAtLeast(1)
                    val dy = (e.y - lastY) / height.coerceAtLeast(1)
                    lastX = e.x; lastY = e.y
                    queueEvent { renderer.orbit(dx, dy) }
                    requestRender()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                orbiting = false; lastSpan = 0f
            }
        }
        return true
    }

    private class PreviewRenderer(
        private val queue: ConcurrentLinkedQueue<PendingBatch>,
    ) : Renderer {
        private var cloudProg = 0
        private var solidProg = 0
        private var cloudVbo = 0
        private var liveVbo = 0
        private var kfVbo = 0
        private var frustumVbo = 0

        private val stats = FusionPreviewStats()
        private var cloudCount = 0
        private var cloudWriteCursor = 0
        private var cloudGpuCap = MAX_CLOUD_PTS
        private var liveCount = 0
        private var kfCount = 0
        private var hasFrustum = false

        private var viewW = 1
        private var viewH = 1
        var fullscreenMode = false

        // Camera orbit state around object centroid.
        private var userMoved = false
        private var centreX = 0f; private var centreY = 0f; private var centreZ = 0f
        private var radius = 0.35f
        private var yaw = 0.55f
        private var pitch = 0.45f
        private var dist = 0.9f
        private var panX = 0f
        private var panY = 0f

        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val mvp = FloatArray(16)

        // Small reusable 24 KB direct staging buffer (zero per-frame direct allocations).
        private val stageFloats = 6144
        private val stageBuf = ByteBuffer.allocateDirect(stageFloats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val frustumLines = FloatArray(16 * 3) // 8 line segments = 16 vertices

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            cloudProg = link(CLOUD_VS, CLOUD_FS)
            solidProg = link(SOLID_VS, SOLID_FS)
            val ids = IntArray(4)
            GLES30.glGenBuffers(4, ids, 0)
            cloudVbo = ids[0]; liveVbo = ids[1]; kfVbo = ids[2]; frustumVbo = ids[3]

            // Reset GPU-backed counts on surface (re)creation so EGL context loss never draws empty VBOs.
            stats.reset()
            cloudCount = 0
            cloudWriteCursor = 0
            liveCount = 0
            kfCount = 0
            hasFrustum = false

            // Pre-allocate GPU-resident VBOs (6 MB for 524k laser points, 96 KB for 8k keyframes).
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cloudVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, cloudGpuCap * 12, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, kfVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MAX_KF_PTS * 12, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = max(1, width); viewH = max(1, height)
            GLES30.glViewport(0, 0, viewW, viewH)
        }

        fun clear() {
            stats.reset()
            cloudCount = 0
            cloudWriteCursor = 0
            liveCount = 0
            kfCount = 0
            hasFrustum = false
            resetCamera()
        }

        fun resetCamera() {
            userMoved = false
            yaw = 0.55f
            pitch = 0.45f
            panX = 0f
            panY = 0f
            val f = stats.computeFraming()
            centreX = f[0]; centreY = f[1]; centreZ = f[2]; radius = f[3]
            dist = radius * 2.5f
        }

        fun orbit(dx: Float, dy: Float) {
            userMoved = true
            yaw -= dx * 3.4f
            pitch = (pitch + dy * 2.8f).coerceIn(-1.48f, 1.48f)
        }

        fun zoom(factor: Float) {
            userMoved = true
            dist = (dist / factor).coerceIn(radius * 0.25f, radius * 12f)
        }

        fun pan(dx: Float, dy: Float) {
            userMoved = true
            panX -= dx * dist * 0.9f
            panY += dy * dist * 0.9f
        }

        private fun drainQueue() {
            var latestLive: FloatArray? = null
            var latestPose: RigidPose? = null
            var anyAdded = false

            while (true) {
                val b = queue.poll() ?: break
                if (b.capturedLaser != null && b.capturedLaser.size >= 3) {
                    uploadCloudChunk(b.capturedLaser)
                    stats.recordLaserPoints(b.capturedLaser)
                    anyAdded = true
                }
                if (b.liveLaser != null) latestLive = b.liveLaser
                if (b.pose != null) {
                    latestPose = b.pose
                    val c = b.pose.cameraCentre()
                    val cx = c[0].toFloat(); val cy = c[1].toFloat(); val cz = c[2].toFloat()
                    stats.recordCameraPosition(cx, cy, cz, b.isKeyframe)
                    if (b.isKeyframe && kfCount < MAX_KF_PTS) {
                        stageBuf.clear()
                        stageBuf.put(cx).put(cy).put(cz)
                        stageBuf.flip()
                        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, kfVbo)
                        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, kfCount * 12, 12, stageBuf)
                        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
                        kfCount++
                        anyAdded = true
                    }
                }
            }

            if (latestLive != null) {
                val n = min(latestLive.size / 3, stageFloats / 3)
                liveCount = n
                if (n > 0) {
                    stageBuf.clear()
                    stageBuf.put(latestLive, 0, n * 3)
                    stageBuf.flip()
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, liveVbo)
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 12, stageBuf, GLES30.GL_DYNAMIC_DRAW)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
                }
            }

            if (latestPose != null) {
                buildFrustumLines(latestPose, (radius * 0.14f).coerceIn(0.025f, 0.09f))
                stageBuf.clear()
                stageBuf.put(frustumLines)
                stageBuf.flip()
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, frustumVbo)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, frustumLines.size * 4, stageBuf, GLES30.GL_DYNAMIC_DRAW)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
                hasFrustum = true
            }

            if (anyAdded && !userMoved) {
                val f = stats.computeFraming()
                // Smoothly ease camera target/radius so auto-framing never jumps abruptly.
                val alpha = if (cloudCount < 400) 0.35f else 0.12f
                centreX += (f[0] - centreX) * alpha
                centreY += (f[1] - centreY) * alpha
                centreZ += (f[2] - centreZ) * alpha
                radius += (f[3] - radius) * alpha
                dist = radius * 2.55f
            }
        }

        private fun uploadCloudChunk(pts: FloatArray) {
            val totalPts = pts.size / 3
            if (totalPts <= 0 || cloudVbo == 0) return
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cloudVbo)
            var srcPt = 0
            while (srcPt < totalPts) {
                val spaceLeft = cloudGpuCap - cloudWriteCursor
                val batchPts = min(min(totalPts - srcPt, stageFloats / 3), spaceLeft)
                if (batchPts > 0) {
                    stageBuf.clear()
                    stageBuf.put(pts, srcPt * 3, batchPts * 3)
                    stageBuf.flip()
                    GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, cloudWriteCursor * 12, batchPts * 12, stageBuf)
                    cloudWriteCursor += batchPts
                    if (cloudWriteCursor > cloudCount) cloudCount = cloudWriteCursor
                    if (cloudWriteCursor >= cloudGpuCap) cloudWriteCursor = 0
                    srcPt += batchPts
                } else {
                    cloudWriteCursor = 0
                }
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }

        private fun buildFrustumLines(pose: RigidPose, s: Float) {
            val c = pose.cameraCentre()
            val cx = c[0].toFloat(); val cy = c[1].toFloat(); val cz = c[2].toFloat()
            val w = s * 0.75f
            val h = s * 0.56f
            val d = s * 1.15f
            val tl = pose.cameraToWorld(-w.toDouble(), -h.toDouble(), d.toDouble())
            val tr = pose.cameraToWorld(w.toDouble(), -h.toDouble(), d.toDouble())
            val br = pose.cameraToWorld(w.toDouble(), h.toDouble(), d.toDouble())
            val bl = pose.cameraToWorld(-w.toDouble(), h.toDouble(), d.toDouble())
            var o = 0
            fun seg(ax: Float, ay: Float, az: Float, bx: Double, by: Double, bz: Double) {
                frustumLines[o++] = ax; frustumLines[o++] = ay; frustumLines[o++] = az
                frustumLines[o++] = bx.toFloat(); frustumLines[o++] = by.toFloat(); frustumLines[o++] = bz.toFloat()
            }
            fun segD(a: DoubleArray, b: DoubleArray) {
                frustumLines[o++] = a[0].toFloat(); frustumLines[o++] = a[1].toFloat(); frustumLines[o++] = a[2].toFloat()
                frustumLines[o++] = b[0].toFloat(); frustumLines[o++] = b[1].toFloat(); frustumLines[o++] = b[2].toFloat()
            }
            seg(cx, cy, cz, tl[0], tl[1], tl[2])
            seg(cx, cy, cz, tr[0], tr[1], tr[2])
            seg(cx, cy, cz, br[0], br[1], br[2])
            seg(cx, cy, cz, bl[0], bl[1], bl[2])
            segD(tl, tr); segD(tr, br); segD(br, bl); segD(bl, tl)
        }

        override fun onDrawFrame(gl: GL10?) {
            drainQueue()
            GLES30.glClearColor(0.05f, 0.06f, 0.09f, if (fullscreenMode) 0.96f else 0.88f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            if (cloudCount <= 0 && kfCount <= 0 && !hasFrustum && liveCount <= 0) return

            val aspect = viewW.toFloat() / viewH.toFloat()
            Matrix.perspectiveM(proj, 0, 48f, aspect, 0.01f, 25f)
            val cp = cos(pitch); val sp = sin(pitch)
            val cy = cos(yaw); val sy = sin(yaw)
            // SuperVINS world has +Z roughly up (gravity aligned) -> orbit around Z-up!
            val dx = cp * sy
            val dy = -cp * cy
            val dz = sp
            // Right and up vectors for screen-space panning
            val rx = cy; val ry = sy; val rz = 0f
            val ux = -sp * sy; val uy = sp * cy; val uz = cp
            val tx = centreX + rx * panX + ux * panY
            val ty = centreY + ry * panX + uy * panY
            val tz = centreZ + rz * panX + uz * panY
            val ex = tx + dx * dist
            val ey = ty + dy * dist
            val ez = tz + dz * dist
            Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, tz, 0f, 0f, 1f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

            // 1. Captured laser point cloud (height-colored cyan -> emerald -> gold).
            if (cloudCount > 0) {
                GLES30.glUseProgram(cloudProg)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(cloudProg, "uMvp"), 1, false, mvp, 0)
                val zLo = if (stats.minZ.isFinite()) stats.minZ else -0.2f
                val zHi = if (stats.maxZ.isFinite() && stats.maxZ > zLo + 0.01f) stats.maxZ else zLo + 0.2f
                GLES30.glUniform1f(GLES30.glGetUniformLocation(cloudProg, "uZMin"), zLo)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(cloudProg, "uZSpan"), zHi - zLo)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(cloudProg, "uPtSize"), if (fullscreenMode) 4.6f else 3.2f)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cloudVbo)
                val aPos = GLES30.glGetAttribLocation(cloudProg, "aPos")
                GLES30.glEnableVertexAttribArray(aPos)
                GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 12, 0)
                GLES30.glDrawArrays(GLES30.GL_POINTS, 0, cloudCount)
                GLES30.glDisableVertexAttribArray(aPos)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            }

            // 2. Recorded keyframe trajectory & camera dots (sky blue).
            if (kfCount > 0) {
                GLES30.glUseProgram(solidProg)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(solidProg, "uMvp"), 1, false, mvp, 0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, kfVbo)
                val aPos = GLES30.glGetAttribLocation(solidProg, "aPos")
                GLES30.glEnableVertexAttribArray(aPos)
                GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 12, 0)
                if (kfCount >= 2) {
                    GLES30.glUniform4f(GLES30.glGetUniformLocation(solidProg, "uColor"), 0.22f, 0.74f, 0.97f, 0.55f)
                    GLES30.glLineWidth(2.0f)
                    GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, kfCount)
                }
                GLES30.glUniform4f(GLES30.glGetUniformLocation(solidProg, "uColor"), 0.35f, 0.85f, 1.0f, 0.95f)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(solidProg, "uPtSize"), if (fullscreenMode) 7.5f else 5.5f)
                GLES30.glDrawArrays(GLES30.GL_POINTS, 0, kfCount)
                GLES30.glDisableVertexAttribArray(aPos)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            }

            // 3. Live active camera frustum (warm gold wireframe).
            if (hasFrustum) {
                GLES30.glUseProgram(solidProg)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(solidProg, "uMvp"), 1, false, mvp, 0)
                GLES30.glUniform4f(GLES30.glGetUniformLocation(solidProg, "uColor"), 0.98f, 0.80f, 0.08f, 0.95f)
                GLES30.glLineWidth(2.5f)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, frustumVbo)
                val aPos = GLES30.glGetAttribLocation(solidProg, "aPos")
                GLES30.glEnableVertexAttribArray(aPos)
                GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 12, 0)
                GLES30.glDrawArrays(GLES30.GL_LINES, 0, 16)
                GLES30.glDisableVertexAttribArray(aPos)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            }

            // 4. Live active laser stripe on top (neon magenta).
            if (liveCount > 0) {
                GLES30.glUseProgram(solidProg)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(solidProg, "uMvp"), 1, false, mvp, 0)
                GLES30.glUniform4f(GLES30.glGetUniformLocation(solidProg, "uColor"), 1.0f, 0.18f, 0.58f, 1.0f)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(solidProg, "uPtSize"), if (fullscreenMode) 7.5f else 5.5f)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, liveVbo)
                val aPos = GLES30.glGetAttribLocation(solidProg, "aPos")
                GLES30.glEnableVertexAttribArray(aPos)
                GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 12, 0)
                GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                GLES30.glDrawArrays(GLES30.GL_POINTS, 0, liveCount)
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                GLES30.glDisableVertexAttribArray(aPos)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            }
        }

        private fun link(vs: String, fs: String): Int {
            val v = compile(GLES30.GL_VERTEX_SHADER, vs)
            val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, v)
            GLES30.glAttachShader(p, f)
            GLES30.glLinkProgram(p)
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            return s
        }
    }

    companion object {
        private const val MAX_CLOUD_PTS = 524_288
        private const val MAX_KF_PTS = 8_192

        private const val CLOUD_VS = """#version 300 es
            uniform mat4 uMvp;
            uniform float uZMin;
            uniform float uZSpan;
            uniform float uPtSize;
            in vec3 aPos;
            out float vHeight;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                gl_PointSize = uPtSize;
                vHeight = clamp((aPos.z - uZMin) / max(uZSpan, 0.02), 0.0, 1.0);
            }
        """

        private const val CLOUD_FS = """#version 300 es
            precision mediump float;
            in float vHeight;
            out vec4 fragColor;
            void main() {
                vec2 p = gl_PointCoord - vec2(0.5);
                if (dot(p, p) > 0.25) discard;
                vec3 lo = vec3(0.08, 0.72, 0.88);
                vec3 mid = vec3(0.18, 0.90, 0.58);
                vec3 hi = vec3(0.98, 0.84, 0.28);
                vec3 col = vHeight < 0.5 ? mix(lo, mid, vHeight * 2.0) : mix(mid, hi, (vHeight - 0.5) * 2.0);
                fragColor = vec4(col, 0.95);
            }
        """

        private const val SOLID_VS = """#version 300 es
            uniform mat4 uMvp;
            uniform float uPtSize;
            in vec3 aPos;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                gl_PointSize = uPtSize;
            }
        """

        private const val SOLID_FS = """#version 300 es
            precision mediump float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                fragColor = uColor;
            }
        """
    }
}
