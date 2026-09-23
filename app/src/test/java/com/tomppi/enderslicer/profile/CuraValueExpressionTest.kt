package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E
import kotlin.math.PI

class CuraValueExpressionTest {
    private fun eval(source: String): Any? =
        CuraValueExpressionParser.parse(source).eval(CuraEvaluationContext(emptyMap(), emptyMap(), emptyMap()))

    @Test
    fun resolvesMathFunctionsUsedByCuraDefinitions() {
        assertEquals(4.0, eval("math.sqrt(16)") as Double, 1e-9)
        assertEquals(PI / 4.0, eval("math.atan2(1, 1)") as Double, 1e-9)
        assertEquals(0.5, eval("math.sin(math.radians(30))") as Double, 1e-9)
        assertEquals(0.0, eval("math.cos(math.radians(90))") as Double, 1e-9)
        assertEquals(8.0, eval("math.pow(2, 3)") as Double, 1e-9)
        assertEquals(5.0, eval("math.hypot(3, 4)") as Double, 1e-9)
        assertEquals(1.0, eval("math.log(math.exp(1))") as Double, 1e-9)
        assertEquals(2.0, eval("math.log2(4)") as Double, 1e-9)
        assertEquals(2.0, eval("math.log10(100)") as Double, 1e-9)
    }

    @Test
    fun resolvesPowerFloorDivisionAndPythonModulo() {
        assertEquals(8.0, eval("2 ** 3") as Double, 1e-9)
        assertEquals(512.0, eval("2 ** 3 ** 2") as Double, 1e-9)
        assertEquals(-4.0, eval("-2 ** 2") as Double, 1e-9)
        assertEquals(3.0, eval("7 // 2") as Double, 1e-9)
        assertEquals(1.0, eval("7 % 3") as Double, 1e-9)
        assertEquals(2.0, eval("-7 % 3") as Double, 1e-9)
    }

    @Test
    fun resolvesMathConstants() {
        assertEquals(PI, eval("math.pi") as Double, 1e-9)
        assertEquals(E, eval("math.e") as Double, 1e-9)
    }

    @Test
    fun refusesAnExpressionNestedPastTheParserLimit() {
        // The source is a Cura profile's own formula, so its nesting is
        // peer-authored: recursing without a limit overflowed the stack, and the
        // importer swallowed that as a warning and kept a half-resolved profile.
        val error = runCatching {
            eval("(".repeat(10_000) + "1" + ")".repeat(10_000))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("nests deeper than"))
    }

    @Test
    fun refusesAnUnaryChainNestedPastTheParserLimit() {
        val error = runCatching { eval("-".repeat(10_000) + "1") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("nests deeper than"))
    }

    @Test
    fun resolvesTheNestingRealCuraFormulasUse() {
        assertEquals(1.0, eval("(".repeat(20) + "1" + ")".repeat(20)) as Double, 1e-9)
        assertEquals(2.0, eval("1 + " + "(".repeat(20) + "1" + ")".repeat(20)) as Double, 1e-9)
        assertEquals(-1.0, eval("-".repeat(21) + "1") as Double, 1e-9)
    }
}
