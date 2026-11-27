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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live OpenGL ES 3.0 shaded mesh viewer for the hybrid DA3 + laser scanner.
 *
 * Supports two view modes:
 * - [ViewMode.ORBIT]: dark 3D workshop view with one-finger orbit, two-finger zoom/pan,
 *   double-tap reframe, and live red laser stripe overlaid on the mesh.
 * - [ViewMode.AR_OVERLAY]: translucent mesh drawn from the live camera pose directly
 *   over the camera preview.
 */
class LiveMeshView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    enum class ShadeMode(val label: String) {
        DETAIL("Detail"),
        CLAY("Clay"),
        COLOR("Colour"),
    }

    enum class ViewMode(val label: String) {
        ORBIT("Orbit"),
        AR_OVERLAY("AR"),
    }

    private val renderer = MeshRenderer()

    @Volatile var viewMode: ViewMode = ViewMode.ORBIT
        set(value) {
            field = value
            queueEvent { renderer.arMode = (value == ViewMode.AR_OVERLAY) }
            requestRender()
        }

    @Volatile var shadeMode: ShadeMode = ShadeMode.DETAIL
        set(value) {
            field = value
            queueEvent { renderer.shadeMode = value.ordinal }
            requestRender()
        }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun updateMesh(mesh: AdaptiveDepthMesh.MeshData, refPose: RigidPose, reframe: Boolean = false) {
        queueEvent {
            renderer.uploadMesh(mesh, refPose, reframe)
            requestRender()
        }
    }

    /**
     * Uploads a native off-heap TSDF mesh directly into OpenGL ES 3.0 GPU VBO/IBO memory
     * on the GL thread with zero Java heap allocation.
     */
    fun updateNativeTsdf(
        handle: Long,
        refPose: RigidPose,
        reframe: Boolean = false,
        nativeLock: java.util.concurrent.locks.ReentrantReadWriteLock? = null,
        isValid: () -> Boolean = { true },
        onUploaded: (triangles: Int, activeBlocks: Int, laserBlocks: Int) -> Unit = { _, _, _ -> },
    ) {
        queueEvent {
            val stats = IntArray(6)
            val ok = if (nativeLock != null) {
                val rl = nativeLock.readLock()
                rl.lock()
                try {
                    if (!isValid()) false
                    else renderer.uploadNativeTsdf(handle, refPose, reframe, stats)
                } finally {
                    rl.unlock()
                }
            } else {
                if (!isValid()) false
                else renderer.uploadNativeTsdf(handle, refPose, reframe, stats)
            }
            requestRender()
            if (ok) onUploaded(stats[0] / 3, stats[2], stats[3])
        }
    }

    fun updateLiveFrame(livePose: RigidPose?, camera: DepthCamera?, sensorOrientation: Int, laserWorld: FloatArray) {
        queueEvent {
            renderer.updateLive(livePose, camera, sensorOrientation, laserWorld)
            requestRender()
        }
    }

    fun clearMesh() {
        queueEvent {
            renderer.clear()
            requestRender()
        }
    }

    // --- touch orbit / zoom / pan in Orbit mode ---
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            queueEvent { renderer.resetOrbit() }
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
        if (viewMode == ViewMode.AR_OVERLAY) return false
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

    private class MeshRenderer : Renderer {
        private var meshProgram = 0
        private var laserProgram = 0
        private var meshVbo = 0
        private var meshIbo = 0
        private var laserVbo = 0
        private var indexCount = 0
        private var laserCount = 0
        private var viewW = 1
        private var viewH = 1

        var arMode = false
        var shadeMode = 0

        // Orbit camera anchored around the mesh centroid, oriented along the reference camera frame.
        private var hasMesh = false
        private var centreX = 0f; private var centreY = 0f; private var centreZ = 0f
        private var refCamRight = floatArrayOf(1f, 0f, 0f)
        private var refCamUp = floatArrayOf(0f, -1f, 0f)
        private var refCamBack = floatArrayOf(0f, 0f, -1f)
        private var radius = 0.25f
        private var yaw = 0f
        private var pitch = 0.15f
        private var dist = 0.6f
        private var panX = 0f
        private var panY = 0f

        private var livePose: RigidPose? = null
        private var liveCamera: DepthCamera? = null
        private var liveSensorRot = 90

        private val mvp = FloatArray(16)
        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val rotMat = FloatArray(16)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            meshProgram = link(MESH_VS, MESH_FS)
            laserProgram = link(LASER_VS, LASER_FS)
            val ids = IntArray(3)
            GLES30.glGenBuffers(3, ids, 0)
            meshVbo = ids[0]; meshIbo = ids[1]; laserVbo = ids[2]
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = max(1, width); viewH = max(1, height)
            GLES30.glViewport(0, 0, viewW, viewH)
        }

        // Tiny fixed 64 KB staging buffers for chunked Patch-mode uploads (never allocates whole-mesh direct buffers).
        private val chunkVerts = 1600
        private val stageVb = ByteBuffer.allocateDirect(chunkVerts * 40).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val chunkIndices = 16384
        private val stageIb = ByteBuffer.allocateDirect(chunkIndices * 4).order(ByteOrder.nativeOrder()).asIntBuffer()

        fun uploadNativeTsdf(handle: Long, refPose: RigidPose, reframe: Boolean, outStats6: IntArray): Boolean {
            if (handle == 0L || meshVbo == 0 || meshIbo == 0) return false
            val bounds = FloatArray(6)
            val ok = NativeLiveTsdf.nativeUploadToGpu(handle, meshVbo, meshIbo, outStats6, bounds)
            if (!ok) return false
            indexCount = outStats6[0]
            if (indexCount > 0 && (!hasMesh || reframe)) {
                frameFromBounds(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5], refPose)
            }
            return true
        }

        fun uploadMesh(mesh: AdaptiveDepthMesh.MeshData, refPose: RigidPose, reframe: Boolean) {
            val vCount = mesh.vertexCount
            val iCount = mesh.indices.size
            if (vCount == 0 || iCount == 0) { indexCount = 0; return }
            // Stream into GPU VBO in 64 KB chunks so Java heap never holds a whole-mesh direct ByteBuffer.
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vCount * 40, null, GLES30.GL_DYNAMIC_DRAW)
            var vStart = 0
            while (vStart < vCount) {
                val batch = min(chunkVerts, vCount - vStart)
                stageVb.clear()
                for (k in vStart until vStart + batch) {
                    val o = k * 3
                    stageVb.put(mesh.positions[o]).put(mesh.positions[o + 1]).put(mesh.positions[o + 2])
                    stageVb.put(mesh.normals[o]).put(mesh.normals[o + 1]).put(mesh.normals[o + 2])
                    stageVb.put((mesh.colors[o].toInt() and 0xff) / 255f)
                    stageVb.put((mesh.colors[o + 1].toInt() and 0xff) / 255f)
                    stageVb.put((mesh.colors[o + 2].toInt() and 0xff) / 255f)
                    stageVb.put(mesh.refinedWeight[k])
                }
                stageVb.flip()
                GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, vStart * 40, batch * 40, stageVb)
                vStart += batch
            }

            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, meshIbo)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, iCount * 4, null, GLES30.GL_DYNAMIC_DRAW)
            var iStart = 0
            while (iStart < iCount) {
                val batch = min(chunkIndices, iCount - iStart)
                stageIb.clear()
                stageIb.put(mesh.indices, iStart, batch)
                stageIb.flip()
                GLES30.glBufferSubData(GLES30.GL_ELEMENT_ARRAY_BUFFER, iStart * 4, batch * 4, stageIb)
                iStart += batch
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
            indexCount = iCount

            if (!hasMesh || reframe) {
                frameFromBounds(
                    mesh.boundsMin[0], mesh.boundsMin[1], mesh.boundsMin[2],
                    mesh.boundsMax[0], mesh.boundsMax[1], mesh.boundsMax[2],
                    refPose,
                )
            }
        }

        private fun frameFromBounds(
            minX: Float, minY: Float, minZ: Float,
            maxX: Float, maxY: Float, maxZ: Float,
            refPose: RigidPose,
        ) {
            hasMesh = true
            centreX = (minX + maxX) * 0.5f
            centreY = (minY + maxY) * 0.5f
            centreZ = (minZ + maxZ) * 0.5f
            val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
            radius = (sqrt(dx * dx + dy * dy + dz * dz) * 0.5f).coerceIn(0.05f, 5.0f)
            val r = refPose.r
            refCamRight = floatArrayOf(r[0].toFloat(), r[1].toFloat(), r[2].toFloat())
            refCamUp = floatArrayOf(-r[3].toFloat(), -r[4].toFloat(), -r[5].toFloat())
            refCamBack = floatArrayOf(-r[6].toFloat(), -r[7].toFloat(), -r[8].toFloat())
            resetOrbit()
        }

        private var laserBufCapFloats = 0
        private var laserStage: java.nio.FloatBuffer? = null

        fun updateLive(pose: RigidPose?, cam: DepthCamera?, sensorRot: Int, laserWorld: FloatArray) {
            livePose = pose
            liveCamera = cam
            liveSensorRot = sensorRot
            val n = laserWorld.size / 3
            laserCount = n
            if (n > 0) {
                val neededFloats = n * 3
                var lb = laserStage
                if (lb == null || laserBufCapFloats < neededFloats) {
                    val cap = max(neededFloats, 2048)
                    lb = ByteBuffer.allocateDirect(cap * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    laserStage = lb
                    laserBufCapFloats = cap
                }
                lb.clear()
                lb.put(laserWorld, 0, neededFloats)
                lb.flip()
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, laserVbo)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 12, lb, GLES30.GL_DYNAMIC_DRAW)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            }
        }

        fun clear() {
            indexCount = 0; laserCount = 0; hasMesh = false
        }

        fun resetOrbit() {
            yaw = 0f; pitch = 0.15f; dist = radius * 2.4f; panX = 0f; panY = 0f
        }

        fun orbit(dx: Float, dy: Float) {
            yaw -= dx * 3.2f
            pitch = (pitch + dy * 2.6f).coerceIn(-1.45f, 1.45f)
        }

        fun zoom(factor: Float) {
            dist = (dist / factor).coerceIn(radius * 0.4f, radius * 10f)
        }

        fun pan(dx: Float, dy: Float) {
            panX -= dx * dist * 0.9f
            panY += dy * dist * 0.9f
        }

        override fun onDrawFrame(gl: GL10?) {
            if (arMode || indexCount <= 0) {
                GLES30.glClearColor(0f, 0f, 0f, 0f)
            } else {
                GLES30.glClearColor(0.06f, 0.07f, 0.09f, 1f)
            }
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            if (indexCount <= 0 && laserCount <= 0) return

            val lightDir = FloatArray(3)
            val builtCamera = if (arMode && livePose != null && liveCamera != null) {
                buildArMatrices(livePose!!, liveCamera!!, liveSensorRot, lightDir)
            } else {
                buildOrbitMatrices(lightDir)
                true
            }
            if (!builtCamera) return

            if (indexCount > 0) {
                GLES30.glUseProgram(meshProgram)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(meshProgram, "uMvp"), 1, false, mvp, 0)
                GLES30.glUniform3fv(GLES30.glGetUniformLocation(meshProgram, "uLightDir"), 1, lightDir, 0)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(meshProgram, "uShadeMode"), shadeMode)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(meshProgram, "uAlpha"), if (arMode) 0.68f else 1.0f)

                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, meshIbo)
                val aPos = GLES30.glGetAttribLocation(meshProgram, "aPos")
                val aNorm = GLES30.glGetAttribLocation(meshProgram, "aNorm")
                val aColor = GLES30.glGetAttribLocation(meshProgram, "aColor")
                val aRef = GLES30.glGetAttribLocation(meshProgram, "aRefined")
                GLES30.glEnableVertexAttribArray(aPos)
                GLES30.glEnableVertexAttribArray(aNorm)
                GLES30.glEnableVertexAttribArray(aColor)
                GLES30.glEnableVertexAttribArray(aRef)
                GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 40, 0)
                GLES30.glVertexAttribPointer(aNorm, 3, GLES30.GL_FLOAT, false, 40, 12)
                GLES30.glVertexAttribPointer(aColor, 3, GLES30.GL_FLOAT, false, 40, 24)
                GLES30.glVertexAttribPointer(aRef, 1, GLES30.GL_FLOAT, false, 40, 36)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
                GLES30.glDisableVertexAttribArray(aPos)
                GLES30.glDisableVertexAttribArray(aNorm)
                GLES30.glDisableVertexAttribArray(aColor)
                GLES30.glDisableVertexAttribArray(aRef)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
            }

            if (laserCount > 0) {
                GLES30.glUseProgram(laserProgram)
                GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(laserProgram, "uMvp"), 1, false, mvp, 0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, laserVbo)
                val aPos = GLES30.glGetAttribLocation(laserProgram, "aPos")
                GLES30.glEnableVertexAttribArray(aPos)
                GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 12, 0)
                GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                GLES30.glDrawArrays(GLES30.GL_POINTS, 0, laserCount)
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                GLES30.glDisableVertexAttribArray(aPos)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            }
        }

        private fun buildOrbitMatrices(lightOut: FloatArray) {
            val aspect = viewW.toFloat() / viewH.toFloat()
            Matrix.perspectiveM(proj, 0, 48f, aspect, 0.01f, 30f)
            val cp = cos(pitch); val sp = sin(pitch)
            val cy = cos(yaw); val sy = sin(yaw)
            // Orbit direction in reference-camera basis (right, up, back).
            val ox = cp * sy
            val oy = sp
            val oz = cp * cy
            val dx = refCamRight[0] * ox + refCamUp[0] * oy + refCamBack[0] * oz
            val dy = refCamRight[1] * ox + refCamUp[1] * oy + refCamBack[1] * oz
            val dz = refCamRight[2] * ox + refCamUp[2] * oy + refCamBack[2] * oz
            val tx = centreX + refCamRight[0] * panX + refCamUp[0] * panY
            val ty = centreY + refCamRight[1] * panX + refCamUp[1] * panY
            val tz = centreZ + refCamRight[2] * panX + refCamUp[2] * panY
            val ex = tx + dx * dist
            val ey = ty + dy * dist
            val ez = tz + dz * dist
            Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, tz, refCamUp[0], refCamUp[1], refCamUp[2])
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
            // Headlamp slightly offset from eye for crisp relief shading.
            val lx = dx + refCamUp[0] * 0.25f + refCamRight[0] * 0.2f
            val ly = dy + refCamUp[1] * 0.25f + refCamRight[1] * 0.2f
            val lz = dz + refCamUp[2] * 0.25f + refCamRight[2] * 0.2f
            val len = sqrt(lx * lx + ly * ly + lz * lz).coerceAtLeast(1e-6f)
            lightOut[0] = lx / len; lightOut[1] = ly / len; lightOut[2] = lz / len
        }

        private fun buildArMatrices(pose: RigidPose, cam: DepthCamera, sensorRot: Int, lightOut: FloatArray): Boolean {
            val w = cam.width.toFloat(); val h = cam.height.toFloat()
            if (w <= 1f || h <= 1f) return false
            val near = 0.03f; val far = 15f
            // OpenGL column-major projection from camera intrinsics (sensor coordinates, Y down -> NDC Y up).
            val fx = cam.fx.toFloat(); val fy = cam.fy.toFloat()
            val cx = cam.cx.toFloat(); val cy = cam.cy.toFloat()
            val pMat = FloatArray(16)
            pMat[0] = 2f * fx / w
            pMat[5] = 2f * fy / h
            pMat[8] = 1f - 2f * cx / w
            pMat[9] = 2f * cy / h - 1f
            pMat[10] = -(far + near) / (far - near)
            pMat[11] = -1f
            pMat[14] = -2f * far * near / (far - near)
            // Match TextureView sensor rotation in NDC.
            Matrix.setIdentityM(rotMat, 0)
            val rot = ((sensorRot % 360) + 360) % 360
            if (rot != 0) Matrix.rotateM(rotMat, 0, -rot.toFloat(), 0f, 0f, 1f)
            Matrix.multiplyMM(proj, 0, rotMat, 0, pMat, 0)

            // World -> OpenGL camera: apply diag(1, -1, -1) to CV camera pose (R_cw, t_cw).
            val r = pose.r; val t = pose.t
            view[0] = r[0].toFloat(); view[4] = r[1].toFloat(); view[8] = r[2].toFloat(); view[12] = t[0].toFloat()
            view[1] = -r[3].toFloat(); view[5] = -r[4].toFloat(); view[9] = -r[5].toFloat(); view[13] = -t[1].toFloat()
            view[2] = -r[6].toFloat(); view[6] = -r[7].toFloat(); view[10] = -r[8].toFloat(); view[14] = -t[2].toFloat()
            view[3] = 0f; view[7] = 0f; view[11] = 0f; view[15] = 1f
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
            // Light from camera backward direction (-row 2 of R_cw).
            lightOut[0] = -r[6].toFloat(); lightOut[1] = -r[7].toFloat(); lightOut[2] = -r[8].toFloat()
            return true
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src.trimIndent().trimStart())
            GLES30.glCompileShader(s)
            val status = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                android.util.Log.e("LiveMeshView", "shader compile failed: " + GLES30.glGetShaderInfoLog(s))
            }
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            val status = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                android.util.Log.e("LiveMeshView", "program link failed: " + GLES30.glGetProgramInfoLog(p))
            }
            return p
        }
    }

    companion object {
        private const val MESH_VS = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNorm;
            layout(location = 2) in vec3 aColor;
            layout(location = 3) in float aRefined;
            uniform mat4 uMvp;
            out vec3 vNorm;
            out vec3 vColor;
            out float vRefined;
            void main() {
                vNorm = aNorm;
                vColor = aColor;
                vRefined = aRefined;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val MESH_FS = """
            #version 300 es
            precision mediump float;
            in vec3 vNorm;
            in vec3 vColor;
            in float vRefined;
            uniform vec3 uLightDir;
            uniform int uShadeMode;
            uniform float uAlpha;
            out vec4 fragColor;
            void main() {
                vec3 n = normalize(vNorm);
                vec3 l = normalize(uLightDir);
                float ndl = abs(dot(n, l));
                float rim = pow(1.0 - ndl, 2.0);
                float spec = pow(ndl, 24.0) * (0.08 + 0.28 * vRefined);
                vec3 base;
                if (uShadeMode == 1) {
                    // Clay relief: matte slate on coarse DA3, crisp warm ivory where laser refined.
                    vec3 coarseClay = vec3(0.48, 0.52, 0.58);
                    vec3 fineClay = vec3(0.84, 0.90, 0.86);
                    base = mix(coarseClay, fineClay, vRefined);
                    vec3 lit = base * (0.26 + 0.74 * ndl) + vec3(spec) + vec3(0.05, 0.10, 0.08) * rim * vRefined;
                    fragColor = vec4(lit, uAlpha);
                } else if (uShadeMode == 2) {
                    // Pure lit camera colour.
                    vec3 lit = vColor * (0.35 + 0.65 * ndl) + vec3(spec * 0.5);
                    fragColor = vec4(lit, uAlpha);
                } else {
                    // Detail highlight: muted DA3 base, bright & crisp laser-refined detail.
                    vec3 coarseCol = vColor * 0.72;
                    vec3 fineCol = mix(vColor, vec3(0.75, 1.0, 0.85), 0.18);
                    base = mix(coarseCol, fineCol, vRefined);
                    vec3 lit = base * (0.28 + 0.72 * ndl) + vec3(spec) + vec3(0.06, 0.22, 0.14) * rim * vRefined;
                    fragColor = vec4(lit, uAlpha);
                }
            }
        """

        private const val LASER_VS = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            uniform mat4 uMvp;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                gl_PointSize = 7.0;
            }
        """

        private const val LASER_FS = """
            #version 300 es
            precision mediump float;
            out vec4 fragColor;
            void main() {
                vec2 d = gl_PointCoord - vec2(0.5);
                if (dot(d, d) > 0.25) discard;
                fragColor = vec4(1.0, 0.22, 0.16, 0.95);
            }
        """
    }
}
