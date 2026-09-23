package com.tomppi.enderslicer.storage

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The crash this guards against: the Apply button wrote the exported modifier
 * archive to a cache directory the provider does not serve, and
 * `FileProvider.getUriForFile` threw `IllegalArgumentException: Failed to find
 * configured root` on the UI thread. Every directory the app hands out has to be
 * listed in `res/xml/file_paths.xml`, and this turns a real file in each of them
 * into a URI through the real provider and the packaged configuration.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ExportPathsTest {

    @Test
    fun everyServedCacheDirectoryCanBeTurnedIntoAContentUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${context.packageName}.files"
        val directories = listOf(
            OneShotExportFileProvider.SMART_INFILL_DIRECTORY,
            OneShotExportFileProvider.BUMPMESH_DIRECTORY,
        )

        for (name in directories) {
            val directory = File(context.cacheDir, name).apply { mkdirs() }
            val file = File(directory, "probe.zip").apply { writeText("probe") }

            val uri = FileProvider.getUriForFile(context, authority, file)

            assertTrue(
                "$name must be served under its own root, got $uri",
                uri.toString().startsWith("content://$authority/"),
            )
            assertTrue("the URI names the file: $uri", uri.toString().endsWith("probe.zip"))
        }
    }

    /**
     * The other half of the guard: the test above proves the provider serves the
     * configured directories, this proves the Apply path writes into one. A literal
     * directory here is exactly what crashed the app.
     */
    @Test
    fun theApplyPathWritesWhereTheProviderLooks() {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt"),
            File("app/src/main/java/com/tomppi/enderslicer/ui/IntegratedEnderSlicerApp.kt"),
        )
        val source = candidates.firstOrNull { it.isFile }
        assertTrue("the Apply source was not found from ${File(".").absolutePath}", source != null)
        val text = source!!.readText()

        assertTrue(
            "the Apply path must build its export directory from the provider's constant",
            text.contains("OneShotExportFileProvider.SMART_INFILL_DIRECTORY"),
        )
        assertFalse(
            "an unserved cache directory has no content URI: FileProvider throws",
            text.contains("\"smart-infill-native\""),
        )
    }
}