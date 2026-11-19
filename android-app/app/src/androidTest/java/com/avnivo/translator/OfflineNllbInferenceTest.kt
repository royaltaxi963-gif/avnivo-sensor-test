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

/**
 * On-device verification of the offline NLLB-200 translation module. The heavy test downloads the
 * real ~916 MB model from the signed GCS ticket, verifies every SHA-256, initializes ONNX Runtime
 * through JNI and translates real Uzbek/Russian/English sentences with no network in the loop.
 *
 * Gated because of the download size and the ~1.8 GB of RAM the loaded model needs:
 * pass `-e avnivoNllbInference true` to the instrumentation runner.
 */
@RunWith(AndroidJUnit4::class)
class OfflineNllbInferenceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nllbNativeRuntimeLoads() {
        assertTrue("libavnivo_nllb must load on this ABI", NllbEngine.ensureNativeLoaded())
    }

    @Test
    fun onnxRuntimeIsReachableFromTheJniLayer() {
        // The JNI layer resolves ONNX Runtime with dlopen; if the packaged library were missing or
        // ABI-incompatible this would throw instead of returning ids.
        val engine = NllbEngine(File(context.cacheDir, "absent-${System.nanoTime()}"))
        val failed = try {
            engine.prepare()
            false
        } catch (expected: Exception) {
            true
        } finally {
            engine.close()
        }
        assertTrue("prepare() must fail closed when models are absent", failed)
    }

    @Test
    fun translatesUzbekToRussianOffline() {
        assumeTrue(
            "set -e avnivoNllbInference true to run",
            InstrumentationRegistry.getArguments().getString("avnivoNllbInference") == "true"
        )

        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            manager.download("nllb-200-600m-int8")
            manager.awaitIdle()

            val status = JSONObject(manager.status("nllb-200-600m-int8") ?: "{}")
            assertTrue(
                "module must reach ready after download + verify + init + smoke test, was: $status",
                status.optString("state") == "ready"
            )

            val russian = manager.translate(
                "nllb-200-600m-int8", "Salom, qalaysiz?", NllbEngine.UZBEK, NllbEngine.RUSSIAN
            )
            assertFalse("translation must not be empty", russian.isBlank())
            assertTrue("expected Cyrillic Russian output, got: $russian", russian.any { it in '\u0400'..'\u04FF' })

            val english = manager.translate(
                "nllb-200-600m-int8", "Bugun havo juda issiq.", NllbEngine.UZBEK, NllbEngine.ENGLISH
            )
            assertFalse("translation must not be empty", english.isBlank())
            assertTrue(
                "expected Latin English output, got: $english",
                english.any { it.isLetter() && it.code < 0x250 }
            )

            // Same engine instance is reused: no reload between calls.
            val again = manager.translate(
                "nllb-200-600m-int8", "Rahmat", NllbEngine.UZBEK, NllbEngine.RUSSIAN
            )
            assertFalse("repeated translation must work", again.isBlank())

            val timings = manager.inferenceTimings("nllb-200-600m-int8")
            android.util.Log.i(
                "AvnivoNllb",
                "real inference latency: " + timings.joinToString(", ") {
                    "${it.first}=${it.second}ms"
                } + " | results: $russian / $english / $again"
            )
            assertTrue("inference latency must be measured, got $timings", timings.isNotEmpty())
            assertTrue(
                "a 7-token Uzbek sentence must translate in under 60s, measured $timings",
                timings.all { it.second in 1..60_000 }
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun closingTheEngineReleasesItsNativeSessions() {
        // prepare() opens two ONNX sessions and close() must free them, otherwise a re-download or a
        // process restart would accumulate sessions until the device runs out of memory.
        val engine = NllbEngine(File(context.cacheDir, "absent-${System.nanoTime()}"))
        assertTrue("close() must be safe before prepare()", runCatching { engine.close() }.isSuccess)
        val failed = try {
            engine.prepare()
            false
        } catch (expected: Exception) {
            true
        } finally {
            engine.close()
        }
        assertTrue("prepare() must fail closed when models are absent", failed)
        assertTrue("close() must be idempotent", runCatching { engine.close() }.isSuccess)
    }

    @Test
    fun theNllbCardInTheUiMapsToThisModule() {
        // The settings card for "Offline tarjima" must be backed by a real spec, or the download
        // button would have nothing to run.
        val html = context.assets.open("index.html").bufferedReader().use { it.readText() }
        assertTrue(
            "the offline translation card must point at the NLLB module",
            html.contains("{id:'offline-translate',installerId:'nllb-200-600m-int8'")
        )
        assertTrue("the module must be registered natively", OfflineModelSpecs.spec("nllb-200-600m-int8") != null)
    }

    @Test
    fun refusesLanguagePairTheModelDoesNotSupport() {
        assumeTrue(
            "set -e avnivoNllbInference true to run",
            InstrumentationRegistry.getArguments().getString("avnivoNllbInference") == "true"
        )

        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            manager.download("nllb-200-600m-int8")
            manager.awaitIdle()
            assertTrue(
                "engine must be ready before the language gate is meaningful",
                JSONObject(manager.status("nllb-200-600m-int8") ?: "{}").optString("state") == "ready"
            )

            assertFalse(
                "a code outside NLLB's vocabulary must not be accepted",
                manager.supportsTranslation("nllb-200-600m-int8", "uz", NllbEngine.RUSSIAN)
            )
            assertTrue(
                "a real NLLB pair must be accepted",
                manager.supportsTranslation("nllb-200-600m-int8", NllbEngine.UZBEK, NllbEngine.RUSSIAN)
            )

            val failed = try {
                manager.translate("nllb-200-600m-int8", "Salom", "uz", NllbEngine.RUSSIAN)
                false
            } catch (expected: IllegalArgumentException) {
                true
            }
            assertTrue("an unsupported source code must be rejected", failed)
        } finally {
            manager.close()
        }
    }
}
