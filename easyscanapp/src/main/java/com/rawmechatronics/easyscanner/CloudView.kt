package com.rawmechatronics.easyscanner

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

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var fx = 0f
        private var fy = 0f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { fx = d.focusX; fy = d.focusY; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val dx = d.focusX - fx; val dy = d.focusY - fy
            fx = d.focusX; fy = d.focusY
            queueEvent { renderer.zoom(d.scaleFactor); renderer.pan(dx / height, dy / height) }
            requestRender()
            return true
        }
    })

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        scale.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; orbiting = true }
            MotionEvent.ACTION_POINTER_DOWN -> orbiting = false
            MotionEvent.ACTION_MOVE -> if (orbiting && e.pointerCount == 1 && !scale.isInProgress) {
                val dx = e.x - lastX; val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                queueEvent { renderer.orbit(dx / width, dy / height) }
                requestRender()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> orbiting = false
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
        private var yaw = -0.9f
        private var pitch = 0.6f
        private var distance = 3f
        private var aspect = 1f
        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val mvp = FloatArray(16)

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
            yaw = -0.9f
            pitch = 0.6f
            distance = radius * 3.2f
        }

        fun orbit(dx: Float, dy: Float) {
            yaw -= dx * 4f
            pitch = (pitch + dy * 3f).coerceIn(-1.5f, 1.5f)
        }

        fun zoom(factor: Float) {
            distance = (distance / factor).coerceIn(radius * .05f, radius * 20f)
        }

        /** Move the orbit centre in the view plane; dx, dy in screen heights. */
        fun pan(dx: Float, dy: Float) {
            val k = distance * .8f
            // right = (-sin yaw, cos yaw, 0); up = Z-ish, tilted with the pitch
            val rx = -sin(yaw); val ry = cos(yaw)
            val ux = -cos(yaw) * sin(pitch); val uy = -sin(yaw) * sin(pitch); val uz = cos(pitch)
            target[0] -= (rx * dx - ux * dy) * k
            target[1] -= (ry * dx - uy * dy) * k
            target[2] -= (-uz * dy) * k
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
            val ex = target[0] + distance * cos(pitch) * cos(yaw)
            val ey = target[1] + distance * cos(pitch) * sin(yaw)
            val ez = target[2] + distance * sin(pitch)
            Matrix.setLookAtM(view, 0, ex, ey, ez, target[0], target[1], target[2], 0f, 0f, 1f)
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
