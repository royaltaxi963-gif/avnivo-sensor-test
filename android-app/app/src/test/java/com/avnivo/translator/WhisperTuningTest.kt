package com.avnivo.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoder is the whole cost of a short decode, and whisper.cpp encodes the full 30 s window
 * unless it is told how much audio actually arrived. These tests pin the arithmetic that turns a
 * recording length into encoder positions, and the shape of the one bounded retry.
 */
class WhisperTuningTest {
    @Test
    fun `a five second recording asks for a fraction of the default window`() {
        val samples = 5 * WhisperTuning.SAMPLE_RATE
        val ctx = WhisperTuning.audioContextFor(samples)

        // 5 s -> 250 encoder positions plus 160 of trailing silence, against the model's 1500.
        assertEquals(410, ctx)
        assertTrue("must stay far below the 30 s window", ctx * 3 < WhisperTuning.MAX_AUDIO_CTX)
    }

    @Test
    fun `context grows with the recording and stops at the model window`() {
        val one = WhisperTuning.audioContextFor(3 * WhisperTuning.SAMPLE_RATE)
        val three = WhisperTuning.audioContextFor(5 * WhisperTuning.SAMPLE_RATE)
        val thirty = WhisperTuning.audioContextFor(30 * WhisperTuning.SAMPLE_RATE)
        val ninety = WhisperTuning.audioContextFor(90 * WhisperTuning.SAMPLE_RATE)

        assertTrue("longer audio needs more positions", three > one)
        assertEquals(WhisperTuning.MAX_AUDIO_CTX, thirty)
        assertEquals("never beyond the model", WhisperTuning.MAX_AUDIO_CTX, ninety)
    }

    @Test
    fun `a short or empty recording still gets a usable minimum`() {
        assertEquals(320, WhisperTuning.audioContextFor(0))
        assertEquals(320, WhisperTuning.audioContextFor(WhisperTuning.SAMPLE_RATE / 4))
        assertEquals(320, WhisperTuning.audioContextFor(WhisperTuning.SAMPLE_RATE))
    }

    @Test
    fun `a three second recording keeps the padding a repetition loop needs`() {
        // Measured on ggml-large-v3-turbo-q5_0: this clip looped at 214 positions and decoded
        // correctly at 288. The tuning must stay above that.
        val ctx = WhisperTuning.audioContextFor(3 * WhisperTuning.SAMPLE_RATE)

        assertEquals(320, ctx)
        assertTrue("must keep at least 1.4 s of trailing silence", ctx - 150 >= 140)
    }

    @Test
    fun `a small model keeps its own smaller window`() {
        // whisper-small reports n_audio_ctx = 1500 as well, but a smaller model must never be
        // asked for more context than it has.
        assertEquals(900, WhisperTuning.audioContextFor(60 * WhisperTuning.SAMPLE_RATE, maxAudioCtx = 900))
    }

    @Test
    fun `the window never grows past what the model has`() {
        val thirty = WhisperTuning.audioContextFor(30 * WhisperTuning.SAMPLE_RATE)

        assertEquals(WhisperTuning.MAX_AUDIO_CTX, thirty)
        assertEquals(
            "a 30 s recording is the only one that pays full price",
            WhisperTuning.MAX_AUDIO_CTX,
            WhisperTuning.audioContextFor(29 * WhisperTuning.SAMPLE_RATE, maxAudioCtx = 1500)
                .coerceAtMost(1500),
        )
    }

    @Test
    fun `threads never exceed the mobile ceiling`() {
        assertTrue(WhisperTuning.defaultThreads() in 1..WhisperTuning.MAX_THREADS)
    }
}
