package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Reading a machine's resonances out of a recording of it being swept.
 *
 * The numbers here come from the printer's side rather than from this file: the sweep is
 * Klipper's, played by the playback module, and what is checked is that the analysis agrees
 * with it about what was being played when.
 */
class ResonanceAnalysisTest {
    private val sampleRate = 416.0

    /**
     * The generator's own loop, mirrored from resonance_tester.py - the thing the closed-form
     * mapping is supposed to describe.
     */
    private fun generatorPairs(fStart: Double, fEnd: Double, hzPerSec: Double): List<Pair<Double, Double>> {
        var freq = fStart
        var time = 0.0
        val out = mutableListOf<Pair<Double, Double>>()
        while (freq <= fEnd + 1e-9) {
            val tSeg = 0.25 / freq
            time += 2 * tSeg
            freq += 2 * tSeg * hzPerSec
            out += time to freq
        }
        return out
    }

    @Test
    fun theFrequencyMapIsTheGeneratorsOwn() {
        val hzPerSec = 1.0
        val pairs = generatorPairs(5.0, 135.0, hzPerSec)
        pairs.forEach { (time, frequency) ->
            assertEquals(frequency, ResonanceAnalysis.sweepFrequency(5.0, hzPerSec, time), 1e-9)
        }
        // And the inverse lands back where it started, which is what the analysis uses to
        // ask "when was 42 Hz being played".
        assertEquals(42.0, ResonanceAnalysis.sweepTime(5.0, hzPerSec, 42.0).let {
            ResonanceAnalysis.sweepFrequency(5.0, hzPerSec, it)
        }, 1e-9)
    }

    @Test
    fun theSweepTakesAsLongAsKlipperSaysItDoes() {
        // 130 seconds for 5 to 135 Hz at 1 Hz/s, and the segment count the playback module was
        // verified against (36398 half periods, so 18199 of these).
        assertEquals(130.0, ResonanceAnalysis.sweepDuration(5.0, 135.0, 1.0), 1e-9)
        assertEquals(18199, generatorPairs(5.0, 135.0, 1.0).size)
    }

    @Test
    fun oneFrequencyInIsFoundAtThatFrequency() {
        // A pure 42 Hz tone, sampled like the phone samples, read back by name.
        val samples = DoubleArray((2 * sampleRate).toInt()) { index ->
            sin(2 * PI * 42.0 * index / sampleRate)
        }
        val magnitude = ResonanceAnalysis.goertzel(samples, 0, samples.size, 42.0, sampleRate)
        val elsewhere = ResonanceAnalysis.goertzel(samples, 0, samples.size, 55.0, sampleRate)
        assertTrue("42 Hz should dominate: $magnitude against $elsewhere", magnitude > 20 * elsewhere)
    }

    @Test
    fun aMachineThatRingsAtFortyTwoHertzIsReadAsRingingAtFortyTwoHertz() {
        // The physical model: the machine is driven at the sweep's frequency, and answers in
        // proportion to how close that is to its resonance. Phase is integrated from the
        // instantaneous frequency, as the printer's motion would be.
        val fStart = 20.0
        val fEnd = 120.0
        val hzPerSec = 2.0
        val resonance = 42.0
        val duration = ResonanceAnalysis.sweepDuration(fStart, fEnd, hzPerSec)
        val samples = DoubleArray(((duration + 0.2) * sampleRate).toInt())
        var phase = 0.0
        for (index in samples.indices) {
            val t = index / sampleRate
            val f = ResonanceAnalysis.sweepFrequency(fStart, hzPerSec, t)
            phase += 2 * PI * f / sampleRate
            val response = 1.0 + 12.0 / (1.0 + ((f - resonance) / 2.0) * ((f - resonance) / 2.0))
            samples[index] = response * sin(phase) + 0.05 * sin(2 * PI * 97.0 * index / sampleRate)
        }

        val curve = ResonanceAnalysis.response(samples, sampleRate, 0.0, fStart, fEnd, hzPerSec)
        val peaks = curve.peaks()
        assertTrue("no peak found in ${curve.magnitudes.size} points", peaks.isNotEmpty())
        assertEquals(resonance, peaks.first().frequencyHz, 1.5)
        assertTrue("the peak should stand out: ${peaks.first().signalToNoise}", peaks.first().signalToNoise > 2.0)
    }

