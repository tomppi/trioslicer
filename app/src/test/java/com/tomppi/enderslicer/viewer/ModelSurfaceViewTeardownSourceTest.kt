package com.tomppi.enderslicer.viewer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The renderer's buffer teardown, pinned source level (a GLSurfaceView test would need a GL
 * context): releaseGpuBuffers deletes every buffer the renderer owns. The viewer keeps its EGL
 * context across a pause, so one buffer left out stays pinned while the app sits in the
 * background - which is exactly what happened to the texture-region tint VBO.
 */
class ModelSurfaceViewTeardownSourceTest {

    private fun viewerSource(): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("the app module was not found from " + File("").absolutePath)
        return File(root, "app/src/main/java/com/tomppi/enderslicer/viewer/ModelSurfaceView.kt")
            .readText()
    }

    @Test
    fun releaseGpuBuffersReleasesEveryBufferIncludingTheTextureRegionTint() {
        val source = viewerSource()
        val body = source.substringAfter("fun releaseGpuBuffers() {").substringBefore("\n    }")
        assertTrue("the mesh buffer is released", body.contains("releaseMeshBuffer()"))
        assertTrue("the face tint buffer is released", body.contains("releaseFaceTintBuffer()"))
        assertTrue(
            "the texture-region tint VBO goes the same way as the face tint one",
            body.contains("releaseTextureRegionTintBuffer()"),
        )
    }
}
