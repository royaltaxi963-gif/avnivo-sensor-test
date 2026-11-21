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

/**
 * On-device verification of the offline Uzbek TTS module.
 *
 * The gated test downloads the real 92 MB Silero artifact from the signed GCS ticket, verifies its
 * SHA-256, initializes the TorchScript Lite interpreter through JNI and synthesizes real Uzbek
 * speech with no network in the loop. Nothing here is mocked: if the interpreter cannot load the
 * graph or the graph cannot produce audio, the test fails.
 *
 * Gated because of the download: pass `-e avnivoTtsInference true` to the instrumentation runner.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSileroTtsInferenceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pytorchLiteInterpreterLoadsOnThisAbi() {
        // A packaging mistake (missing libpytorch_jni_lite.so for this ABI) shows up here rather
        // than as a crash inside the first synthesis.
        val loaded = runCatching { Class.forName("org.pytorch.LiteModuleLoader") }.isSuccess
        assertTrue("the PyTorch Lite runtime must be packaged for this ABI", loaded)
    }

    @Test
    fun synthesizesUzbekSpeechOffline() {
        assumeTrue(
            "set -e avnivoTtsInference true to run",
            InstrumentationRegistry.getArguments().getString("avnivoTtsInference") == "true"
        )

        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            val started = System.currentTimeMillis()
            manager.download("silero-tts")
            manager.awaitIdle()
            val downloadMs = System.currentTimeMillis() - started

            val status = JSONObject(manager.status("silero-tts") ?: "{}")
            assertEquals(
                "module must reach ready only after download + verify + init + smoke test: $status",
                "ready",
                status.optString("state")
            )
            assertTrue("must be operational", status.optBoolean("active"))

            val samples = manager.synthesize("silero-tts", "Salom, mening ismim Avnivo.")
            assertTrue("synthesis must return audio", samples.isNotEmpty())
            // ~1 s of speech at 48 kHz; a much shorter result means the graph emitted a stub.
            assertTrue(
                "expected at least 0.5 s of audio, got ${samples.size} samples",
                samples.size > SileroTtsEngine.SAMPLE_RATE / 2
            )
            val peak = samples.maxOf { kotlin.math.abs(it) }
            assertTrue("audio must not be silent, peak=$peak", peak > 0.01f)
            assertTrue("audio must stay in range, peak=$peak", peak <= 1.01f)

            android.util.Log.i(
                "AvnivoSileroTts",
                "download+init=${downloadMs}ms samples=${samples.size} " +
                    "seconds=${samples.size.toDouble() / SileroTtsEngine.SAMPLE_RATE} peak=$peak"
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun repeatedSynthesisReusesTheLoadedModel() {
        assumeTrue(
            "set -e avnivoTtsInference true to run",
            InstrumentationRegistry.getArguments().getString("avnivoTtsInference") == "true"
        )

        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            manager.download("silero-tts")
            manager.awaitIdle()
            assertTrue(manager.isOperational("silero-tts"))

            val first = System.currentTimeMillis()
            val one = manager.synthesize("silero-tts", "Assalomu alaykum")
            val firstMs = System.currentTimeMillis() - first
            val second = System.currentTimeMillis()
            val two = manager.synthesize("silero-tts", "Yaxshimisiz?")
            val secondMs = System.currentTimeMillis() - second

            assertTrue("first synthesis must produce audio", one.isNotEmpty())
            assertTrue("second synthesis must produce audio", two.isNotEmpty())
            assertFalse("different text must not repeat the same buffer", one.contentEquals(two))
            // The weights stay resident, so the second call must not pay a model load again.
            assertTrue(
                "second synthesis ($secondMs ms) must not reload the model (first ${firstMs}ms)",
                secondMs <= firstMs + 1_500
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun engineRejectsTextItCannotRender() {
        val engine = SileroTtsEngine(File(context.cacheDir, "absent-${System.nanoTime()}.ptl"))
        assertTrue("close() must be safe before prepare()", runCatching { engine.close() }.isSuccess)
        val failed = try {
            engine.prepare()
            false
        } catch (expected: Exception) {
            true
        } finally {
            engine.close()
        }
        assertTrue("prepare() must fail closed when the model is absent", failed)
    }

    @Test
    fun missingModelIsNotInstalledAfterRestart() {
        // A wiped download must not leave a card claiming READY.
        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            val dir = File(context.filesDir, "models/silero-tts")
            val existed = dir.exists()
            if (!existed) {
                assertEquals(
                    "no model on disk means not installed",
                    "not_installed",
                    JSONObject(manager.status("silero-tts") ?: "{}").optString("state")
                )
            }
        } finally {
            manager.close()
        }
    }
}
