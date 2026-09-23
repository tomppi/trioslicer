package com.tomppi.enderslicer.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PrusaSlicer child must be owned from the moment it is created: the log
 * writer and the reader thread used to be set up outside the try/finally that
 * reaps it, so a throw there left a running slicer behind while the caller
 * deleted its workspace.
 */
class PrusaProcessOwnershipTest {
    @Test
    fun reapsTheChildWhenTheLogWriterCannotBeOpened() {
        val process = RecordingProcess()
        // The parent of this path is a regular file, so opening the log throws
        // after the process has already been started.
        val blocker = File.createTempFile("prusa-ownership", ".parent").apply { deleteOnExit() }
        val log = File(blocker, "prusaengine-child.log")

        val failure = runCatching {
            PrusaEngineRunner.runWithProgress({ process }, log) { }
        }.exceptionOrNull()

        assertTrue("expected the log failure but got " + failure, failure is IOException)
        assertTrue("the started child must be reaped", process.destroyed)
    }

    @Test
    fun returnsTheExitCodeOfAFinishedProcessWithoutKillingIt() {
        val process = RecordingProcess(exitCode = 0, running = false)
        val log = File.createTempFile("prusa-ownership", ".log").apply { deleteOnExit() }

        val exitCode = PrusaEngineRunner.runWithProgress({ process }, log) { }

        assertEquals(0, exitCode)
        assertFalse(process.destroyed)
    }

    private class RecordingProcess(exitCode: Int = 0, private val running: Boolean = true) : Process() {
        private val code = exitCode
        var destroyed = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArray(0).inputStream()

        override fun getErrorStream(): InputStream = ByteArray(0).inputStream()

        override fun waitFor(): Int = code

        override fun exitValue(): Int = code

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }

        override fun isAlive(): Boolean = running && !destroyed

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !isAlive
    }
}
