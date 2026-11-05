package com.tomppi.enderslicer.octoprint

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The optimistic file-list rewrites, the active-file guard and the
 * authorization failure handling.
 *
 * A move must never leave two rows with the same path: the Files list keys by
 * path and Compose crashes on a duplicate key. A delete or move takes
 * everything under its target, so the file being printed is guarded by its
 * whole path subtree. And an authorization failure may erase the stored key
 * only on the documented rejection - a 401 from a proxy in front of OctoPrint
 * is not one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OctoPrintRepositoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        preferences().edit().clear().commit()
    }

    // The OctoPrintSecretStore's own preference keys, seeded raw so the
    // credential paths can be exercised without the device Keystore.
    private fun preferences() = context.getSharedPreferences("octoprint_client", Context.MODE_PRIVATE)

    private fun newRepository(): OctoPrintRepository =
        OctoPrintRepository(context, CoroutineScope(SupervisorJob()))

    private fun entry(path: String, isFolder: Boolean = false) = OctoPrintFileEntry(
        name = path.substringAfterLast('/'),
        path = path,
        origin = "local",
        isFolder = isFolder,
    )

    @Test
    fun moveRewritesThePathWhenTheDestinationIsFree() {
        val files = listOf(entry("a/cube.gcode"), entry("b/other.gcode"))

        val moved = newRepository().filesWithMoved(files, "a/cube.gcode", "b")

        assertEquals(listOf("b/cube.gcode", "b/other.gcode"), moved.map { it.path })
    }

    @Test
    fun moveRefusesAnOptimisticRewriteOntoATakenPath() {
        // OctoPrint's shutil.move overwrites on the server, but the optimistic
        // list must not hold two rows with one path before the refresh lands.
        val files = listOf(entry("a/cube.gcode"), entry("b/cube.gcode"))

        val moved = newRepository().filesWithMoved(files, "a/cube.gcode", "b")

        assertEquals(listOf("a/cube.gcode", "b/cube.gcode"), moved.map { it.path })
    }

    @Test
    fun movingAFolderRefusesWhenAChildPathIsTaken() {
        val files = listOf(entry("a", isFolder = true), entry("a/x.gcode"), entry("b/a/x.gcode"))

        val moved = newRepository().filesWithMoved(files, "a", "b")

        assertEquals(listOf("a", "a/x.gcode", "b/a/x.gcode"), moved.map { it.path })
    }

    @Test
    fun movingAFolderRewritesEveryChildWhenNothingCollides() {
        val files = listOf(entry("a", isFolder = true), entry("a/x.gcode"), entry("b/keep.gcode"))

        val moved = newRepository().filesWithMoved(files, "a", "b")

        assertEquals(listOf("b/a", "b/a/x.gcode", "b/keep.gcode"), moved.map { it.path })
    }

    @Test
    fun movingToTheRootRefusesWhenTheNameIsTakenAtTheRoot() {
        val files = listOf(entry("a/cube.gcode"), entry("cube.gcode"))

        val moved = newRepository().filesWithMoved(files, "a/cube.gcode", "")

        assertEquals(listOf("a/cube.gcode", "cube.gcode"), moved.map { it.path })
    }

    @Test
    fun deletingTheFolderAboveThePrintingFileIsRefused() {
        val guard = newRepository().activeFileGuard("deleted", "prints")

        val reason = guard(OctoPrintUiState(job = OctoPrintJobState(filePath = "prints/cube.gcode")))

        assertEquals("The file currently being printed (prints/cube.gcode) cannot be deleted", reason)
    }

    @Test
    fun movingTheFolderAboveThePrintingFileIsRefused() {
        val guard = newRepository().activeFileGuard("moved", "prints")

        val reason = guard(OctoPrintUiState(job = OctoPrintJobState(filePath = "prints/sub/cube.gcode")))

        assertEquals("The file currently being printed (prints/sub/cube.gcode) cannot be moved", reason)
    }

    @Test
    fun deletingThePrintingFileItselfIsStillRefused() {
        val guard = newRepository().activeFileGuard("deleted", "prints/cube.gcode")

        val reason = guard(OctoPrintUiState(job = OctoPrintJobState(filePath = "prints/cube.gcode")))

        assertEquals("The file currently being printed (prints/cube.gcode) cannot be deleted", reason)
    }

    @Test
    fun aFolderSharingOnlyANamePrefixIsNotRefused() {
        // "prints2" is a sibling of "prints", not the folder above the file.
        val guard = newRepository().activeFileGuard("deleted", "prints2")

        assertNull(guard(OctoPrintUiState(job = OctoPrintJobState(filePath = "prints/cube.gcode"))))
    }

    @Test
    fun aFolderBelowThePrintingFileIsNotRefused() {
        val guard = newRepository().activeFileGuard("deleted", "prints/sub")

        assertNull(guard(OctoPrintUiState(job = OctoPrintJobState(filePath = "prints/cube.gcode"))))
    }

    @Test
    fun theActiveFileGuardNormalizesBeforeComparing() {
        val guard = newRepository().activeFileGuard("deleted", "/PRINTS/")

        val reason = guard(OctoPrintUiState(job = OctoPrintJobState(filePath = "prints/cube.gcode")))

        assertEquals("The file currently being printed (prints/cube.gcode) cannot be deleted", reason)
    }

    @Test
    fun nothingIsRefusedWithoutAFileBeingPrinted() {
        val guard = newRepository().activeFileGuard("deleted", "prints")

        assertNull(guard(OctoPrintUiState(job = OctoPrintJobState(state = "Printing"))))
    }

    private fun seedStoredKey() {
        // A ciphertext nothing can decrypt here: these tests observe whether a
        // failure path keeps or erases the stored credential, not its contents.
        preferences().edit()
            .putString("base_url", "http://octopi.local")
            .putString("encrypted_api_key", "v1:!!!")
            .putString("api_key_origin", "http://octopi.local")
            .commit()
    }

    private fun storedKeyPresent(): Boolean =
        preferences().getString("encrypted_api_key", null) != null

    @Test
    fun a401NeverErasesTheStoredKey() {
        // A reverse proxy with its own auth in front of OctoPrint, or a
        // DHCP-reassigned address, answers 401 for a key OctoPrint never
        // rejected; erasing it would cost the user a re-approval in OctoPrint's
        // web UI for nothing. Every call site must keep the key.
        for (forbiddenMeansInvalidKey in listOf(true, false)) {
            seedStoredKey()
            val repository = newRepository()

            repository.handleSessionError(
                OctoPrintClient.OctoPrintHttpException(401, "Unauthorized"),
                requestGeneration = 0L, // the generation a fresh repository starts at
                forbiddenMeansInvalidKey = forbiddenMeansInvalidKey,
            )

            assertTrue("the 401 erased the stored key", storedKeyPresent())
            assertFalse("the re-authorization state was not surfaced", repository.state.value.hasApiKey)
            assertEquals("Unauthorized. Authorize OctoPrint again.", repository.state.value.errorMessage)
        }
    }

    @Test
    fun theDocumentedRejectionStillErasesTheStoredKey() {
        seedStoredKey()
        val repository = newRepository()

        repository.handleSessionError(
            OctoPrintClient.OctoPrintHttpException(403, "Forbidden", apiKeyRejected = true),
            requestGeneration = 0L,
            forbiddenMeansInvalidKey = true,
        )

        assertFalse("the documented rejection left the key behind", storedKeyPresent())
        assertEquals("Forbidden. Authorize OctoPrint again.", repository.state.value.errorMessage)
    }

    @Test
    fun a403WithoutTheDocumentedRejectionKeepsTheStoredKey() {
        seedStoredKey()
        val repository = newRepository()

        repository.handleSessionError(
            OctoPrintClient.OctoPrintHttpException(403, "Forbidden"),
            requestGeneration = 0L,
            forbiddenMeansInvalidKey = true,
        )

        assertTrue("a worded 403 erased the stored key", storedKeyPresent())
        assertEquals("Forbidden", repository.state.value.errorMessage)
    }
}
