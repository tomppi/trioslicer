package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the plate render says about the selected face's amber tint.
 *
 * The caption tells the agent the face is tinted only when a look at the pixels finds it, so the
 * test must not count the plate's other warm things: annotation amber, Smart Infill's load and
 * armed surfaces, density-ramp reds, support paint. Each case here is a frame that has that other
 * thing and no tint, plus frames that really carry a tint over the blue part and over support
 * paint under it.
 */
class PlateImageTintTest {

    private val background = argb(24, 74, 132)
    private val tintAmberR = 1.0f
    private val tintAmberG = 0.78f
    private val tintAmberB = 0.22f
    private val tintMix = 0.85f
    private val baseBlue = floatArrayOf(0.14f, 0.58f, 0.86f)
    private val selectedBase = floatArrayOf(0.52f, 0.78f, 0.98f)

    private fun argb(r: Int, g: Int, b: Int): Int =
        (255 shl 24) or ((r.coerceIn(0, 255)) shl 16) or ((g.coerceIn(0, 255)) shl 8) or b.coerceIn(0, 255)

    /** A pixel the mesh shader mixes for a tinted face: 85% amber, 15% the shaded material. */
    private fun tintPixel(base: FloatArray, lighting: Float): Int {
        val shadedR = base[0] * lighting
        val shadedG = base[1] * lighting
        val shadedB = base[2] * lighting
        return argb(
            ((shadedR * (1f - tintMix) + tintAmberR * tintMix) * 255f).toInt(),
            ((shadedG * (1f - tintMix) + tintAmberG * tintMix) * 255f).toInt(),
            ((shadedB * (1f - tintMix) + tintAmberB * tintMix) * 255f).toInt(),
        )
    }

    private fun frame(width: Int = 240, height: Int = 160): IntArray = IntArray(width * height) { background }

    private fun fill(frame: IntArray, width: Int, x0: Int, y0: Int, w: Int, h: Int, pixel: Int) {
        for (y in y0 until minOf(y0 + h, frame.size / width)) {
            for (x in x0 until minOf(x0 + w, width)) frame[y * width + x] = pixel
        }
    }

    @Test
    fun aTintedFaceIsFound() {
        for (lighting in listOf(0.35f, 0.6f, 0.9f, 1.12f)) {
            for (base in listOf(baseBlue, selectedBase)) {
                val frame = frame()
                fill(frame, 240, 100, 60, 30, 30, tintPixel(base, lighting))
                assertTrue(
                    "a 30x30 face at lighting " + lighting + " is the tint",
                    FaceTintScan.visible(frame, 240, 160),
                )
            }
        }
    }

    @Test
    fun annotationAmberAloneIsNotAFaceTint() {
        for (thickness in listOf(2, 6, 16, 24)) {
            val frame = frame()
            // The annotation is drawn flat in exactly the tint's amber, at any width.
            fill(frame, 240, 10, 70, 220, thickness, argb(255, 194, 56))
            assertFalse(
                "annotation amber " + thickness + "px thick is not a face tint",
                FaceTintScan.visible(frame, 240, 160),
            )
        }
    }

    @Test
    fun smartInfillAndPaintWarmColoursAreNotAFaceTint() {
        val warm = listOf(
            "load" to floatArrayOf(0.95f, 0.45f, 0.10f),
            "armed pick" to floatArrayOf(1.00f, 0.84f, 0.16f),
            "blocker paint" to floatArrayOf(0.90f, 0.25f, 0.22f),
            "density red" to floatArrayOf(0.95f, 0.06f, 0.10f),
            "density yellow" to floatArrayOf(0.94f, 0.90f, 0.108f),
            "enforcer paint" to floatArrayOf(0.20f, 0.85f, 0.32f),
        )
        for ((name, colour) in warm) {
            for (lighting in listOf(0.4f, 0.7f, 1.0f, 1.12f)) {
                val frame = frame()
                fill(
                    frame, 240, 20, 20, 200, 120,
                    argb(
                        (colour[0] * lighting * 255f).toInt(),
                        (colour[1] * lighting * 255f).toInt(),
                        (colour[2] * lighting * 255f).toInt(),
                    ),
                )
                assertFalse(
                    name + " at lighting " + lighting + " is not a face tint",
                    FaceTintScan.visible(frame, 240, 160),
                )
            }
        }
    }

    @Test
    fun scatteredWarmPixelsAreNotAFaceTint() {
        val frame = frame()
        for (y in 0 until 160) {
            for (x in 0 until 240) {
                if ((x + y) % 7 == 0) frame[y * 240 + x] = argb(255, 194, 56)
                if ((x * y) % 11 == 0) frame[y * 240 + x] = tintPixel(baseBlue, 0.8f)
            }
        }
        assertFalse("isolated warm pixels are not a solid face", FaceTintScan.visible(frame, 240, 160))
    }

    @Test
    fun aTintTogetherWithAnnotationAmberIsStillFound() {
        val frame = frame()
        fill(frame, 240, 10, 20, 220, 12, argb(255, 194, 56))
        fill(frame, 240, 60, 90, 30, 30, tintPixel(baseBlue, 0.7f))
        assertTrue("the tint patch is still found beside annotation amber", FaceTintScan.visible(frame, 240, 160))
    }

    @Test
    fun theTintOverSupportPaintUnderItIsFound() {
        // A painted face keeps the tint's mix but over the paint's own colour instead of blue.
        val blocker = floatArrayOf(0.90f, 0.25f, 0.22f)
        val frame = frame()
        fill(frame, 240, 40, 40, 40, 40, tintPixel(blocker, 0.9f))
        assertTrue("a tinted painted face is still the tint", FaceTintScan.visible(frame, 240, 160))
    }

    @Test
    fun aLargeFrameIsScannedOnItsStrideAndStillFindsASmallTint() {
        val width = 800
        val height = 600
        val frame = IntArray(width * height) { background }
        fill(frame, width, 500, 400, 18, 18, tintPixel(baseBlue, 0.8f))
        assertTrue("a small solid tint in a large capture is found", FaceTintScan.visible(frame, width, height))
    }

    @Test
    fun aFrameWithNoWarmPatchSaysNothing() {
        assertFalse(FaceTintScan.visible(frame(), 240, 160))
        assertFalse("a malformed frame is not a tint", FaceTintScan.visible(IntArray(4), 240, 160))
    }
}
