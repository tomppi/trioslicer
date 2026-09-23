package com.tomppi.enderslicer.smartinfill

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The JNI shim on the device.
 *
 * The host smoke binary talks to the engine's `Session` directly, so it never
 * exercises the shim: the handle registry, the JSON the Kotlin layer writes, the
 * array marshalling and the store contract only exist here. This test opens a real
 * session through [FilaSimEngine], drives a whole graded optimization and hands the
 * result to the real [SmartInfillPackageStore].
 *
 * It needs a device but no screen: it runs while the phone is locked.
 */
@RunWith(AndroidJUnit4::class)
class FilaSimShimTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun intLe(value: Int) = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    )

    private fun floatLe(value: Float) = intLe(java.lang.Float.floatToIntBits(value))

    /** A closed box as a binary STL, wound the way the app writes one. */
    private fun writeCube(file: File, size: Float) {
        val v = listOf(
            Triple(0f, 0f, 0f), Triple(size, 0f, 0f), Triple(size, size, 0f), Triple(0f, size, 0f),
            Triple(0f, 0f, size), Triple(size, 0f, size), Triple(size, size, size), Triple(0f, size, size),
        )
        val quads = listOf(
            intArrayOf(0, 3, 2, 1), intArrayOf(4, 5, 6, 7), intArrayOf(0, 1, 5, 4),
            intArrayOf(1, 2, 6, 5), intArrayOf(2, 3, 7, 6), intArrayOf(3, 0, 4, 7),
        )
        val triangles = mutableListOf<IntArray>()
        for (quad in quads) {
            triangles += intArrayOf(quad[0], quad[1], quad[2])
            triangles += intArrayOf(quad[0], quad[2], quad[3])
        }
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            out.write(ByteArray(80))
            out.write(intLe(triangles.size))
            for (triangle in triangles) {
                repeat(3) { out.write(floatLe(0f)) }
                for (index in triangle) {
                    val (x, y, z) = v[index]
                    out.write(floatLe(x))
                    out.write(floatLe(y))
                    out.write(floatLe(z))
                }
                out.write(ByteArray(2))
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun model(name: String, size: Float): File {
        val file = File(File(context.cacheDir, "filasim-shim-test"), name)
        writeCube(file, size)
        return file
    }

    @Test
    fun theWholeWorkflowRunsThroughTheShim() = runBlocking {
        assertTrue("the native engine must load", FilaSimNative.ensureLoaded())
        assertTrue("the library reports a version", FilaSimNative.nativeVersion().isNotBlank())

        val file = model("cube.stl", 20f)
        val engine = FilaSimEngine.open(file, "cube.stl")
        try {
            assertEquals("cube.stl", engine.sourceName)
            assertEquals("the fingerprint is the analysed file", sha256(file), engine.sourceSha256)
            assertEquals("a box has six crease patches", 6, engine.patchCount)

            val info = engine.sessionInfo()
            assertEquals(1, info.bodies)
            assertEquals(12, info.originalTriangles)
            assertTrue("the grid has cells", info.cells > 0)

            val defaults = engine.configuration()
            assertEquals(2400.0, defaults.youngsModulusMpa ?: 0.0, 1e-6)
            assertEquals(300_000, defaults.targetCells)

            // A resolution change goes through configure_json and rebuilds the grid.
            engine.setConfiguration(defaults.copy(targetCells = 30_000))
            val coarse = engine.sessionInfo()
            assertTrue("the grid followed the setting", coarse.cells < info.cells)

            val patches = engine.patchOfTriangle()
            assertEquals(12, patches.size)
            // Nine floats per triangle in a 9-float soup, the convention the
            // selection centroid reads.
            assertEquals(12 * 9, engine.originalPositions().size)
            assertTrue("a tap finds triangles", engine.regionAround(0, 4.0).isNotEmpty())
            assertTrue("a face is a patch", engine.trianglesOfPatch(0).isNotEmpty())

            // Fixed bottom, force down on the top face: one constrained body.
            assertEquals(1, engine.addBoundaryCondition(FilaSimBoundaryCondition.Fixed(intArrayOf(0, 1))))
            assertEquals(
                2,
                engine.addBoundaryCondition(
                    FilaSimBoundaryCondition.Force(intArrayOf(2, 3), listOf(0.0, 0.0, -50.0)),
                ),
            )
            val check = engine.checkSetup()
            assertTrue("the setup is solvable: $check", check.ok)
            assertEquals(1, check.islandCount)
            assertTrue("the force arrives as a load", check.components.first().hasLoads)

            val solve = engine.solve()
            assertTrue("the static solve converged", solve.converged)
            assertTrue("and moved", solve.maxDisplacementMm > 0.0)

            val options = FilaSimOptimizeOptions(budgetPercent = 25.0)
            val optimization = engine.optimize(options) { }
            assertTrue("the optimizer converged", optimization.converged)
            assertTrue("material was placed", optimization.massGrams > 0.0)
            assertTrue("the base infill is a real density", optimization.baseDensityPercent in 1.0..100.0)
            assertTrue("regions came back", optimization.regions.isNotEmpty())
            val densities = optimization.regions.map { it.densityPercent }.sorted()
            assertTrue(
                "every modifier is denser than the base: $densities over ${optimization.baseDensityPercent}",
                densities.first() > optimization.baseDensityPercent,
            )

            // The result tint: one bin per surface triangle, and the march found
            // material under them.
            val bins = engine.surfaceBins()
            assertEquals("a bin per original triangle", 12, bins.size)
            assertTrue("the tint resolved some of them", bins.any { it >= 0 })

            val archive = engine.modifierArchive()
            val names = mutableListOf<String>()
            ZipInputStream(archive.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    names += entry.name
                    assertTrue("an entry carries an STL", zip.readBytes().size > 84)
                }
            }
            assertEquals("one entry per region", optimization.regions.size, names.size)
            for (name in names) {
                assertTrue(
                    "the store only accepts modifier_NNpct.stl, not $name",
                    Regex("modifier_\\d{1,3}pct\\.stl").matches(name),
                )
            }

            // The real store validates the real archive against the real metadata.
            val directory = File(context.cacheDir, "filasim-shim-test")
            val zip = File(directory, "modifiers.zip").apply { writeBytes(archive) }
            val metadata = smartInfillMetadataJson(
                optimization = optimization,
                options = options,
                sourceName = engine.sourceName,
                sourceSha256 = engine.sourceSha256,
            )
            assertEquals(2, JSONObject(metadata).getInt("metadataVersion"))
            val store = SmartInfillPackageStore(context)
            val imported = store.importPackage(Uri.fromFile(zip), metadata, engine.sourceSha256)
            try {
                assertEquals(
                    "the store keeps the densities the engine chose",
                    optimization.regions.map { it.densityPercent.toInt() }.sorted(),
                    imported.modifiers.map { it.densityPercent }.sorted(),
                )
                assertEquals(optimization.baseDensityPercent, imported.baseDensityPercent, 1e-6)
                assertEquals("graded", imported.mode)
                assertNotNull("the package staged its modifier files", imported.modifiers.first().file)
                imported.requireMatchesSource(file)
            } finally {
                store.clearActive()
                imported.directory.deleteRecursively()
            }
        } finally {
            engine.close()
        }
    }

    /**
     * The shim hands out raw addresses. Closing twice must not free the same
     * memory twice, and a call after the close must be refused by the registry
     * rather than dereference it.
     */
    @Test
    fun aDestroyedSessionIsRefusedInsteadOfDereferenced() = runBlocking {
        val engine = FilaSimEngine.open(model("closed.stl", 10f), "closed.stl")
        engine.close()
        engine.close()
        try {
            engine.solve()
            fail("a closed session must not run a solve")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the shim names the problem: ${expected.message}",
                expected.message?.contains("not open") != false,
            )
        }
    }
}
