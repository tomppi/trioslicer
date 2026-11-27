package com.rawmechatronics.easyscanner

/**
 * Converts Android YUV_420_888 planes to a planar RGB byte array
 * (R plane, G plane, B plane) for the SuperPoint TFLite input.
 *
 * Uses pixel-centred bilinear sampling on luma and pixel-centred sampling on chroma
 * so 960x720 -> 640x480 downsampling has zero asymmetric -0.5 px subpixel shift.
 */
object YuvRgb {
    fun toRgb(
        y: ByteArray, u: ByteArray, v: ByteArray,
        width: Int, height: Int,
        yStride: Int, uvStride: Int, uvPixelStride: Int,
        outW: Int = width, outH: Int = height,
    ): ByteArray {
        val out = ByteArray(outW * outH * 3)
        val scaleX = width.toDouble() / outW
        val scaleY = height.toDouble() / outH
        val x0Lut = IntArray(outW)
        val x1Lut = IntArray(outW)
        val wxLut = IntArray(outW)
        val uxLut = IntArray(outW)
        val maxChromaX = (width - 1) / 2
        val maxChromaY = (height - 1) / 2
        for (ox in 0 until outW) {
            val fx = ((ox + 0.5) * scaleX - 0.5).coerceIn(0.0, (width - 1).toDouble())
            val x0 = fx.toInt()
            x0Lut[ox] = x0
            x1Lut[ox] = (x0 + 1).coerceAtMost(width - 1)
            wxLut[ox] = ((fx - x0) * 256.0).toInt().coerceIn(0, 256)
            uxLut[ox] = (((ox + 0.5) * scaleX).toInt() / 2).coerceIn(0, maxChromaX) * uvPixelStride
        }
        val plane = outW * outH
        val plane2 = plane * 2
        for (oy in 0 until outH) {
            val fy = ((oy + 0.5) * scaleY - 0.5).coerceIn(0.0, (height - 1).toDouble())
            val y0 = fy.toInt()
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val wy = ((fy - y0) * 256.0).toInt().coerceIn(0, 256)
            val iwy = 256 - wy
            val row0 = y0 * yStride
            val row1 = y1 * yStride
            val uvRow = (((oy + 0.5) * scaleY).toInt() / 2).coerceIn(0, maxChromaY) * uvStride
            val outRow = oy * outW
            for (ox in 0 until outW) {
                val x0 = x0Lut[ox]; val x1 = x1Lut[ox]
                val wx = wxLut[ox]; val iwx = 256 - wx
                val y00 = y[row0 + x0].toInt() and 0xff
                val y10 = y[row0 + x1].toInt() and 0xff
                val y01 = y[row1 + x0].toInt() and 0xff
                val y11 = y[row1 + x1].toInt() and 0xff
                val yv = (((y00 * iwx + y10 * wx) * iwy + (y01 * iwx + y11 * wx) * wy) + 32768) ushr 16
                val ui = uvRow + uxLut[ox]
                val uu = u[ui].toInt() and 0xff
                val vv = v[ui].toInt() and 0xff
                val c = yv - 16
                val d = uu - 128
                val e = vv - 128
                val p = outRow + ox
                out[p] = clamp((298 * c + 409 * e + 128) shr 8).toByte()
                out[plane + p] = clamp((298 * c - 100 * d - 208 * e + 128) shr 8).toByte()
                out[plane2 + p] = clamp((298 * c + 516 * d + 128) shr 8).toByte()
            }
        }
        return out
    }

    private fun clamp(x: Int): Int = if (x < 0) 0 else if (x > 255) 255 else x
}
