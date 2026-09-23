package com.tomppi.enderslicer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InfillPatternLabelTest {
    @Test
    fun knownPatternsUseTheDropdownLabels() {
        assertEquals("Cubic", infillPatternLabel("cubic"))
        assertEquals("Grid", infillPatternLabel("grid"))
        assertEquals("Cubic subdivision", infillPatternLabel("cubicsubdiv"))
        assertEquals("Zig zag", infillPatternLabel("zigzag"))
        assertEquals("Quarter cubic", infillPatternLabel("quarter_cubic"))
        assertEquals("Gyroid", infillPatternLabel("gyroid"))
        assertEquals("Honeycomb", infillPatternLabel("honeycomb"))
        assertEquals("Rectilinear", infillPatternLabel("rectilinear"))
        assertEquals("Aligned rectilinear", infillPatternLabel("aligned rectilinear"))
        assertEquals("Cross hatch", infillPatternLabel("cross-hatch"))
    }

    @Test
    fun unknownPatternsFallBackToCapitalizedValue() {
        assertEquals("Zig-zag", infillPatternLabel("zig-zag"))
        assertEquals("", infillPatternLabel(""))
    }
}
