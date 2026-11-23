package com.avnivo.translator

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NllbLanguageRegistryTest {

    @Test
    fun registryHasExactlyTheTokenizerCounts() {
        assertEquals(202, NllbLanguageRegistry.CODE_COUNT)
        assertEquals(202, NllbLanguageRegistry.languages.size)
        assertEquals(196, NllbLanguageRegistry.BASE_LANGUAGE_COUNT)
        assertEquals(196, NllbLanguageRegistry.languages.map { it.code.substringBefore('_') }.toSet().size)
        assertEquals("codes must be unique", 202, NllbLanguageRegistry.languages.map { it.code }.toSet().size)
        assertEquals("codes must be sorted", NllbLanguageRegistry.languages.map { it.code }.sorted(),
            NllbLanguageRegistry.languages.map { it.code })
    }

    @Test
    fun everyCodeHasANameScriptAndLocale() {
        for (language in NllbLanguageRegistry.languages) {
            assertTrue("${language.code} matches the NLLB shape", language.code.matches(Regex("[a-z]{3}_[A-Za-z]{4}")))
            assertTrue("${language.code} has a name", language.name.isNotBlank())
            assertTrue("${language.code} has a script", language.script.isNotBlank())
            assertTrue("${language.code} has a locale", language.locale.isNotBlank())
            assertTrue("${language.code} script matches its suffix", language.scriptCode == language.code.substringAfter('_'))
        }
    }

    @Test
    fun scriptsAreDistinguishedForTheSameLanguage() {
        assertEquals("Arabic", NllbLanguageRegistry.find("ace_Arab")!!.script)
        assertEquals("Latin", NllbLanguageRegistry.find("ace_Latn")!!.script)
        assertEquals("Arabic", NllbLanguageRegistry.find("kas_Arab")!!.script)
        assertEquals("Devanagari", NllbLanguageRegistry.find("kas_Deva")!!.script)
        assertEquals("Traditional Chinese", NllbLanguageRegistry.find("zho_Hant")!!.script)
        assertEquals("Simplified Chinese", NllbLanguageRegistry.find("zho_Hans")!!.script)
        assertEquals("Tifinagh", NllbLanguageRegistry.find("taq_Tfng")!!.script)
        assertEquals("Bengali", NllbLanguageRegistry.find("sat_Beng")!!.script)
        assertEquals("Cyrillic", NllbLanguageRegistry.find("rus_Cyrl")!!.script)
    }

    @Test
    fun knownNamesAreCorrect() {
        assertEquals("Northern Uzbek", NllbLanguageRegistry.find("uzn_Latn")!!.name)
        assertEquals("English", NllbLanguageRegistry.find("eng_Latn")!!.name)
        assertEquals("Russian", NllbLanguageRegistry.find("rus_Cyrl")!!.name)
        assertEquals("Hindi", NllbLanguageRegistry.find("hin_Deva")!!.name)
        assertEquals("Bengali", NllbLanguageRegistry.find("ben_Beng")!!.name)
        assertEquals("Modern Standard Arabic", NllbLanguageRegistry.find("arb_Arab")!!.name)
        assertEquals("Yue Chinese", NllbLanguageRegistry.find("yue_Hant")!!.name)
    }

    @Test
    fun whisperMappingIsSeparateFromNllbCodes() {
        assertEquals("uz", NllbLanguageRegistry.whisperCode("uzn_Latn"))
        assertEquals("en", NllbLanguageRegistry.whisperCode("eng_Latn"))
        assertEquals("ru", NllbLanguageRegistry.whisperCode("rus_Cyrl"))
        assertEquals("hi", NllbLanguageRegistry.whisperCode("hin_Deva"))
        assertEquals("bn", NllbLanguageRegistry.whisperCode("ben_Beng"))
        assertEquals("sq", NllbLanguageRegistry.whisperCode("als_Latn"))
        assertEquals("no", NllbLanguageRegistry.whisperCode("nob_Latn"))
        assertNull("Whisper has no Santali language", NllbLanguageRegistry.whisperCode("sat_Beng"))
        assertNull("Whisper has no Acehnese language", NllbLanguageRegistry.whisperCode("ace_Arab"))
        // The registry must not assume NLLB code == Whisper code.
        assertFalse(NllbLanguageRegistry.whisperCode("rus_Cyrl") == "rus_Cyrl")
    }

    @Test
    fun jsonContainsAllCodesAndMetadata() {
        val root = JSONObject(NllbLanguageRegistry.json())
        assertEquals(202, root.getInt("codeCount"))
        assertEquals(196, root.getInt("baseLanguageCount"))
        assertEquals(NllbLanguageRegistry.TOKENIZER_SHA256, root.getString("tokenizerSha256"))
        val array = root.getJSONArray("languages")
        assertEquals(202, array.length())
        val codes = (0 until array.length()).map { array.getJSONObject(it).getString("code") }.toSet()
        assertEquals(NllbLanguageRegistry.languages.map { it.code }.toSet(), codes)
    }

    @Test
    fun registryMatchesTheRealTokenizerWhenAvailable() {
        val modelDir = System.getenv("AVNIVO_NLLB_MODEL_DIR")?.let(::File)
        val tokenizer = modelDir?.let { NllbTokenizer.load(File(it, "tokenizer.json")) }
        assumeTrue("set AVNIVO_NLLB_MODEL_DIR to audit against the real tokenizer", tokenizer != null)
        val tokenizerCodes = tokenizer!!.languageCodes
        assertEquals("registry and tokenizer must contain the same 202 codes",
            tokenizerCodes, NllbLanguageRegistry.languages.map { it.code })
        for (code in tokenizerCodes) {
            assertTrue("tokenizer must accept $code", tokenizer.supportsLanguage(code))
            assertNotNull("registry must describe $code", NllbLanguageRegistry.find(code))
        }
    }
}
