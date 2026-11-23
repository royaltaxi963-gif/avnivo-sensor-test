package com.avnivo.translator

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/** Offline-only NeMo CTC decoder; all methods must be called off the UI thread. */
class OfflineSpeechEngine(private val modelDirectory: File) : SttRecognizer {
    private var recognizer: OfflineRecognizer? = null

    companion object {
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 80

        /** Required files exist and are non-empty. Pure file-system check, no native code. */
        fun hasRequiredFiles(directory: File): Boolean =
            File(directory, MODEL_FILE).let { it.isFile && it.length() > 0L } &&
                File(directory, TOKENS_FILE).let { it.isFile && it.length() > 0L }
    }

    /** True once [prepare] has succeeded and the native decoder is loaded. */
    @get:Synchronized
    override val isPrepared: Boolean get() = recognizer != null

    /**
     * Loads the native decoder. Idempotent and thread-safe: the settings card verifies the engine
     * on startup while a press may initialize the same engine on the recognition thread, and both
     * must end up sharing one decoder instead of failing with "already initialized".
     */
    @Synchronized
    override fun prepare() {
        if (recognizer != null) return
        val model = File(modelDirectory, MODEL_FILE)
        val tokens = File(modelDirectory, TOKENS_FILE)
        require(model.isFile && model.length() > 0L) { "Missing $MODEL_FILE" }
        require(tokens.isFile && tokens.length() > 0L) { "Missing $TOKENS_FILE" }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = model.absolutePath),
                tokens = tokens.absolutePath,
                numThreads = 2,
                provider = "cpu"
            )
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    /**
     * Runs one decoding pass. Synchronized because the startup smoke test and a user recording can
     * reach the same decoder from different threads, and one recognizer must not decode two
     * streams at once.
     */
    @Synchronized
    override fun transcribe(samples: FloatArray): String {
        val decoder = checkNotNull(recognizer) { "Call prepare() first" }
        require(samples.isNotEmpty()) { "Empty recording" }
        val stream = decoder.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
            decoder.decode(stream)
            decoder.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    /**
     * Decodes one second of silence through the full native pipeline. Success proves the model
     * loads, the feature extractor runs and the decoder returns without error, so [READY] can be
     * granted. Must be called after [prepare] and off the UI thread.
     */
    override fun smokeTest(): String = transcribe(FloatArray(SAMPLE_RATE))

    @Synchronized
    override fun close() {
        recognizer?.release()
        recognizer = null
    }
}
