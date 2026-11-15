package com.tomppi.enderslicer.easyscan

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * A point cloud on the phone: OpenGL ES 2 points with their own colours, Z up.
 * One finger orbits, two fingers zoom and pan, a double tap frames it again.
 * Renders only when something changes.
 */
class CloudView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GLSurfaceView(context, attrs) {

    private val renderer = Renderer(resources.displayMetrics.density)

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun show(cloud: PlyCloud) {
        val (centre, radius) = cloud.bounds()
        queueEvent {
            renderer.upload(cloud, centre, radius)
            requestRender()
        }
    }

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            queueEvent { renderer.reset() }
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
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                orbiting = true
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
                        val dx = fx - lastFocusX
                        val dy = fy - lastFocusY
                        queueEvent {
                            renderer.zoom(factor)
                            renderer.pan(dx / width, dy / height)
                        }
                        requestRender()
                    }
                    lastSpan = sp
                    lastFocusX = fx
                    lastFocusY = fy
                } else if (orbiting) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    queueEvent { renderer.orbit(dx / width, dy / height) }
                    requestRender()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> lastSpan = 0f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                orbiting = false
                lastSpan = 0f
            }
        }
        return true
    }

    private class Renderer(private val density: Float) : GLSurfaceView.Renderer {
        private var program = 0
        private var buffers = IntArray(2)
        private var cloud: PlyCloud? = null
        private var count = 0
        private val centre = FloatArray(3)
        private val target = FloatArray(3)
        private var radius = 1f
        /** Camera orientation: columns are right, up, back (camera -> world). */
        private val rot = FloatArray(9)
        private var distance = 3f
        private var aspect = 1f
        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val mvp = FloatArray(16)

        init {
            setRotation(-0.9f, 0.6f)
        }

        /** Build the camera orientation from the old yaw/pitch framing. */
        private fun setRotation(yaw: Float, pitch: Float) {
            val cy = cos(yaw); val sy = sin(yaw)
            val cp = cos(pitch); val sp = sin(pitch)
            // right, up, back columns
            rot[0] = -sy; rot[1] = cy; rot[2] = 0f
            rot[3] = -sp * cy; rot[4] = -sp * sy; rot[5] = cp
            rot[6] = cp * cy; rot[7] = cp * sy; rot[8] = sp
        }

        /** rot = R(axis, angle) * rot, so the camera turns in world space and never locks. */
        private fun rotateAxis(ax: Float, ay: Float, az: Float, angle: Float) {
            val c = cos(angle); val s = sin(angle); val t = 1f - c
            val m00 = c + ax * ax * t; val m01 = ax * ay * t - az * s; val m02 = ax * az * t + ay * s
            val m10 = ay * ax * t + az * s; val m11 = c + ay * ay * t; val m12 = ay * az * t - ax * s
            val m20 = az * ax * t - ay * s; val m21 = az * ay * t + ax * s; val m22 = c + az * az * t
            val r0 = rot[0]; val r1 = rot[1]; val r2 = rot[2]
            val r3 = rot[3]; val r4 = rot[4]; val r5 = rot[5]
            val r6 = rot[6]; val r7 = rot[7]; val r8 = rot[8]
            rot[0] = m00 * r0 + m01 * r3 + m02 * r6
            rot[1] = m00 * r1 + m01 * r4 + m02 * r7
            rot[2] = m00 * r2 + m01 * r5 + m02 * r8
            rot[3] = m10 * r0 + m11 * r3 + m12 * r6
            rot[4] = m10 * r1 + m11 * r4 + m12 * r7
            rot[5] = m10 * r2 + m11 * r5 + m12 * r8
            rot[6] = m20 * r0 + m21 * r3 + m22 * r6
            rot[7] = m20 * r1 + m21 * r4 + m22 * r7
            rot[8] = m20 * r2 + m21 * r5 + m22 * r8
        }

        fun upload(cloud: PlyCloud, c: FloatArray, r: Float) {
            this.cloud = cloud
            centre.indices.forEach { centre[it] = c[it] }
            radius = r
            reset()
            push(cloud)
        }

        private fun push(cloud: PlyCloud) {
            val pos = ByteBuffer.allocateDirect(cloud.xyz.size * 4).order(ByteOrder.nativeOrder())
            pos.asFloatBuffer().put(cloud.xyz)
            val col = ByteBuffer.allocateDirect(cloud.rgb.size).order(ByteOrder.nativeOrder()).put(cloud.rgb)
            col.position(0)
            if (buffers[0] == 0) GLES20.glGenBuffers(2, buffers, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, cloud.xyz.size * 4, pos, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[1])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, cloud.rgb.size, col, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            count = cloud.count
        }

        fun reset() {
            target.indices.forEach { target[it] = centre[it] }
            setRotation(-0.9f, 0.6f)
            distance = radius * 3.2f
        }

        fun orbit(dx: Float, dy: Float) {
            // One rotation per drag step, about the screen-plane axis perpendicular to
            // the drag: horizontal drags spin about the view's up axis, vertical ones
            // about its right axis, and mixed drags about a diagonal between them. A
            // single axis keeps the direction the finger asks for (two sequential
            // rotations mixed the axes and sent a sideways swipe downwards).
            val uw = -dx * 4f
            val vw = -dy * 3f
            var ax = rot[3] * uw + rot[0] * vw
            var ay = rot[4] * uw + rot[1] * vw
            var az = rot[5] * uw + rot[2] * vw
            val len = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
            if (len > 1e-6f) {
                ax /= len; ay /= len; az /= len
                rotateAxis(ax, ay, az, len)
            }
        }

        fun zoom(factor: Float) {
            distance = (distance / factor).coerceIn(radius * .05f, radius * 20f)
        }

        /** Move the orbit centre in the view plane; dx, dy in screen heights. */
        fun pan(dx: Float, dy: Float) {
            val k = distance * .8f
            val rx = rot[0]; val ry = rot[1]; val rz = rot[2]
            val ux = rot[3]; val uy = rot[4]; val uz = rot[5]
            target[0] -= (rx * dx - ux * dy) * k
            target[1] -= (ry * dx - uy * dy) * k
            target[2] -= (rz * dx - uz * dy) * k
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.031f, 0.031f, 0.039f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            program = link(VERTEX, FRAGMENT)
            buffers = IntArray(2)   // a new context lost the old buffers
            cloud?.let(::push)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            aspect = w.toFloat() / maxOf(h, 1)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (count == 0 || buffers[0] == 0) return
            Matrix.perspectiveM(proj, 0, 45f, aspect, distance * .01f, distance + radius * 40f)
            val ex = target[0] + distance * rot[6]
            val ey = target[1] + distance * rot[7]
            val ez = target[2] + distance * rot[8]
            Matrix.setLookAtM(view, 0, ex, ey, ez, target[0], target[1], target[2], rot[3], rot[4], rot[5])
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSize"), 2.2f * density)
            val aPos = GLES20.glGetAttribLocation(program, "aPos")
            val aCol = GLES20.glGetAttribLocation(program, "aCol")
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[1])
            GLES20.glEnableVertexAttribArray(aCol)
            GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_UNSIGNED_BYTE, true, 0, 0)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aCol)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        private fun link(vs: String, fs: String): Int {
            fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, src)
                GLES20.glCompileShader(it)
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vs))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(it)
            }
        }

        companion object {
            const val VERTEX = """
                uniform mat4 uMvp;
                uniform float uSize;
                attribute vec3 aPos;
                attribute vec3 aCol;
                varying vec3 vCol;
                void main() {
                    gl_Position = uMvp * vec4(aPos, 1.0);
                    gl_PointSize = uSize;
                    vCol = aCol;
                }"""
            const val FRAGMENT = """
                precision mediump float;
                varying vec3 vCol;
                void main() {
                    vec2 c = gl_PointCoord - 0.5;
                    if (dot(c, c) > 0.25) discard;
                    gl_FragColor = vec4(vCol, 1.0);
                }"""
        }
    }
}
