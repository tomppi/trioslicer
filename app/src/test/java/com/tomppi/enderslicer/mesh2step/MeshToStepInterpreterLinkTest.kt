package com.tomppi.enderslicer.mesh2step

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
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
 * The fresh-install linker bug: the interpreter asks for libpython3.11.so.1.0, the app ships
 * libpython3.11.so, and the soname alias used to be made only by CadEngine.startEngine().
 * A first Mesh-to-STEP conversion extracts files/cad itself and never starts the CAD engine,
 * so the alias was missing and libklipper_exec.so died in the linker. The seam now ensures the
 * alias after extraction, through the same CadEngine helper, on every conversion.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MeshToStepInterpreterLinkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var nativeDirectory: File
    private var originalNativeDirectory: String? = null
    private val libexec: File get() = File(context.filesDir, "cad/libexec")
    private val alias: File get() = File(libexec, "libpython3.11.so.1.0")

    @Before
    fun setNativeDirectory() {
        nativeDirectory = Files.createTempDirectory("mesh2step-native").toFile()
        File(nativeDirectory, "libpython3.11.so").writeText("fake interpreter library")
        originalNativeDirectory = context.applicationInfo.nativeLibraryDir
        context.applicationInfo.nativeLibraryDir = nativeDirectory.absolutePath
        assertEquals(nativeDirectory.absolutePath, context.applicationInfo.nativeLibraryDir)
        File(context.filesDir, "cad").deleteRecursively()
    }

    @After
    fun cleanUp() {
        context.applicationInfo.nativeLibraryDir = originalNativeDirectory
        File(context.filesDir, "cad").deleteRecursively()
        nativeDirectory.deleteRecursively()
    }

    @Test
    fun theSeamCreatesTheSonameAliasAfterAPayloadExtraction() {
        assertFalse("nothing should be linked before the extraction", Files.isSymbolicLink(alias.toPath()))

        assertNull(MeshToStepEngine.ensureInterpreterLink(context))

        assertTrue("libpython3.11.so.1.0 must be a symlink", Files.isSymbolicLink(alias.toPath()))
        assertEquals(
            File(nativeDirectory, "libpython3.11.so").absolutePath,
            Files.readSymbolicLink(alias.toPath()).toString(),
        )
    }

    @Test
    fun aRewrittenCadTreeIsRelinkedAndAStaleAliasIsReplaced() {
        assertNull(MeshToStepEngine.ensureInterpreterLink(context))

        // The payload extraction wipes files/cad; the next conversion must put the alias back.
        Files.delete(alias.toPath())
        assertFalse(Files.isSymbolicLink(alias.toPath()))
        assertNull(MeshToStepEngine.ensureInterpreterLink(context))
        assertTrue(Files.isSymbolicLink(alias.toPath()))

        // An app update changes nativeLibraryDir: the old alias points into a dead install.
        Files.delete(alias.toPath())
        Files.createSymbolicLink(alias.toPath(), File("/old/install/libpython3.11.so").toPath())
        assertNull(MeshToStepEngine.ensureInterpreterLink(context))
        assertEquals(
            File(nativeDirectory, "libpython3.11.so").absolutePath,
            Files.readSymbolicLink(alias.toPath()).toString(),
        )
    }

    @Test
    fun aMissingNativeLibraryIsANamedFailureInsteadOfALinkerCrash() {
        File(nativeDirectory, "libpython3.11.so").delete()

        val reason = MeshToStepEngine.ensureInterpreterLink(context)

        assertTrue("expected a sentence, got: " + reason, reason != null)
        assertTrue(reason!!.contains("Python library link"))
        assertFalse(Files.isSymbolicLink(alias.toPath()))
    }
}
