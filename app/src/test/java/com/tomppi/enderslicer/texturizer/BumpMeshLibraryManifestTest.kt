package com.tomppi.enderslicer.texturizer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The studio's texture catalogue and validation: the pinned library manifest is canonical, the legacy vendored preset
 * folder remains a fallback, and the webp thumbnails must never reach the displacement path.
 */
class BumpMeshLibraryManifestTest {

    @Test
    fun theManifestBecomesTheCanonicalCatalogue() {
        val manifest = """
            {
              "format": 1,
              "count": 3,
              "categories": [
                {"id": "geometric", "label": "Geometric"},
                {"id": "natural", "label": "Natural"}
              ],
              "textures": [
                {"name": "Wood", "file": "textures/wood.jpg", "thumb": "textures/thumbs/wood.webp", "category": "natural", "default_scale": 0.5},
                {"name": "Armor", "file": "textures/armor.png", "thumb": "textures/thumbs/armor.webp", "category": "geometric", "default_scale": 0.25},
                {"name": "Broken", "file": "", "thumb": "", "category": "geometric", "default_scale": 1}
              ]
            }
        """.trimIndent()

        val entries = BumpMeshLibraryManifest.parse(manifest)

        assertEquals(2, entries.size)
        assertEquals(listOf("Armor", "Wood"), entries.map { it.displayName })
        assertEquals("bumpmesh/library/textures/armor.png", entries[0].assetPath)
        assertEquals("bumpmesh/library/textures/thumbs/armor.webp", entries[0].thumbnailAssetPath)
        assertEquals("Geometric", entries[0].category)
        assertEquals(0.25f, entries[0].defaultScale!!, 0.0001f)
        assertEquals("Natural", entries[1].category)
        // The webp thumbnail is gallery data, never the asset the engine hands the page.
        assertTrue(entries.all { it.assetPath.substringAfterLast('.').lowercase() != "webp" })
    }

    @Test
    fun aMalformedManifestIsAnEmptyCatalogueRatherThanACrash() {
        assertEquals(emptyList<BumpMeshLibraryManifest.Entry>(), BumpMeshLibraryManifest.parse("{\"textures\": ["))
        assertEquals(emptyList<BumpMeshLibraryManifest.Entry>(), BumpMeshLibraryManifest.parse("not json"))
        assertEquals(emptyList<BumpMeshLibraryManifest.Entry>(), BumpMeshLibraryManifest.parse("{\"other\": true}"))
    }

    @Test
    fun theRealStagedLibraryIsThe101TextureCatalogue() {
        val appModule = File(".")
        val manifestFile = File(appModule, "src/main/assets/bumpmesh/library/manifest.json")
        assumeTrue("the asset lane has not staged the 101-texture library in this checkout", manifestFile.isFile)

        val manifestText = manifestFile.readText()
        val entries = BumpMeshLibraryManifest.parse(manifestText)
        assertEquals("the pinned library count", 101, entries.size)
        assertTrue("sorted like the gallery", entries.map { it.displayName.lowercase() } ==
            entries.map { it.displayName.lowercase() }.sorted())

        val assetsRoot = File(appModule, "src/main/assets")
        entries.forEach { entry ->
            assertTrue("canonical path: " + entry.assetPath, BumpMeshLibraryManifest.isLibraryTexturePath(entry.assetPath))
            assertTrue("display name present", entry.displayName.isNotBlank())
            assertNotNull("category label resolved", entry.category)
            assertNotNull("default scale carried", entry.defaultScale)
            assertTrue("the displacement asset exists: " + entry.assetPath, File(assetsRoot, entry.assetPath).isFile)
            val extension = entry.assetPath.substringAfterLast('.').lowercase()
            assertTrue("a real image is handed to the page, not a thumb: " + entry.assetPath, extension == "png" || extension == "jpg" || extension == "jpeg")
            val thumbnail = entry.thumbnailAssetPath
            assertNotNull("the gallery thumbnail is in the manifest: " + entry.assetPath, thumbnail)
            assertTrue("the thumbnail exists: " + thumbnail, File(assetsRoot, thumbnail!!).isFile)
        }
    }

    @Test
    fun theLegacyVendoredFolderStillWorksAsAFallback() {
        val entries = BumpMeshLibraryManifest.legacy(listOf("brick.png", "stripes_02.png", "notes.txt", "thumbs"))
        assertEquals(listOf("Brick", "Stripes 2"), entries.map { it.displayName })
        assertEquals(listOf("bumpmesh/textures/brick.png", "bumpmesh/textures/stripes_02.png"), entries.map { it.assetPath })
    }

    @Test
    fun canonicalAndLegacyPathsAreAcceptedAndEverythingElseIsRefusedByName() {
        val exists = { _: String -> true }
        assertNull(BumpMeshLibraryManifest.refusalFor("bumpmesh/library/textures/armor.png", exists))
        assertNull(BumpMeshLibraryManifest.refusalFor("bumpmesh/library/textures/wood.jpg", exists))
        assertNull(BumpMeshLibraryManifest.refusalFor("bumpmesh/textures/brick.png", exists))

        val outside = BumpMeshLibraryManifest.refusalFor("bumpmesh/library/credits.txt", exists)
        assertNotNull(outside)
        assertTrue(outside!!.contains("not one of the bundled BumpMesh textures"))

        val thumbnail = BumpMeshLibraryManifest.refusalFor("bumpmesh/library/textures/thumbs/armor.webp", exists)
        assertNotNull(thumbnail)
        assertTrue(thumbnail!!.contains("gallery thumbnail"))

        val missing = BumpMeshLibraryManifest.refusalFor("bumpmesh/library/textures/armor.png") { _ -> false }
        assertNotNull(missing)
        assertTrue(missing!!.contains("missing from the app"))
    }
}
