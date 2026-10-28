package com.tomppi.enderslicer.mesh2step

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The public preparation seam the first-run "Prepare the engines" screen calls: it must use
 * the app's packaged assets, the same stamp as the lazy converter, be idempotent, and never
 * extract the huge CAD/Klipper payloads itself.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MeshToStepEnginePreparationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val engineDirectory: File get() = File(context.filesDir, "mesh2step")
    private val stamp: File get() = File(context.filesDir, "mesh2step.extracted")

    @Before
    fun clean() {
        engineDirectory.deleteRecursively()
        stamp.delete()
    }

    @After
    fun cleanAfter() {
        engineDirectory.deleteRecursively()
        stamp.delete()
    }

    @Test
    fun prepareExtractsTheBundledScriptsOnceAndNeverTouchesTheCadPayload() {
        assertFalse("a clean install is not prepared", MeshToStepEngine.isPrepared(context))
        assertFalse(File(context.filesDir, "cad").exists())

        var progressCalls = 0
        var files = 0
        var bytes = 0L
        val error = MeshToStepEngine.prepare(context) { reportedFiles, reportedBytes ->
            progressCalls++
            files = reportedFiles
            bytes = reportedBytes
        }

        assertNull("prepare failed: " + error, error)
        assertTrue("no progress was reported", progressCalls > 0)
        assertTrue("expected every engine file, reported " + files, files >= 26)
        assertTrue("expected bytes copied, reported " + bytes, bytes > 0L)
        assertTrue(MeshToStepEngine.isPrepared(context))
        assertTrue(File(engineDirectory, "launcher.py").isFile)
        assertTrue(File(engineDirectory, "mmm/pipeline.py").isFile)
        assertTrue(File(engineDirectory, "ocp_compat.py").isFile)
        assertFalse("prepare must not extract the CAD payload", File(context.filesDir, "cad").exists())
        assertFalse("prepare must not extract Klipper", File(context.filesDir, "klipper").exists())

        // The screen can call it again after its own row was marked ready: a no-op.
        var secondCalls = 0
        assertNull(MeshToStepEngine.prepare(context) { _, _ -> secondCalls++ })
        assertEquals("second prepare must not re-extract", 0, secondCalls)
    }

    @Test
    fun aStaleStampIsNotPreparedAndPrepareReExtractsIt() {
        assertNull(MeshToStepEngine.prepare(context) { _, _ -> })
        assertTrue(MeshToStepEngine.isPrepared(context))

        stamp.writeText("an older install")
        assertFalse("a stale stamp must not report ready", MeshToStepEngine.isPrepared(context))

        var progressCalls = 0
        assertNull(MeshToStepEngine.prepare(context) { _, _ -> progressCalls++ })
        assertTrue("a stale tree must be re-extracted", progressCalls > 0)
        assertTrue(MeshToStepEngine.isPrepared(context))
    }
}
