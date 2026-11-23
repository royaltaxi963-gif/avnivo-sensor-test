package com.avnivo.translator

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the Uzbek STT lifecycle with a real state machine and a stand-in recognizer; only the
 * native decoder is substituted, because the JVM has no NDK runtime.
 *
 * These are the acceptance scenarios that do not need a microphone: repeated sessions, a decode
 * overlapping the next recording, memory release and recovery, restarts, and honest error states.
 */
class UzbekSttControllerTest {

    private class FakeRecognizer(var text: String = "salom") : SttRecognizer {
        var prepared = 0
        var smoked = 0
        var decodes = 0
        var closed = 0
        var failPrepare: Throwable? = null
        var failTranscribe: Throwable? = null

        override val isPrepared: Boolean get() = prepared > 0

        override fun prepare() {
            failPrepare?.let { throw it }
            prepared += 1
        }

        override fun smokeTest(): String {
            smoked += 1
            return ""
        }

        override fun transcribe(samples: FloatArray): String {
            failTranscribe?.let { throw it }
            decodes += 1
            return text
        }

        override fun close() {
            closed += 1
        }
    }

    private class Harness(initialInstalled: Boolean = true) {
        var installed = initialInstalled
        var nextText = "salom"
        val built = mutableListOf<FakeRecognizer>()
        val events = mutableListOf<Triple<OfflineModuleState, String?, Int>>()

        val controller = UzbekSttController(
            recognizerFactory = { FakeRecognizer(nextText).also { built += it } },
            modelPresent = { this.installed },
            onStateChanged = { state, reason, session -> events += Triple(state, reason, session) }
        )

        val current: FakeRecognizer get() = built.last()
    }

    @Test
    fun notInstalledWithoutModel() {
        val h = Harness(installed = false)
        val failed = try {
            h.controller.ensureReady()
            false
        } catch (expected: IllegalStateException) {
            true
        }
        assertTrue("a missing model must fail closed", failed)
        assertEquals(OfflineModuleState.NOT_INSTALLED, h.controller.state)
        assertFalse(h.controller.isOperational)
        h.controller.close()
    }

    @Test
    fun readyOnlyAfterPrepareAndSmokeTest() {
        val h = Harness()
        h.controller.ensureReady()
        assertEquals(OfflineModuleState.READY, h.controller.state)
        assertTrue(h.controller.recognizerVerified)
        assertEquals(1, h.current.prepared)
        assertEquals(1, h.current.smoked)
        assertTrue(h.controller.isOperational)
        h.controller.close()
    }

    @Test
    fun repeatedEnsureReadyBuildsAndSmokesOnce() {
        val h = Harness()
        repeat(5) { h.controller.ensureReady() }
        assertEquals("the recognizer must be built once", 1, h.built.size)
        assertEquals("prepare must run once", 1, h.current.prepared)
        assertEquals("smoke decode must run once", 1, h.current.smoked)
        h.controller.close()
    }

    @Test
    fun thirtySequentialSessionsProduceTheirOwnTranscript() {
        val h = Harness()
        h.controller.ensureReady()
        repeat(30) { index ->
            val session = index + 1
            assertEquals(session, h.controller.beginCapture(session))
            h.controller.endCapture(session)
            h.current.text = "natija-$session"
            assertEquals("natija-$session", h.controller.transcribe(session, FloatArray(1600)))
            h.controller.complete(session)
            assertEquals(OfflineModuleState.READY, h.controller.state)
        }
        assertEquals(30, h.current.decodes)
        assertEquals(1, h.built.size)
        assertEquals(30, h.controller.lastSession)
        h.controller.close()
    }

    @Test
    fun aNewPressIsAllowedWhileThePreviousDecodeRuns() {
        val h = Harness()
        h.controller.ensureReady()
        assertEquals(1, h.controller.beginCapture(1))
        h.controller.endCapture(1)
        h.controller.transcribe(1, FloatArray(1600))
        assertEquals(OfflineModuleState.TRANSCRIBING, h.controller.state)

        assertEquals("the next press must not wait for the decode", 2, h.controller.beginCapture(2))
        assertEquals(OfflineModuleState.RECORDING, h.controller.state)

        // The older decode finishing must not overwrite the newer recording's state.
        h.controller.complete(1)
        assertEquals(OfflineModuleState.RECORDING, h.controller.state)

        h.controller.endCapture(2)
        h.controller.transcribe(2, FloatArray(1600))
        h.controller.complete(2)
        assertEquals(OfflineModuleState.READY, h.controller.state)
        h.controller.close()
    }

    @Test
    fun aStaleReleaseDoesNotOwnTheNewerCapture() {
        val h = Harness()
        assertEquals(1, h.controller.beginCapture(1))
        assertTrue(h.controller.ownsCapture(0))
        assertTrue(h.controller.ownsCapture(1))
        assertFalse("an older session must not stop a newer press", h.controller.ownsCapture(2))
        h.controller.endCapture(1)
        assertFalse("a closed capture is owned by nobody", h.controller.ownsCapture(0))
        h.controller.close()
    }

    @Test
    fun fiveMemoryReleaseAndRecoveryCycles() {
        val h = Harness()
        repeat(5) {
            h.controller.ensureReady()
            assertEquals(OfflineModuleState.READY, h.controller.state)
            assertTrue(h.controller.releaseForMemory())
            assertEquals(OfflineModuleState.INSTALLED, h.controller.state)
            assertFalse(h.controller.isOperational)
            assertNotNull(h.controller.reason)
        }
        assertEquals("every recovery must rebuild the decoder", 6, h.built.size)
        assertEquals(5, h.built.count { it.closed == 1 })
        h.controller.close()
    }

