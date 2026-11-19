package com.avnivo.translator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device verification of the offline Whisper module. Two layers:
 *  - the ggml native runtime loads on the real device ABI and fails closed without a model;
 *  - the full path (download -> SHA-256 verify -> runtime init -> smoke test -> real inference)
 *    produces a correct transcription of a known English utterance, offline.
 *
 * The heavy test downloads the ~264 MB Whisper Small model and is skipped unless
 * ``-e avnivoWhisperInference true`` is passed to the instrumentation runner.
 */
@RunWith(AndroidJUnit4::class)
class OfflineWhisperInferenceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun readWavSamples(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dataStart = 44
        val samples = FloatArray((bytes.size - dataStart) / 2)
        buffer.position(dataStart)
        for (i in samples.indices) samples[i] = buffer.short / 32768.0f
        return samples
    }

    @Test
    fun whisperNativeRuntimeLoads() {
        assertTrue("libavnivo_whisper must load on this ABI", WhisperEngine.ensureNativeLoaded())
    }

    @Test
    fun whisperEngineFailsClosedWithoutModel() {
        val missing = File(context.cacheDir, "no-whisper-${System.nanoTime()}.bin")
        val engine = WhisperEngine(missing, language = "auto")
        val failed = try {
            engine.prepare()
            false
        } catch (expected: Exception) {
            true
        }
        engine.close()
        assertTrue("prepare() must fail closed when the model is missing", failed)
    }

    @Test
    fun transcribesKnownEnglishSpeechOffline() {
        assumeTrue(
            "set -e avnivoWhisperInference true to run",
            InstrumentationRegistry.getArguments().getString("avnivoWhisperInference") == "true"
        )

        val manager = OfflineModuleManager(context.filesDir) { _, _ -> }
        try {
            manager.download("whisper-small-q8")
            manager.awaitIdle()
            val status = JSONObject(manager.status("whisper-small-q8") ?: "{}")
            assertTrue(
                "module must reach ready after real download + init, was: $status",
                status.optString("state") == "ready"
            )

            val asset = InstrumentationRegistry.getInstrumentation().context.assets.open("whisper_jfk_16k.wav")
            val audio = File(context.cacheDir, "whisper_jfk_16k.wav")
            asset.use { input -> audio.outputStream().use { input.copyTo(it) } }
            val samples = readWavSamples(audio)
            assertTrue("expected non-trivial audio", samples.size > 16000)

            val text = manager.transcribe("whisper-small-q8", samples).lowercase()
            assertFalse("transcription must not be empty", text.isBlank())
            val expected = listOf("country", "ask", "what", "you", "can", "do", "for")
            val hits = expected.count { it in text }
            assertTrue("recall $hits/${expected.size} too low for: $text", hits >= 4)
        } finally {
            manager.close()
        }
    }
}
