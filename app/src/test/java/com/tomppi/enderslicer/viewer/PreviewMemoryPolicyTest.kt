package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview's capacity comes from the device, not from a constant: the old fixed budgets are
 * the low-memory profile, and a phone with RAM to spare holds the whole path. The small fake
 * devices here are the point - a real phone cannot be asked to be low-RAM on demand.
 */
class PreviewMemoryPolicyTest {

    private fun device(totalGb: Double, freeGb: Double, lowRam: Boolean, memoryClassMb: Int) =
        PreviewDeviceMemory(
            totalBytes = (totalGb * 1_000_000_000.0).toLong(),
            availableBytes = (freeGb * 1_000_000_000.0).toLong(),
            lowRamDevice = lowRam,
            memoryClassMb = memoryClassMb,
            largeMemoryClassMb = memoryClassMb,
        )

    @Test
    fun aLowRamDeviceGetsTheLowMemoryProfile() {
        val capacity = PreviewMemoryPolicy.resolve(
            PreviewMemoryProfile.AUTO,
            device(1.0, 0.4, lowRam = true, memoryClassMb = 128),
            conservativeMoves = 400_000,
        )
        assertEquals(PreviewMemoryProfile.LOW_MEMORY, capacity.profile)
        assertEquals(PreviewMemoryPolicy.LOW_MEMORY_WINDOWS, capacity.windows)
        assertEquals("the conservative parse budget is the low-memory case", 400_000, capacity.moves)
        assertTrue(capacity.note.contains("low-memory"))
    }

    @Test
    fun anEightGigabyteDeviceGetsTheUnlockedBudget() {
        val capacity = PreviewMemoryPolicy.resolve(
            PreviewMemoryProfile.AUTO,
            device(8.0, 7.2, lowRam = false, memoryClassMb = 512),
            conservativeMoves = 1_000_000,
        )
        // Half of 7.2 GB over 792 bytes a window is 4.5M, capped at 4M.
        assertEquals(4_000_000, capacity.windows)
        assertTrue("the conservative budget must not be the default on a big device", capacity.moves > 1_000_000)
        assertTrue(capacity.note, capacity.note.contains("7.2 GB free"))
    }

    @Test
    fun theProfileIsOverridableInBothDirections() {
        val big = device(8.0, 7.2, lowRam = false, memoryClassMb = 512)
        val small = device(1.0, 0.4, lowRam = true, memoryClassMb = 128)
        assertEquals(
            "a big device can be asked for the low-memory profile",
            PreviewMemoryPolicy.LOW_MEMORY_WINDOWS,
            PreviewMemoryPolicy.resolve(PreviewMemoryProfile.LOW_MEMORY, big, 400_000).windows,
        )
        assertTrue(
            "a small device can be asked for the unlocked profile",
            PreviewMemoryPolicy.resolve(PreviewMemoryProfile.UNLOCKED, small, 400_000).windows >
                PreviewMemoryPolicy.LOW_MEMORY_WINDOWS,
        )
    }

    @Test
    fun aSmallHeapClassBoundsTheParseBudget() {
        val capacity = PreviewMemoryPolicy.resolve(
            PreviewMemoryProfile.UNLOCKED,
            device(8.0, 8.0, lowRam = false, memoryClassMb = 96),
            conservativeMoves = 400_000,
        )
        // A quarter of a 96 MB heap class over 40 bytes a stored move.
        assertEquals(629_145, capacity.moves)
    }

    @Test
    fun aRefusedAllocationHalvesDownToTheFloor() {
        val attempts = BandAllocator.attempts(1_617_938, RibbonBand.MINIMUM_WINDOWS, Int.MAX_VALUE)
        assertEquals(listOf(1_617_938, 808_969, 404_484, 202_242, 101_121, 50_560, 25_280, 20_000), attempts)
        // A driver that only grants 100,000 windows takes the first attempt at or below it.
        assertEquals(50_560, attempts.first { it <= 100_000 })
        // A driver that grants nothing ends in the named refusal, with both numbers in it.
        val refusal = BandAllocator.refusal(1_617_938, RibbonBand.MINIMUM_WINDOWS)
        assertTrue(refusal, refusal.contains("1617938") && refusal.contains("20000"))
    }

    @Test
    fun aSessionCeilingStartsTheNextPathLower() {
        val attempts = BandAllocator.attempts(1_617_938, RibbonBand.MINIMUM_WINDOWS, ceiling = 200_000)
        assertEquals(200_000, attempts.first())
        assertEquals(RibbonBand.MINIMUM_WINDOWS, attempts.last())
    }

    @Test
    fun travelsScaleWithTheGrantedWindows() {
        assertEquals(2_560, BandAllocator.travels(29_451, 2_300_000, 200_000))
        assertEquals(1_024, BandAllocator.travels(10, 1_000_000, 20_000))
    }
}
