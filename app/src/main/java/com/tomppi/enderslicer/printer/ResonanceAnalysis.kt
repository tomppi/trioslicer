package com.tomppi.enderslicer.printer

import kotlin.math.abs
import kotlin.math.sqrt

/** One frequency the machine answered at, and how far it stood out. */
data class ResonancePeak(
    val frequencyHz: Double,
    /** The response at that frequency, in whatever units the samples were in. */
    val magnitude: Double,
    /** What the machine's own response was around there, for a sense of scale. */
    val noise: Double,
) {
    /** How many times the surrounding response the peak is. */
    val signalToNoise: Double get() = if (noise > 0.0) magnitude / noise else 0.0
}

/** The machine's response across the swept band: one magnitude per frequency. */
data class ResonanceCurve(
    val frequencies: DoubleArray,
    val magnitudes: DoubleArray,
) {
    /** The strongest frequencies, strongest first, ignoring anything below [minimumSnr]. */
    fun peaks(minimumSnr: Double = 2.0, limit: Int = 3): List<ResonancePeak> {
        if (magnitudes.size < 3) return emptyList()
        val floor = median()
        val found = mutableListOf<ResonancePeak>()
        for (index in 1 until magnitudes.size - 1) {
            val value = magnitudes[index]
            if (value > magnitudes[index - 1] && value >= magnitudes[index + 1]) {
                val peak = ResonancePeak(frequencies[index], value, floor)
                if (peak.signalToNoise >= minimumSnr) found += peak
            }
        }
        return found.sortedByDescending { it.magnitude }.take(limit)
    }

    /** The middle of the curve, which is what "the machine's own noise" means here. */
    fun median(): Double {
        if (magnitudes.isEmpty()) return 0.0
        val sorted = magnitudes.sorted()
        return sorted[sorted.size / 2]
    }
}

/**
 * Reading a resonance sweep out of a recording of the machine shaking.
 *
 * The printer is excited with the sweep Klipper's own test uses - played by the
 * resonance_playback module in the payload, because those moves cannot be sent as G-code -
 * and the phone records its accelerometer while that happens. This turns the recording into
 * the one thing the shaper needs: how strongly the machine answers at each frequency.
 *
 * The method is the classic stepped-sine one, done in one pass. For each frequency in the
 * band: work out when the sweep was playing that frequency, take the recording from then,
 * and measure how much of that frequency is in it (Goertzel, which is a single-bin DFT). The
 * result is a response curve rather than a spectrum of the whole recording, which is what
 * makes it immune to the fact that the phone's clock and the printer's are unrelated: each
 * measurement only needs to know *when* a frequency was played, and the sweep's own start is
 * found in the recording itself.
 */
object ResonanceAnalysis {
    /**
     * The frequency the sweep is playing [seconds] after it started.
     *
     * Linear, and exactly so rather than as an approximation. Each half period lasts
     * 0.25/f, and the frequency then advances by 2 * that * hz_per_sec, so a whole period
     * takes 0.5/f seconds and moves the frequency by 0.5 * hz_per_sec / f: the two carry
     * the same factor, which leaves df/dt = hz_per_sec exactly. The sweep crosses the band
     * at the rate its name says, at every frequency - which is also why the default sweep
     * takes 130 seconds for 5 to 135 Hz.
     *
     * A test mirrors the generator's loop and checks this against it, which is how the
     * first version of this function - a square root, from treating the step as
     * continuous - was caught before it became a wrong frequency axis.
     */
    fun sweepFrequency(fStart: Double, hzPerSec: Double, seconds: Double): Double =
        fStart + hzPerSec * seconds

    /** When the sweep reached [frequency], the inverse of [sweepFrequency]. */
    fun sweepTime(fStart: Double, hzPerSec: Double, frequency: Double): Double =
        (frequency - fStart) / hzPerSec

    /**
     * How long the sweep takes, in seconds: the inverse above at the end frequency.
     */
    fun sweepDuration(fStart: Double, fEnd: Double, hzPerSec: Double): Double =
        sweepTime(fStart, hzPerSec, fEnd)

