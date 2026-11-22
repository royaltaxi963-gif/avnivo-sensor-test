package com.avnivo.translator

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Kotlin tokenizer is a from-scratch reimplementation, so it is pinned to ids produced by the
 * reference `tokenizers` library from the very same `tokenizer.json`. Vectors are the body of the
 * encoded sequence: NLLB's post-processor prepends the source language token and appends EOS, which
 * [NllbEngine] adds itself.
 *
 * Set `AVNIVO_NLLB_MODEL_DIR` to a downloaded model directory to run these; otherwise they skip.
 */
class NllbTokenizerTest {

    @get:Rule val temp = TemporaryFolder()

    private val modelDir: File? by lazy {
        System.getenv("AVNIVO_NLLB_MODEL_DIR")?.let(::File)?.takeIf { File(it, "tokenizer.json").isFile }
    }

    private fun tokenizer(): NllbTokenizer? = modelDir?.let { NllbTokenizer.load(File(it, "tokenizer.json")) }

    private fun requireModel(): NllbTokenizer {
        val loaded = tokenizer()
        assumeTrue("set AVNIVO_NLLB_MODEL_DIR to a downloaded nllb-200-600m-int8 directory", loaded != null)
        return loaded!!
    }

    @Test fun matchesReferenceIds() {
        val tokenizer = requireModel()
        val expected = mapOf(
            "Salom, qalaysiz?" to listOf(56911, 248079, 29809, 248078, 9483, 248130),
            "Bugun havo juda issiq." to listOf(178618, 185557, 39926, 248, 166027, 248075),
            "Men O'zbekistondan keldim." to listOf(2755, 193, 248116, 118981, 290, 74614, 156378, 75, 248075),
            "Здравствуйте, как дела?" to listOf(169771, 248128, 18570, 17568, 248079, 5691, 22282, 248130),
            "hello world" to listOf(133863, 15697),
            "Rahmat" to listOf(21928, 2853),
            "Yaxshi" to listOf(89441, 8642),
            "Qalaysiz?" to listOf(967, 59997, 9483, 248130),
            "Bu kitob juda qiziqarli." to listOf(2056, 99000, 39926, 228338, 22709, 25, 248075),
            "Men tarjimon ilovasini sinab ko'rmoqchiman." to
                listOf(2755, 1609, 8336, 18, 563, 50636, 294, 11577, 248076, 262, 248116, 248067, 44101, 65, 8616, 248075)
        )
        for ((text, ids) in expected) {
            assertEquals("token ids for \"$text\"", ids, tokenizer.encode(text))
        }
    }

    @Test fun roundTripsThroughDecode() {
        val tokenizer = requireModel()
        for (text in listOf("Salom, qalaysiz?", "Rahmat", "hello world")) {
            assertEquals(text, tokenizer.decode(tokenizer.encode(text)))
        }
    }

    @Test fun recognisesTheLanguageTokensTheAppExposes() {
        val tokenizer = requireModel()
        assertNotNull(tokenizer.tokenId(NllbEngine.UZBEK))
        assertNotNull(tokenizer.tokenId(NllbEngine.RUSSIAN))
        assertNotNull(tokenizer.tokenId(NllbEngine.ENGLISH))
        assertEquals(256191, tokenizer.tokenId(NllbEngine.UZBEK))
        assertEquals(256147, tokenizer.tokenId(NllbEngine.RUSSIAN))
        assertEquals(256047, tokenizer.tokenId(NllbEngine.ENGLISH))
    }

    @Test fun rejectsLanguageCodesThatAreNotInTheVocabulary() {
        val tokenizer = requireModel()
        // "uz" is an ordinary BPE piece, and the added-token block also holds `亵` and `<mask>`,
        // so membership in the vocabulary alone must never be treated as a language code.
        assertNotNull("\"uz\" exists as a plain piece", tokenizer.tokenId("uz"))
        assertNull(tokenizer.tokenId("uzb_Cyrl"))
        assertNull(tokenizer.tokenId("xx_YY"))
        for (code in listOf("uzb_Cyrl", "uz", "xx_YY", "uzn_latn", "uznLatn", "\u8912")) {
            assertFalse("$code must be rejected as a language code", tokenizer.supportsLanguage(code))
        }
        for (code in listOf(NllbEngine.UZBEK, NllbEngine.RUSSIAN, NllbEngine.ENGLISH)) {
            assertTrue("$code is a valid NLLB code", tokenizer.supportsLanguage(code))
        }
    }

