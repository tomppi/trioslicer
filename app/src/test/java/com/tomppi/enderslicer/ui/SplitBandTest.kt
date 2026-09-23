package com.tomppi.enderslicer.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The band between the split's two columns: the device's fold when the directive
 * reports one inside the panel, and the directive's own spacer otherwise.
 */
class SplitBandTest {

    private val density = Density(1f)

    @Test
    fun withoutAFoldTheColumnsShareTheWidthAroundTheSpacer() {
        val band = splitBand(
            panelLeftPx = 0f,
            panelWidthPx = 1000f,
            fold = null,
            spacer = 24.dp,
            density = density,
        )

        assertEquals(24.dp, band.width)
        assertNull("nothing to sit on, so the band is centred", band.start)
    }

    @Test
    fun aFoldInsideThePanelIsTheBand() {
        // The panel starts after the navigation rail, so the fold is nearer its
        // left edge than the window's: the band has to move with it.
        val band = splitBand(
            panelLeftPx = 80f,
            panelWidthPx = 1104f,
            fold = Rect(left = 592f, top = 0f, right = 620f, bottom = 900f),
            spacer = 24.dp,
            density = density,
        )

        assertEquals(28.dp, band.width)
        assertEquals(512.dp, band.start)
    }

    @Test
    fun aFoldThisLayoutCannotHonourFallsBackToTheSpacer() {
        val outside = splitBand(
            panelLeftPx = 80f,
            panelWidthPx = 1104f,
            fold = Rect(left = 10f, top = 0f, right = 38f, bottom = 900f),
            spacer = 24.dp,
            density = density,
        )
        assertNull(outside.start)

        // Wide enough to leave nothing but slivers either side is not a fold this
        // two-column split can sit on.
        val tooWide = splitBand(
            panelLeftPx = 0f,
            panelWidthPx = 400f,
            fold = Rect(left = 100f, top = 0f, right = 400f, bottom = 900f),
            spacer = 24.dp,
            density = density,
        )
        assertNull(tooWide.start)
        assertEquals(24.dp, tooWide.width)
    }
}
