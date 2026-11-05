package com.tomppi.enderslicer.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.PrusaPresetSelection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The saved Cura import: the one expendable file of the persisted state.
 *
 * A bundle a crash left half written used to abort the restore of everything else - the plate,
 * the settings, the snapshots - on every launch, forever. Every other store treats corrupt data
 * as absent; this one now does too, and clears the bad bytes so the next launch starts clean.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppStateStoreTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    /** The store's own file layout, which the restore reads directly. */
    private val directory = File(context.filesDir, "persistent-state").apply { mkdirs() }

    @Test
    fun aCorruptImportBundleIsTreatedAsAbsentAndCleared() {
        val bundle = File(directory, "current-cura-import.bundle")
        bundle.writeText("not a bundle at all")

        assertNull("a bundle that cannot be read is no import", AppStateStore(context).savedImport())

        assertFalse("and it is cleared rather than tripped over again", bundle.exists())
        assertNull("the next launch is clean", AppStateStore(context).savedImport())
    }

    /**
     * The staging file is what a crash mid-write leaves behind; promoting it must not install
     * half a bundle that then blocks the restore forever.
     */
    @Test
    fun aHalfWrittenStagingBundleNeverBlocksTheRestore() {
        val next = File(directory, "current-cura-import.bundle.next")
        next.writeText("half a bun")

        assertNull(AppStateStore(context).savedImport())

        assertFalse(next.exists())
        assertFalse(File(directory, "current-cura-import.bundle").exists())
    }

    /** The happy path the soft handling must not cost: a published bundle still restores. */
    @Test
    fun aPublishedBundleStillRestores() {
        val store = AppStateStore(context)
        val staged = File(directory, "staged-config.ini")
        staged.writeText("[print]\nlayer_height = 0.2")
        store.commitImport(staged, AppStateStore.KIND_PROFILE, "prusa-config.ini")

        val saved = AppStateStore(context).savedImport()

        assertEquals(AppStateStore.KIND_PROFILE, saved?.kind)
        assertEquals("prusa-config.ini", saved?.displayName)
        assertEquals("[print]\nlayer_height = 0.2", saved!!.file.readText())
    }

    /** One bad import file costs that import, never the state the rest of the restore reads. */
    @Test
    fun otherStoredStateSurvivesACorruptImportBundle() {
        File(directory, "current-cura-import.bundle").writeText("not a bundle at all")
        val selection = PrusaPresetSelection(
            printerId = "mk4",
            printName = "0.20mm SPEED @MK4 0.4",
            filamentName = "Prusament PLA",
        )
        val store = AppStateStore(context)
        store.savePrusaPreset(selection)

        assertNull(store.savedImport())
        assertEquals(selection, AppStateStore(context).restorePrusaPreset())
    }
}
