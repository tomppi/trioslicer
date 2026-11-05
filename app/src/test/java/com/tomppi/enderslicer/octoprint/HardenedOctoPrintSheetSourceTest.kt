package com.tomppi.enderslicer.octoprint

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cleartext warning's trigger, as a source contract.
 *
 * A bare hostname is sent over HTTPS - OctoPrintClient.normalizeBaseUrl
 * upgrades it precisely to keep the key out of cleartext - so the error-styled
 * "HTTP is unencrypted" warning must fire only for a URL that explicitly says
 * http://. Warning on a bare hostname read as a defect and pushed users to
 * typing http:// just to silence it.
 */
class HardenedOctoPrintSheetSourceTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for $relative")
        return file.readText()
    }

    @Test
    fun theUnencryptedWarningFiresOnlyForAnExplicitHttpUrl() {
        val sheet = source("ui/HardenedOctoPrintSheet.kt")

        assertTrue(
            "the warning stays for an address that explicitly says http://",
            sheet.contains("if (baseUrl.trim().startsWith(\"http://\", true)) {"),
        )
        assertTrue(sheet.contains("HTTP is unencrypted."))
        assertFalse(
            "a bare hostname is upgraded to HTTPS and must not be warned about",
            sheet.contains("\"://\" !in baseUrl"),
        )
    }

    @Test
    fun theFolderDeleteCopyDescribesTheRecursiveDelete() {
        val sheet = source("ui/HardenedOctoPrintSheet.kt")

        assertTrue(
            "the dialog must say the folder contents go with it",
            sheet.contains("This permanently removes the folder and everything stored inside it"),
        )
        assertFalse(
            "OctoPrint deletes folders recursively, so only-when-empty copy is false",
            sheet.contains("only when it is empty"),
        )
    }
}
