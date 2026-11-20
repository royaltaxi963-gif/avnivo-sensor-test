package com.avnivo.translator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
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

    /**
     * Measures the Whisper path as the user experiences it: model load, first inference, then a
     * repeat inference on the same warm engine. Emits one parseable line per phase so CI can chart
     * the numbers instead of trusting a suite wall clock.
     */
    @Test
    fun reportsWhisperLatencyAndRepeats() {
        assumeTrue(
            "set -e avnivoWhisperPerf true to run",
            InstrumentationRegistry.getArguments().getString("avnivoWhisperPerf") == "true"
        )

        val asset = InstrumentationRegistry.getInstrumentation().context.assets.open("whisper_jfk_16k.wav")
        val audio = File(context.cacheDir, "whisper_jfk_perf_16k.wav")
        asset.use { input -> audio.outputStream().use { input.copyTo(it) } }
        val samples = readWavSamples(audio)
        val audioSeconds = samples.size / 16000.0

        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            // Time the download separately so the load figure is not polluted by network work.
            val downloadStarted = System.nanoTime()
            manager.download("whisper-small-q8")
            manager.awaitIdle()
            val downloadSeconds = (System.nanoTime() - downloadStarted) / 1e9
            val status = JSONObject(manager.status("whisper-small-q8") ?: "{}")
            assertTrue("module must be ready, was: $status", status.optString("state") == "ready")

            val firstStarted = System.nanoTime()
            val first = manager.transcribe("whisper-small-q8", samples)
            val firstSeconds = (System.nanoTime() - firstStarted) / 1e9

            val secondStarted = System.nanoTime()
            val second = manager.transcribe("whisper-small-q8", samples)
            val secondSeconds = (System.nanoTime() - secondStarted) / 1e9

            val perf = "WHISPER_PERF audio=${"%.2f".format(audioSeconds)}s " +
                "download=${"%.1f".format(downloadSeconds)}s " +
                "first=${"%.1f".format(firstSeconds)}s repeat=${"%.1f".format(secondSeconds)}s " +
                "first_rt=${"%.2f".format(firstSeconds / audioSeconds)}x " +
                "repeat_rt=${"%.2f".format(secondSeconds / audioSeconds)}x"
            // println is dropped by am instrument unless -e log true is set, and even then it lands
            // in logcat rather than on stdout, so log it directly and let CI read logcat.
            println(perf)
            android.util.Log.i("AvnivoWhisper", perf)

            assertFalse("first transcription must not be empty", first.isBlank())
            assertEquals("repeat inference must be deterministic", first, second)
            // Same recall bar as the functional test, so this measurement run is also a real
            // transcription check and cannot pass on a stub.
            val expected = listOf("country", "ask", "what", "you", "can", "do", "for")
            val hits = expected.count { it in first.lowercase() }
            assertTrue("recall $hits/${expected.size} too low for: $first", hits >= 4)

            // An 11 s clip should not take minutes once ggml is optimised; guard against regressions
            // back to an -O0 build without hardcoding a figure tuned to one machine.
            assertTrue(
                "first inference ${"%.1f".format(firstSeconds)}s is far slower than realtime",
                firstSeconds / audioSeconds < 30.0
            )
        } finally {
            manager.close()
        }
    }

    /**
     * Same end-to-end path for the large model (~574 MB). Gated separately because it needs more
     * RAM and much more time; it proves the large model actually initializes and infers on-device
     * rather than merely downloading.
     */
    @Test
    fun transcribesKnownEnglishSpeechWithLargeModel() {
        assumeTrue(
            "set -e avnivoWhisperLargeInference true to run",
            InstrumentationRegistry.getArguments().getString("avnivoWhisperLargeInference") == "true"
        )

        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            manager.download("whisper-large-v3-turbo")
            manager.awaitIdle()
            val status = JSONObject(manager.status("whisper-large-v3-turbo") ?: "{}")
            assertTrue(
                "large module must reach ready after real download + init, was: $status",
                status.optString("state") == "ready"
            )

            val asset = InstrumentationRegistry.getInstrumentation().context.assets.open("whisper_jfk_16k.wav")
            val audio = File(context.cacheDir, "whisper_jfk_large_16k.wav")
            asset.use { input -> audio.outputStream().use { input.copyTo(it) } }
            val samples = readWavSamples(audio)

            val text = manager.transcribe("whisper-large-v3-turbo", samples).lowercase()
            assertFalse("transcription must not be empty", text.isBlank())
            val expected = listOf("country", "ask", "what", "you", "can", "do", "for")
            val hits = expected.count { it in text }
            assertTrue("recall $hits/${expected.size} too low for: $text", hits >= 4)
        } finally {
            manager.close()
        }
    }
}
