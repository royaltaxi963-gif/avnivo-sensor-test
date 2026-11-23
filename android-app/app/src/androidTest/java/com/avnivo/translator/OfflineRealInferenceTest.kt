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

        val engine = OfflineSpeechEngine(modelDir)
        var prepareMs = 0L
        var smokeMs = 0L
        var decodeMs = 0L
        val text = engine.use {
            var started = System.nanoTime()
            it.prepare()
            prepareMs = (System.nanoTime() - started) / 1_000_000
            started = System.nanoTime()
            it.smokeTest()
            smokeMs = (System.nanoTime() - started) / 1_000_000
            started = System.nanoTime()
            val decoded = it.transcribe(samples)
            decodeMs = (System.nanoTime() - started) / 1_000_000
            decoded
        }.lowercase()

        // An assumption skip also prints "OK", so CI cannot tell a real run from a no-op without a
        // marker it can grep for. Log the transcript itself.
        val marker = "UZBEK_STT_RESULT text=${text.replace("\n", " ")}"
        println(marker)
        android.util.Log.i("AvnivoUzbekStt", marker)

        val reference = "navoiy o'zbekcha matnni tabiiy ravon ovozga aylantiradi"
        val refWords = SpeechAccuracy.words(reference)
        val hypWords = SpeechAccuracy.words(text)
        val wer = SpeechAccuracy.wer(reference, text)
        val cer = SpeechAccuracy.cer(reference, text)
        val accuracy = "UZBEK_STT_ACCURACY wer=%.4f cer=%.4f ref_words=%d hyp_words=%d word_errors=%d"
            .format(wer, cer, refWords.size, hypWords.size, SpeechAccuracy.levenshtein(refWords, hypWords))
        println(accuracy)
        android.util.Log.i("AvnivoUzbekStt", accuracy)
        val latency = "UZBEK_STT_LATENCY prepare_ms=$prepareMs smoke_ms=$smokeMs decode_ms=$decodeMs samples=${samples.size}"
        println(latency)
        android.util.Log.i("AvnivoUzbekStt", latency)

        val expected = listOf("navoiy", "o'zbekcha", "matnni", "tabiiy", "ravon", "ovozga", "aylantiradi")
        val hits = expected.count { it in text }
        assertTrue("recall $hits/${expected.size} too low for: $text", hits >= expected.size - 1)
        // Report the real number instead of claiming perfection: a hypothesis that does not match
        // the reference must produce a positive error rate.
        assertTrue("WER must stay below 1.0, measured $wer for: $text", wer < 1.0)
    }
}
