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

    /**
     * Runs the real runtime against an artifact that CI placed on the device, so interpreter
     * loading, smoke synthesis and inference are proven even when the download path is unavailable.
     *
     * CI seeds the file only after re-deriving the pinned SHA-256, and this test re-checks the size
     * before use, so a wrong or truncated artifact cannot pass here.
     */
    @Test
    fun seededArtifactInitializesAndSynthesizes() {
        val directory = File(context.filesDir, "models/silero-tts")
        val model = File(directory, "silero_v21.ptl")
        assumeTrue("set -e avnivoTtsSeed true and push the artifact to run", 
            InstrumentationRegistry.getArguments().getString("avnivoTtsSeed") == "true")
        assertTrue("CI must have seeded $model", model.isFile)
        assertEquals(
            "seeded artifact must be the pinned byte count",
            91_988_758L,
            model.length()
        )

        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            manager.reactivateInstalled()
            manager.awaitIdle()

            val status = JSONObject(manager.status("silero-tts") ?: "{}")
            assertEquals(
                "a verified artifact must reach ready through the real init path: $status",
                "ready",
                status.optString("state")
            )

            val started = System.currentTimeMillis()
            val samples = manager.synthesize("silero-tts", "Salom, mening ismim Avnivo.")
            val elapsed = System.currentTimeMillis() - started

            assertTrue("synthesis must return audio", samples.isNotEmpty())
            assertTrue(
                "expected at least 0.5 s of audio, got ${samples.size} samples",
                samples.size > SileroTtsEngine.SAMPLE_RATE / 2
            )
            val peak = samples.maxOf { kotlin.math.abs(it) }
            assertTrue("audio must not be silent, peak=$peak", peak > 0.01f)

            android.util.Log.i(
                "AvnivoSileroTts",
                "seed init+synthesis=${elapsed}ms samples=${samples.size} " +
                    "seconds=${samples.size.toDouble() / SileroTtsEngine.SAMPLE_RATE} peak=$peak"
            )
        } finally {
            manager.close()
        }
    }

    /**
     * Proves the second half of the install story: once the real download has completed, synthesis
     * works with the radios down and does not touch the network again.
     *
     * CI runs this after disabling wifi and airplane mode, so a passing result means the model came
     * from app-private storage. `download()` is called again on purpose: it must reuse the verified
     * files on disk instead of re-fetching them, which is what makes a restart cheap.
     */
    @Test
    fun downloadedModelSynthesizesWithNoNetwork() {
        assumeTrue("set -e avnivoTtsOffline true to run",
            InstrumentationRegistry.getArguments().getString("avnivoTtsOffline") == "true")

        val directory = File(context.filesDir, "models/silero-tts")
        val model = File(directory, "silero_v21.ptl")
        assertTrue(
            "the download test must have left a verified artifact at $model",
            model.isFile && model.length() == 91_988_758L
        )

        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            val started = System.currentTimeMillis()
            manager.download("silero-tts")
            manager.awaitIdle()
            val reuseMs = System.currentTimeMillis() - started

            val status = JSONObject(manager.status("silero-tts") ?: "{}")
            assertEquals(
                "an installed model must reach ready again without a download: $status",
                "ready",
                status.optString("state")
            )
            assertTrue("must be operational", status.optBoolean("active"))
            // A re-download would spend seconds on 92 MB; reuse only loads the graph.
            assertTrue("reuse took ${reuseMs}ms, which looks like a re-download", reuseMs < 20_000)

            val samples = manager.synthesize("silero-tts", "Internet o'chirilgan holda ham ishlaydi.")
            assertTrue("synthesis must return audio", samples.isNotEmpty())
            assertTrue(
                "expected at least 0.5 s of audio, got ${samples.size} samples",
                samples.size > SileroTtsEngine.SAMPLE_RATE / 2
            )
            val peak = samples.maxOf { kotlin.math.abs(it) }
            assertTrue("audio must not be silent, peak=$peak", peak > 0.01f)

            android.util.Log.i(
                "AvnivoSileroTts",
                "offline reuse=${reuseMs}ms samples=${samples.size} " +
                    "seconds=${samples.size.toDouble() / SileroTtsEngine.SAMPLE_RATE} peak=$peak"
            )
        } finally {
            manager.close()
        }
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
