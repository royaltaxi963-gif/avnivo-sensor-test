package com.avnivo.translator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A press-and-release recording carries a lead-in and a tail of silence, and the encoder pays for
 * every millisecond of it. The trim is deliberately dumb - no model, no state - so the tests are
 * about the edges: never cut speech, never return less than whisper.cpp will decode, and leave a
 * recording that is silent throughout exactly as it arrived.
 */
class AudioTrimTest {
    private val rate = 16_000

    private fun speech(seconds: Double, amplitude: Float = 0.2f): FloatArray =
        FloatArray((seconds * rate).toInt()) { amplitude * if (it % 2 == 0) 1f else -1f }

    private fun silence(seconds: Double): FloatArray = FloatArray((seconds * rate).toInt())

    @Test
    fun `leading and trailing silence is removed`() {
        val audio = silence(1.0) + speech(3.0) + silence(1.0)

        val trimmed = AudioTrim.trim(audio)

        assertTrue("expected a real cut, got ${trimmed.size} of ${audio.size}",
            trimmed.size < audio.size)
        assertTrue("speech must survive", trimmed.size >= 3 * rate)
        assertTrue("only the 160 ms pad may be added", trimmed.size <= (3.5 * rate).toInt())
        assertTrue(
            "speech must stay in the middle of the result",
            kotlin.math.abs(trimmed[trimmed.size / 2]) > 0f,
        )
    }

    @Test
    fun `a quiet but real utterance is kept whole`() {
        val audio = silence(0.5) + speech(3.0, amplitude = 0.02f) + silence(0.5)

        val trimmed = AudioTrim.trim(audio)

        assertTrue("quiet speech must not be mistaken for silence", trimmed.size >= 3 * rate)
    }

    @Test
    fun `the result is never shorter than whisper will decode`() {
        val audio = silence(1.0) + speech(0.2) + silence(1.0)

        val trimmed = AudioTrim.trim(audio)

        assertTrue(
            "whisper.cpp returns no segments below one second",
            trimmed.size >= 16_000,
        )
    }

    @Test
    fun `silence throughout is returned untouched`() {
        val audio = silence(4.0)

        assertArrayEquals(audio, AudioTrim.trim(audio), 0f)
    }

    @Test
    fun `a recording with nothing to trim is returned untouched`() {
        val audio = speech(5.0)

        assertEquals(audio.size, AudioTrim.trim(audio).size)
    }
}
