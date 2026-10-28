package com.tomppi.enderslicer.mesh2step

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared install path behind MeshToStepEngine.isPrepared()/prepare() and the lazy
 * conversion. These tests use a temporary directory and a fake copier so the idempotence,
 * stamp staleness and failure sentences are pinned without needing an Android context.
 */
class MeshToStepPreparationTest {

    private val root: File = Files.createTempDirectory("mesh2step-preparation").toFile()
    private val directory = File(root, "mesh2step")
    private val stamp = File(root, "mesh2step.extracted")

    private val sentinels = listOf(
        "launcher.py",
        "ocp_compat.py",
        "mmm_engine.py",
        "VERSION",
        "mmm/__init__.py",
        "mmm/pipeline.py",
        "mmm/stepio.py",
        "mmm/cli.py",
    )

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun seed(destination: File) {
        for (name in sentinels) {
            File(destination, name).apply {
                parentFile?.mkdirs()
                writeText(name)
            }
        }
        repeat(20) { index ->
            File(destination, "mmm/extra$index.py").apply {
                parentFile?.mkdirs()
                writeText("x")
            }
        }
    }

    @Test
    fun theFirstCallInstallsAndTheSecondCallIsANoOp() {
        var copies = 0
        var progressCalls = 0
        var lastFiles = 0
        var lastBytes = 0L
        val copy: (File, (Int, Long) -> Unit) -> Unit = { destination, report ->
            copies++
            seed(destination)
            report(28, 1_234L)
        }

        val first = MeshToStepEngine.installEngineAssets(
            directory, stamp, "install-1",
            onProgress = { files, bytes ->
                progressCalls++
                lastFiles = files
                lastBytes = bytes
            },
            copy = copy,
        )

        assertNull("first install should succeed", first)
        assertEquals(1, copies)
        assertEquals(1, progressCalls)
        assertEquals(28, lastFiles)
        assertEquals(1_234L, lastBytes)
        assertTrue(MeshToStepEngine.assetsPrepared(directory, stamp, "install-1"))
        assertEquals("install-1", stamp.readText())

        var secondCopies = 0
        var secondProgress = 0
        val second = MeshToStepEngine.installEngineAssets(
            directory, stamp, "install-1",
            onProgress = { _, _ -> secondProgress++ },
            copy = { _, _ -> secondCopies++ },
        )

        assertNull("an already prepared tree is a no-op", second)
        assertEquals("the tree must not be copied again", 0, secondCopies)
        assertEquals("no progress callbacks for a no-op", 0, secondProgress)
    }

    @Test
    fun aStaleStampIsNotPreparedAndTriggersAReExtract() {
        assertNull(
            MeshToStepEngine.installEngineAssets(
                directory, stamp, "install-1",
                onProgress = { _, _ -> },
                copy = { destination, report ->
                    seed(destination)
                    report(28, 1L)
                },
            ),
        )
        assertTrue(MeshToStepEngine.assetsPrepared(directory, stamp, "install-1"))

        // A new install has a different lastUpdateTime: the old stamp no longer matches.
        assertFalse(MeshToStepEngine.assetsPrepared(directory, stamp, "install-2"))

        var copies = 0
        var progressCalls = 0
        val second = MeshToStepEngine.installEngineAssets(
            directory, stamp, "install-2",
            onProgress = { _, _ -> progressCalls++ },
            copy = { destination, report ->
                copies++
                seed(destination)
                report(28, 1L)
            },
        )

        assertNull(second)
        assertEquals(1, copies)
        assertTrue(progressCalls > 0)
        assertTrue(MeshToStepEngine.assetsPrepared(directory, stamp, "install-2"))
        assertFalse(MeshToStepEngine.assetsPrepared(directory, stamp, "install-1"))
    }

    @Test
    fun aPartialCopyIsAPackagingFailureAndIsNotStamped() {
        val result = MeshToStepEngine.installEngineAssets(
            directory, stamp, "install-1",
            onProgress = { _, _ -> },
            copy = { destination, _ ->
                File(destination, "launcher.py").apply {
                    parentFile?.mkdirs()
                    writeText("not enough files")
                }
            },
        )

        assertTrue("expected a sentence, got: " + result, result!!.contains("not packaged in this APK"))
        assertFalse(stamp.exists())
        assertFalse(MeshToStepEngine.assetsPrepared(directory, stamp, "install-1"))
    }

    @Test
    fun aCopyFailureReturnsTheReasonAndLeavesNoStamp() {
        val result = MeshToStepEngine.installEngineAssets(
            directory, stamp, "install-1",
            onProgress = { _, _ -> },
            copy = { _, _ -> throw IllegalStateException("the disk is full") },
        )

        assertTrue("expected the copier's reason, got: " + result, result!!.contains("the disk is full"))
        assertTrue(result.contains("could not be unpacked"))
        assertFalse(stamp.exists())
    }
}
