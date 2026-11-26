package com.avnivo.translator

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Removes the silence a press-and-release recording collects before and after the utterance.
 *
 * The encoder is linear in the amount of audio it is given, and a 0.6 s lead-in plus a 0.4 s tail
 * on a four second utterance is a quarter of the work for nothing. Trimming also removes the long
 * silences Whisper likes to hallucinate text over.
 *
 * Deliberately not a VAD: no model, no state, nothing that can fail on a device. Speech that is
 * quieter than [ABSOLUTE_FLOOR] everywhere is returned untouched, and a result shorter than
 * [MIN_SAMPLES] is padded back, because whisper.cpp silently returns no segments for less than a
 * second of audio.
 */
internal object AudioTrim {
    /** 20 ms at 16 kHz. */
    private const val FRAME = 320
    private const val PAD_FRAMES = 8
    private const val ABSOLUTE_FLOOR = 0.0035f
    private const val RELATIVE_FLOOR = 0.08f
    private const val MIN_SAMPLES = 16_000

    /** True when nothing in the recording rises above the noise floor. */
    fun isSilent(samples: FloatArray): Boolean {
        for (sample in samples) if (abs(sample) >= ABSOLUTE_FLOOR) return false
        return true
    }

    fun trim(samples: FloatArray): FloatArray {
        if (samples.size <= MIN_SAMPLES + 2 * PAD_FRAMES * FRAME) return samples

        var peak = 0f
        for (sample in samples) peak = max(peak, abs(sample))
        if (peak <= 0f) return samples

        val frames = samples.size / FRAME
        val rms = FloatArray(frames)
        var loudest = 0f
        for (frame in 0 until frames) {
            var sum = 0.0
            val base = frame * FRAME
            for (i in base until base + FRAME) sum += samples[i] * samples[i]
            rms[frame] = sqrt(sum / FRAME).toFloat()
            loudest = max(loudest, rms[frame])
        }
        if (loudest <= 0f) return samples

        val floor = max(ABSOLUTE_FLOOR, loudest * RELATIVE_FLOOR)
        val first = rms.indexOfFirst { it >= floor }
        if (first < 0) return samples
        val last = rms.indexOfLast { it >= floor }

        var start = max(0, (first - PAD_FRAMES) * FRAME)
        var end = minOf(samples.size, (last + 1 + PAD_FRAMES) * FRAME)
        if (end - start >= samples.size) return samples

        if (end - start < MIN_SAMPLES) {
            val missing = MIN_SAMPLES - (end - start)
            start = max(0, start - missing / 2)
            end = minOf(samples.size, start + MIN_SAMPLES)
            start = max(0, end - MIN_SAMPLES)
        }
        return samples.copyOfRange(start, end)
    }
}
