package com.avnivo.translator

import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MADLAD-400 tokenizer: a SentencePiece **Unigram** model with a Metaspace pre-tokenizer.
 *
 * This is a different algorithm from [NllbTokenizer], which is BPE. Unigram scores every candidate
 * segmentation and keeps the highest total log-probability via Viterbi, so it cannot be reproduced
 * by merging adjacent pairs. The vocabulary and scores are read from the model's own
 * `tokenizer.json`, never hardcoded, so the tokenizer always matches the downloaded model.
 *
 * The only normalizer in the shipped file collapses runs of spaces, which is reproduced exactly;
 * there is no NFKC step in this model.
 */
internal class MadladTokenizer private constructor(
    private val vocabulary: Map<String, Int>,
    private val scores: Map<String, Float>,
    private val unkId: Int
) {
    private val cache = HashMap<String, List<Int>>()

    fun tokenId(piece: String): Int? = vocabulary[piece]

    /**
     * The `<2xx>` target-language tags this model exposes, e.g. `<2ru>` or `<2fr_CA>`.
     *
     * Membership in the vocabulary is not enough on its own: the vocabulary also contains the bare
     * `<2>` tag and noise tags like `<2zxx_xx_dtynoise>`, so an empty or malformed code would
     * otherwise validate and produce a prompt with no real language.
     */
    fun supportsLanguage(code: String): Boolean =
        LANGUAGE_CODE.matches(code) && tokenId("<2$code>") != null

    fun encode(text: String): List<Int> {
        cache[text]?.let { return it }
        // `replace(Regex, ...)` is required here: the String overload is a literal match and would
        // never collapse a run of spaces.
        val normalized = text.trim().replace(SPACE_RUN, " ").replace(' ', METASPACE)
        val input = METASPACE + normalized
        val pieces = viterbi(input) ?: return emptyList()
        return pieces.also { cache[text] = it }
    }

    /**
     * Viterbi over the character positions of [input], maximising the summed piece scores.
     *
     * A character with no covering piece falls back to a single-character step scored below any
     * real piece, so one stray character degrades that character to `<unk>` instead of failing the
     * whole sentence. The shipped model has `byte_fallback: false`, so this is the only way an
     * unknown character can be represented.
     */
    private fun viterbi(input: String): List<Int>? {
        val n = input.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val back = IntArray(n + 1) { -1 }
        best[0] = 0f
        for (end in 1..n) {
            val start = maxOf(0, end - MAX_PIECE_LENGTH)
            for (from in start until end) {
                if (best[from] == Float.NEGATIVE_INFINITY) continue
                val score = scores[input.substring(from, end)] ?: continue
                val candidate = best[from] + score
                if (candidate > best[end]) {
                    best[end] = candidate
                    back[end] = from
                }
            }
            if (back[end] < 0 && best[end - 1] != Float.NEGATIVE_INFINITY) {
                best[end] = best[end - 1] + UNKNOWN_CHARACTER_SCORE
                back[end] = end - 1
            }
        }
        if (back[n] < 0) return null
        val ids = ArrayList<Int>()
        var position = n
        while (position > 0) {
            val from = back[position]
            if (from < 0) return null
            val piece = input.substring(from, position)
            ids.add(vocabulary[piece] ?: unkId)
            position = from
        }
        ids.reverse()
        return ids
    }

    fun decode(ids: List<Int>): String {
        val builder = StringBuilder()
        for (id in ids) {
            val piece = pieceFor(id) ?: continue
            builder.append(piece)
        }
        return builder.toString()
            .replace(METASPACE, ' ')
            .trim()
    }

    private fun pieceFor(id: Int): String? = idToPiece[id]

    private val idToPiece: List<String> by lazy {
        val table = arrayOfNulls<String>(vocabulary.size)
        for ((piece, id) in vocabulary) if (id in table.indices) table[id] = piece
        table.map { it ?: "" }
    }

    companion object {
        private const val METASPACE = '\u2581'
        private val SPACE_RUN = Regex(" {2,}")

        /**
         * Longest piece in the shipped vocabulary is 18 characters, so 24 bounds the Viterbi inner
         * loop without truncating any real piece.
         */
        private const val MAX_PIECE_LENGTH = 24

        /**
         * Below every real piece score, which are log-probabilities and therefore <= 0, so the
         * unknown-character step is only taken when nothing else covers the position.
         */
        private const val UNKNOWN_CHARACTER_SCORE = -1.0e4f

        /** Base code plus optional script/region subtags, as MADLAD's tag list uses. */
        private val LANGUAGE_CODE = Regex("[a-z]{2,3}(_[A-Z][a-z]{3}|_[A-Z]{2})*")

        /** Returns null when the file is absent or not a Unigram model. */
        fun load(file: File): MadladTokenizer? {
            if (!file.isFile) return null
            return runCatching {
                val model = JSONObject(file.readText()).getJSONObject("model")
                if (model.optString("type") != "Unigram") return@runCatching null
                val entries = model.getJSONArray("vocab")
                val vocabulary = HashMap<String, Int>(entries.length() * 2)
                val scores = HashMap<String, Float>(entries.length() * 2)
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONArray(i)
                    val piece = entry.getString(0)
                    vocabulary[piece] = i
                    scores[piece] = entry.getDouble(1).toFloat()
                }
                MadladTokenizer(vocabulary, scores, model.optInt("unk_id", 0))
            }.getOrNull()
        }
    }
}

