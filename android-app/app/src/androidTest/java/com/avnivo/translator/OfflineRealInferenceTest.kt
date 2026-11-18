package com.avnivo.translator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real end-to-end inference on a device/emulator: download the actual Uzbek STT model through the
 * production downloader, initialize the native sherpa-onnx engine, and decode a bundled real Uzbek
 * utterance. This is the on-device counterpart to ``offline-stt/verify_uzbek_stt_inference.py``.
 *
 * The model is 592 MB and the test needs network access, so it is skipped unless
 * ``-e avnivoRealInference true`` is passed to the instrumentation runner.
 */
@RunWith(AndroidJUnit4::class)
class OfflineRealInferenceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun readWavSamples(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dataStart = 44 // canonical 44-byte PCM header produced by the asset pipeline
        val samples = FloatArray((bytes.size - dataStart) / 2)
        buffer.position(dataStart)
        for (i in samples.indices) samples[i] = buffer.short / 32768.0f
        return samples
    }

    @Test
    fun transcribesRealUzbekSpeechOffline() {
        assumeTrue(
            "set -e avnivoRealInference true to run",
            InstrumentationRegistry.getArguments().getString("avnivoRealInference") == "true"
        )
        val modelDir = File(context.filesDir, "models/uzbek-stt")

        if (!OfflineSpeechEngine.hasRequiredFiles(modelDir)) {
            VerifiedModelDownloader(modelDir).download({}, { _, _ -> })
        }
        assertTrue("model files must be present after download", OfflineSpeechEngine.hasRequiredFiles(modelDir))

        val asset = InstrumentationRegistry.getInstrumentation().context.assets.open("uzbek_calm_intro_16k.wav")
        val audio = File(context.cacheDir, "uzbek_calm_intro_16k.wav")
        asset.use { input -> audio.outputStream().use { input.copyTo(it) } }
        val samples = readWavSamples(audio)
        assertTrue("expected non-trivial audio", samples.size > 16000)

        val text = OfflineSpeechEngine(modelDir).use { engine ->
            engine.prepare()
            engine.smokeTest()
            engine.transcribe(samples)
        }.lowercase()

        val expected = listOf("navoiy", "o'zbekcha", "matnni", "tabiiy", "ravon", "ovozga", "aylantiradi")
        val hits = expected.count { it in text }
        assertTrue("recall $hits/${expected.size} too low for: $text", hits >= expected.size - 1)
    }
}
