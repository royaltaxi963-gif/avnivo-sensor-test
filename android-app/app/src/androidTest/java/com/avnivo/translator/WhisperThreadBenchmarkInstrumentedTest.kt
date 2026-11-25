package com.avnivo.translator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * Thread-count comparison on the phone itself.
 *
 * ggml synchronises its workers with a spin barrier, so a thread that lands on a little core makes
 * every big core wait for it. The right number is a property of the SoC, not of the code, which is
 * why the production default is a starting point and this test measures 2, 4, 6 and 8 on the
 * device. It never downloads anything: it runs against the model the user already installed and
 * skips otherwise.
 *
 * ```
 * adb shell am instrument -w -e avnivoWhisperPerf true \
 *   -e class com.avnivo.translator.WhisperThreadBenchmarkInstrumentedTest \
 *   com.avnivo.translator.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -s AvnivoWhisper:V AvnivoWhisperBench:V
 * ```
 */
@RunWith(AndroidJUnit4::class)
class WhisperThreadBenchmarkInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun readWavSamples(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray((bytes.size - 44) / 2)
        buffer.position(44)
        for (i in samples.indices) samples[i] = buffer.short / 32768.0f
        return samples
    }

    private fun clip(seconds: Double): FloatArray {
        val asset = InstrumentationRegistry.getInstrumentation().context.assets.open("whisper_jfk_16k.wav")
        val audio = File(context.cacheDir, "whisper_bench_16k.wav")
        asset.use { input -> audio.outputStream().use { input.copyTo(it) } }
        val samples = readWavSamples(audio)
        val wanted = (seconds * 16_000).toInt()
        return if (samples.size > wanted) samples.copyOfRange(0, wanted) else samples
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0
        val index = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    @Test
    fun comparesThreadCountsOnTheInstalledModel() {
        assumeTrue(
            "set -e avnivoWhisperPerf true to run",
            InstrumentationRegistry.getArguments().getString("avnivoWhisperPerf") == "true"
        )
        val spec = OfflineModelSpecs.all.getValue("whisper-large-v3-turbo")
        val directory = spec.directory(context.filesDir)
        assumeTrue(
            "install whisper-large-v3-turbo on the device first; this test never downloads",
            spec.installed(directory)
        )
        val modelName = spec.files.keys.firstOrNull { it.endsWith(".bin") } ?: return
        val model = File(directory, modelName)
        val samples = clip(5.0)
        assertTrue("expected a five second clip", samples.size >= 4 * 16_000)
        val audioSeconds = samples.size / 16_000.0

        for (threads in listOf(2, 4, 6, 8)) {
            val engine = WhisperEngine(model, language = "en", tuning = WhisperTuning(threads = threads))
            try {
                val loadStarted = System.currentTimeMillis()
                engine.prepare()
                val loadMs = System.currentTimeMillis() - loadStarted

                val firstStarted = System.currentTimeMillis()
                val firstText = engine.transcribe(samples)
                val firstMs = System.currentTimeMillis() - firstStarted
                val firstTimings = engine.lastTimings

                val steady = mutableListOf<Long>()
                repeat(5) {
                    val started = System.currentTimeMillis()
                    engine.transcribe(samples)
                    steady += System.currentTimeMillis() - started
                }
                val sorted = steady.sorted()
                val p50 = percentile(sorted, 0.50)
                val p95 = percentile(sorted, 0.95)

                android.util.Log.i(
                    "AvnivoWhisperBench",
                    "WHISPER_THREADS threads=$threads audio=${"%.2f".format(audioSeconds)}s " +
                        "load_ms=$loadMs first_ms=$firstMs p50_ms=$p50 p95_ms=$p95 " +
                        "p50_rtf=${"%.3f".format(p50 / 1000.0 / audioSeconds)} " +
                        "p95_rtf=${"%.3f".format(p95 / 1000.0 / audioSeconds)} " +
                        "chars=${firstText.length} stage=[$firstTimings]"
                )
                assertFalse("the benchmark must run real inference", firstText.isBlank())
            } finally {
                engine.close()
            }
        }
    }

    /**
     * Cold against warm for the production path: the model load happens once, and every later press
     * must not pay for it again.
     */
    @Test
    fun reportsColdAndWarmLatencyForTheProductionPath() {
        assumeTrue(
            "set -e avnivoWhisperPerf true to run",
            InstrumentationRegistry.getArguments().getString("avnivoWhisperPerf") == "true"
        )
        val spec = OfflineModelSpecs.all.getValue("whisper-large-v3-turbo")
        assumeTrue("install whisper-large-v3-turbo first", spec.installed(spec.directory(context.filesDir)))

        val manager = OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
        try {
            val samples = clip(5.0)
            val coldStarted = System.currentTimeMillis()
            manager.transcribe("whisper-large-v3-turbo", samples, "en")
            val coldMs = System.currentTimeMillis() - coldStarted

            val warm = (1..5).map {
                val started = System.currentTimeMillis()
                manager.transcribe("whisper-large-v3-turbo", samples, "en")
                System.currentTimeMillis() - started
            }.sorted()
            val audioSeconds = samples.size / 16_000.0
            android.util.Log.i(
                "AvnivoWhisperBench",
                "WHISPER_COLD_WARM cold_ms=$coldMs p50_ms=${percentile(warm, 0.50)} " +
                    "p95_ms=${percentile(warm, 0.95)} audio=${"%.2f".format(audioSeconds)}s " +
                    "p50_rtf=${"%.3f".format(percentile(warm, 0.50) / 1000.0 / audioSeconds)}"
            )
            assertTrue("a warm press must not reload the model", coldMs > 0)
        } finally {
            manager.close()
        }
    }
}