    /**
     * The recording with its slow parts removed: gravity, and the drift of however the phone
     * is lying. What is left is the shaking.
     */
    fun detrend(samples: DoubleArray, sampleRateHz: Double, windowSeconds: Double = 0.5): DoubleArray {
        val window = (windowSeconds * sampleRateHz).toInt().coerceAtLeast(1)
        val out = DoubleArray(samples.size)
        var sum = 0.0
        for (index in samples.indices) {
            sum += samples[index]
            if (index >= window) sum -= samples[index - window]
            val count = if (index >= window) window else index + 1
            // The middle of the window is what the sample is compared against, so a value
            // that is still in the window's future is not being subtracted from itself.
            val centre = index - count / 2
            val mean = if (centre >= 0) {
                var windowSum = 0.0
                val from = (centre - window / 2).coerceAtLeast(0)
                val to = (centre + window / 2).coerceAtMost(samples.size - 1)
                for (at in from..to) windowSum += samples[at]
                windowSum / (to - from + 1)
            } else {
                sum / count
            }
            out[index] = samples[index] - mean
        }
        return out
    }

    /**
     * Where in the recording the machine started moving.
     *
     * The sweep announces itself: before it, the phone is sitting still, and after it the
     * signal is orders of magnitude larger. The alignment only has to be good to a small
     * fraction of a second, because a frequency a second either side of the true one is a
     * fraction of a hertz of error across the band.
     *
     * Returns null when nothing stands out - which is a real answer: the machine was not
     * heard, and guessing at a start would produce a curve out of noise.
     */
    fun motionStart(samples: DoubleArray, sampleRateHz: Double, quietSeconds: Double = 1.0): Int? {
        val quiet = (quietSeconds * sampleRateHz).toInt().coerceAtLeast(1)
        if (samples.size <= quiet) return null
        var baseline = 0.0
        for (index in 0 until quiet) baseline += samples[index] * samples[index]
        baseline = sqrt(baseline / quiet)
        if (baseline <= 0.0) return null
        val threshold = baseline * MOTION_THRESHOLD
        for (index in quiet until samples.size) {
            if (abs(samples[index]) > threshold) return index
        }
        return null
    }

    /**
     * The machine's response, measured frequency by frequency.
     *
     * [motionStartSeconds] is when the sweep began in this recording, from [motionStart].
     * Frequencies are stepped by [stepHz] across the band; each is measured over
     * [windowSeconds] of the recording that followed its moment in the sweep.
     */
    fun response(
        samples: DoubleArray,
        sampleRateHz: Double,
        motionStartSeconds: Double,
        fStart: Double,
        fEnd: Double,
        hzPerSec: Double,
        stepHz: Double = 1.0,
        windowSeconds: Double = 1.0,
    ): ResonanceCurve {
        val frequencies = mutableListOf<Double>()
        val magnitudes = mutableListOf<Double>()
        val windowSamples = (windowSeconds * sampleRateHz).toInt().coerceAtLeast(16)
        var frequency = fStart
        while (frequency <= fEnd + 1e-9) {
            val at = ((motionStartSeconds + sweepTime(fStart, hzPerSec, frequency)) * sampleRateHz).toInt()
            if (at < 0 || at + windowSamples > samples.size) break
            frequencies += frequency
            magnitudes += goertzel(samples, at, windowSamples, frequency, sampleRateHz)
            frequency += stepHz
        }
        return ResonanceCurve(frequencies.toDoubleArray(), magnitudes.toDoubleArray())
    }

    /**
     * How much of one frequency is in one stretch of samples.
     *
     * Goertzel's algorithm: a single bin of a DFT, without computing the rest of it. Divided
     * by the sample count so that windows of different lengths are comparable.
     */
    fun goertzel(
        samples: DoubleArray,
        from: Int,
        count: Int,
        frequency: Double,
        sampleRateHz: Double,
    ): Double {
        val omega = 2.0 * Math.PI * frequency / sampleRateHz
        val coefficient = 2.0 * Math.cos(omega)
        var previous = 0.0
        var beforeThat = 0.0
        for (index in from until (from + count).coerceAtMost(samples.size)) {
            val current = samples[index] + coefficient * previous - beforeThat
            beforeThat = previous
            previous = current
        }
        val power = previous * previous + beforeThat * beforeThat - coefficient * previous * beforeThat
        return sqrt(power.coerceAtLeast(0.0)) / count
    }

    /** How many times the quiet before the sweep a sample has to be to count as motion. */
    private const val MOTION_THRESHOLD = 8.0
}
