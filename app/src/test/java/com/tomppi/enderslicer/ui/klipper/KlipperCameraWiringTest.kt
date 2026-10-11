package com.tomppi.enderslicer.ui.klipper

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the camera list is asked for, and what the card may say about it.
 *
 * Two defects, both about a failed request being reported as a fact about the host: the list was
 * fetched BEFORE the connection was made, in the seconds a cold start spends with no network,
 * and every failure came back as an empty list - the same value as a host that publishes no
 * cameras. The card then said "No camera is configured on this host." for the rest of the
 * session. These pin the order, the bounds, the retry and the words.
 */
class KlipperCameraWiringTest {

    private val repository = source("printer/KlipperPrinterRepository.kt")
    private val viewModel = source("printer/KlipperViewModel.kt")
    private val dialect = source("printer/MoonrakerDialect.kt")
    private val dashboard = source("ui/klipper/KlipperDashboardTab.kt")

    @Test
    fun theCameraListIsAskedForAfterTheConnectionAndRetriedABoundedNumberOfTimes() {
        val session = body(repository, "private suspend fun session(", "private fun readHostLogTail")
        val connectAt = session.indexOf("c.connect()")
        val readAt = session.indexOf("scope.launch { readWebcams(choice, mine) }")
        assertTrue("the connection is made first", connectAt >= 0)
        assertTrue("and the cameras are asked for after it", readAt > connectAt)
        assertFalse(
            "never before it, which is where the cold start used to lose them",
            session.substring(0, connectAt).contains("MoonrakerWebcams"),
        )

        val read = body(repository, "private suspend fun readWebcams(", "fun refreshWebcams()")
        assertTrue(
            "a few tries, with a pause between them, and no loop",
            read.contains("for (attempt in 0 until WEBCAM_READ_TRIES)") &&
                read.contains("delay(WEBCAM_READ_PAUSE_MS)") &&
                read.contains("if (read != null) break"),
        )
        assertTrue(
            "a failure is the host not having said, not the host having none",
            read.contains("current.webcams.isNullOrEmpty() -> current.copy(webcams = null)"),
        )
        assertTrue(
            "cameras already known survive a failed read",
            read.contains("else -> current"),
        )
        assertTrue(
            "and an answer from a host the app has left is dropped",
            read.contains("updateIfCurrent(token)"),
        )
    }

    @Test
    fun aCameraListThatChangesIsReadAgain() {
        assertTrue(
            "Moonraker's own push is passed through the dialect",
            dialect.contains("\"notify_webcams_changed\""),
        )
        assertTrue(
            "and the repository answers it by reading the list again",
            repository.contains("message.optString(\"method\") == WEBCAMS_CHANGED_METHOD") &&
                repository.contains("refreshWebcams()"),
        )
        assertTrue(
            "the card can ask again too",
            viewModel.contains("fun refreshWebcams() = repository.refreshWebcams()"),
        )
    }

    @Test
    fun theCameraCardSaysWhichOfTheThreeItIs() {
        assertTrue(
            "the card is given the list, not a first camera",
            dashboard.contains("KlipperCameraCard(state.webcams, viewModel)"),
        )
        assertTrue(
            "unread has its own words",
            dashboard.contains("\"The camera list could not be read.\""),
        )
        assertTrue(
            "answered-with-none keeps the sentence that is true of the host",
            dashboard.contains("\"No camera is configured on this host.\""),
        )
        assertTrue(
            "a frame that has not arrived is still its own state",
            dashboard.contains("\"Waiting for a frame from the camera.\""),
        )
        assertTrue(
            "unread offers the retry",
            dashboard.contains("KlipperButton(\"Check again\") { viewModel.refreshWebcams() }"),
        )
        assertTrue(
            "and asks again by itself while the screen is open",
            dashboard.contains("LaunchedEffect(cameras)") &&
                dashboard.contains("if (cameras == null) viewModel.refreshWebcams()"),
        )
        assertTrue(
            "with the retry bounded to the unknown case: an answer or none is not retried",
            dashboard.contains("cameras == null -> Box(") &&
                dashboard.contains("camera == null -> Box("),
        )
    }

    private fun body(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("the source no longer holds " + from, start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("the source no longer holds " + to + " after " + from, end > start)
        return text.substring(start, end)
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate source file for $relative from " + File(".").absolutePath)
    }
}