    @Test
    fun gravityAndTheWayThePhoneIsLyingAreRemoved() {
        // A phone on a printer's base reads 9.81 m/s^2 of gravity plus whatever it is being
        // shaken by; only the second is interesting.
        val samples = DoubleArray((sampleRate * 2).toInt()) { index ->
            9.81 + 0.3 * sin(2 * PI * 35.0 * index / sampleRate)
        }
        val quiet = ResonanceAnalysis.detrend(samples, sampleRate)
        val mean = quiet.sum() / quiet.size
        assertEquals("gravity should be gone", 0.0, mean, 0.01)
        val peak = ResonanceAnalysis.goertzel(quiet, 0, quiet.size, 35.0, sampleRate)
        assertTrue("the shaking should survive", peak > 0.1)
    }

    @Test
    fun theMomentTheMachineStartsMovingIsFoundInTheRecording() {
        val quietSamples = (sampleRate * 2).toInt()
        val samples = DoubleArray(quietSamples + (sampleRate * 3).toInt()) { index ->
            val noise = 0.002 * sin(2 * PI * 7.3 * index / sampleRate)
            if (index < quietSamples) noise
            else noise + 0.25 * sin(2 * PI * 35.0 * index / sampleRate)
        }
        val start = ResonanceAnalysis.motionStart(samples, sampleRate)
        assertNotNull("the sweep should be noticed", start)
        assertEquals(2.0, start!! / sampleRate, 0.05)
    }

    @Test
    fun aRecordingOfNothingIsNotReadAsAMachine() {
        val samples = DoubleArray((sampleRate * 3).toInt()) { index ->
            0.002 * sin(2 * PI * 7.3 * index / sampleRate)
        }
        assertNull("nothing moved, so there is no start", ResonanceAnalysis.motionStart(samples, sampleRate))
    }

    @Test
    fun aFlatCurveHasNoPeaks() {
        val frequencies = DoubleArray(100) { 20.0 + it }
        val flat = ResonanceCurve(frequencies, DoubleArray(100) { 1.0 })
        assertTrue(flat.peaks().isEmpty())
    }

    @Test
    fun theFrequencyThatComesBackIsTheOneWorthHaving() {
        // Two runs from two places on the machine: both hear 88, one of them also hears
        // something at 42 that the other does not - which is where the phone was standing.
        val first = listOf(ResonancePeak(88.1, 10.0, 1.0), ResonancePeak(42.0, 9.0, 1.0))
        val second = listOf(ResonancePeak(88.6, 8.0, 1.0), ResonancePeak(101.0, 7.0, 1.0))
        val agreed = ResonanceAnalysis.agreeing(listOf(first, second))
        assertEquals(1, agreed.size)
        assertEquals(88.1, agreed.first().frequencyHz, 0.5)
        assertEquals(2, agreed.first().seenIn)
        assertEquals(2, agreed.first().ofRuns)
    }

    @Test
    fun oneMeasurementAgreesWithNothing() {
        val only = listOf(listOf(ResonancePeak(88.1, 10.0, 1.0)))
        assertTrue(ResonanceAnalysis.agreeing(only).isEmpty())
    }

    @Test
    fun peaksFarApartAreNotTheSamePeak() {
        val first = listOf(ResonancePeak(35.0, 10.0, 1.0))
        val second = listOf(ResonancePeak(88.0, 10.0, 1.0))
        assertTrue(ResonanceAnalysis.agreeing(listOf(first, second)).isEmpty())
    }

