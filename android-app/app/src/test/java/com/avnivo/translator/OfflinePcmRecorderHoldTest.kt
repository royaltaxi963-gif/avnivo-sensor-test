package com.avnivo.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The minimum-hold contract that makes the first press usable.
 *
 * A release can arrive before the microphone has opened. Without a floor the capture returned an
 * empty array, which the page then reported as "no speech" — the first recording looked lost and
 * only the second press produced text. These assertions exercise the real loop condition.
 */
class OfflinePcmRecorderHoldTest {
    private val limit = 16_000 * 30

    @Test fun minimumHoldCoversTheSpeechFloor() {
        // The recorder floor and the recognizer's floor must describe the same duration, otherwise
        // a capture can be long enough to keep and still too short to decode.
        assertEquals(4800, OfflinePcmRecorder.MIN_HOLD_SAMPLES)
    }

    @Test fun minimumHoldIsThreeHundredMilliseconds() {
        assertEquals(0.3, OfflinePcmRecorder.MIN_HOLD_SAMPLES / 16_000.0, 1e-9)
    }

    @Test fun recordingContinuesWhileTheButtonIsHeld() {
        assertTrue(OfflinePcmRecorder.shouldContinue(stopRequested = false, sampleCount = 0, limit = limit))
        assertTrue(OfflinePcmRecorder.shouldContinue(stopRequested = false, sampleCount = 15_999, limit = limit))
    }

    @Test fun aReleaseBeforeTheMicrophoneOpensStillCaptures() {
        // The reported defect: a release at sample 0 used to end the loop immediately.
        assertTrue(OfflinePcmRecorder.shouldContinue(stopRequested = true, sampleCount = 0, limit = limit))
        assertTrue(OfflinePcmRecorder.shouldContinue(stopRequested = true, sampleCount = 4799, limit = limit))
    }

    @Test fun aReleaseEndsCaptureOnceTheFloorIsReached() {
        assertFalse(OfflinePcmRecorder.shouldContinue(stopRequested = true, sampleCount = 4800, limit = limit))
        assertFalse(OfflinePcmRecorder.shouldContinue(stopRequested = true, sampleCount = 9600, limit = limit))
    }

    @Test fun theMaximumDurationStillStopsCapture() {
        // A held button must not record past the limit even though nothing was released.
        assertFalse(OfflinePcmRecorder.shouldContinue(stopRequested = false, sampleCount = limit, limit = limit))
        assertFalse(OfflinePcmRecorder.shouldContinue(stopRequested = true, sampleCount = limit, limit = limit))
    }
}
