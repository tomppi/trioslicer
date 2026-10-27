package com.tomppi.enderslicer.texturizer

import org.junit.Assert.assertEquals
import org.junit.Test

/** The textures the UI lane lists: asset paths under assets/ and the names the page's presets use. */
class BumpMeshTextureCatalogueTest {

    @Test
    fun theShippedTextureNamesFollowTheBumpMeshPresetNames() {
        assertEquals("Brick", textureDisplayName("brick.png"))
        assertEquals("Carbon Fiber", textureDisplayName("carbonFiber.jpg"))
        assertEquals("Grip Surface", textureDisplayName("gripSurface.jpg"))
        assertEquals("Stripes 1", textureDisplayName("stripes.png"))
        assertEquals("Stripes 2", textureDisplayName("stripes_02.png"))
        assertEquals("Wood 1", textureDisplayName("wood.jpg"))
        assertEquals("Wood 2", textureDisplayName("woodgrain_02.jpg"))
        assertEquals("Wood 3", textureDisplayName("woodgrain_03.jpg"))
        assertEquals("Knurling", textureDisplayName("knurling.jpg"))
    }

    @Test
    fun aTextureAddedLaterFallsBackToAPrettifiedFileName() {
        assertEquals("New Texture 03", textureDisplayName("newTexture_03.png"))
        assertEquals("Cellular Mesh", textureDisplayName("cellular-mesh.webp"))
        assertEquals("Hexagons", textureDisplayName("hexagons.jpg"))
    }
}