    @Test
    fun aSensorRidingTheToolheadIsReadAsATransferFunction() {
        // The toolhead case: the phone is on the moving mass, so it reads the sweep that is
        // being played with the machine's answer added around it. The resonance is a bump on
        // that baseline, not a peak above a floor - and without dividing the drive out, the
        // loudest thing in the band is simply where the drive is strongest.
        val fStart = 20.0
        val fEnd = 120.0
        val hzPerSec = 2.0
        val accelPerHz = 30.0
        val resonance = 68.0
        val duration = ResonanceAnalysis.sweepDuration(fStart, fEnd, hzPerSec)
        val samples = DoubleArray(((duration + 0.2) * sampleRate).toInt())
        var phase = 0.0
        for (index in samples.indices) {
            val t = index / sampleRate
            val f = ResonanceAnalysis.sweepFrequency(fStart, hzPerSec, t)
            phase += 2 * PI * f / sampleRate
            val amplification = 6.0 / (1.0 + ((f - resonance) / 2.0) * ((f - resonance) / 2.0))
            samples[index] = accelPerHz * f * (1.0 + amplification) * sin(phase) / 1000.0
        }

        fun at(curve: ResonanceCurve, frequency: Double): Double =
            curve.magnitudes[curve.frequencies.indexOfFirst { it >= frequency }]

        val normalised = ResonanceAnalysis.response(samples, sampleRate, 0.0, fStart, fEnd, hzPerSec)
        // The drive's own acceleration is accel_per_hz * f, so it climbs with frequency and
        // tilts everything it is fed into - which is why the division is not optional: on a
        // curve that rises threefold across the band, the middle of it is no yardstick, and a
        // low resonance (a bed at 35 Hz) needs several times its neighbourhood to be seen.
        // Divided out, the same value a third of the way up the band and near the top of it.
        assertEquals("the baseline is flat", at(normalised, 40.0), at(normalised, 110.0), 0.2 * at(normalised, 40.0))
        val peaks = normalised.peaks(minimumSnr = 1.5)
        assertTrue("no peak found", peaks.isNotEmpty())
        assertEquals("and the machine answers at its resonance", resonance, peaks.first().frequencyHz, 3.0)
    }

    @Test
    fun aLighterToolheadIsShiftedFurtherByTheSamePhone() {
        // What the phone's own weight does to the frequency underneath it, and what a pair of
        // measurements says about the mass it was sitting on.
        assertEquals(1.32, ResonanceAnalysis.massCorrection(350.0, 253.0), 0.01)
        assertEquals(1.23, ResonanceAnalysis.massCorrection(500.0, 253.0), 0.01)
        assertEquals(356.0, ResonanceAnalysis.impliedMovingMass(89.0, 68.0, 253.0), 5.0)
    }

    @Test
    fun aSweepStartDetectedLateMovesEveryFrequencyDown() {
        // Why the printer's own announcement is worth having: the amplitude of a gentle sweep is
        // close to the noise at first, so a start found by vibration can be seconds late - and
        // every frequency is then read at the wrong moment. At two hertz per second, six seconds
        // late is twelve hertz low, which is the difference between one shaper and another.
        val fStart = 20.0
        val fEnd = 120.0
        val hzPerSec = 2.0
        val resonance = 60.0
        val duration = ResonanceAnalysis.sweepDuration(fStart, fEnd, hzPerSec)
        val samples = DoubleArray(((duration + 0.2) * sampleRate).toInt())
        var phase = 0.0
        for (index in samples.indices) {
            val t = index / sampleRate
            val f = ResonanceAnalysis.sweepFrequency(fStart, hzPerSec, t)
            phase += 2 * PI * f / sampleRate
            val response = 1.0 + 8.0 / (1.0 + ((f - resonance) / 2.0) * ((f - resonance) / 2.0))
            samples[index] = response * sin(phase)
        }

        val exact = ResonanceAnalysis.response(samples, sampleRate, 0.0, fStart, fEnd, hzPerSec)
        val late = ResonanceAnalysis.response(samples, sampleRate, 6.0, fStart, fEnd, hzPerSec)
        val exactPeak = exact.peaks(minimumSnr = 1.5).first().frequencyHz
        val latePeak = late.peaks(minimumSnr = 1.5).first().frequencyHz
        assertEquals(resonance, exactPeak, 2.0)
        assertTrue(
            "a late start should read the resonance low, by seconds times hertz per second",
            latePeak < exactPeak - 8.0,
        )
    }
}


