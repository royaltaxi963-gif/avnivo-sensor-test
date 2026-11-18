package com.avnivo.translator

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.Closeable
import java.io.File

/** Offline-only NeMo CTC decoder; all methods must be called off the UI thread. */
class OfflineSpeechEngine(private val modelDirectory: File) : Closeable {
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

    fun prepare() {
        check(recognizer == null) { "Recognizer already initialized" }
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

    fun transcribe(samples: FloatArray): String {
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
    fun smokeTest(): String = transcribe(FloatArray(SAMPLE_RATE))

    override fun close() {
        recognizer?.release()
        recognizer = null
    }
}