    @Test
    fun releaseIsRefusedWhileACaptureOrDecodeIsActive() {
        val h = Harness()
        h.controller.ensureReady()
        h.controller.beginCapture(1)
        assertFalse("the microphone must never lose its engine mid-recording", h.controller.releaseForMemory())
        h.controller.endCapture(1)
        h.controller.transcribe(1, FloatArray(1600))
        assertFalse("the decoder must not be closed mid-decode", h.controller.releaseForMemory())
        h.controller.complete(1)
        assertTrue(h.controller.releaseForMemory())
        h.controller.close()
    }

    @Test
    fun outOfMemorySettlesErrorWithAReason() {
        val failing = FakeRecognizer().apply { failPrepare = OutOfMemoryError("native alloc failed") }
        val controller = UzbekSttController(
            recognizerFactory = { failing },
            modelPresent = { true }
        )
        val failed = try {
            controller.ensureReady()
            false
        } catch (expected: OutOfMemoryError) {
            true
        }
        assertTrue(failed)
        assertEquals(OfflineModuleState.ERROR, controller.state)
        assertTrue("the reason must name memory", controller.reason.orEmpty().contains("xotira"))
        assertEquals("a failed decoder must be released", 1, failing.closed)
        assertFalse(controller.recognizerVerified)
        controller.close()
    }

    @Test
    fun aFailedDecodeDoesNotPoisonTheNextSession() {
        val h = Harness()
        h.controller.ensureReady()
        h.current.failTranscribe = IllegalStateException("decoder failed")
        h.controller.beginCapture(1)
        h.controller.endCapture(1)
        val failed = try {
            h.controller.transcribe(1, FloatArray(1600))
            false
        } catch (expected: IllegalStateException) {
            true
        }
        assertTrue(failed)
        h.controller.fail(1, "decoder failed")
        assertEquals(OfflineModuleState.ERROR, h.controller.state)

        // A new press retries: the failed recognizer was dropped, so a fresh one is built.
        assertEquals(2, h.controller.beginCapture(2))
        h.nextText = "tiklangan"
        h.controller.endCapture(2)
        assertEquals("tiklangan", h.controller.transcribe(2, FloatArray(1600)))
        h.controller.complete(2)
        assertEquals(OfflineModuleState.READY, h.controller.state)
        assertEquals(2, h.built.size)
        h.controller.close()
    }

    @Test
    fun stateCallbackCarriesTheSessionAndReason() {
        val h = Harness()
        h.controller.ensureReady()
        h.controller.beginCapture(7)
        h.controller.endCapture(7)
        h.controller.transcribe(7, FloatArray(1600))
        h.controller.complete(7)

        assertTrue(h.events.any { it.first == OfflineModuleState.READY && it.third == 0 })
        assertTrue(h.events.any { it.first == OfflineModuleState.RECORDING && it.third == 7 })
        assertTrue(h.events.any { it.first == OfflineModuleState.TRANSCRIBING && it.third == 7 })
        assertTrue(h.events.any { it.first == OfflineModuleState.READY && it.third == 7 })
        h.controller.close()
    }

    @Test
    fun refreshInstalledMarksTheModelAfterAProcessRestart() {
        val h = Harness()
        h.controller.refreshInstalled()
        assertEquals(OfflineModuleState.INSTALLED, h.controller.state)
        assertFalse("installed is not yet a verified engine", h.controller.isOperational)
        h.controller.ensureReady()
        assertEquals(OfflineModuleState.READY, h.controller.state)
        h.controller.close()
    }

    @Test
    fun tenRestartsEachReachReady() {
        repeat(10) { attempt ->
            val h = Harness()
            h.controller.refreshInstalled()
            h.controller.ensureReady()
            assertEquals("restart ${attempt + 1}", OfflineModuleState.READY, h.controller.state)
            assertTrue(h.controller.recognizerVerified)
            h.controller.close()
        }
    }

    @Test
    fun statusJsonExposesTheEvidence() {
        val h = Harness()
        h.controller.refreshInstalled()
        var status = JSONObject(h.controller.statusJson().toString())
        assertEquals("installed", status.getString("state"))
        assertFalse(status.getBoolean("readyForSpeech"))
        assertFalse(status.getBoolean("resident"))
        assertFalse(status.getBoolean("verified"))

        h.controller.ensureReady()
        status = JSONObject(h.controller.statusJson().toString())
        assertEquals("ready", status.getString("state"))
        assertTrue(status.getBoolean("readyForSpeech"))
        assertTrue(status.getBoolean("active"))
        assertTrue(status.getBoolean("resident"))
        assertTrue(status.getBoolean("verified"))
        assertTrue(status.isNull("reason"))

        h.controller.releaseForMemory()
        status = JSONObject(h.controller.statusJson().toString())
        assertEquals("installed", status.getString("state"))
        assertFalse(status.getBoolean("readyForSpeech"))
        assertFalse(status.getBoolean("resident"))
        assertFalse(status.isNull("reason"))
        h.controller.close()
    }

    @Test
    fun downloadStatesFollowTheEvidence() {
        val h = Harness(installed = false)
        h.controller.markDownloading()
        assertEquals(OfflineModuleState.DOWNLOADING, h.controller.state)
        h.controller.markVerifying()
        assertEquals(OfflineModuleState.VERIFYING, h.controller.state)
        h.controller.markInstalled()
        assertEquals(OfflineModuleState.INSTALLED, h.controller.state)
        h.controller.markError("buzilgan")
        assertEquals(OfflineModuleState.ERROR, h.controller.state)
        assertEquals("buzilgan", h.controller.reason)
        h.controller.close()
    }

    @Test
    fun closingReleasesTheDecoder() {
        val h = Harness()
        h.controller.ensureReady()
        h.controller.close()
        assertEquals(1, h.current.closed)
        assertNull(h.controller.reason)
    }
}
