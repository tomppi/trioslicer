package com.tomppi.enderslicer.viewer

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * One render of the plate, at the camera the user is looking through.
 *
 * The picture is the app's own renderer's: the plate's GL surface is drawn for a frame and read
 * back from the frame buffer it just drew, then scaled down so its long side is at most
 * [MAX_LONG_SIDE] pixels and encoded as a JPEG. The engine's screenshot is never used - it is a
 * second camera nobody is looking through, and the bridge refuses it for that reason. Nothing of
 * the app around the plate is in the picture: the frame buffer is the plate view alone.
 *
 * [dataUrl] is how the image reaches an OpenAI-compatible endpoint: the standard
 * `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}}` content part. Nothing
 * else ever renders [bytes] as text - [sizeLine] is the only form a chat transcript, a tool row
 * or a log line names it by, so a base64 payload can never end up in a copied transcript.
 */
class PlateImage(
    val bytes: ByteArray,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    /** How many bodies the plate was showing when it was rendered. */
    val bodies: Int,
    /** How many triangles the plate was drawing when it was rendered. */
    val triangles: Int,
    /**
     * True when the picture really carries the selected face's amber tint.
     *
     * Checked on the encoded frame rather than assumed from the selection state: the caption
     * says "the selected face is tinted" only when a look at the pixels finds it, so a driver,
     * a lost context or a frame drawn before the tint arrived can never make the agent read a
     * claim about a picture that does not show it.
     */
    val tintVisible: Boolean = false,
) {
    /** The OpenAI chat-completions content part that carries this image. */
    val dataUrl: String
        get() = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes)

    /** "768×1660 px, 84 KB" - what a transcript or a tool row says instead of the payload. */
    val sizeLine: String
        get() = widthPx.toString() + "×" + heightPx + " px, " + ((bytes.size + 1023) / 1024) + " KB"

    /** "1 body, 12,000 triangles" - the plate's geometry in the caption. */
    val geometryLine: String
        get() = bodies.toString() + (if (bodies == 1) " body, " else " bodies, ") +
            triangles.toString() + (if (triangles == 1) " triangle" else " triangles")

    companion object {
        /** The longest side a plate render may have, in pixels. */
        const val MAX_LONG_SIDE = 1_536

        /** JPEG quality: legible at 1,536 px, and still a sane size on a phone connection. */
        const val JPEG_QUALITY = 85
    }
}

/**
 * Encodes one drawn plate frame into a [PlateImage].
 *
 * The frame has already been read back and scaled on the GL thread (see
 * [ModelSurfaceView.capturePlate]); this is only the compression, kept out of the renderer so a
 * capture costs the frame it was taken on and nothing after it.
 */
