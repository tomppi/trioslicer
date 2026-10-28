package com.tomppi.enderslicer.mesh2step

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The packaged Mesh-Mix-S2C assets and their verifier: presence, the provenance pin, the
 * committed hash manifest, the OCP.collections shim resolving, and the fail-closed behaviour
 * when a vendored file is changed.
 */
class MeshToStepAssetsTest {

    private val assets: File = sequenceOf(
        File("src/main/assets/mesh2step"),
        File("../app/src/main/assets/mesh2step"),
    ).firstOrNull { it.isDirectory } ?: error("serialized mesh2step assets not found")

    private val verifier: File = sequenceOf(
        File("../scripts/verify-mesh2step-assets.py"),
        File("scripts/verify-mesh2step-assets.py"),
    ).firstOrNull { it.isFile } ?: error("mesh2step verifier not found")

    private fun python(): Boolean = runCatching {
        ProcessBuilder("python3", "--version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun runVerifier(root: File): Pair<Int, String> {
        val process = ProcessBuilder(
            "python3",
            verifier.absolutePath,
            "--root",
            root.absolutePath,
            "--check-only",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test
    fun theEngineMarkerNamesThePinnedUpstreamCommit() {
        val marker = File(assets, ".source-version").readText()
        assertTrue(marker, marker.contains("format=1"))
        assertTrue(marker, marker.contains("engine=1.6.0"))
        assertTrue(marker, marker.contains("tag=v1.6.0"))
        assertTrue(marker, marker.contains("commit=4cf72ea5900f40d99116bda7877a8e7550ec604f"))
        assertEquals("1.6.0", File(assets, "VERSION").readText().trim())
        assertTrue(File(assets, "LICENSE").readText().contains("GNU AFFERO GENERAL PUBLIC LICENSE"))
    }

    @Test
    fun theLauncherInstallsTheShimBeforeTheEngine() {
        val launcher = File(assets, "launcher.py").readText()
        val shimInstall = launcher.indexOf("ocp_compat")
        val engineImport = launcher.indexOf("from mmm.cli import main")
        assertTrue("the launcher does not import the shim", shimInstall >= 0)
        assertTrue("the launcher does not start the engine", engineImport >= 0)
        assertTrue("the shim must be installed before the engine is imported", shimInstall < engineImport)
        val stepCompat = File(assets, "engine_compat.py").readText()
        assertTrue("the launcher must install the STEP reader compat", launcher.contains("engine_compat"))
        assertTrue("missing STEP reader compat", stepCompat.contains("read_step_named"))
        assertTrue("missing TransferRoots guard", stepCompat.contains("TransferRoots"))
        assertTrue("missing BRepExtrema compat", stepCompat.contains("BRepExtrema_DistShapeShape"))
        assertTrue("missing PerformDist fallback", stepCompat.contains("PerformDist"))
        val shim = File(assets, "ocp_compat.py").readText()
        for (name in listOf(
            "Array1_gp_Pnt",
            "Array2_gp_Pnt",
            "Array1_double",
            "Array1_int",
            "Array1_gp_Pnt2d",
            "HArray1_gp_Pnt",
            "HArray1_gp_Pnt2d",
            "HArray1_double",
            "IndexedMap_TopoDS_Shape_TopTools_ShapeMapHasher",
            "Sequence_TDF_Label",
        )) {
            assertTrue("missing OCP.collections name: " + name, shim.contains(name))
        }
    }

    @Test
    fun theCommittedVerifierPasses() {
        assumeTrue("python3 is needed for the asset verifier", python())
        val (code, output) = runVerifier(assets)
        assertEquals("verifier said:\n" + output, 0, code)
        assertTrue(output, output.contains("Verified 29 Mesh-Mix-S2C engine files"))
    }

    @Test
    fun theVerifierFailsClosedWhenAVendoredFileChanges() {
        assumeTrue("python3 is needed for the asset verifier", python())
        val temp = Files.createTempDirectory("mesh2step-verifier").toFile()
        try {
            assets.copyRecursively(temp, overwrite = true)
            val vendored = File(temp, "mmm_engine.py")
            vendored.appendText("\n# a change to a vendored file must be rejected\n")
            val (code, output) = runVerifier(temp)
            assertTrue("a changed engine file was accepted:\n" + output, code != 0)
            assertTrue(output, output.contains("hash mismatch") || output.contains("asset set changed"))
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun everyEnginePythonFileParses() {
        assumeTrue("python3 is needed to parse the engine sources", python())
        val files = assets.walkTopDown()
            .filter { it.isFile && it.extension == "py" }
            .map { it.absolutePath }
            .toList()
        assertTrue("no engine python files found", files.size >= 20)
        val process = ProcessBuilder(
            listOf(
                "python3",
                "-c",
                "import ast,sys\nfor path in sys.argv[1:]:\n    ast.parse(open(path, encoding='utf-8').read(), filename=path)\nprint('parsed', len(sys.argv)-1)",
            ) + files,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("python parse said:\n" + output, 0, process.waitFor())
        assertTrue(output, output.contains("parsed"))
    }

    @Test
    fun theOcpCollectionsShimResolvesAgainstTheAppsOcpShape() {
        assumeTrue("python3 is needed for the shim test", python())
        val fake = Files.createTempDirectory("mesh2step-ocp").toFile()
        try {
            // The app's payload has OCP 7.9: the TC classes exist under their classic names and
            // there is no OCP.collections package. A minimal fake with the same shape is enough
            // to prove the shim publishes the names the engine imports.
            File(fake, "OCP").mkdirs()
            File(fake, "OCP/__init__.py").writeText("")
            File(fake, "OCP/TColgp.py").writeText(
                """
                class TColgp_Array1OfPnt: pass
                class TColgp_HArray1OfPnt: pass
                class TColgp_Array2OfPnt: pass
                class TColgp_Array1OfPnt2d: pass
                class TColgp_HArray1OfPnt2d: pass
                """.trimIndent(),
            )
            File(fake, "OCP/TColStd.py").writeText(
                """
                class TColStd_Array1OfReal: pass
                class TColStd_Array1OfInteger: pass
                class TColStd_HArray1OfReal: pass
                """.trimIndent(),
            )
            File(fake, "OCP/TopTools.py").writeText("class TopTools_IndexedMapOfShape: pass")
            File(fake, "OCP/TDF.py").writeText("class TDF_LabelSequence: pass")
            val pythonCode = listOf(
                "import sys",
                "import ocp_compat",
                "ocp_compat.install()",
                "import OCP, OCP.collections as collections",
                "from OCP.TColgp import TColgp_Array1OfPnt",
                "assert collections.Array1_gp_Pnt is TColgp_Array1OfPnt",
                "assert hasattr(collections, 'HArray1_double')",
                "assert hasattr(collections, 'IndexedMap_TopoDS_Shape_TopTools_ShapeMapHasher')",
                "assert hasattr(collections, 'Sequence_TDF_Label')",
                "assert collections.__name__ == 'OCP.collections'",
                "print('shim ok')",
            ).joinToString("\n")
            val process = ProcessBuilder(
                "python3", "-c", pythonCode,
            ).apply {
                // The shim is imported from the packaged asset directory; keep the test from
                // writing __pycache__ there (the verifier rejects bytecode, and the assets are
                // part of the APK, not a scratch tree).
                environment()["PYTHONPATH"] = fake.absolutePath + ":" + assets.absolutePath
                environment()["PYTHONDONTWRITEBYTECODE"] = "1"
            }.redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals("shim said:\n" + output, 0, process.waitFor())
            assertTrue(output, output.contains("shim ok"))
        } finally {
            fake.deleteRecursively()
        }
    }
}