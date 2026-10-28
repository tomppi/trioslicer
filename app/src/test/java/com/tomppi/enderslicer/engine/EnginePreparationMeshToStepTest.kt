package com.tomppi.enderslicer.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The first-run screen's Mesh-to-STEP row as a wiring contract.
 *
 * The row must reuse the engine lane's own extractor and stamp through
 * MeshToStepEngine.prepare/isPrepared - a second extractor here would write a second tree and
 * a lazy first conversion would not find it. The row is last in the enum because it borrows
 * both the Klipper CPython and the CAD tree.
 */
class EnginePreparationMeshToStepTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/" + relative),
            File("app/src/main/java/com/tomppi/enderslicer/" + relative),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for " + relative)
        return file.readText()
    }

    @Test
    fun theRowReusesTheSeamAndWritesNoSecondTree() {
        val preparation = source("engine/EnginePreparation.kt")
        assertTrue(
            "preparation delegates to the engine lane's own extractor",
            preparation.contains("Engine.MESH2STEP -> MeshToStepEngine.prepare(context)"),
        )
        assertTrue(
            "readiness is the engine lane's stamp, not a local one",
            preparation.contains("Engine.MESH2STEP -> MeshToStepEngine.isPrepared(context)"),
        )
        assertFalse(
            "the ui/engine prep side owns no mesh2step tree of its own",
            preparation.contains("from = \"mesh2step\"") ||
                preparation.contains("mesh2step.extracted") ||
                preparation.contains("AssetTreeExtractor.copyTree"),
        )
    }

    @Test
    fun theSetupScreenRendersWhateverRowsTheEngineReports() {
        val screen = source("ui/EngineSetupScreen.kt")
        assertTrue(screen.contains("row.engine.label"))
        assertTrue(screen.contains("row.engine.detail"))
        assertTrue(screen.contains("row.engine.needsBytes"))
        assertTrue(
            "the screen does not hardcode an engine list that would miss the new row",
            !screen.contains("listOf(EnginePreparation.Engine."),
        )
    }
}
