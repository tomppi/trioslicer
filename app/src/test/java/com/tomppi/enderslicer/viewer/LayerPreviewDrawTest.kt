package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.engine.GcodeLayerPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the build-up renderer draws, counted against the window that was planned: every layer of
 * the stack the window holds is in the draw, and the selected layer is drawn in full over it -
 * never a sample, and never a stack shorter than the footer says.
 */
class LayerPreviewDrawTest {

    private fun preview(layers: Int = 120, pathsOf: (Int) -> Int = { 25 }): GcodeLayerPreview {
        val list = (0 until layers).map { index ->
            val count = pathsOf(index)
            val segments = FloatArray(count * GcodeLayerPreview.VALUES_PER_SEGMENT)
            for (path in 0 until count) {
                val offset = path * GcodeLayerPreview.VALUES_PER_SEGMENT
                segments[offset] = (index * 1_000 + path).toFloat()
                segments[offset + 1] = index.toFloat()
                segments[offset + 2] = (index * 1_000 + path).toFloat() + 0.4f
                segments[offset + 3] = index.toFloat()
                segments[offset + 4] = 30f
                segments[offset + 5] = GcodeLayerPreview.Feature.MODEL.code.toFloat()
            }
            GcodeLayerPreview.Layer(
                number = index,
                z = 0.2f * (index + 1),
                height = 0.2f,
                segments = segments,
                supportSegmentCount = 0,
                supportInterfaceSegmentCount = 0,
                sourceSegmentCount = count,
            )
        }
        return GcodeLayerPreview(
            layers = list,
            minX = 0f, minY = 0f, maxX = 100_000f, maxY = 100f,
            minSpeedMmPerSecond = 30f, maxSpeedMmPerSecond = 30f,
            minLayerHeightMm = 0.2f, maxLayerHeightMm = 0.2f,
            totalSegmentCount = list.sumOf { it.sourceSegmentCount },
            storedSegmentCount = list.sumOf { it.segmentCount },
        )
    }

    private fun geometry(
        value: GcodeLayerPreview,
        style: LayerPreviewStyle,
        capacitySegments: Int = Int.MAX_VALUE,
    ) = LayerPreviewGeometry(
        value,
        PreviewCapacity(
            profile = PreviewMemoryProfile.AUTO,
            moves = 1_000,
            windows = 1_000,
            travels = 1_000,
            segments = capacitySegments,
            note = "auto profile · 4.0 GB free",
        ),
        style,
    )

    @Test
    fun theBuildUpDrawCoversEveryLayerOfThePlannedStack() {
        val value = preview(layers = 120, pathsOf = { 25 })
        val geometry = geometry(value, LayerPreviewStyle.BUILD_UP)
        for (selected in intArrayOf(1, 10, 50, 119)) {
            geometry.select(selected)
            val plan = geometry.window
            val stack = LayerPreviewDraw.buildUp(geometry, LayerPreviewStyle.BUILD_UP)
            val current = LayerPreviewDraw.selected(geometry)

            assertEquals("the plan is the stack from the bed", 0..selected, plan)
            assertEquals(
                "the draw covers exactly the layers the plan holds",
                plan.count(),
                stack.layerCount + current.layerCount,
            )
            assertEquals("every layer under the selection is in the stack draw", 0 until selected, stack.layers)
            assertEquals("the selection is drawn in full", selected..selected, current.layers)
            assertEquals(0, stack.firstVertex)
            assertEquals(
                "the stack draw is every stored path of every layer it stands for",
                LayerWindow.segments(0 until selected) { value.layers[it].segmentCount } * 2,
                stack.vertexCount,
            )
            assertEquals(
                "stack and selection together are the whole path buffer",
                geometry.pathVertexCount(),
                stack.vertexCount + current.vertexCount,
            )

            // Each layer's two vertices per path sit inside the draw that names it.
            var drawn = 0
            for (layer in plan) {
                val start = geometry.vertexStart(layer)
                val end = geometry.vertexEnd(layer)
                val inStack = start >= stack.firstVertex && end <= stack.firstVertex + stack.vertexCount
                val inCurrent = start >= current.firstVertex && end <= current.firstVertex + current.vertexCount
                assertTrue(
                    "layer " + layer + " of the plan is not in either draw",
                    inStack || inCurrent,
                )
                assertTrue("a drawn layer has a vertex range", end > start)
                drawn++
            }
            assertEquals("every layer of the plan is drawn", plan.count(), drawn)
        }
    }

    @Test
    fun theBuildUpDrawGrowsWithTheStack() {
        val value = preview(layers = 120, pathsOf = { 25 })
        val geometry = geometry(value, LayerPreviewStyle.BUILD_UP)
        geometry.select(20)
        val before = LayerPreviewDraw.buildUp(geometry, LayerPreviewStyle.BUILD_UP)
        assertEquals(0..19, before.layers)

        geometry.select(21)
        val after = LayerPreviewDraw.buildUp(geometry, LayerPreviewStyle.BUILD_UP)
        assertEquals(0..20, after.layers)
        assertEquals(before.vertexCount + value.layers[20].segmentCount * 2, after.vertexCount)
        assertEquals(0, after.firstVertex)
    }

    @Test
    fun theCurrentLayerDrawIsOnlyTheSelectedLayer() {
        val value = preview(layers = 120, pathsOf = { 25 })
        val geometry = geometry(value, LayerPreviewStyle.CURRENT_LAYER)
        geometry.select(50)

        assertTrue("the current-layer style draws no stack", LayerPreviewDraw.buildUp(geometry, LayerPreviewStyle.CURRENT_LAYER).isEmpty)
        val current = LayerPreviewDraw.selected(geometry)
        assertEquals(50..50, current.layers)
        assertEquals(value.layers[50].segmentCount * 2, current.vertexCount)
    }

    @Test
    fun aReducedStackDrawsWhatIsHeldAndTheFooterSaysTheRestIsMissing() {
        val value = preview(layers = 100, pathsOf = { 1_000 })
        val capacity = PreviewCapacity(
            profile = PreviewMemoryProfile.AUTO,
            moves = 1_000,
            windows = 1_000,
            travels = 1_000,
            segments = 200_000,
            note = "auto profile · 4.0 GB free",
        )
        val geometry = LayerPreviewGeometry(value, capacity, LayerPreviewStyle.BUILD_UP)
        geometry.select(99)

        val stack = LayerPreviewDraw.buildUp(geometry, LayerPreviewStyle.BUILD_UP)
        val current = LayerPreviewDraw.selected(geometry)
        assertEquals("the draw is the held window, not the plan", geometry.window.count(), stack.layerCount + current.layerCount)
        assertEquals(geometry.window.first..98, stack.layers)
        assertTrue(stack.vertexCount > 0)
        assertTrue(
            "the footer says the stack is reduced",
            geometry.status.contains("the window holds 85000 of the 100000 runs"),
        )
    }
}
