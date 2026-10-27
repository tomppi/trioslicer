package com.tomppi.enderslicer.texturizer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 1.9.17 failure discipline and the locale fix it protects: a stuck import names the step and the expected count, a
 * page/studio triangle-count disagreement fails the painted mask closed with both counts, and the shipped app carries no
 * diagnostic logging or oversized host from the investigation.
 */
class BumpMeshStudioProtectionTest {

    private fun appFile(relative: String): File = File(relative).also {
        assertTrue("missing " + it.absolutePath, it.isFile)
    }

    private fun hostSource(): String = appFile("src/main/java/com/tomppi/enderslicer/texturizer/BumpMeshStudio.kt").readText()

    private fun driverSource(): String = appFile("src/main/res/raw/bumpmesh_studio_driver.js").readText()

    @Test
    fun aStalledImportNamesTheStepAndTheExpectedCountWithoutADiagnosticDump() {
        val sentence = BumpMeshFailureReport.importTimeout(
            step = "the project import",
            expectedTriangleCount = 225143,
        )
        assertTrue(sentence.contains("the project import"))
        assertTrue(sentence.contains("225,143 triangles"))
        assertTrue(sentence.contains("press Apply again"))
        assertFalse("no console tail in the user sentence", sentence.contains("console"))
    }

    @Test
    fun aCountDisagreementNamesBothCountsAndFailsClosedForAMask() {
        val mismatch = BumpMeshFailureReport.countMismatch(225143, 225142)
        assertNotNull(mismatch)
        assertTrue(mismatch!!.contains("225,142"))
        assertTrue(mismatch.contains("225,143"))
        assertTrue(mismatch.contains("Whole object"))
        assertNull("equal counts are fine", BumpMeshFailureReport.countMismatch(225143, 225143))
    }

    @Test
    fun theReadyAndPhaseTimeoutsAreNamedSentences() {
        assertTrue(BumpMeshFailureReport.readyTimeout().contains("never reported itself ready"))
        assertTrue(BumpMeshFailureReport.phaseTimeout("the export").contains("the export"))
    }

    @Test
    fun theDriverHasTheLocaleFixTheAcknowledgedDispatchAndNoDiagnosticLogging() {
        val driver = driverSource()
        // The locale fix and the 1.9.17 protections.
        assertTrue(driver.contains("replace(/[^0-9]/g"))
        assertTrue(driver.contains("u202f"))
        assertTrue(driver.contains("acknowledge"))
        assertTrue(driver.contains("did not happen within"))
        assertTrue(driver.contains("the painted mask was built for"))
        assertTrue(driver.contains("welcome-overlay"))
        assertTrue(driver.contains("'driver-ready'"))
        // The diagnostic instrumentation must not ship: no step/heartbeat events, no console logging.
        listOf("heartbeat", "console.info", "ev('step'", "driver steps:", "__studioDriverStep", "__studioConsole")
            .forEach { token -> assertFalse("diagnostic logging in the driver: " + token, driver.contains(token)) }
    }

    @Test
    fun theHostKeepsTheSmallHiddenHostAndTheSilentRecycleButNoProbeOrVisibleHost() {
        val host = hostSource()
        assertTrue(host.contains("ViewGroup.LayoutParams(2, 2)"))
        assertTrue(host.contains("alpha = 0f"))
        assertTrue(host.contains("openSourceWithRetry"))
        assertTrue(host.contains("recycleForRetry"))
        assertTrue(host.contains("EVENT_DRIVER_READY"))
        assertTrue(host.contains("fun markReady()"))
        assertTrue(host.contains("fun failReady("))
        assertTrue(host.contains("BumpMeshFailureReport"))
        assertTrue(host.contains("Log.w(TAG, \"BumpMesh apply failed: " ))
        // Nothing from the investigation ships.
        listOf("stall-probe", "STALL_PROBE", "consoleTail", "lastPageTriangles", "heartbeat",
            "resumedActivity", "IMPORTANT_FOR_ACCESSIBILITY_NO", "MATCH_PARENT")
            .forEach { token -> assertFalse("diagnostic/experimental host code: " + token, host.contains(token)) }
    }

    @Test
    fun cancelDuringAnImportResetsThePageSoTheNextApplyReloadsTheModel() {
        val host = hostSource()
        assertTrue(host.contains("active.events.trySend(StudioEvent(EVENT_CANCELLED"))
        assertTrue(host.contains("page.abortAndReload()"))
        assertTrue(host.contains("private fun resetAfterAbort()"))
        assertTrue(host.contains("loadedSource = null"))
        assertTrue(host.contains("loadedMaskKey = null"))
        assertTrue(host.contains("recycleForRetry()"))
    }
}
