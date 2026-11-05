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

    @Test
    fun recordsAppendedFromSeveralThreadsLoseNothing() {
        val file = File(folder.root, "history.json")
        val store = KlipperPrintHistory(file)
        val writers = 8
        val each = 10
        val pool = java.util.concurrent.Executors.newFixedThreadPool(writers)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val done = java.util.concurrent.CountDownLatch(writers)
            repeat(writers) { writer ->
                pool.execute {
                    start.await()
                    repeat(each) { index ->
                        store.append(record("print-$writer-$index.gcode"))
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("the writers finish", done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        // An append is a read-modify-write of one file: two that interleave lose one of
        // the records, or leave the file half-written - which loads as a whole lost
        // history rather than as a complaint.
        val records = KlipperPrintHistory(file).load()
        assertEquals("every record survives", writers * each, records.size)
        assertEquals(
            "and no record stands for two",
            writers * each,
            records.map { it.fileName }.toSet().size,
        )
    }
}
