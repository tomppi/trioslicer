package com.tomppi.enderslicer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The panel's numeric fields: what they accept, and — more importantly — what
 * they must refuse before it reaches the engine's JSON.
 */
class SmartInfillNumberInputTest {

    @Test
    fun numbersParseWithEitherDecimalSeparator() {
        assertEquals(0.5, parseNumberInput("0.5")!!, 1e-9)
        assertEquals(0.5, parseNumberInput("0,5")!!, 1e-9)
        assertEquals(-120.0, parseNumberInput("-120")!!, 1e-9)
        assertEquals(1e-4, parseNumberInput("1e-4")!!, 1e-12)
        assertEquals(210000.0, parseNumberInput("210000")!!, 1e-6)
    }

    @Test
    fun emptyAndUnparsableInputIsIgnored() {
        assertNull("clearing the field must not invent a value", parseNumberInput(""))
        assertNull(parseNumberInput(" "))
        assertNull(parseNumberInput("abc"))
        assertNull(parseNumberInput("-"))
    }

    @Test
    fun nonFiniteValuesNeverReachTheEngine() {
        // Double.parseDouble accepts these words, and org.json throws on the
        // values they produce — so a typed one has to be dropped here.
        assertNull(parseNumberInput("NaN"))
        assertNull(parseNumberInput("nan"))
        assertNull(parseNumberInput("Infinity"))
        assertNull(parseNumberInput("-Infinity"))
        assertNull("1e400 overflows to infinity", parseNumberInput("1e400"))
    }
}
