package com.avnivo.translator

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NLLB-200 tokenizer rebuilt from the model's own `tokenizer.json`: a SentencePiece BPE with a
 * Metaspace pre-tokenizer. The vocabulary and merge ranks are read from the file, never hardcoded,
 * so the tokenizer always matches the downloaded model.
 *
 * The shipped `precompiled_charsmap` normalizer is approximated with Unicode NFKC, which produces
 * identical ids for the languages this app exposes; that is asserted by the tokenizer tests.
 */
internal class NllbTokenizer private constructor(
    private val vocabulary: Map<String, Int>,
    private val mergeRanks: Map<String, Int>,
    private val unkId: Int
) {
    private val cache = HashMap<String, List<Int>>()

    fun tokenId(piece: String): Int? = vocabulary[piece]

    /**
     * True only for a real NLLB language code. Membership in the vocabulary is not enough: it also
     * holds ordinary pieces like `uz` plus the `亵`/`<mask>` added tokens, so the id must also fall
     * inside the language block.
     */
    fun supportsLanguage(code: String): Boolean =
        LANGUAGE_CODE.matches(code) && (tokenId(code) ?: 0) in LANGUAGE_ID_RANGE

    fun encode(text: String): List<Int> {
        cache[text]?.let { return it }
        // SentencePiece always prepends the word marker, so the first word is a word start too.
        val normalized = METASPACE + Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
            .replace(SPACE, METASPACE)
        val pieces = mutableListOf<Int>()
        // Metaspace splits on the replacement char; every chunk is BPE-encoded separately, and the
        // leading marker is kept so word starts stay distinguishable.
        var index = 0
        while (index < normalized.length) {
            var end = normalized.indexOf(METASPACE, index + 1)
            if (end < 0) end = normalized.length
            for (symbol in bpe(normalized.substring(index, end))) {
                pieces += vocabulary[symbol] ?: unkId
            }
            index = end
        }
        return pieces.also { cache[text] = it }
    }

    /** Merges the best-ranked adjacent pair repeatedly, exactly as SentencePiece BPE does. */
    private fun bpe(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        val symbols = chunk.map { it.toString() }.toMutableList()
        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIndex = -1
            for (i in 0 until symbols.size - 1) {
                val rank = mergeRanks[symbols[i] + PAIR_SEPARATOR + symbols[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = i
                }
            }
            if (bestIndex < 0) break
            symbols[bestIndex] = symbols[bestIndex] + symbols[bestIndex + 1]
            symbols.removeAt(bestIndex + 1)
        }
        return symbols
    }

    fun decode(ids: List<Int>): String {
        val builder = StringBuilder()
        for (id in ids) {
            val piece = reverse[id] ?: continue
            builder.append(piece)
        }
        return builder.toString()
            .replace(METASPACE.toString(), " ")
            .trim()
    }

    private val reverse: Map<Int, String> by lazy {
        vocabulary.entries.associate { (piece, id) -> id to piece }
    }

    companion object {
        private val LANGUAGE_CODE = Regex("[a-z]{3}_[A-Za-z]{4}")
        private val LANGUAGE_ID_RANGE = 256001..256202
        private const val METASPACE = '\u2581'
        private const val SPACE = ' '
        private const val PAIR_SEPARATOR = '\u0000'

        /** Parses `tokenizer.json`; returns null when the file is missing or not a BPE tokenizer. */
        fun load(file: File): NllbTokenizer? {
            if (!file.isFile) return null
            return runCatching {
                val model = JSONObject(file.readText()).getJSONObject("model")
                if (model.optString("type") != "BPE") return@runCatching null
                val vocabJson = model.getJSONObject("vocab")
                val vocabulary = HashMap<String, Int>(vocabJson.length() * 2)
                for (piece in vocabJson.keys()) vocabulary[piece] = vocabJson.getInt(piece)
                val mergesJson = model.getJSONArray("merges")
                val mergeRanks = HashMap<String, Int>(mergesJson.length() * 2)
                for (i in 0 until mergesJson.length()) {
                    val pair = mergePair(mergesJson, i) ?: continue
                    mergeRanks[pair.first + PAIR_SEPARATOR + pair.second] = i
                }
                val unk = vocabulary[model.optString("unk_token", "<unk>")] ?: 3
                NllbTokenizer(vocabulary, mergeRanks, unk)
            }.getOrNull()
        }

        /** Merges are `"a b"` in older exports and `["a", "b"]` in newer ones. */
        private fun mergePair(merges: JSONArray, index: Int): Pair<String, String>? {
            val entry = merges.opt(index)
            if (entry is JSONArray) {
                return if (entry.length() == 2) entry.getString(0) to entry.getString(1) else null
            }
            val text = entry?.toString() ?: return null
            val parts = text.split(' ')
            return if (parts.size == 2) parts[0] to parts[1] else null
        }
    }
}

