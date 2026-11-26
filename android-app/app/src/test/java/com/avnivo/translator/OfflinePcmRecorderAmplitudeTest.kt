package com.avnivo.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The waveform amplitude contract shared by the native recorder and the browser waveform. */
class OfflinePcmRecorderAmplitudeTest {
    @Test fun silenceIsZero() {
        assertEquals(0f, OfflinePcmRecorder.amplitude(0.0, 1600), 1e-6f)
    }

    @Test fun emptyBufferIsZero() {
        assertEquals(0f, OfflinePcmRecorder.amplitude(0.0, 0), 1e-6f)
    }

    @Test fun louderSpeechProducesLargerMovement() {
        val quiet = OfflinePcmRecorder.amplitude(1600 * 0.0004, 1600)  // rms 0.02
        val loud = OfflinePcmRecorder.amplitude(1600 * 0.01, 1600)    // rms 0.1
        assertTrue("quiet=$quiet loud=$loud", loud > quiet)
    }

    @Test fun amplitudeIsBoundedToOne() {
        assertEquals(1f, OfflinePcmRecorder.amplitude(1600 * 1.0, 1600), 1e-6f)
    }
}
