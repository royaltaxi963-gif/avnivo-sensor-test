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

    private fun assetSamples(assetName: String, cacheName: String): FloatArray {
        val asset = InstrumentationRegistry.getInstrumentation().context.assets.open(assetName)
        val audio = File(context.cacheDir, cacheName)
        asset.use { input -> audio.outputStream().use { input.copyTo(it) } }
        return readWavSamples(audio)
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

        val events = mutableListOf<Pair<String, String>>()
        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { fn, v ->
            synchronized(events) { events += fn to v }
            if (fn == "AvnivoOfflineModuleProgress" || fn == "AvnivoOfflineModuleReady" ||
                fn == "AvnivoOfflineModuleError" || fn == "AvnivoOfflineModuleStatus"
            ) {
                android.util.Log.i("AvnivoWhisperSmall", "$fn $v")
            }
        }
        try {
            val spec = OfflineModelSpecs.all.getValue("whisper-small-q8")
            val alreadyOnDisk = spec.installed(spec.directory(context.filesDir))
            manager.download("whisper-small-q8")
            manager.awaitIdle()
            val status = JSONObject(manager.status("whisper-small-q8") ?: "{}")
            assertTrue(
                "module must reach ready after real download + init, was: $status",
                status.optString("state") == "ready"
            )
            assertTrue("the SHA-256 stamp must exist after verification: $status", status.optBoolean("verified"))

            val progress = synchronized(events) {
                events.filter { it.first == "AvnivoOfflineModuleProgress" }.map { it.second }
            }
            android.util.Log.i(
                "AvnivoWhisperSmall",
                "WHISPER_SMALL_DOWNLOAD already_on_disk=$alreadyOnDisk progress_events=${progress.size} " +
                    "first=${progress.firstOrNull() ?: "none"} last=${progress.lastOrNull() ?: "none"}"
            )
            assertTrue(
                "a fresh download must report real byte progress: $progress",
                alreadyOnDisk || progress.isNotEmpty()
            )
            if (progress.isNotEmpty()) {
                assertTrue(
                    "the last progress must cover the whole artifact: ${progress.last()}",
                    progress.last().endsWith("/264464607")
                )
            }
            android.util.Log.i(
                "AvnivoWhisperSmall",
                "WHISPER_SMALL_STATE state=${status.optString("state")} sha_verified=${status.optBoolean("verified")} " +
                    "resident=${status.optBoolean("resident")}"
            )

            val samples = assetSamples("whisper_jfk_16k.wav", "whisper_jfk_16k.wav")
            assertTrue("expected non-trivial audio", samples.size > 16000)

            val text = manager.transcribe("whisper-small-q8", samples, "en").lowercase()
            assertFalse("transcription must not be empty", text.isBlank())
            val expected = listOf("country", "ask", "what", "you", "can", "do", "for")
            val hits = expected.count { it in text }
            android.util.Log.i(
                "AvnivoWhisperSmall",
                "WHISPER_SMALL_TRANSCRIPT chars=${text.length} recall=$hits/${expected.size} text=${text.take(120)}"
            )
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
     * Same real path with Russian speech. This is the regression for the S23 Ultra report that the
     * multilingual model answered only English: the audio language must follow the clip, not a
     * hard-coded source language.
     */
    @Test
    fun transcribesRussianSpeechOffline() {
        assumeTrue(
            "set -e avnivoWhisperInference true to run",
            InstrumentationRegistry.getArguments().getString("avnivoWhisperInference") == "true"
        )

        val events = mutableListOf<Pair<String, String>>()
        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { fn, v ->
            synchronized(events) { events += fn to v }
            if (fn == "AvnivoOfflineModuleProgress" || fn == "AvnivoOfflineModuleReady") {
                android.util.Log.i("AvnivoWhisperSmall", "$fn $v")
            }
        }
        try {
            manager.download("whisper-small-q8")
            manager.awaitIdle()
            val status = JSONObject(manager.status("whisper-small-q8") ?: "{}")
            assertEquals("ready", status.optString("state"))
            assertTrue("the SHA-256 stamp must exist after verification: $status", status.optBoolean("verified"))
            val progress = synchronized(events) {
                events.filter { it.first == "AvnivoOfflineModuleProgress" }.map { it.second }
            }
            android.util.Log.i(
                "AvnivoWhisperSmall",
                "WHISPER_SMALL_DOWNLOAD ru_run progress_events=${progress.size} " +
                    "last=${progress.lastOrNull() ?: "none"}"
            )

            val samples = assetSamples("whisper_ru_16k.wav", "whisper_ru_16k.wav")
            assertTrue("expected non-trivial Russian audio", samples.size > 16000)
            val text = manager.transcribe("whisper-small-q8", samples, "ru").lowercase()
            assertFalse("Russian transcription must not be empty", text.isBlank())
            assertTrue(
                "Russian audio must produce Cyrillic text, got: $text",
                text.any { it in '\u0400'..'\u04FF' }
            )
            val expected = listOf("сегодня", "погода", "говорим", "русски")
            val hits = expected.count { it in text }
            android.util.Log.i(
                "AvnivoWhisper",
                "WHISPER_SMALL_RU chars=${text.length} recall=$hits/${expected.size} text=${text.take(120)}"
            )
            assertTrue("Russian recall $hits/${expected.size} too low for: $text", hits >= 2)
        } finally {
            manager.close()
        }
    }

    /**
     * Twenty real decodes in a row, alternating English and Russian. A leaked session or a cached
     * previous result would show up as the wrong script in one of the pairs, so each session must
     * return text in the language of its own clip.
     */
    @Test
    fun twentyRealSessionsKeepTheirOwnTranscript() {
        assumeTrue(
            "set -e avnivoWhisperStress true to run",
            InstrumentationRegistry.getArguments().getString("avnivoWhisperStress") == "true"
        )

        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            manager.download("whisper-small-q8")
            manager.awaitIdle()
            assertEquals("ready", JSONObject(manager.status("whisper-small-q8") ?: "{}").optString("state"))

            val en = assetSamples("whisper_jfk_16k.wav", "whisper_jfk_stress_16k.wav")
            val ru = assetSamples("whisper_ru_16k.wav", "whisper_ru_stress_16k.wav")
            val shortEn = en.copyOfRange(0, minOf(en.size, 80000))
            val shortRu = ru.copyOfRange(0, minOf(ru.size, 80000))

            var ok = 0
            var first = ""
            var last = ""
            repeat(20) { index ->
                val english = index % 2 == 0
                val text = manager.transcribe("whisper-small-q8", if (english) shortEn else shortRu).trim()
                if (text.isNotBlank()) ok++
                if (index == 0) first = text
                if (index == 19) last = text
                android.util.Log.i(
                    "AvnivoWhisper",
                    "WHISPER_SMALL_STRESS session=${index + 1} lang=${if (english) "en" else "ru"} " +
                        "chars=${text.length} text=${text.take(80)}"
                )
                if (english) {
                    assertTrue(
                        "English session ${index + 1} returned no Latin text: $text",
                        text.any { it in 'a'..'z' || it in 'A'..'Z' }
                    )
                } else {
                    assertTrue(
                        "Russian session ${index + 1} returned no Cyrillic text: $text",
                        text.any { it in '\u0400'..'\u04FF' }
                    )
                }
            }
            android.util.Log.i(
                "AvnivoWhisper",
                "WHISPER_SMALL_STRESS sessions=20 ok=$ok first=${first.take(60)} last=${last.take(60)}"
            )
            assertEquals("every session must return its own transcript", 20, ok)
        } finally {
            manager.close()
        }
    }

    /**
     * Ten release/reload cycles. The model must stay installed between cycles and reload on the
     * next transcribe without downloading again.
     */
    @Test
    fun tenUnloadReloadCyclesKeepTheModelUsable() {
        assumeTrue(
            "set -e avnivoWhisperStress true to run",
            InstrumentationRegistry.getArguments().getString("avnivoWhisperStress") == "true"
        )

        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            manager.download("whisper-small-q8")
            manager.awaitIdle()
            assertEquals("ready", JSONObject(manager.status("whisper-small-q8") ?: "{}").optString("state"))

            val en = assetSamples("whisper_jfk_16k.wav", "whisper_jfk_reload_16k.wav")
            val shortEn = en.copyOfRange(0, minOf(en.size, 80000))
            var ok = 0
            repeat(10) { cycle ->
                manager.unload("whisper-small-q8")
                manager.awaitIdle()
                val released = JSONObject(manager.status("whisper-small-q8") ?: "{}")
                assertFalse("cycle ${cycle + 1} must release the engine", released.optBoolean("active"))
                assertEquals("cycle ${cycle + 1} must stay installed", "installed", released.optString("state"))

                val text = manager.transcribe("whisper-small-q8", shortEn).trim()
                assertFalse("cycle ${cycle + 1} returned no transcript", text.isBlank())
                val ready = JSONObject(manager.status("whisper-small-q8") ?: "{}")
                assertEquals("cycle ${cycle + 1} must reload on demand", "ready", ready.optString("state"))
                ok++
                android.util.Log.i(
                    "AvnivoWhisper",
                    "WHISPER_SMALL_RELOAD cycle=${cycle + 1} text=${text.take(60)}"
                )
            }
            android.util.Log.i("AvnivoWhisper", "WHISPER_SMALL_RELOAD cycles=10 ok=$ok")
            assertEquals(10, ok)
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

            // dumpsys meminfo runs after the instrumentation process has exited, so it reports
            // nothing useful. Read the numbers from inside the process instead, immediately after
            // inference while the model is still resident: heap in use, native allocation, and the
            // VM heap ceiling that decides whether this fits on a real phone.
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
            val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
            val perf = "WHISPER_LARGE_MEM heap_used_mb=${"%.0f".format(usedMb)} " +
                "native_alloc_mb=${"%.0f".format(nativeMb)} max_heap_mb=${runtime.maxMemory() / (1024 * 1024)} " +
                "recall=$hits/${expected.size}"
            println(perf)
            android.util.Log.i("AvnivoWhisperLarge", perf)
        } finally {
            manager.close()
        }
    }

    /**
     * Hindi and Bengali must be sent to Whisper with their mapped language code, not auto-detected
     * and not the NLLB code. Both clips are real speech; the script assertion prevents an English
     * hallucination from counting as success.
     */
    @Test
    fun transcribesHindiAndBengaliWithExplicitLanguage() {
        assumeTrue(
            "set -e avnivoWhisperInference true to run",
            InstrumentationRegistry.getArguments().getString("avnivoWhisperInference") == "true"
        )
        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            manager.download("whisper-small-q8")
            manager.awaitIdle()
            val status = JSONObject(manager.status("whisper-small-q8") ?: "{}")
            assertEquals("ready", status.optString("state"))

            val cases = listOf(
                Triple("whisper_hi_16k.wav", "hi", listOf("नमस्ते", "आज", "मौसम", "अच्छा")),
                Triple("whisper_bn_16k.wav", "bn", listOf("আজ", "আবহাওয়া", "সুন্দর"))
            )
            for ((assetName, language, expected) in cases) {
                val samples = assetSamples(assetName, assetName)
                assertTrue("$language audio must be non-trivial", samples.size > 16000)
                val text = manager.transcribe("whisper-small-q8", samples, language).lowercase()
                assertFalse("$language transcription must not be empty", text.isBlank())
                val script = if (language == "hi") '\u0900'..'\u097F' else '\u0980'..'\u09FF'
                assertTrue(
                    "$language audio must produce its own script, got: $text",
                    text.any { it in script }
                )
                val hits = expected.count { it in text }
                android.util.Log.i(
                    "AvnivoWhisper",
                    "WHISPER_SMALL_LANG lang=$language chars=${text.length} recall=$hits/${expected.size} text=${text.take(120)}"
                )
                assertTrue("$language recall $hits/${expected.size} too low for: $text", hits >= 1)
            }
        } finally {
            manager.close()
        }
    }
}
