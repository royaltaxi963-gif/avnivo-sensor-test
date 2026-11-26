package com.avnivo.translator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-inference stability run for the Uzbek STT module. It uses the production recognizer and the
 * real model, and covers the acceptance counts that do not need a live microphone:
 *
 *   - 30 sequential sessions through one controller, each producing its own transcript;
 *   - 10 controller restarts (the app-restart equivalent) that all reach READY;
 *   - 5 memory release/recovery cycles that reload the decoder automatically.
 *
 * It is opt-in because the model is 592 MB and the test needs network access the first time:
 * ``-e avnivoRealInference true``.
 */
@RunWith(AndroidJUnit4::class)
class OfflineUzbekSttStressInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val modelDir get() = File(context.filesDir, "models/uzbek-stt")

    private fun readWavSamples(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dataStart = 44
        val samples = FloatArray((bytes.size - dataStart) / 2)
        buffer.position(dataStart)
        for (i in samples.indices) samples[i] = buffer.short / 32768.0f
        return samples
    }

    private fun ensureModel() {
        if (!OfflineSpeechEngine.hasRequiredFiles(modelDir)) {
            VerifiedModelDownloader(modelDir).download({}, { _, _ -> })
        }
        assertTrue("model files must be present", OfflineSpeechEngine.hasRequiredFiles(modelDir))
    }

    private fun samples(): FloatArray {
        val asset = InstrumentationRegistry.getInstrumentation().context.assets.open("uzbek_calm_intro_16k.wav")
        val audio = File(context.cacheDir, "uzbek_calm_intro_16k.wav")
        asset.use { input -> audio.outputStream().use { input.copyTo(it) } }
        return readWavSamples(audio)
    }

    /** Counts prepare() calls so the test can prove the decoder is loaded once per controller. */
    private class CountingRecognizer(private val delegate: OfflineSpeechEngine) : SttRecognizer {
        var prepares = 0
        override val isPrepared: Boolean get() = delegate.isPrepared
        override fun prepare() {
            prepares += 1
            delegate.prepare()
        }
        override fun smokeTest(): String = delegate.smokeTest()
        override fun transcribe(samples: FloatArray): String = delegate.transcribe(samples)
        override fun close() = delegate.close()
    }

    private fun controller(built: MutableList<CountingRecognizer> = mutableListOf()) =
        UzbekSttController(
            recognizerFactory = { CountingRecognizer(OfflineSpeechEngine(modelDir)).also { built += it } },
            modelPresent = { OfflineSpeechEngine.hasRequiredFiles(modelDir) }
        )

    @Test
    fun thirtySessionsTenRestartsAndFiveRecoveries() {
        assumeTrue(
            "set -e avnivoRealInference true to run",
            InstrumentationRegistry.getArguments().getString("avnivoRealInference") == "true"
        )
        ensureModel()
        val audio = samples()
        assertTrue("expected non-trivial audio", audio.size > 16000)

        // 30 sequential sessions on one controller: one decoder, thirty matching transcripts.
        val built = mutableListOf<CountingRecognizer>()
        val sessions = controller(built)
        var reference = ""
        var ok = 0
        for (index in 1..30) {
            assertEquals("the page session must be adopted", index, sessions.beginCapture(index))
            sessions.endCapture(index)
            val text = sessions.transcribe(index, audio)
            sessions.complete(index)
            assertTrue("session $index must return text", text.isNotBlank())
            if (index == 1) reference = text else assertEquals("session $index drifted", reference, text)
            assertEquals("session $index must settle READY", OfflineModuleState.READY, sessions.state)
            ok += 1
        }
        assertEquals("the decoder must be built once", 1, built.size)
        assertEquals("prepare must run once", 1, built[0].prepares)
        val stress = "UZBEK_STT_STRESS sessions=30 ok=$ok prepare_calls=${built[0].prepares}"
        println(stress)
        android.util.Log.i("AvnivoUzbekStt", stress)
        sessions.close()

        // 10 restarts: a fresh controller over the same installed files must reach READY each time.
        var ready = 0
        repeat(10) {
            val restarted = controller()
            restarted.refreshInstalled()
            restarted.ensureReady()
            if (restarted.state == OfflineModuleState.READY && restarted.recognizerVerified) ready += 1
            restarted.close()
        }
        assertEquals(10, ready)
        val restarts = "UZBEK_STT_RESTARTS restarts=10 ready=$ready"
        println(restarts)
        android.util.Log.i("AvnivoUzbekStt", restarts)

        // 5 memory-pressure cycles: release the engine, then let the next use reload it.
        val recoveredController = controller()
        var recovered = 0
        repeat(5) {
            recoveredController.ensureReady()
            if (recoveredController.releaseForMemory()) {
                assertEquals(OfflineModuleState.INSTALLED, recoveredController.state)
                recoveredController.ensureReady()
                if (recoveredController.state == OfflineModuleState.READY) recovered += 1
            }
        }
        assertEquals(5, recovered)
        val recovery = "UZBEK_STT_RECOVERY cycles=5 recovered=$recovered"
        println(recovery)
        android.util.Log.i("AvnivoUzbekStt", recovery)
        recoveredController.close()
    }
}
