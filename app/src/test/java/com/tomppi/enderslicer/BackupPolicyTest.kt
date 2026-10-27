package com.tomppi.enderslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The backup policy for the three engine tokens and the SolidCraft workspace.
 *
 * A token is code execution as this app (docs/MCP_TOKEN_SECURITY.md), so no token may ride into
 * an Auto Backup or a device-to-device transfer. The SolidCraft tree is special: its designs are
 * authored work and stay restorable, while the token and the derived Convert to STL exports must
 * not go with them - the exports can exceed the 25 MB quota on their own, and an over-quota
 * backup fails whole.
 *
 * Pure file assertions: no device, no Android runtime, no Gradle-managed test assets.
 */
class BackupPolicyTest {

    private fun appFile(relative: String): File {
        val direct = File(relative)
        if (direct.isFile) return direct
        val fromModuleParent = File("..", relative)
        assertTrue(
            "missing " + relative + " (cwd " + File(".").absolutePath + ")",
            fromModuleParent.isFile,
        )
        return fromModuleParent
    }

    private fun repoFile(relative: String): File {
        val candidates = listOf(File(relative), File("..", relative), File("../..", relative))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "missing " + relative + " (cwd " + File(".").absolutePath + ")",
            )
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    @Test
    fun theSolidCraftTokenIsExcludedFromBothBackupListsAndTheDesignsAreNot() {
        val full = appFile("src/main/res/xml/backup_rules.xml").readText()
        val extraction = appFile("src/main/res/xml/data_extraction_rules.xml").readText()

        val fullContent = full.substringAfter("<full-backup-content>").substringBefore("</full-backup-content>")
        assertTrue(
            "the 9878 bearer token is not excluded from the full backup list",
            fullContent.contains("path=\"solidcraft/solidcraft_mcp_token.txt\""),
        )
        assertTrue(
            "converted STLs are derived and can exceed the quota; they must not be backed up",
            fullContent.contains("path=\"solidcraft/workspace/converted/\""),
        )
        assertFalse(
            "the whole SolidCraft tree must not be excluded: the designs are authored work",
            fullContent.contains("path=\"solidcraft/\""),
        )
        assertFalse(
            "the designs must stay restorable",
            fullContent.contains("path=\"solidcraft/workspace/designs"),
        )

        val cloud = extraction.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = extraction.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        for ((name, body) in listOf("cloud backup" to cloud, "device transfer" to transfer)) {
            assertEquals(
                "the SolidCraft token must be excluded exactly once from the " + name,
                1,
                countOccurrences(body, "path=\"solidcraft/solidcraft_mcp_token.txt\""),
            )
            assertEquals(
                "the converted STLs must be excluded exactly once from the " + name,
                1,
                countOccurrences(body, "path=\"solidcraft/workspace/converted/\""),
            )
            assertFalse(
                "the " + name + " must not drop the designs",
                body.contains("path=\"solidcraft/workspace/designs"),
            )
            assertFalse(
                "the " + name + " must not exclude the whole SolidCraft tree",
                body.contains("path=\"solidcraft/\""),
            )
        }
    }

    @Test
    fun theTokenDocNamesAllThreeEnginesAndTheExactBackupSplit() {
        val doc = repoFile("docs/MCP_TOKEN_SECURITY.md").readText()
        assertTrue("the Blender token is named", doc.contains("blender_mcp_token.txt"))
        assertTrue("the CAD token is named", doc.contains("cad_mcp_token.txt"))
        assertTrue("the SolidCraft token is named", doc.contains("solidcraft_mcp_token.txt"))
        assertTrue(
            "the derived converted STLs are named",
            doc.contains("solidcraft/workspace/converted/"),
        )
        assertTrue("the designs are stated to be restorable", doc.contains("designs"))
        assertTrue(
            "the document says all three tokens are covered",
            doc.contains("three") && doc.contains("tokens"),
        )
    }
}
