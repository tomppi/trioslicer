package com.tomppi.enderslicer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.solidcraft.CadDesignEntry
import com.tomppi.enderslicer.solidcraft.CadDesignLibrary
import com.tomppi.enderslicer.solidcraft.CadWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The SolidCraft files screen: the designs the workspace holds, Load to plate (asking first
 * when the plate is not empty) and Delete (confirming by name, refusing the plate's document).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SolidCraftFilesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun workspace(): Pair<CadWorkspace, File> {
        val root = Files.createTempDirectory("solidcraft-screen").toFile()
        return CadWorkspace(root).ensureDirectories() to root
    }

    private fun design(workspace: CadWorkspace, name: String = "Bracket"): File {
        val file = workspace.designFile(name)
        file.writeBytes(ByteArray(32))
        CadDesignLibrary.writeMeta(workspace, file, name, partCount = 2, savedAtMillis = 1L)
        return file
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun anEmptyWorkspaceSaysWhatToDo() {
        val (workspace, root) = workspace()
        try {
            compose.setContent {
                SolidCraftFilesScreen(
                    workspace = workspace,
                    plateDesignPath = null,
                    plateHasContent = false,
                    onBack = {},
                    onLoad = {},
                )
            }
            awaitText(CadDesignLibrary.EMPTY_SENTENCE)
            compose.onNodeWithText(CadDesignLibrary.EMPTY_SENTENCE).assertIsDisplayed()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aDesignRowNamesItAndOffersBothActions() {
        val (workspace, root) = workspace()
        try {
            design(workspace)
            compose.setContent {
                SolidCraftFilesScreen(
                    workspace = workspace,
                    plateDesignPath = null,
                    plateHasContent = false,
                    onBack = {},
                    onLoad = {},
                )
            }
            awaitText("Bracket")
            compose.onNodeWithText("Bracket").assertIsDisplayed()
            compose.onNodeWithText("2 parts", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Load to plate").assertIsDisplayed()
            compose.onNodeWithText("Delete").assertIsDisplayed()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun loadOnAnEmptyPlateLoadsAtOnceAndAsksFirstOnAFullOne() {
        val (workspace, root) = workspace()
        try {
            val file = design(workspace)
            var loaded: CadDesignEntry? = null
            compose.setContent {
                SolidCraftFilesScreen(
                    workspace = workspace,
                    plateDesignPath = null,
                    plateHasContent = true,
                    onBack = {},
                    onLoad = { loaded = it },
                )
            }
            awaitText("Load to plate")
            compose.onNodeWithText("Load to plate").performClick()
            // The plate is not empty: the load asks first, and names the design it would open.
            awaitText("Load bracket.solidcraft to the plate?")
            assertNull(loaded)
            compose.onNodeWithText("Load").performClick()
            assertEquals(file.absolutePath, loaded?.designFile?.absolutePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteAsksByNameThenRefusesThePlatesDocument() {
        val (workspace, root) = workspace()
        try {
            val file = design(workspace)
            compose.setContent {
                SolidCraftFilesScreen(
                    workspace = workspace,
                    plateDesignPath = file.absolutePath,
                    plateHasContent = true,
                    onBack = {},
                    onLoad = {},
                )
            }
            awaitText("Delete")
            compose.onNodeWithText("Delete").performClick()
            awaitText("Delete bracket.solidcraft?")
            compose.onNodeWithText("Delete design").performClick()
            awaitText(CadDesignLibrary.ON_PLATE_REFUSAL)
            assertTrue(file.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deletingAnUnusedDesignRemovesItAndTheEmptyStateComesBack() {
        val (workspace, root) = workspace()
        try {
            val file = design(workspace)
            compose.setContent {
                SolidCraftFilesScreen(
                    workspace = workspace,
                    plateDesignPath = null,
                    plateHasContent = false,
                    onBack = {},
                    onLoad = {},
                )
            }
            awaitText("Delete")
            compose.onNodeWithText("Delete").performClick()
            awaitText("Delete bracket.solidcraft?")
            compose.onNodeWithText("Delete design").performClick()
            awaitText(CadDesignLibrary.EMPTY_SENTENCE)
            assertFalse(file.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
