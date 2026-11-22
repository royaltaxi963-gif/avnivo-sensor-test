package com.avnivo.translator

import android.content.Context
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.Closeable
import java.io.File

/**
 * Offline Uzbek speech synthesis using Silero `v5_cis_base` (MIT) and its `uzb_saida` speaker.
 *
 * The model is a TorchScript Lite artifact whose forward pass takes token ids only; the text
 * frontend that turns Uzbek text into those ids lives in [SileroTtsTokenizer]. The scripted graph
 * returns `(audio, durations)`: `audio` is a 48 kHz float tensor shaped `[1, samples]`, and the
 * second value is the per-token duration prediction, not a sample rate.
 */
internal class SileroTtsEngine(
    private val modelFile: File,
    private val speakerId: Int = SileroTtsTokenizer.UZBEK_SPEAKER_ID
) : Closeable {

    companion object {
        const val SAMPLE_RATE = 48_000
        /** Bounded so one request cannot allocate an unbounded PCM buffer. */
        const val MAX_TEXT_CHARS = 400
    }

    private var module: Module? = null

    @Synchronized
    fun prepare() {
        if (module != null) return
        check(modelFile.isFile) { "Silero model file is missing" }
        module = LiteModuleLoader.load(modelFile.absolutePath)
    }

    /**
     * Synthesizes [text] to mono PCM in [-1, 1] at [SAMPLE_RATE].
     * Throws rather than returning silence, so a failed synthesis is never played as if it worked.
     */
    @Synchronized
    fun synthesize(text: String): FloatArray {
        val loaded = module ?: error("Silero engine is not prepared")
        require(text.isNotBlank()) { "Nothing to synthesize" }
        require(text.length <= MAX_TEXT_CHARS) { "Text is too long for one synthesis pass" }
        val ids = SileroTtsTokenizer.tokenize(text)
        val tokens = LongArray(ids.size) { ids[it].toLong() }
        val inputs = IValue.from(Tensor.fromBlob(tokens, longArrayOf(1, tokens.size.toLong())))
        val speakers = IValue.from(Tensor.fromBlob(longArrayOf(speakerId.toLong()), longArrayOf(1)))
        val output = synchronized(this) { loaded.forward(inputs, speakers) }
        val audio = (if (output.isTuple) output.toTuple()[0] else output).toTensor()
        val samples = audio.shape().last().toInt()
        check(samples > 0) { "Model returned no audio" }
        // getDataAsFloatArray() returns a detached copy of the flat tensor data.
        val pcm = audio.dataAsFloatArray
        return if (pcm.size == samples) pcm else pcm.copyOf(samples)
    }

    /** Loads the model and speaks a short phrase, so READY means the graph really runs. */
    fun smokeTest() {
        val pcm = synthesize("salom")
        check(pcm.size > SAMPLE_RATE / 10) { "Smoke synthesis returned too little audio" }
        check(pcm.any { it != 0f }) { "Smoke synthesis returned silence" }
    }

    @Synchronized
    override fun close() {
        module = null
    }
}
