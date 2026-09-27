package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The console's scrollback: what it keeps, how it splits it, and how much. */
class KlipperConsoleTest {
    @Test
    fun oneResponseWithSeveralLinesBecomesSeveralLines() {
        val console = KlipperConsole()
        // A shutdown explains itself in five lines, which is how klippy sends it.
        val lines = console.add("// line one\n// line two\n// line three", KlipperConsoleLine.Source.OUTPUT, 1L)
        assertEquals(3, lines.size)
        assertEquals("// line two", lines[1].text)
    }

    @Test
    fun blankLinesAreNotLines() {
        val console = KlipperConsole()
        val lines = console.add("\n\n  \n", KlipperConsoleLine.Source.OUTPUT, 1L)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun theScrollbackIsBounded() {
        val console = KlipperConsole(capacity = 3)
        repeat(10) { index -> console.add("line $index", KlipperConsoleLine.Source.OUTPUT, index.toLong()) }
        val lines = console.snapshot()
        assertEquals(3, lines.size)
        // The oldest are the ones dropped: a console shows what just happened.
        assertEquals(listOf("line 7", "line 8", "line 9"), lines.map { it.text })
    }

    @Test
    fun clearingLeavesNothing() {
        val console = KlipperConsole()
        console.add("something", KlipperConsoleLine.Source.SENT, 1L)
        console.clear()
        assertTrue(console.snapshot().isEmpty())
    }

    @Test
    fun whatWasSentAndWhatWasSaidAreDistinguishable() {
        val console = KlipperConsole()
        console.add("G28", KlipperConsoleLine.Source.SENT, 1L)
        val lines = console.add("!! Printer is shutdown", KlipperConsoleLine.Source.ERROR, 2L)
        assertEquals(KlipperConsoleLine.Source.ERROR, lines.last().source)
        assertEquals(2L, lines.last().atMillis)
    }
}
