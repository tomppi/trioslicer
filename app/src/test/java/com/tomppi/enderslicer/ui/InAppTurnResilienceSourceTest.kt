package com.tomppi.enderslicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The turn's resilience, as a source contract: a foreground keeper with a bounded wake lock and
 * no screen-on flag, the retry notice and the error row wired into the conversation.
 *
 * The keeper's behaviour is covered by [com.tomppi.enderslicer.cadagent.InAppAgentTurnServiceTest]
 * and the retry by [com.tomppi.enderslicer.cadagent.CadAgentRetryTest]; this pins that the turn
 * actually uses them, and that the screen-on route the user rejected is absent from the app.
 */
class InAppTurnResilienceSourceTest {

    private val main = File("src/main/java")

    private fun source(relative: String): String {
        val file = File(main, relative)
        assertTrue("missing " + file.path, file.isFile)
        return file.readText()
    }

    @Test
    fun noPartOfTheAppTouchesTheWindowScreenOnFlag() {
        // The agent path only: the vendored SDLActivity carries the flag for its game window and
        // is not ours. What this pins is that no code the in-app agent can reach keeps the
        // screen on.
        val agentPath = listOf(
            "com/tomppi/enderslicer/cadagent",
            "com/tomppi/enderslicer/ui/EnderSlicerApp.kt",
            "com/tomppi/enderslicer/ui/AiChatOverlay.kt",
            "com/tomppi/enderslicer/ui/SolidCraftSheet.kt",
        )
        val offenders = agentPath
            .flatMap { relative ->
                val file = File(main, relative)
                if (file.isDirectory) file.walkTopDown().filter { it.isFile }.toList() else listOf(file)
            }
            .filter { it.extension == "kt" || it.extension == "java" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val lower = line.lowercase()
                    if (lower.contains("keep_screen_on") || lower.contains("keepscreenon")) {
                        file.name + ":" + (index + 1) + ": " + line.trim()
                    } else {
                        null
                    }
                }
            }
            .toList()
        assertTrue("the screen is allowed to sleep: " + offenders, offenders.isEmpty())
    }

    @Test
    fun aTurnBeginsAndEndsTheKeeperOnEveryExitPath() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        // One turn function: begin before the try, end inside its finally.
        val turn = app.substringAfter("fun askInAppAgent(text: String, stored: StoredAgentSettings)")
            .substringBefore("fun askInAppAgentFromChat")
        val begin = turn.indexOf("InAppTurnKeeper.begin(context)")
        val tryIndex = turn.indexOf("try {")
        val finallyBody = turn.substringAfter("finally {")
        assertTrue("the keeper begins with the turn", begin >= 0)
        assertTrue("it begins before any request leaves the phone", begin < tryIndex)
        assertTrue("the keeper ends in the turn's finally", finallyBody.contains("InAppTurnKeeper.end(context)"))
    }

    @Test
    fun theRetryNoticeAndTheErrorRowArePartOfTheConversation() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        assertTrue(
            "a retry is said in the transcript",
            Regex("onRetry = \\{ notice ->[\\s\\S]{0,600}appendInApp\\(").containsMatchIn(app),
        )
        assertTrue(
            "a failed turn is an ERROR row, not only a status line",
            app.contains("kind = AiChatEntryKind.ERROR"),
        )
    }

    @Test
    fun theKeeperIsAForegroundServiceWithABoundedPartialWakeLock() {
        val service = source("com/tomppi/enderslicer/cadagent/InAppAgentTurnService.kt")
        assertTrue(service.contains("startForeground("))
        assertTrue(service.contains("PARTIAL_WAKE_LOCK"))
        assertTrue("the lease is bounded", service.contains("acquire(WAKE_LOCK_TIMEOUT_MS)"))
        assertTrue("the notification names the work", service.contains("CAD agent working"))
        assertTrue("destroy releases the lock", service.contains("releaseWakeLock()"))
        assertTrue(
            "the service is declared for the manifest",
            File("src/main/AndroidManifest.xml").readText()
                .contains(".cadagent.InAppAgentTurnService"),
        )
    }
}
