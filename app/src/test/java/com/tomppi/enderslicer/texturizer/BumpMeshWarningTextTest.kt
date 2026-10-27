package com.tomppi.enderslicer.texturizer

import org.junit.Assert.assertEquals
import org.junit.Test

/** The page's console-styled repair log must reach the UI as plain sentences. */
class BumpMeshWarningTextTest {

    @Test
    fun theConsoleColourDirectivesAreStrippedFromTheRepairLog() {
        val raw = "%c[stlTexturizer] mesh repair (build 2026-06-10w): removed 186 zero-area slivers; " +
            "final open=61, non-manifold=0, slivers=0 (415,619 tris) color:#0a0;font-weight:bold"
        assertEquals(
            "mesh repair (build 2026-06-10w): removed 186 zero-area slivers; " +
                "final open=61, non-manifold=0, slivers=0 (415,619 tris)",
            cleanBumpMeshWarning(raw),
        )
    }

    @Test
    fun aPlainWarningIsLeftAlone() {
        val plain = "BumpMesh is limited to 8,000,000 triangles by the app setting"
        assertEquals(plain, cleanBumpMeshWarning(plain))
    }
}