object PlateImageCodec {
    fun encode(bitmap: Bitmap, bodies: Int, triangles: Int): PlateImage? {
        val scaled = scaleToLimit(bitmap)
        val stream = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, PlateImage.JPEG_QUALITY, stream)) return null
        val bytes = stream.toByteArray()
        if (bytes.isEmpty()) return null
        return PlateImage(
            bytes = bytes,
            mimeType = "image/jpeg",
            widthPx = scaled.width,
            heightPx = scaled.height,
            bodies = bodies,
            triangles = triangles,
            tintVisible = hasFaceTint(scaled),
        )
    }

    /**
     * True when the picture really carries the selected face's amber tint.
     *
     * See [FaceTintScan] for the test; this only reads the frame's pixels out.
     */
    private fun hasFaceTint(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return FaceTintScan.visible(pixels, bitmap.width, bitmap.height)
    }

    private fun scaleToLimit(bitmap: Bitmap): Bitmap {
        val longSide = maxOf(bitmap.width, bitmap.height)
        if (longSide <= PlateImage.MAX_LONG_SIDE) return bitmap
        val factor = PlateImage.MAX_LONG_SIDE.toFloat() / longSide.toFloat()
        val width = (bitmap.width * factor).toInt().coerceAtLeast(1)
        val height = (bitmap.height * factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}


/**
 * The selected face's amber tint, found in a plate frame's pixels.
 *
 * The renderer does not draw the tint as a flat colour: the mesh shader mixes it over the shaded
 * face - `mix(shaded, FACE_TINT_COLOR, 0.85)` with `FACE_TINT_COLOR = (1.0, 0.78, 0.22)` - so a
 * tinted pixel is 85% amber and 15% of the face's own material (a blue part, or the support paint
 * under the selection) under the frame's lighting. That 15% is what keeps the test honest: it
 * holds the blue channel up and the red below white, which is a narrow band no other warm thing on
 * the plate occupies.
 *
 * This replaced a general "warm pixel" test (red above green above blue) that also matched amber
 * annotations, Smart Infill's load and armed surfaces and red blocker paint, so a frame with no
 * tint at all could claim one to the agent. The band below rejects those by their own colour, the
 * flat annotation amber (the one colour that is exactly the tint's own amber) is rejected
 * outright, and a solid patch is required so a line's or an edge's few pixels cannot answer for a
 * face. The caption says nothing when in doubt.
 */
internal object FaceTintScan {

    /** Points sampled per axis in the coarse pass over the frame. */
    private const val SAMPLES = 3

    /** A tint needs a solid square patch this many pixels wide... */
    private const val PATCH = 6

    /** ...with at least this many of its pixels passing (a few JPEG outliers are tolerated). */
    private const val PATCH_PASSING = 30

    /** Frames bigger than this are coarse-scanned with a stride. */
    private const val LARGE_FRAME_PIXELS = 400_000

    /**
     * True when one solid patch of the frame is the selected face's tint.
     *
     * [pixels] is row-major ARGB, top row first, as [Bitmap.getPixels] returns it.
     */
    fun visible(pixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return false
        val step = if (pixels.size > LARGE_FRAME_PIXELS) 3 else 1
        val span = SAMPLES * step
        if (width < span || height < span) return false
        var y = 0
        while (y <= height - span) {
            var x = 0
            while (x <= width - span) {
                var sampled = 0
                var passing = 0
                var dy = 0
                while (dy < SAMPLES) {
                    val row = (y + dy * step) * width + x
                    var dx = 0
                    while (dx < SAMPLES) {
                        if (isTint(pixels[row + dx * step])) passing++
                        sampled++
                        dx++
                    }
                    dy++
                }
                // One sample may fall on an antialiased edge; a whole face does not.
                if (passing >= sampled - 1 && solidPatch(pixels, width, height, x, y)) return true
                x += step
            }
            y += step
        }
        return false
    }

    /** Counts a real [PATCH] x [PATCH] window at a coarse candidate's corner. */
    private fun solidPatch(
        pixels: IntArray,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
    ): Boolean {
        val x0 = startX.coerceIn(0, maxOf(width - PATCH, 0))
        val y0 = startY.coerceIn(0, maxOf(height - PATCH, 0))
        var passing = 0
        for (row in 0 until PATCH) {
            val base = (y0 + row) * width + x0
            for (column in 0 until PATCH) {
                if (isTint(pixels[base + column])) passing++
            }
        }
        return passing >= PATCH_PASSING
    }

    /**
     * One pixel of the tint's band.
     *
     * Bounds are the mix's own range for a blue part (with or without support paint under it)
     * across the shader's lighting range, with a little room for JPEG. Deliberately not a hue
     * test: a hue test is what counted every warm overlay as a tint.
     */
    private fun isTint(pixel: Int): Boolean {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF

        // The annotation colour IS the tint's amber, drawn flat and unlit. A mixed tint never
        // reaches it (the 15% of the blue part keeps the red below the flat amber and the green
        // off it), so the exact colour cannot be a tint pixel - a line of annotations must not
        // answer for a face.
        if (red >= 245 && green in 184..204 && blue in 46..66) return false

        if (red < 212) return false
        if (green !in 158..218) return false
        if (blue !in 42..112) return false
        val redMinusGreen = red - green
        if (redMinusGreen < 10 || redMinusGreen > 96) return false
        val greenMinusBlue = green - blue
        return greenMinusBlue in 76..152
    }
}
