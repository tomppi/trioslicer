package com.tomppi.enderslicer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Android document providers make a duplicate save unique by appending " (N)" on either side
 * of the extension ("part.step (1)" is what the dev phone's provider does). The CAD Open gate
 * must canonicalise a real CAD extension out of that name instead of refusing the STEP the app
 * itself just produced, while still refusing a file that is genuinely not a CAD document.
 */
class CadDocumentOpenNameTest {

    @Test
    fun aPlainCadNameIsKept() {
        assertEquals("part.step", cadDocumentOpenName("part.step"))
        assertEquals("part.stp", cadDocumentOpenName("part.stp"))
        assertEquals("part.iges", cadDocumentOpenName("part.iges"))
        assertEquals("part.igs", cadDocumentOpenName("part.igs"))
        assertEquals("part.3mf", cadDocumentOpenName("part.3mf"))
        assertEquals("part.obj", cadDocumentOpenName("part.obj"))
        assertEquals("part.gltf", cadDocumentOpenName("part.gltf"))
        assertEquals("part.glb", cadDocumentOpenName("part.glb"))
        assertEquals("part.solidcraft", cadDocumentOpenName("part.solidcraft"))
    }

    @Test
    fun theExtensionIsCaseInsensitive() {
        assertEquals("Part.STEP", cadDocumentOpenName("Part.STEP"))
        assertEquals("Part.IGES", cadDocumentOpenName("Part.IGES"))
    }

    @Test
    fun theProvidersDuplicateSuffixAfterTheExtensionIsStripped() {
        // The dev phone's provider: "Arc-Overhang-TestPrint-cad.step (1)".
        assertEquals(
            "Arc-Overhang-TestPrint-cad.step",
            cadDocumentOpenName("Arc-Overhang-TestPrint-cad.step (1)"),
        )
        assertEquals("part.step", cadDocumentOpenName("part.step (12)"))
        assertEquals("part.step", cadDocumentOpenName("part.step  (1)"))
    }

    @Test
    fun aDuplicateSuffixBeforeTheExtensionIsStrippedToo() {
        assertEquals("part.step", cadDocumentOpenName("part (1).step"))
        assertEquals("part.STEP", cadDocumentOpenName("part (3).STEP"))
    }

    @Test
    fun twoDuplicateSuffixesStillCanonicalise() {
        assertEquals("part.step", cadDocumentOpenName("part (1).step (2)"))
    }

    @Test
    fun notACadDocumentIsStillRefused() {
        assertNull(cadDocumentOpenName("part.stl"))
        assertNull(cadDocumentOpenName("part.gcode"))
        assertNull(cadDocumentOpenName("notes.txt"))
        assertNull(cadDocumentOpenName("part.step (1).stl"))
        assertNull(cadDocumentOpenName(""))
        assertNull(cadDocumentOpenName("   "))
        // A duplicate suffix alone does not turn something into a CAD file.
        assertNull(cadDocumentOpenName("part (1)"))
    }
}
