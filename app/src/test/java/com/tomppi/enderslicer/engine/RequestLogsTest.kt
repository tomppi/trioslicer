package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-request logs are diagnostics: keeping a day of them is enough to look
 * at a failure, and every engine has to reap its own or the directory grows with
 * every slice.
 */
class RequestLogsTest {

    private fun logFile(directory: File, name: String, ageMillis: Long): File {
        val file = File(directory, name)
        file.writeText("TrioSlicer diagnostic log\n")
        check(file.setLastModified(System.currentTimeMillis() - ageMillis)) {
            "unable to age " + file.name
        }
        return file
    }

    @Test
    fun reapsThisEnginesOldRequestLogsAndKeepsTheRest() {
        val directory = kotlin.io.path.createTempDirectory("request-logs").toFile()
        try {
            val day = 24L * 60L * 60L * 1_000L
            val stale = logFile(directory, "prusaengine-1-old.log", day + 60_000L)
            val fresh = logFile(directory, "prusaengine-2-new.log", 60_000L)
            val latest = logFile(directory, "latest-prusaengine.log", day + 60_000L)
            val other = logFile(directory, "orcaengine-1-old.log", day + 60_000L)

            cleanupStaleRequestLogs(directory, "prusaengine-")

            assertFalse("an old request log is reaped", stale.exists())
            assertTrue("a fresh one is kept", fresh.exists())
            assertTrue("the latest copy is what Export log reads", latest.exists())
            assertTrue("another engine's logs are not touched", other.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
