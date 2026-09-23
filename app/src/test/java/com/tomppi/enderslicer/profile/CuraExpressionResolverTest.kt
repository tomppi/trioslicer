package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraExpressionResolverTest {
    @Test
    fun resolvesSafeArithmeticAndOmitsPythonStyleExpressions() {
        val result = CuraExpressionResolver.resolve(
            globalValues = linkedMapOf(
                "layer_height" to "0.2",
                "layer_height_0" to "0.28",
                "top_bottom_thickness" to "=layer_height_0+layer_height*3",
            ),
            extruderValues = linkedMapOf(
                "line_width" to "0.4",
                "wall_thickness" to "=line_width*2",
                "top_layers" to "=math.ceil(top_thickness / layer_height)",
            ),
        )

        assertEquals("0.88", result.globalValues["top_bottom_thickness"])
        assertEquals("0.8", result.extruderValues["wall_thickness"])
        assertFalse(result.extruderValues.containsKey("top_layers"))
        assertTrue(result.unresolvedExpressions.contains("extruder.top_layers"))
    }
}
