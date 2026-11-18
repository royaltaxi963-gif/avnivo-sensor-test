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

    fun prepare() {
        check(recognizer == null) { "Recognizer already initialized" }
        val model = File(modelDirectory, "model.int8.onnx")
        val tokens = File(modelDirectory, "tokens.txt")
        require(model.isFile && model.length() > 0L) { "Missing model.int8.onnx" }
        require(tokens.isFile && tokens.length() > 0L) { "Missing tokens.txt" }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
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
            stream.acceptWaveform(samples, sampleRate = 16000)
            decoder.decode(stream)
            decoder.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
    }
}
