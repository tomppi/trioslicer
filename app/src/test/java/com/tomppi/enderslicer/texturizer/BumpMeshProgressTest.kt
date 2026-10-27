package com.tomppi.enderslicer.texturizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The public progress phases and the page-fraction → phase mapping the UI lane renders. */
class BumpMeshProgressTest {

    @Test
    fun allSevenPhasesExistInApplyOrder() {
        assertEquals(
            listOf(
                "loading engine",
                "importing",
                "subdividing",
                "displacing",
                "collecting",
                "exporting",
                "receiving mesh",
            ),
            BumpMeshProgress.phases,
        )
    }

    @Test
    fun thePageProgressIsMappedToNamedPhasesWithAnIncreasingFraction() {
        assertEquals("subdividing", BumpMeshProgress.phaseForPageFraction(0.01f).first)
        assertEquals(0.20f, BumpMeshProgress.phaseForPageFraction(0f).second, 0.0001f)
        assertEquals("displacing", BumpMeshProgress.phaseForPageFraction(0.5f).first)
        assertEquals("collecting", BumpMeshProgress.phaseForPageFraction(0.8f).first)
        assertEquals("exporting", BumpMeshProgress.phaseForPageFraction(0.99f).first)

        var previous = -1f
        for (step in 0..100) {
            val (phase, fraction) = BumpMeshProgress.phaseForPageFraction(step / 100f)
            assertTrue("phase " + phase + " at " + step, BumpMeshProgress.phases.contains(phase))
            assertTrue("fraction must not go backwards", fraction >= previous)
            previous = fraction
        }
    }

    @Test
    fun theBridgeStreamIsTheReceivingMeshPhase() {
        assertEquals(0.96f, BumpMeshProgress.receivingFraction(0, 100), 0.0001f)
        assertEquals(0.98f, BumpMeshProgress.receivingFraction(50, 100), 0.0001f)
        assertEquals(1f, BumpMeshProgress.receivingFraction(100, 100), 0.0001f)
        assertEquals(1f, BumpMeshProgress.receivingFraction(500, 100), 0.0001f)
        assertEquals(0.96f, BumpMeshProgress.receivingFraction(10, 0), 0.0001f)
    }
}
