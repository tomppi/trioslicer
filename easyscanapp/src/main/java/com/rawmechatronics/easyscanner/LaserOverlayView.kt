package com.rawmechatronics.easyscanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Draws the engine's laser layers over the AR camera texture. All arrays are in
 * view pixels (already mapped with ARCore's IMAGE_PIXELS -> VIEW transform);
 * points are interleaved x,y; segments are x0,y0,x1,y1.
 */
class LaserOverlayView(ctx: Context) : View(ctx) {

    private val rawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 0, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val inPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val outPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 140, 140)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val volumePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 60, 190, 210)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var raw: FloatArray = FloatArray(0)
    private var inside: FloatArray = FloatArray(0)
    private var outside: FloatArray = FloatArray(0)
    private var volume: FloatArray = FloatArray(0)
    private var gizmoX: FloatArray = FloatArray(0)
    private var gizmoY: FloatArray = FloatArray(0)
    private var gizmoZ: FloatArray = FloatArray(0)
    private var ring: FloatArray = FloatArray(0)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 230, 120)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    fun updateRing(r: FloatArray) {
        ring = r
        invalidate()
    }

    private fun gizmoPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    private val paintX = gizmoPaint(Color.rgb(255, 96, 96))
    private val paintY = gizmoPaint(Color.rgb(96, 240, 120))
    private val paintZ = gizmoPaint(Color.rgb(110, 160, 255))

    fun updateGizmo(x: FloatArray, y: FloatArray, z: FloatArray) {
        gizmoX = x
        gizmoY = y
        gizmoZ = z
        invalidate()
    }

    fun update(raw: FloatArray, inside: FloatArray, outside: FloatArray, volume: FloatArray) {
        this.raw = raw
        this.inside = inside
        this.outside = outside
        this.volume = volume
        invalidate()
    }

    fun clear() = update(FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))

    override fun onDraw(canvas: Canvas) {
        if (ring.size >= 4) canvas.drawLines(ring, ringPaint)
        if (gizmoX.size >= 4) canvas.drawLines(gizmoX, paintX)
        if (gizmoY.size >= 4) canvas.drawLines(gizmoY, paintY)
        if (gizmoZ.size >= 4) canvas.drawLines(gizmoZ, paintZ)
        if (volume.size >= 4) canvas.drawLines(volume, volumePaint)
        if (outside.size >= 2) canvas.drawPoints(outside, outPaint)
        if (raw.size >= 2) canvas.drawPoints(raw, rawPaint)
        if (inside.size >= 2) canvas.drawPoints(inside, inPaint)
    }
}