/**
 * Offline NLLB-200 translation. Decoding is greedy and deterministic; the native side owns the
 * ONNX Runtime session, this class owns the tokenizer and language-code validation.
 */
internal class NllbEngine(
    private val directory: File,
    private val maxNewTokens: Int = 48
) : java.io.Closeable {
    private val loaded = AtomicBoolean(false)

    private val tokenizer: NllbTokenizer by lazy {
        NllbTokenizer.load(File(directory, "tokenizer.json")) ?: error("NLLB tokenizer is missing")
    }

    private val encoderFile: File get() = File(directory, "onnx/encoder_model_quantized.onnx")
    private val decoderFile: File get() = File(directory, "onnx/decoder_model_merged_quantized.onnx")

    fun prepare() {
        require(ensureNativeLoaded()) { "NLLB native library is unavailable" }
        check(encoderFile.isFile) { "Missing encoder model" }
        check(decoderFile.isFile) { "Missing decoder model" }
        check(tokenizer.tokenId("<unk>") != null) { "Tokenizer vocabulary is empty" }
        loaded.set(true)
    }

    fun supportsLanguage(code: String): Boolean = tokenizer.supportsLanguage(code)

    fun translate(text: String, source: String, target: String): String {
        require(text.isNotBlank()) { "Nothing to translate" }
        require(supportsLanguage(source)) { "Unsupported source language: $source" }
        require(supportsLanguage(target)) { "Unsupported target language: $target" }
        check(loaded.get()) { "Engine not initialized" }

        val sourceIds = mutableListOf(tokenizer.tokenId(source)!!)
        sourceIds += tokenizer.encode(text)
        sourceIds += EOS_ID

        val targetBos = tokenizer.tokenId(target)!!
        val generated = nativeTranslate(
            encoderFile.absolutePath,
            decoderFile.absolutePath,
            sourceIds.toIntArray(),
            targetBos,
            maxNewTokens
        )
        if (generated.isEmpty()) error("Model produced no output")
        return tokenizer.decode(generated.toList())
    }

    /** Runs a fixed phrase so READY is only granted after real inference, not just file checks. */
    fun smokeTest() {
        val output = translate("Salom", UZBEK, UZBEK)
        check(output.isNotEmpty()) { "Smoke translation returned nothing" }
    }

    override fun close() {
        loaded.set(false)
    }

    private external fun nativeTranslate(
        encoderPath: String,
        decoderPath: String,
        sourceIds: IntArray,
        targetBosId: Int,
        maxNewTokens: Int
    ): IntArray

    companion object {
        @Volatile private var nativeLoaded = false

        /** Loads the JNI library on first use so constructing the engine cannot throw. */
        internal fun ensureNativeLoaded(): Boolean {
            if (nativeLoaded) return true
            return try {
                System.loadLibrary("avnivo_nllb")
                nativeLoaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }

        const val UZBEK = "uzn_Latn"
        const val RUSSIAN = "rus_Cyrl"
        const val ENGLISH = "eng_Latn"
        private const val EOS_ID = 2
    }
}
