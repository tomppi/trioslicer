package com.rawmechatronics.easyscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.rawmechatronics.easyscanner.FrameResult.Layer

/**
 * Draws the newest [FrameResult]'s layers over the camera preview. Layers are
 * in analysis-frame pixels (sensor orientation); one matrix rotates and
 * scales them onto the view, so the float data is drawn in place, uncopied.
 */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var source: TripleBuffer<FrameResult>? = null
    @Volatile var vio: VioRunner? = null
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
            drawVioStatus(canvas, vs)
        }
    }

    /** The minimap is the tracking indicator: it only moves while VIO is actually tracking. */
    private fun drawVioStatus(canvas: Canvas, vs: VioRunner) {
        if (!vs.initialized) return
        if (!vs.trajectoryHasData()) return   // no box, no empty map: absence means "not tracking yet"
        val size = 96f * dp
        val left = width - size - 10f * dp
        val top = height - size - 10f * dp
        val bg = Paint().apply { this.color = Color.argb(110, 0, 0, 0); style = Paint.Style.FILL }
        canvas.drawRect(left, top, left + size, top + size, bg)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.rgb(0, 190, 255)
            strokeWidth = 1.5f * dp
            style = Paint.Style.STROKE
        }
        vs.drawTrajectory(canvas, left, top, size, line)
        if (vs.poseIsLive()) {
            val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.rgb(0, 190, 255)
                textSize = 9f * dp
                style = Paint.Style.FILL
                isFakeBoldText = true
            }
            canvas.drawText("TRACKING", left + 4f * dp, top + 12f * dp, caption)
        }
    }
}