/**
 * Offline MADLAD-400 3B translation on INT4 ONNX graphs. Greedy and deterministic; the native
 * side owns the ONNX Runtime sessions, this class owns the tokenizer and the prompt convention.
 *
 * MADLAD expects the `<2xx>` target tag to prefix the **source** text, and the decoder to start
 * from `decoder_start_token_id` alone. Seeding the decoder with the tag instead silently drops
 * the first output token.
 */
internal class MadladEngine(
    private val directory: File,
    private val maxNewTokens: Int = 64
) : java.io.Closeable {
    private val loaded = AtomicBoolean(false)
    private val handle = AtomicLong(0L)

    private val tokenizer: MadladTokenizer by lazy {
        MadladTokenizer.load(File(directory, "tokenizer.json")) ?: error("MADLAD tokenizer is missing")
    }

    private val encoderFile: File get() = File(directory, "onnx/encoder_int4.onnx")
    private val decoderFile: File get() = File(directory, "onnx/decoder_int4.onnx")

    fun prepare() {
        require(ensureNativeLoaded()) { "MADLAD native library is unavailable" }
        check(encoderFile.isFile) { "Missing encoder model" }
        check(decoderFile.isFile) { "Missing decoder model" }
        check(tokenizer.tokenId("<unk>") != null) { "Tokenizer vocabulary is empty" }
        val opened = nativeCreate(encoderFile.absolutePath, decoderFile.absolutePath)
        check(opened != 0L) { "Could not open the MADLAD ONNX sessions" }
        handle.set(opened)
        loaded.set(true)
    }

    var lastInferenceMillis: Long = 0L
        private set

    var lastDecodedTokens: Int = 0
        private set

    private val history = mutableListOf<Pair<String, Long>>()
    val inferenceHistory: List<Pair<String, Long>> get() = history

    fun supportsLanguage(code: String): Boolean = tokenizer.supportsLanguage(code)

    fun translate(text: String, source: String, target: String): String {
        require(text.isNotBlank()) { "Nothing to translate" }
        require(supportsLanguage(target)) { "Unsupported target language: $target" }
        check(loaded.get()) { "Engine not initialized" }

        val sourceIds = tokenizer.encode("<2$target> $text")
        check(sourceIds.isNotEmpty()) { "Tokenizer produced no tokens" }
        val native = handle.get()
        check(native != 0L) { "Engine not initialized" }
        val started = System.currentTimeMillis()
        val generated = nativeTranslate(native, sourceIds.toIntArray(), maxNewTokens)
        lastInferenceMillis = System.currentTimeMillis() - started
        lastDecodedTokens = generated.size
        history += text to lastInferenceMillis
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
        val open = handle.getAndSet(0L)
        if (open != 0L) runCatching { nativeFree(open) }
    }

    private external fun nativeCreate(encoderPath: String, decoderPath: String): Long

    private external fun nativeFree(handle: Long)

    private external fun nativeTranslate(handle: Long, sourceIds: IntArray, maxNewTokens: Int): IntArray

    companion object {
        @Volatile private var nativeLoaded = false

        internal fun ensureNativeLoaded(): Boolean {
            if (nativeLoaded) return true
            return try {
                System.loadLibrary("avnivo_madlad")
                nativeLoaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }

        const val UZBEK = "uz"
        const val RUSSIAN = "ru"
        const val ENGLISH = "en"

        /** MADLAD emits `</s>` (2) to stop and starts from id 0; `pad` is 1. */
        const val EOS_ID = 2
        const val DECODER_START_ID = 0
    }
}
