package com.tomppi.enderslicer.smartinfill

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartInfillRuntimeTest {
    @After
    fun clearRuntime() {
        SmartInfillRuntime.activate(null)
    }

    @Test
    fun sliceSnapshotKeepsPackageAWhenUiActivatesPackageB() {
        val packageA = packageValue("A")
        val packageB = packageValue("B")
        SmartInfillRuntime.activate(packageA)
        val snapshot = requireNotNull(SmartInfillRuntime.snapshot())

        SmartInfillRuntime.withSnapshot(snapshot) {
            assertEquals("A", SmartInfillRuntime.current()?.id)
            SmartInfillRuntime.activate(packageB)
            assertEquals("A", SmartInfillRuntime.current()?.id)
            assertTrue(!SmartInfillRuntime.isCurrent(snapshot))
        }

        assertEquals("B", SmartInfillRuntime.current()?.id)
    }

    @Test
    fun nullSliceSnapshotCannotAcquirePackageActivatedMidSlice() {
        SmartInfillRuntime.activate(null)
        val snapshot = SmartInfillRuntime.snapshot()

        SmartInfillRuntime.withSnapshot(snapshot) {
            assertNull(SmartInfillRuntime.current())
            SmartInfillRuntime.activate(packageValue("late"))
            assertNull(SmartInfillRuntime.current())
        }

        assertEquals("late", SmartInfillRuntime.current()?.id)
    }

    @Test
    fun nestedSnapshotRestoresOuterGeneration() {
        val packageA = packageValue("A")
        val packageB = packageValue("B")
        SmartInfillRuntime.activate(packageA)
        val snapshotA = requireNotNull(SmartInfillRuntime.snapshot())
        SmartInfillRuntime.activate(packageB)
        val snapshotB = requireNotNull(SmartInfillRuntime.snapshot())

        SmartInfillRuntime.withSnapshot(snapshotA) {
            assertEquals("A", SmartInfillRuntime.current()?.id)
            SmartInfillRuntime.withSnapshot(snapshotB) {
                assertEquals("B", SmartInfillRuntime.current()?.id)
            }
            assertEquals("A", SmartInfillRuntime.current()?.id)
        }
    }

    private fun packageValue(id: String): SmartInfillPackage = SmartInfillPackage(
        id = id,
        directory = File("."),
        sourceName = "$id.stl",
        sourceSha256 = "0".repeat(64),
        baseDensityPercent = 10.0,
        pattern = "cubic",
        mode = "graded",
        perimeters = 2,
        lineWidthMm = 0.4,
        topBottomLayers = 4,
        layerHeightMm = 0.2,
        upstreamCommit = SmartInfillActivity.FILASIM_COMMIT,
        modifiers = emptyList(),
    )
}
