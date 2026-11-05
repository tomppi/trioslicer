package com.tomppi.enderslicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Ask AI engine menu's readiness is live state.
 *
 * The probe answers the wire engines by opening their own socket. Android throws
 * NetworkOnMainThreadException for a socket opened on the UI thread, and the probe's own
 * runCatching turns that into "not running" - so a poll evaluated on the composition thread
 * reports every wire engine stopped however alive it is. That is exactly the frozen status
 * line this pins away: the poll body hops to IO, and the first frame reads only the checks
 * that need no socket.
 */
class EngineReadinessSourceTest {

    private val app by lazy {
        listOf(
            File("src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt"),
            File("app/src/main/java/com/tomppi/enderslicer/ui/EnderSlicerApp.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Unable to locate ui/EnderSlicerApp.kt")
    }

    @Test
    fun theReadinessPollRunsOffTheUiThread() {
        assertTrue(
            "the poll body hops to IO - a socket opened on the main thread is refused",
            app.contains("value = withContext(Dispatchers.IO) { engineReadinessSnapshot(viewModel) }"),
        )
        assertFalse(
            "the poll body never calls the wire probe directly on the composition thread",
            Regex("""value = engineReadinessSnapshot\(viewModel\)""").containsMatchIn(app),
        )
    }

    @Test
    fun theFirstFrameUsesSocketFreeChecks() {
        assertTrue(
            "the first frame must not ping an engine from composition",
            app.contains("initialValue = cheapEngineReadiness(viewModel)"),
        )
        assertTrue(app.contains("private fun cheapEngineReadiness("))
        assertTrue(
            "the wire engines wait for the IO poll",
            app.contains("engineReadinessSnapshot(viewModel: MainViewModel): Map<AgentEngine, Boolean>"),
        )
    }

    @Test
    fun thePollIsStillTheLiveStateSource() {
        assertTrue(app.contains("ENGINE_READINESS_POLL_MILLIS"))
        assertTrue(app.contains("delay(ENGINE_READINESS_POLL_MILLIS)"))
        assertTrue("the door carries the polled snapshot", app.contains("running = engineRunning"))
    }
}
