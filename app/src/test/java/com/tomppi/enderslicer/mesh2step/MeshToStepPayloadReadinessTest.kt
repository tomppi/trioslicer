package com.tomppi.enderslicer.mesh2step

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The extraction decision the conversion makes before it starts: both payloads must be
 * extracted for THIS install, as KlipperPayload/CadEngine define it, not merely present on
 * disk. After an app update the previous install's trees still exist but their stamps no
 * longer match the package lastUpdateTime - the first-run screen says "not prepared" for
 * exactly that reason, and the converter must agree instead of silently using the old trees.
 *
 * The fake trees mirror what the owners check: KlipperPayload's four sentinels plus its
 * 800-file completeness floor, and CadEngine's site-packages/libexec/engine-file/stamp.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MeshToStepPayloadReadinessTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val klipperRoot: File get() = File(context.filesDir, "klipper")
    private val klipperStamp: File get() = File(context.filesDir, "klipper.extracted")
    private val cadRoot: File get() = File(context.filesDir, "cad")
    private val cadStamp: File get() = File(cadRoot, ".extracted")
    private val marker: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()

    @Before
    fun clean() {
        klipperRoot.deleteRecursively()
        cadRoot.deleteRecursively()
        klipperStamp.delete()
    }

    @After
    fun cleanAfter() {
        klipperRoot.deleteRecursively()
        cadRoot.deleteRecursively()
        klipperStamp.delete()
    }

    private fun seedKlipper() {
        for (name in listOf(
            "klippy/klippy.py",
            "klippy/chelper/c_helper.so",
            "klippy/extras/resonance_playback.py",
            "lib/python3.11/encodings/__init__.py",
        )) {
            File(klipperRoot, name).apply {
                parentFile?.mkdirs()
                writeText("x")
            }
        }
        // KlipperPayload treats a tree below its 800-file floor as a half-finished copy.
        repeat(800) { index ->
            File(klipperRoot, "lib/python3.11/module$index.py").apply {
                parentFile?.mkdirs()
                writeText("x")
            }
        }
        klipperStamp.writeText(marker)
    }

    private fun seedCad() {
        File(cadRoot, "lib/python3.11/site-packages").mkdirs()
        File(cadRoot, "libexec").mkdirs()
        File(cadRoot, "cad_mcp_slim.py").writeText("x")
        cadStamp.writeText(marker)
    }

    @Test
    fun anInstallWithNoPayloadsIsNotPrepared() {
        assertFalse(MeshToStepEngine.pythonPayloadPrepared(context))
    }

    @Test
    fun bothTreesExtractedForThisInstallArePrepared() {
        seedKlipper()
        seedCad()
        assertTrue(MeshToStepEngine.pythonPayloadPrepared(context))
    }

    @Test
    fun aKlipperTreeStampedByAnOlderInstallIsNotPrepared() {
        seedKlipper()
        seedCad()
        klipperStamp.writeText("the previous install")
        assertFalse(MeshToStepEngine.pythonPayloadPrepared(context))
    }

    @Test
    fun aCadTreeStampedByAnOlderInstallIsNotPrepared() {
        seedKlipper()
        seedCad()
        cadStamp.writeText("the previous install")
        assertFalse(MeshToStepEngine.pythonPayloadPrepared(context))
    }

    @Test
    fun anIncompleteCadTreeIsNotPrepared() {
        seedKlipper()
        seedCad()
        File(cadRoot, "cad_mcp_slim.py").delete()
        assertFalse(MeshToStepEngine.pythonPayloadPrepared(context))
    }

    @Test
    fun anIncompleteKlipperTreeIsNotPrepared() {
        seedKlipper()
        seedCad()
        File(klipperRoot, "klippy/klippy.py").delete()
        assertFalse(MeshToStepEngine.pythonPayloadPrepared(context))
    }
}