    @Test fun languageCodesComeFromTheLoadedVocabulary() {
        val tokenizer = requireModel()
        val codes = tokenizer.languageCodes
        // The UI is driven by this list, so it must be the vocabulary's own set and nothing else.
        assertTrue("tokenizer must expose a substantial language set", codes.size >= 200)
        assertEquals("codes must be sorted", codes.sorted(), codes)
        assertEquals("codes must be unique", codes.size, codes.toSet().size)
        assertTrue(NllbEngine.UZBEK in codes)
        assertTrue(NllbEngine.RUSSIAN in codes)
        assertTrue(NllbEngine.ENGLISH in codes)
        // Every code offered to the UI must pass the same gate the engine applies at translate time.
        for (code in codes) {
            assertTrue("$code is offered but rejected by supportsLanguage", tokenizer.supportsLanguage(code))
        }
    }

    @Test fun languageCodesExcludeNonLanguagePieces() {
        val tokenizer = requireModel()
        val codes = tokenizer.languageCodes.toSet()
        // `uz` is an ordinary piece and `<mask>` is an added token: neither is a language.
        assertFalse("uz" in codes)
        assertFalse("<mask>" in codes)
        assertFalse("<unk>" in codes)
        assertFalse("uzb_Cyrl" in codes)
        assertTrue(codes.all { it.matches(Regex("[a-z]{3}_[A-Z][a-z]{3}")) })
    }

    @Test fun everyLanguageCodeHasAnIdInsideTheLanguageBlock() {
        val tokenizer = requireModel()
        for (code in tokenizer.languageCodes) {
            val id = tokenizer.tokenId(code)
            assertNotNull("$code has no id", id)
            assertTrue("$code id $id is outside the language block", id!! in 256001..256202)
        }
    }

    @Test fun unknownPiecesFallBackToUnk() {
        val tokenizer = requireModel()
        val unk = tokenizer.tokenId("<unk>")
        assertNotNull(unk)
        // Private-use code points have no vocabulary entry, so they must surface as unk rather
        // than being silently dropped.
        assertTrue("unmapped input must produce an unk id", unk in tokenizer.encode("\uE000\uE001"))
    }

    @Test fun missingFileReturnsNullInsteadOfThrowing() {
        assertNull(NllbTokenizer.load(File(temp.root, "absent.json")))
    }

    @Test fun malformedFileReturnsNullInsteadOfThrowing() {
        val bad = File(temp.root, "tokenizer.json")
        bad.writeText("""{"model":{"type":"Unigram"}}""")
        assertNull(NllbTokenizer.load(bad))
        bad.writeText("not json at all")
        assertNull(NllbTokenizer.load(bad))
    }

    @Test fun engineFailsClosedWithoutModels() {
        val engine = NllbEngine(temp.root)
        val failed = try {
            engine.prepare()
            false
        } catch (expected: Exception) {
            true
        }
        engine.close()
        assertTrue("prepare() must fail when encoder/decoder files are absent", failed)
    }

    @Test fun engineRejectsUnsupportedLanguageBeforeTouchingNativeState() {
        val dir = modelDir ?: return
        val engine = NllbEngine(dir)
        // No prepare(): the language gate must fire before the engine is ever initialized, so a
        // bad UI code can never reach the native runtime.
        val failed = try {
            engine.translate("Salom", "uz", NllbEngine.RUSSIAN)
            false
        } catch (expected: IllegalArgumentException) {
            true
        } finally {
            engine.close()
        }
        assertTrue("an unknown language code must be rejected, not guessed", failed)
    }

    @Test fun engineRejectsBlankInput() {
        val dir = modelDir ?: return
        val engine = NllbEngine(dir)
        val failed = try {
            engine.translate("   ", NllbEngine.UZBEK, NllbEngine.RUSSIAN)
            false
        } catch (expected: IllegalArgumentException) {
            true
        } finally {
            engine.close()
        }
        assertTrue("blank input must be rejected", failed)
    }

    @Test fun statusJsonIsWellFormedForEveryModule() {
        val manager = OfflineModuleManager(temp.root, null) { _, _ -> }
        try {
            for (spec in OfflineModelSpecs.all.values) {
                val status = JSONObject(manager.status(spec.id) ?: "{}")
                assertEquals(spec.id, status.getString("id"))
                assertEquals("not_installed", status.getString("state"))
                assertEquals(false, status.getBoolean("active"))
            }
            assertEquals("every catalog card must have a native spec", 6, OfflineModelSpecs.all.size)
        } finally {
            manager.close()
        }
    }
}
