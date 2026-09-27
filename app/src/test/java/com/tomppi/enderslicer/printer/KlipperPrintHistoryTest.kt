package com.tomppi.enderslicer.printer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Prints written down as they end, and read back after a restart. */
class KlipperPrintHistoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun record(file: String, outcome: String = "complete") = KlipperPrintRecord(
        fileName = file,
        startedAtMillis = 1_700_000_000_000L,
        durationSeconds = 3723.0,
        filamentMillimetres = 4321.0,
        outcome = outcome,
        layers = 120,
    )

    @Test
    fun aRecordSurvivesBeingWrittenAndReadBack() {
        val store = KlipperPrintHistory(File(folder.root, "history.json"))
        store.append(record("cube.gcode"))
        val reloaded = KlipperPrintHistory(File(folder.root, "history.json")).load()
        assertEquals(1, reloaded.size)
        assertEquals("cube.gcode", reloaded.first().fileName)
        assertEquals(3723.0, reloaded.first().durationSeconds, 0.001)
        assertEquals(120, reloaded.first().layers)
    }

    @Test
    fun theNewestPrintIsFirst() {
        val store = KlipperPrintHistory(File(folder.root, "history.json"))
        store.append(record("first.gcode"))
        store.append(record("second.gcode"))
        assertEquals(listOf("second.gcode", "first.gcode"), store.load().map { it.fileName })
    }

    @Test
    fun clearingLeavesAnEmptyFileAndNoRecords() {
        val file = File(folder.root, "history.json")
        val store = KlipperPrintHistory(file)
        store.append(record("cube.gcode"))
        assertTrue(store.clear().isEmpty())
        assertTrue(KlipperPrintHistory(file).load().isEmpty())
    }

    @Test
    fun unreadableHistoryIsAnEmptyOneRatherThanAFailure() {
        val file = File(folder.root, "history.json").apply { writeText("{ not json at all") }
        assertTrue(KlipperPrintHistory(file).load().isEmpty())
    }

    @Test
    fun theOldestRecordsFallOffTheEnd() {
        val store = KlipperPrintHistory(File(folder.root, "history.json"))
        repeat(205) { index -> store.append(record("print-$index.gcode")) }
        val records = store.load()
        assertEquals(200, records.size)
        // Newest first, and the newest is the last one added.
        assertEquals("print-204.gcode", records.first().fileName)
        assertEquals("print-5.gcode", records.last().fileName)
    }
}
