package com.tomppi.enderslicer.texturizer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The 1.9.16 hang: the page formats the triangle count with the device's group separator ("225 143 triangles" on a
 * Finnish-locale phone) and the old parser matched the last digit run ("143"), so every count gate waited forever.
 * These cases run the shipped driver's own triCount() under Node so the parser and its test cannot drift.
 */
class BumpMeshTriangleCountLocaleTest {

    private fun driverPath(): File {
        val file = File("src/main/res/raw/bumpmesh_studio_driver.js")
        assertTrue("missing " + file.absolutePath, file.isFile)
        return file
    }

    @Test
    fun theDriverStripsEveryGroupSeparatorBeforeReadingTheCount() {
        val driver = driverPath().readText()
        assertTrue("the parser must strip non-digits", driver.contains("replace(/[^0-9]/g"))
        assertTrue("the parser must accept a NARROW NO-BREAK SPACE", driver.contains("u202f"))
        assertTrue("the parser must accept a NBSP", driver.contains("u00a0"))
    }

    @Test
    fun theShippedParserReadsGroupedCountsInEveryLocale() {
        val node = runCatching { ProcessBuilder("node", "--version").start().waitFor() }.getOrNull()
        assumeTrue("node is not available on this host", node == 0)
        val driver = driverPath().readText()
        val start = driver.indexOf("const triCount = () => {")
        assertTrue("triCount not found in the driver", start >= 0)
        var depth = 0
        var end = -1
        for (index in start until driver.length) {
            when (driver[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = index + 1
                        break
                    }
                }
            }
        }
        assertTrue("triCount block not closed", end > start)
        val parsed = driver.substring(start, end)
        val placeholder = "$" + "{parsed}"
        val script = listOf(
            "let MESH = '';",
            "const document = { getElementById: () => ({ textContent: MESH }) };",
            "const byId = (id) => document.getElementById(id);",
            "const textOf = (element) => (element ? String(element.textContent || '') : '');",
            parsed,
            "const cases = [",
            "  ['model.stl \\u00b7 225,143 triangles \\u00b7 7.73 MB', 225143],",
            "  ['model.stl \\u00b7 225 143 triangles \\u00b7 7.73 MB', 225143],",
            "  ['model.stl \\u00b7 225\\u00A0143 triangles', 225143],",
            "  ['model.stl \\u00b7 225\\u202F143 triangles', 225143],",
            "  ['model.stl \\u00b7 225.143 triangles', 225143],",
            "  ['model.stl \\u00b7 143 triangles', 143],",
            "  ['model.stl \\u00b7 1 234 567 triangles', 1234567],",
            "  ['no count here', 0],",
            "];",
            "for (const item of cases) {",
            "  MESH = item[0];",
            "  const actual = triCount();",
            "  if (actual !== item[1]) {",
            "    console.log('FAIL ' + JSON.stringify(item[0]) + ' expected=' + item[1] + ' actual=' + actual);",
            "    process.exit(1);",
            "  }",
            "}",
            "console.log('OK');",
        ).joinToString("\n")
        val file = File.createTempFile("tricount", ".js").apply { deleteOnExit() }
        file.writeText(script)
        val process = ProcessBuilder("node", file.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("triCount locale regression: " + output, 0, process.waitFor())
        assertTrue(output.contains("OK"))
    }
}
