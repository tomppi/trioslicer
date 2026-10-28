package com.tomppi.enderslicer.mesh2step

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The process half of the Mesh-Mix seam, exercised with a fake engine script: the JSON-lines
 * protocol, stderr capture, exit codes, the wall-clock timeout and cancellation. These are the
 * paths the app can only otherwise reach on a phone with a 15-minute timeout.
 */
class MeshToStepRunnerTest {

    private val directory: File = java.nio.file.Files.createTempDirectory("mesh2step-test").toFile()

    private fun runner(script: String, timeoutMillis: Long = 10_000L): MeshToStepRunner =
        MeshToStepRunner(
            command = listOf("/bin/sh", "-c", script),
            workingDirectory = directory,
            environment = emptyMap(),
            timeoutMillis = timeoutMillis,
        )

    private fun shellCheck(): Boolean = runCatching {
        ProcessBuilder("/bin/sh", "-c", "true").start().waitFor() == 0
    }.getOrDefault(false)

    @Test
    fun aSuccessfulRunParsesEveryEventAndReturnsTheResult() {
        if (!shellCheck()) return
        val events = mutableListOf<MeshToStepProtocol.Event>()
        val script = """
            printf '%s\n' '{"event":"stage","stage":"Reading the file","progress":0.0}'
            printf '%s\n' '{"event":"progress","stage":"Reading the file","progress":0.4}'
            printf '%s\n' '{"event":"log","message":"Read 1,928 triangles"}'
            printf '%s\n' '{"event":"warning","message":"Repaired the mesh"}'
            printf '%s\n' '{"event":"result","valid":true,"faces":31,"preset":"cad"}'
            exit 0
        """.trimIndent()
        val outcome = runner(script).use { it.run { event -> events += event } }
        assertEquals(0, outcome.exitCode)
        assertFalse(outcome.timedOut)
        assertFalse(outcome.cancelled)
        assertEquals("cad", outcome.result?.getString("preset"))
        assertNull(outcome.error)
        assertEquals("Reading the file", outcome.lastPhase)
        assertEquals(5, events.size)
        assertTrue(events[1] is MeshToStepProtocol.Event.Progress)
        assertTrue(events[2] is MeshToStepProtocol.Event.Log)
        assertTrue(events[3] is MeshToStepProtocol.Event.Warning)
        assertTrue(events[4] is MeshToStepProtocol.Event.Result)
    }

    @Test
    fun stderrAndNonProtocolLinesAreKeptAsBoundedLogText() {
        if (!shellCheck()) return
        val script = """
            printf '%s\n' 'a plain line that is not JSON'
            printf '%s\n' 'a warning on stderr' >&2
            printf '%s\n' '{"event":"result","valid":true}'
            exit 0
        """.trimIndent()
        val outcome = runner(script).use { it.run { } }
        assertTrue(outcome.log.any { it.contains("plain line") })
        assertTrue(outcome.log.any { it.contains("warning on stderr") })
    }

    @Test
    fun anEngineErrorEventKeepsItsSentence() {
        if (!shellCheck()) return
        val script = """
            printf '%s\n' '{"event":"error","message":"The file is damaged.","reason":"The file itself is the problem."}'
            exit 1
        """.trimIndent()
        val outcome = runner(script).use { it.run { } }
        assertEquals(1, outcome.exitCode)
        assertEquals("The file is damaged.", outcome.error?.message)
        assertEquals("The file itself is the problem.", outcome.error?.reason)
    }

    @Test
    fun aProcessThatNeverReportsAResultIsNotMistakenForSuccess() {
        if (!shellCheck()) return
        val outcome = runner("exit 3").use { it.run { } }
        assertEquals(3, outcome.exitCode)
        assertNull(outcome.result)
        assertNull(outcome.error)
    }

    @Test
    fun theWallClockTimeoutKillsTheProcessAndReportsTheLastPhase() {
        if (!shellCheck()) return
        val script = """
            printf '%s\n' '{"event":"stage","stage":"Fitting surfaces","progress":0.5}'
            sleep 30
        """.trimIndent()
        val started = System.currentTimeMillis()
        val outcome = runner(script, timeoutMillis = 700L).use { it.run { } }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("the timed-out run returned after " + elapsed + " ms", elapsed < 10_000)
        assertTrue(outcome.timedOut)
        assertFalse(outcome.cancelled)
        assertNull(outcome.result)
        assertEquals("Fitting surfaces", outcome.lastPhase)
    }

    @Test
    fun cancelIsSafeFromAnotherThreadAndStopsTheProcess() {
        if (!shellCheck()) return
        val active = runner("""printf '%s\n' '{"event":"stage","stage":"Waiting","progress":0.1}'; sleep 30""")
        val outcome = AtomicReference<MeshToStepRunner.Outcome?>()
        val worker = thread(name = "mesh2step-cancel-test") {
            outcome.set(active.run { })
        }
        Thread.sleep(400)
        active.cancel()
        worker.join(10_000)
        val result = outcome.get()
        assertTrue("runner did not return after cancel", result != null)
        assertTrue(result!!.cancelled)
        assertFalse(result.timedOut)
        active.close()
    }

    @Test
    fun aCommandThatCannotStartIsANamedProcessFailure() {
        if (!shellCheck()) return
        val missing = MeshToStepRunner(
            command = listOf("/definitely/not/a/command"),
            workingDirectory = directory,
            environment = emptyMap(),
            timeoutMillis = 5_000L,
        )
        val failure = runCatching { missing.run { } }.exceptionOrNull()
        assertTrue(failure is MeshToStepEngine.ConversionException)
        assertEquals(
            MeshToStepEngine.ConversionException.Kind.PROCESS_DIED,
            (failure as MeshToStepEngine.ConversionException).kind,
        )
        assertTrue(failure.message!!.contains("could not start"))
        missing.close()
    }
}