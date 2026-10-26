package com.tomppi.enderslicer.solidcraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The workspace's design folder as the files screen sees it: names, part counts, which one the
 * plate is showing, and a delete that takes the design and its caches but nothing else.
 */
class CadDesignLibraryTest {

    private fun workspace(): Pair<CadWorkspace, File> {
        val root = Files.createTempDirectory("solidcraft-files").toFile()
        return CadWorkspace(root).ensureDirectories() to root
    }

    private fun design(workspace: CadWorkspace, name: String, bytes: Int = 32): File {
        val file = workspace.designFile(name)
        file.writeBytes(ByteArray(bytes))
        return file
    }

    @Test
    fun anEmptyWorkspaceListsNoDesignsAndSaysWhatToDo() {
        val (workspace, root) = workspace()
        try {
            assertTrue(CadDesignLibrary.list(workspace).isEmpty())
            assertTrue(CadDesignLibrary.EMPTY_SENTENCE.contains("No designs yet"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aDesignListsItsNamePartCountAndModifiedTime() {
        val (workspace, root) = workspace()
        try {
            val file = design(workspace, "Bracket", bytes = 64)
            CadDesignLibrary.writeMeta(workspace, file, "Bracket", partCount = 3, savedAtMillis = 500L)
            val entries = CadDesignLibrary.list(workspace)
            assertEquals(1, entries.size)
            assertEquals("Bracket", entries[0].name)
            assertEquals(3, entries[0].partCount)
            assertEquals(file.lastModified(), entries[0].modifiedAtMillis)
            assertEquals(64L, entries[0].sizeBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aDesignWithoutASidecarIsNamedByItsFileAndItsPartCountIsNotInvented() {
        val (workspace, root) = workspace()
        try {
            val file = design(workspace, "Lid")
            assertNull(CadDesignLibrary.readMeta(workspace, file))
            val entry = CadDesignLibrary.list(workspace).single()
            assertEquals("lid", entry.name)
            assertNull(entry.partCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun designsAreListedNewestFirst() {
        val (workspace, root) = workspace()
        try {
            val older = design(workspace, "Older")
            val newer = design(workspace, "Newer")
            CadDesignLibrary.writeMeta(workspace, older, "Older", partCount = 1, savedAtMillis = 1L)
            CadDesignLibrary.writeMeta(workspace, newer, "Newer", partCount = 2, savedAtMillis = 2L)
            older.setLastModified(1_000_000L)
            newer.setLastModified(2_000_000L)
            assertEquals(
                listOf("Newer", "Older"),
                CadDesignLibrary.list(workspace).map { it.name },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteRemovesTheDesignItsSidecarAndItsDisplayCachesButNotConvertedMeshes() {
        val (workspace, root) = workspace()
        try {
            val file = design(workspace, "Bracket")
            CadDesignLibrary.writeMeta(workspace, file, "Bracket", partCount = 2, savedAtMillis = 1L)
            val bodyCache = workspace.bodyFile("Bracket", "Case").apply { writeBytes(ByteArray(8)) }
            val wholeCache = workspace.displayFile("Bracket").apply { writeBytes(ByteArray(8)) }
            val converted = workspace.convertedFile("Bracket", "Case").apply { writeBytes(ByteArray(8)) }
            assertTrue(CadDesignLibrary.delete(workspace, file))
            assertFalse(file.exists())
            assertFalse(CadDesignLibrary.metaFile(workspace, file).exists())
            assertFalse(bodyCache.exists())
            assertFalse(wholeCache.exists())
            // A converted STL is the plate's own model now: deleting the design leaves it.
            assertTrue(converted.exists())
            assertTrue(CadDesignLibrary.list(workspace).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theDesignOnThePlateIsTheOneItsPathNames() {
        val (workspace, root) = workspace()
        try {
            val file = design(workspace, "Bracket")
            val other = design(workspace, "Lid")
            assertTrue(CadDesignLibrary.isOnPlate(file.absolutePath, file))
            assertFalse(CadDesignLibrary.isOnPlate(file.absolutePath, other))
            assertFalse(CadDesignLibrary.isOnPlate(null, file))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theRefusalSentenceIsTheOneTheUserReads() {
        assertEquals(
            "this design is on the plate; close or convert it first",
            CadDesignLibrary.ON_PLATE_REFUSAL,
        )
    }

    @Test
    fun theSidecarRoundTripsItsThreeFactsAndRejectsJunk() {
        val meta = CadDesignMeta("Bracket", 4, 987_654L)
        assertEquals(meta, CadDesignMeta.decode(meta.encode()))
        assertNull(CadDesignMeta.decode(null))
        assertNull(CadDesignMeta.decode(""))
        assertNull(CadDesignMeta.decode("nothing useful"))
        assertEquals(
            CadDesignMeta("Name", 0, 0L),
            CadDesignMeta.decode("name=Name"),
        )
    }
}
