package com.tomppi.enderslicer.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The stored library is engine-scoped, and the document written by 1.3.5 and earlier - which has no
 * engine field, because Cura was the only engine with presets - still loads as Cura's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UserPresetStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.filesDir, "persistent-state")
    private val file = File(directory, "user-presets.json")

    @Before
    fun clearStorage() {
        directory.deleteRecursively()
    }

    @Test
    fun sameNameOnAnotherEngineIsASeparatePresetWithItsOwnActiveMarker() {
        val store = UserPresetStore(context)
        store.create(SlicerEngine.PRUSA, PresetKind.PRINT, "Coarse", prusaPrint(layerHeightMm = 0.3))
        val library = store.create(
            SlicerEngine.ORCA,
            PresetKind.PRINT,
            "Coarse",
            orcaPrint(layerHeightMm = 0.3),
        )

        assertEquals(2, library.presets.size)
        val prusa = library.presets.single { it.engine == SlicerEngine.PRUSA }
        val orca = library.presets.single { it.engine == SlicerEngine.ORCA }
        assertEquals("Coarse", prusa.name)
        assertEquals("Coarse", orca.name)
        assertEquals(prusa.id, library.activeId(SlicerEngine.PRUSA, PresetKind.PRINT))
        assertEquals(orca.id, library.activeId(SlicerEngine.ORCA, PresetKind.PRINT))
        assertNull(library.activeId(SlicerEngine.CURA, PresetKind.PRINT))

        val reloaded = UserPresetStore(context).load()
        assertEquals(
            setOf(SlicerEngine.PRUSA, SlicerEngine.ORCA),
            reloaded.presets.mapTo(hashSetOf(), UserPreset::engine),
        )
        assertEquals(orca.id, reloaded.activeId(SlicerEngine.ORCA, PresetKind.PRINT))
        assertEquals(
            0.3,
            reloaded.presets.single { it.engine == SlicerEngine.ORCA }.values().getDouble("layerHeightMm"),
            0.000_001,
        )
    }

    @Test
    fun valuesOutsideTheEnginesOwnVocabularyAreRejected() {
        val store = UserPresetStore(context)
        val curaOnly = JSONObject().put(SlicerSettings.Keys.WALL_LINE_COUNT, 4).toString()
        val prusaOnly = JSONObject().put(PrusaSliceSettings.Keys.PERIMETERS, 4).toString()

        assertTrue(
            runCatching { store.create(SlicerEngine.PRUSA, PresetKind.PRINT, "Cura keys", curaOnly) }.isFailure,
        )
        assertTrue(
            runCatching { store.create(SlicerEngine.CURA, PresetKind.PRINT, "Prusa keys", prusaOnly) }.isFailure,
        )
        assertTrue(store.load().presets.isEmpty())
    }

    @Test
    fun thePreEngineDocumentLoadsAsCuraAndIsRewrittenInTheCurrentFormat() {
        directory.mkdirs()
        file.writeText(
            """
            {"version":1,"activePrintPresetId":"legacy-print","presets":[
              {"id":"legacy-print","kind":"PRINT","name":"Fine","values":{"layerHeightMm":0.12},"createdAt":1,"updatedAt":2}
            ]}
            """.trimIndent(),
        )

        val store = UserPresetStore(context)
        val library = store.load()

        assertEquals(1, library.presets.size)
        assertEquals(SlicerEngine.CURA, library.presets.single().engine)
        assertEquals("legacy-print", library.activeId(SlicerEngine.CURA, PresetKind.PRINT))
        assertNull(library.activeId(SlicerEngine.PRUSA, PresetKind.PRINT))

        store.create(SlicerEngine.PRUSA, PresetKind.PRINT, "Coarse", prusaPrint(layerHeightMm = 0.3))

        val written = JSONObject(file.readText())
        assertEquals(2, written.getInt("version"))
        val records = written.getJSONArray("presets")
        val engines = (0 until records.length()).map { records.getJSONObject(it).optString("engine") }
        assertTrue(engines.contains("CURA"))
        assertTrue(engines.contains("PRUSA"))
        assertEquals("legacy-print", written.getJSONObject("activePrintPresetIds").getString("CURA"))
    }

    @Test
    fun aRecordWhoseEngineIsUnknownIsSkippedRatherThanMisattributed() {
        directory.mkdirs()
        file.writeText(
            """{"version":2,"presets":[{"id":"x","engine":"CURAENGINE","kind":"PRINT",""" +
                """"name":"Fine","values":{"layerHeightMm":0.2}}]}""",
        )

        assertTrue(UserPresetStore(context).load().presets.isEmpty())
    }

    @Test
    fun deletingAPresetClearsItsActiveMarker() {
        val store = UserPresetStore(context)
        store.create(SlicerEngine.PRUSA, PresetKind.PRINT, "Coarse", prusaPrint(layerHeightMm = 0.3))
        val id = store.load().presets.single().id

        val cleared = store.delete(id)

        assertTrue(cleared.presets.isEmpty())
        assertNull(cleared.activeId(SlicerEngine.PRUSA, PresetKind.PRINT))
    }

    private fun prusaPrint(layerHeightMm: Double): String = PrusaPresetSettings
        .capture(PresetKind.PRINT, PrusaSliceSettings(layerHeightMm = layerHeightMm))
        .toString()

    private fun orcaPrint(layerHeightMm: Double): String = OrcaPresetSettings
        .capture(PresetKind.PRINT, OrcaSliceSettings(layerHeightMm = layerHeightMm))
        .toString()
}
