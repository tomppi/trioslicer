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
        const val MAX_LONG_SIDE = 768

        /** JPEG quality: legible at 768 px, and small enough to send on a phone connection. */
        const val JPEG_QUALITY = 82
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
     * True when enough of the picture is the selected face's amber to say it is tinted.
     *
     * The tint is mixed over the shaded blue, so its pixels are warm: red above green above
     * blue, and clearly warm rather than the part's own blue. The count is a floor, not a
     * measurement - a face only a few pixels across is still tinted, and this would rather say
     * nothing than claim a tint a human would not see.
     */
    private fun hasFaceTint(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var found = 0
        var index = 0
        val step = if (pixels.size > 200_000) 3 else 1
        while (index < pixels.size) {
            val pixel = pixels[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            if (red >= 140 && red - blue >= 60 && green > blue) {
                found++
                if (found >= MINIMUM_TINT_PIXELS) return true
            }
            index += step
        }
        return false
    }

    /** Below this many warm pixels the tint is not claimed - see [hasFaceTint]. */
    private const val MINIMUM_TINT_PIXELS = 24

    private fun scaleToLimit(bitmap: Bitmap): Bitmap {
        val longSide = maxOf(bitmap.width, bitmap.height)
        if (longSide <= PlateImage.MAX_LONG_SIDE) return bitmap
        val factor = PlateImage.MAX_LONG_SIDE.toFloat() / longSide.toFloat()
        val width = (bitmap.width * factor).toInt().coerceAtLeast(1)
        val height = (bitmap.height * factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
