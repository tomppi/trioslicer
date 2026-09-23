package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveOverhangNativeContractTest {
    @Test
    fun nativeGeneratorEmitsAnAnchoredSeedBeforeExpanding() {
        val source = repositoryFile("native/curaengine/patches/src/WaveOverhang.cpp").readText()

        assertTrue(source.contains("seed_expansion = std::max<coord_t>(parameters.perimeter_overlap"))
        assertTrue(source.contains("levels.emplace_back(std::move(seed_front))"))
        assertTrue(source.contains("for (size_t iteration = 1; iteration < parameters.max_iterations"))
    }

    @Test
    fun directionVariationNeverReversesWavefrontSupportOrder() {
        val source = repositoryFile("native/curaengine/patches/src/WaveOverhang.cpp").readText()

        assertFalse(source.contains("std::reverse(levels.begin(), levels.end())"))
        assertTrue(source.contains("line.reverse()"))
        assertTrue(source.contains("parameters.reverse_direction"))
    }

    private fun repositoryFile(path: String): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        repeat(6) {
            File(current, path).takeIf(File::isFile)?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate repository file: $path from ${System.getProperty("user.dir")}")
    }
}
