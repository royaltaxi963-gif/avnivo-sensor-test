package com.avnivo.translator

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * MADLAD's tokenizer is SentencePiece **Unigram**, so the property that matters is that encoding
 * maximises the summed piece score via Viterbi. A greedy longest-match implementation would pass a
 * naive test and still be wrong, so the first test builds a vocabulary where the two disagree.
 *
 * The pinned id vectors come from the reference `tokenizers` library run against the model's own
 * `tokenizer.json`. Set `AVNIVO_MADLAD_MODEL_DIR` to run those; otherwise they skip.
 */
class MadladTokenizerTest {

    @get:Rule val temp = TemporaryFolder()

    private val modelDir: File? by lazy {
        System.getenv("AVNIVO_MADLAD_MODEL_DIR")
            ?.let(::File)
            ?.takeIf { File(it, "tokenizer.json").isFile }
    }

    /** Writes a Unigram `tokenizer.json` with the given (piece, score) pairs. */
    private fun unigram(vararg pieces: Pair<String, Double>): MadladTokenizer {
        val vocab = JSONArray()
        for ((piece, score) in pieces) vocab.put(JSONArray().put(piece).put(score))
        val json = JSONObject()
            .put("model", JSONObject().put("type", "Unigram").put("unk_id", 0).put("vocab", vocab))
        val file = File(temp.root, "tokenizer-${System.nanoTime()}.json")
        file.writeText(json.toString())
        return MadladTokenizer.load(file) ?: error("synthetic tokenizer did not load")
    }

    @Test fun viterbiBeatsGreedyLongestMatch() {
        // "▁abc" alone scores -10. Splitting as "▁ab"+"c" scores -2, which must win even though
        // "▁abc" is the longest single piece. A longest-match tokenizer returns id 0 here.
        val tokenizer = unigram(
            "<unk>" to 0.0,
            "\u2581abc" to -10.0,
            "\u2581ab" to -1.0,
            "c" to -1.0
        )
        assertEquals(listOf(2, 3), tokenizer.encode("abc"))
    }

    @Test fun longestPieceWinsWhenItScoresHigher() {
        val tokenizer = unigram(
            "<unk>" to 0.0,
            "\u2581abc" to -1.0,
            "\u2581ab" to -3.0,
            "c" to -3.0
        )
        assertEquals(listOf(1), tokenizer.encode("abc"))
    }

    @Test fun metaspaceMarksWordStartsAndCollapsesSpaceRuns() {
        // Two spaces must normalise to one, and each word start becomes a ▁ piece.
        val tokenizer = unigram(
            "<unk>" to 0.0,
            "\u2581salom" to -1.0,
            "\u2581dunyo" to -1.0
        )
        assertEquals(listOf(1, 2), tokenizer.encode("salom  dunyo"))
    }

    @Test fun decodeRestoresSpaces() {
        val tokenizer = unigram(
            "<unk>" to 0.0,
            "\u2581salom" to -1.0,
            "\u2581dunyo" to -1.0
        )
        assertEquals("salom dunyo", tokenizer.decode(listOf(1, 2)))
    }

    @Test fun unknownCharacterDegradesToUnkInsteadOfFailing() {
        // 'z' has no piece; the rest of the word must still tokenize normally.
        val tokenizer = unigram(
            "<unk>" to 0.0,
            "\u2581ab" to -1.0,
            "c" to -1.0
        )
        assertEquals(listOf(1, 0, 2), tokenizer.encode("abzc"))
    }

    @Test fun rejectsNonUnigramModel() {
        val json = JSONObject()
            .put("model", JSONObject().put("type", "BPE").put("vocab", JSONObject()))
        val file = File(temp.root, "bpe.json")
        file.writeText(json.toString())
        assertNull(MadladTokenizer.load(file))
    }

    @Test fun missingFileLoadsAsNull() {
        assertNull(MadladTokenizer.load(File(temp.root, "absent.json")))
    }

    // ---- Pinned against the reference tokenizers library on the real tokenizer.json ----

    private fun requireTokenizer(): MadladTokenizer {
        val loaded = modelDir?.let { MadladTokenizer.load(File(it, "tokenizer.json")) }
        assumeTrue("set AVNIVO_MADLAD_MODEL_DIR to a directory containing tokenizer.json", loaded != null)
        return loaded!!
    }

    @Test fun matchesReferenceIdsForUzbekSourceText() {
        val tokenizer = requireTokenizer()
        assertEquals(listOf(84926, 806, 15314, 873, 8631, 843), tokenizer.encode("Salom, qalaysiz?"))
        assertEquals(
            listOf(1640, 3138, 901, 1518, 27385, 14315, 1123, 807),
            tokenizer.encode("Bugun havo juda issiq.")
        )
        assertEquals(
            listOf(1640, 201770, 27385, 200388, 985, 955, 807),
            tokenizer.encode("Bu kitob juda qiziqarli.")
        )
    }

    @Test fun matchesReferenceIdsWithTargetLanguagePrefix() {
        val tokenizer = requireTokenizer()
        // The tag is part of the source text, which is how [MadladEngine] builds the prompt.
        assertEquals(
            listOf(805, 118, 84926, 806, 15314, 873, 8631, 843),
            tokenizer.encode("<2ru> Salom, qalaysiz?")
        )
        assertEquals(
            listOf(805, 38, 3543, 959, 10073, 143844, 942, 23616, 1092, 807),
            tokenizer.encode("<2en> Men ertaga maktabga boraman.")
        )
    }

    @Test fun exposesTheDocumentedLanguageTags() {
        val tokenizer = requireTokenizer()
        assertTrue(tokenizer.supportsLanguage("ru"))
        assertTrue(tokenizer.supportsLanguage("en"))
        assertTrue(tokenizer.supportsLanguage("uz"))
        assertFalse("unrelated codes must not resolve", tokenizer.supportsLanguage("xx"))
        assertEquals(118, tokenizer.tokenId("<2ru>"))
        assertEquals(38, tokenizer.tokenId("<2en>"))
        assertEquals(150, tokenizer.tokenId("<2uz>"))
    }

    /**
     * The vocabulary contains the bare `<2>` tag and noise tags such as `<2zxx_xx_dtynoise>`, so
     * checking only for membership would let an empty or malformed code validate and build a prompt
     * with no real language. The shape check is what closes that.
     */
    @Test fun rejectsEmptyAndMalformedLanguageCodes() {
        val tokenizer = requireTokenizer()
        assertFalse(tokenizer.supportsLanguage(""))
        assertFalse(tokenizer.supportsLanguage("2"))
        assertFalse(tokenizer.supportsLanguage("zxx_xx_dtynoise"))
        assertFalse("a valid tag must not accept trailing junk", tokenizer.supportsLanguage("ru>"))
    }

    @Test fun decodesSinglePiecesLikeTheReference() {
        val tokenizer = requireTokenizer()
        assertEquals("Salom", tokenizer.decode(listOf(84926)))
        assertEquals("<2ru>", tokenizer.decode(listOf(118)))
        assertNotNull(tokenizer.tokenId("<unk>"))
    }
}
