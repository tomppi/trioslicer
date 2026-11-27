package com.rawmechatronics.easyscanner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.rawmechatronics.easyscanner.FrameResult.Layer
import kotlin.math.hypot
import kotlin.math.min

/**
 * Draws the newest [FrameResult]'s layers over the camera preview. Layers are
 * in analysis-frame pixels (sensor orientation); one matrix rotates and
 * scales them onto the view, so the float data is drawn in place, uncopied.
 *
 * The bottom-right corner holds the 3D minimap: the VIO path in world metres,
 * orbitable by dragging inside the box (pinch to zoom, double-tap resets).
 */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var source: TripleBuffer<FrameResult>? = null
    @Volatile var vio: VioRunner? = null
    @Volatile var hideMinimap: Boolean = false
    var sensorOrientation = 90

    private val m = Matrix()
    private val dp = resources.displayMetrics.density

    private fun stroke(color: Int, widthDp: Float, round: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthDp * dp
        if (round) strokeCap = Paint.Cap.ROUND
    }

    private val paints = mapOf(
        Layer.CORNERS to stroke(Color.rgb(129, 140, 248), 5f, round = true),
        Layer.GRID to stroke(Color.argb(170, 90, 180, 200), 1f),
        Layer.OUTLINE to stroke(Color.rgb(129, 140, 248), 2f),
        Layer.AXIS_X to stroke(Color.rgb(255, 64, 64), 3f),
        Layer.AXIS_Y to stroke(Color.rgb(64, 230, 64), 3f),
        Layer.AXIS_Z to stroke(Color.rgb(64, 120, 255), 3f),
        Layer.VOLUME to stroke(Color.argb(150, 60, 110, 120), 1f),
        Layer.CLOUD to stroke(Color.rgb(170, 210, 190), 2f, round = true),
        Layer.LASER_RAW to stroke(Color.rgb(255, 0, 255), 3f, round = true),
        Layer.LASER_IN to stroke(Color.rgb(255, 255, 0), 3f, round = true),
        Layer.LASER_OUT to stroke(Color.rgb(120, 120, 120), 3f, round = true),
        Layer.LASER_FIT to stroke(Color.rgb(255, 165, 0), 2f),
    )

    // --- 3D minimap -------------------------------------------------------
    private val mini = MiniMap3D()
    private val miniRect = RectF()
    private val proj = FloatArray(3)
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var pinch = false
    private var pinchDist0 = 1f
    private var pinchZoom0 = 1f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    override fun onDraw(canvas: Canvas) {
        val r = source?.latest() ?: return
        val fw = r[FrameResult.H.FRAME_W]
        val fh = r[FrameResult.H.FRAME_H]
        if (fw <= 0f || fh <= 0f) return
        val rot = ((sensorOrientation % 360) + 360) % 360
        val (rw, rh) = if (rot % 180 == 0) fw to fh else fh to fw
        val s = minOf(width / rw, height / rh)
        m.reset()
        m.postRotate(rot.toFloat())
        when (rot) {
            90 -> m.postTranslate(fh, 0f)
            180 -> m.postTranslate(fw, fh)
            270 -> m.postTranslate(0f, fw)
        }
        m.postScale(s, s)
        m.postTranslate((width - rw * s) / 2f, (height - rh * s) / 2f)

        canvas.save()
        canvas.concat(m)
        r.forEachLayer { kind, stride, count, offset ->
            val paint = paints[kind] ?: return@forEachLayer
            val base = paint.strokeWidth
            paint.strokeWidth = base / s // widths are in screen pixels
            if (stride == 4) canvas.drawLines(r.data, offset, count * 4, paint)
            else if (stride == 2) canvas.drawPoints(r.data, offset, count * 2, paint)
            paint.strokeWidth = base
        }
        canvas.restore()

        val vs = vio
        if (vs != null) {
            val kp = vs.lastKp
            if (vs.spOverlay && kp != null) {
                canvas.save()
                canvas.concat(m)
                val sx = fw / 640f
                val sy = fh / 480f
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(0, 255, 190)
                    style = Paint.Style.FILL
                }
                var i = 0
                while (i + 1 < kp.size) {
                    canvas.drawCircle(kp[i] * sx, kp[i + 1] * sy, 2.5f * dp, dot)
                    i += 2
                }
                canvas.restore()
            }
            if (!hideMinimap) drawMiniMap3D(canvas, vs)
        }
    }

    /** Square minimap in the bottom-right corner of the preview. */
    private fun minimapRect(): RectF {
        val margin = 12f * dp
        val size = min(min(width * 0.46f, 200f * dp), height * 0.50f)
        miniRect.set(width - size - margin, height - size - margin, width - margin, height - margin)
        return miniRect
    }

    private fun drawMiniMap3D(canvas: Canvas, vs: VioRunner) {
        if (!vs.initialized) return
        val pts = vs.trajectoryPoints()
        if (pts.size < 2 || !vs.trajectoryHasData()) return   // no box, no empty map: absence means "not tracking yet"
        mini.fit(pts)
        if (!mini.aligned) {
            // first real ground direction becomes "up" so walking forward moves up
            var ax = 0f; var ay = 0f; var bx = 0f; var by = 0f; var haveA = false; var haveB = false
            for (p in pts) {
                if (p.size < 3 || !p[0].isFinite() || !p[1].isFinite()) continue
                if (!haveA) { ax = p[0]; ay = p[1]; haveA = true; continue }
                if (!haveB && kotlin.math.hypot(p[0] - ax, p[1] - ay) > 0.05f) {
                    bx = p[0]; by = p[1]; haveB = true; break
                }
            }
            if (!haveB && pts.size >= 2) {
                val last = pts[pts.size - 1]
                if (last.size >= 3 && last[0].isFinite() && last[1].isFinite()) {
                    bx = last[0]; by = last[1]; haveB = true
                }
            }
            if (!mini.alignToDirection(bx - ax, by - ay)) mini.useNorthUp()
        }
        val r = minimapRect()
        if (r.width() <= 1f) return
        val cx = r.centerX()
        val cy = r.centerY()
        val size = r.width()

        // panel
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(168, 8, 10, 16)
            style = Paint.Style.FILL
        }
        canvas.drawRect(r, bg)

        canvas.save()
        canvas.clipRect(r)

        // ground grid on the floor below the path
        val step = mini.gridStep()
        val floorZ = mini.minZ - 0.04f * mini.radius
        val n = (mini.radius * 1.6f / step).toInt().coerceIn(2, 14)
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(64, 130, 170, 190)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        for (i in -n..n) {
            val x = mini.cx + i * step
            val y0 = mini.cy - n * step
            val y1 = mini.cy + n * step
            mini.project(x, y0, floorZ, size, proj); val ax = cx + proj[0]; val ay = cy + proj[1]
            mini.project(x, y1, floorZ, size, proj); val bx = cx + proj[0]; val by = cy + proj[1]
            canvas.drawLine(ax, ay, bx, by, grid)
            val y = mini.cy + i * step
            val x0 = mini.cx - n * step
            val x1 = mini.cx + n * step
            mini.project(x0, y, floorZ, size, proj); val cxx = cx + proj[0]; val cyy = cy + proj[1]
            mini.project(x1, y, floorZ, size, proj); val dxx = cx + proj[0]; val dyy = cy + proj[1]
            canvas.drawLine(cxx, cyy, dxx, dyy, grid)
        }

        // world axes at the floor centre: +X red, +Y green, +Z blue
        val axisLen = step * 2.2f
        fun axis(dx: Float, dy: Float, dz: Float, color: Int) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = 1.4f * dp
            }
            mini.project(mini.cx, mini.cy, floorZ, size, proj)
            val x0 = cx + proj[0]; val y0 = cy + proj[1]
            mini.project(mini.cx + dx, mini.cy + dy, floorZ + dz, size, proj)
            canvas.drawLine(x0, y0, cx + proj[0], cy + proj[1], paint)
        }
        axis(axisLen, 0f, 0f, Color.argb(200, 255, 96, 96))
        axis(0f, axisLen, 0f, Color.argb(200, 96, 230, 110))
        axis(0f, 0f, axisLen, Color.argb(200, 110, 150, 255))

        // tracked path
        val path = Path()
        var started = false
        var lastPX = 0f
        var lastPY = 0f
        var prevPX = 0f
        var prevPY = 0f
        var haveLast = false
        for (p in pts) {
            if (p.size < 3 || !p[0].isFinite() || !p[1].isFinite() || !p[2].isFinite()) continue
            mini.project(p[0], p[1], p[2], size, proj)
            val x = cx + proj[0]
            val y = cy + proj[1]
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
            prevPX = lastPX; prevPY = lastPY
            lastPX = x; lastPY = y
            haveLast = true
        }
        val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 210, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * dp
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, pathPaint)

        // current position and heading
        if (haveLast) {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 214, 74)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(lastPX, lastPY, 3.6f * dp, dot)
            if (hypot(lastPX - prevPX, lastPY - prevPY) > 1f) {
                val dx = lastPX - prevPX
                val dy = lastPY - prevPY
                val len = hypot(dx, dy)
                val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(255, 214, 74)
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * dp
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(lastPX, lastPY, lastPX + dx / len * 10f * dp, lastPY + dy / len * 10f * dp, head)
            }
        }
        canvas.restore()

        // border and captions
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 0, 190, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * dp
        }
        canvas.drawRect(r, border)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 0, 210, 255)
            textSize = 9f * dp
            isFakeBoldText = true
        }
        canvas.drawText("3D MAP", r.left + 6f * dp, r.top + 12f * dp, title)
        val distance = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 235, 240, 250)
            textSize = 8.5f * dp
            isFakeBoldText = true
        }
        canvas.drawText(
            String.format(java.util.Locale.US, "%.2f m path  ·  %.2f m net", vs.traveledMeters(), vs.netMeters()),
            r.left + 6f * dp, r.top + 23f * dp, distance)
        if (vs.poseIsLive()) {
            val live = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(90, 235, 140)
                textSize = 8.5f * dp
                isFakeBoldText = true
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("TRACKING", r.right - 6f * dp, r.top + 12f * dp, live)
        }
        val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 210, 220, 235)
            textSize = 7.5f * dp
        }
        canvas.drawText("drag · pinch · double-tap re-align", r.left + 6f * dp, r.bottom - 6f * dp, hint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = minimapRect()
            if (!r.contains(e.x, e.y)) return false   // let the controls underneath have it
            dragging = true
            pinch = false
            downX = e.x
            downY = e.y
            lastX = e.x
            lastY = e.y
            downTime = e.eventTime
            return true
        }
        if (!dragging && !pinch) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    pinch = true
                    pinchDist0 = spacing(e).coerceAtLeast(1f)
                    pinchZoom0 = mini.zoom
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinch && e.pointerCount >= 2) {
                    mini.zoomTo(pinchZoom0 * spacing(e) / pinchDist0)
                } else if (dragging) {
                    mini.markUserRotated()
                    mini.orbit(e.x - lastX, e.y - lastY, dp)
                    lastX = e.x
                    lastY = e.y
                }
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount - 1 < 2) pinch = false
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dt = e.eventTime - downTime
                val moved = hypot(e.x - downX, e.y - downY)
                if (!pinch && dt < 280 && moved < 14f * dp) {
                    val doubleTap = e.eventTime - lastTapTime < 320 &&
                        hypot(e.x - lastTapX, e.y - lastTapY) < 32f * dp
                    if (doubleTap) {
                        mini.resetOrbit()
                        lastTapTime = 0L
                    } else {
                        lastTapTime = e.eventTime
                        lastTapX = e.x
                        lastTapY = e.y
                    }
                    performClick()
                }
                dragging = false
                pinch = false
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                pinch = false
                return true
            }
        }
        return true
    }

    private fun spacing(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 1f
        return hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }
}
