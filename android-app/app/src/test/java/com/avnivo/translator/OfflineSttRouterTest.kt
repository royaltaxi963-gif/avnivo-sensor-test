package com.avnivo.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
internal class OfflineSttRouterTest(
    private val source: String,
    private val engine: OfflineSttEngine,
    private val whisperLanguage: String?
) {
    companion object {
        /** 50 routing cases: the examples from the task plus every major NLLB script. */
        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> {1}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf("uzn_Latn", OfflineSttEngine.UZBEK_STT, null),
            arrayOf("eng_Latn", OfflineSttEngine.UZBEK_STT, null),
            arrayOf("rus_Cyrl", OfflineSttEngine.WHISPER_SMALL_Q8, "ru"),
            arrayOf("hin_Deva", OfflineSttEngine.WHISPER_SMALL_Q8, "hi"),
            arrayOf("ben_Beng", OfflineSttEngine.WHISPER_SMALL_Q8, "bn"),
            arrayOf("deu_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "de"),
            arrayOf("fra_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "fr"),
            arrayOf("spa_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "es"),
            arrayOf("por_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "pt"),
            arrayOf("ita_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "it"),
            arrayOf("jpn_Jpan", OfflineSttEngine.WHISPER_SMALL_Q8, "ja"),
            arrayOf("kor_Hang", OfflineSttEngine.WHISPER_SMALL_Q8, "ko"),
            arrayOf("zho_Hans", OfflineSttEngine.WHISPER_SMALL_Q8, "zh"),
            arrayOf("zho_Hant", OfflineSttEngine.WHISPER_SMALL_Q8, "zh"),
            arrayOf("arb_Arab", OfflineSttEngine.WHISPER_SMALL_Q8, "ar"),
            arrayOf("pes_Arab", OfflineSttEngine.WHISPER_SMALL_Q8, "fa"),
            arrayOf("tur_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "tr"),
            arrayOf("pol_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "pl"),
            arrayOf("ukr_Cyrl", OfflineSttEngine.WHISPER_SMALL_Q8, "uk"),
            arrayOf("nld_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "nl"),
            arrayOf("swe_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "sv"),
            arrayOf("dan_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "da"),
            arrayOf("nob_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "no"),
            arrayOf("fin_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "fi"),
            arrayOf("ces_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "cs"),
            arrayOf("slk_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "sk"),
            arrayOf("hun_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "hu"),
            arrayOf("ron_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "ro"),
            arrayOf("bul_Cyrl", OfflineSttEngine.WHISPER_SMALL_Q8, "bg"),
            arrayOf("ell_Grek", OfflineSttEngine.WHISPER_SMALL_Q8, "el"),
            arrayOf("heb_Hebr", OfflineSttEngine.WHISPER_SMALL_Q8, "he"),
            arrayOf("urd_Arab", OfflineSttEngine.WHISPER_SMALL_Q8, "ur"),
            arrayOf("tam_Taml", OfflineSttEngine.WHISPER_SMALL_Q8, "ta"),
            arrayOf("tel_Telu", OfflineSttEngine.WHISPER_SMALL_Q8, "te"),
            arrayOf("kan_Knda", OfflineSttEngine.WHISPER_SMALL_Q8, "kn"),
            arrayOf("mal_Mlym", OfflineSttEngine.WHISPER_SMALL_Q8, "ml"),
            arrayOf("guj_Gujr", OfflineSttEngine.WHISPER_SMALL_Q8, "gu"),
            arrayOf("pan_Guru", OfflineSttEngine.WHISPER_SMALL_Q8, "pa"),
            arrayOf("mar_Deva", OfflineSttEngine.WHISPER_SMALL_Q8, "mr"),
            arrayOf("sin_Sinh", OfflineSttEngine.WHISPER_SMALL_Q8, "si"),
            arrayOf("npi_Deva", OfflineSttEngine.WHISPER_SMALL_Q8, "ne"),
            arrayOf("ory_Orya", OfflineSttEngine.WHISPER_SMALL_Q8, "or"),
            arrayOf("asm_Beng", OfflineSttEngine.WHISPER_SMALL_Q8, "as"),
            arrayOf("khm_Khmr", OfflineSttEngine.WHISPER_SMALL_Q8, "km"),
            arrayOf("lao_Laoo", OfflineSttEngine.WHISPER_SMALL_Q8, "lo"),
            arrayOf("mya_Mymr", OfflineSttEngine.WHISPER_SMALL_Q8, "my"),
            arrayOf("tha_Thai", OfflineSttEngine.WHISPER_SMALL_Q8, "th"),
            arrayOf("vie_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "vi"),
            arrayOf("ind_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "id"),
            arrayOf("zsm_Latn", OfflineSttEngine.WHISPER_SMALL_Q8, "ms")
        )
    }

    @Test
    fun sourceLanguageSelectsTheExpectedEngine() {
        val route = OfflineSttRouter.route(source)
        assertEquals(engine, route.engine)
        assertEquals(whisperLanguage, route.whisperLanguage)
        assertNull("supported route must not carry an error", route.unsupportedReason)
    }

    @Test
    fun targetLanguageIsNotPartOfTheRoute() {
        // The router has no target parameter by construction; the source alone decides.
        assertEquals(engine, OfflineSttRouter.engineFor(source))
    }
}

class OfflineSttRouterRulesTest {
    private val pairs = listOf(
        "uzn_Latn" to "eng_Latn",
        "eng_Latn" to "uzn_Latn",
        "uzn_Latn" to "rus_Cyrl",
        "rus_Cyrl" to "uzn_Latn",
        "eng_Latn" to "hin_Deva",
        "hin_Deva" to "eng_Latn",
        "rus_Cyrl" to "ben_Beng",
        "ben_Beng" to "rus_Cyrl"
    )

    @Test
    fun swapKeepsTheEngineOfTheNewSource() {
        for ((first, second) in pairs) {
            assertEquals(OfflineSttRouter.engineFor(first), OfflineSttRouter.route(first).engine)
            assertEquals(OfflineSttRouter.engineFor(second), OfflineSttRouter.route(second).engine)
        }
    }

    @Test
    fun uzbekAndEnglishAreTheOnlyUzbekSttSources() {
        for (code in NllbLanguageRegistry.languages.map { it.code }) {
            val expected = if (code == "uzn_Latn" || code == "eng_Latn") {
                OfflineSttEngine.UZBEK_STT
            } else {
                OfflineSttEngine.WHISPER_SMALL_Q8
            }
            assertEquals("source $code", expected, OfflineSttRouter.engineFor(code))
        }
    }

    @Test
    fun whisperMappingIsExplicitAndNotNllbEquality() {
        assertEquals("ru", OfflineSttRouter.route("rus_Cyrl").whisperLanguage)
        assertEquals("hi", OfflineSttRouter.route("hin_Deva").whisperLanguage)
        assertEquals("bn", OfflineSttRouter.route("ben_Beng").whisperLanguage)
        assertEquals("uz", WhisperLanguageMap.codeFor("uzn_Latn"))
        assertEquals("en", WhisperLanguageMap.codeFor("eng_Latn"))
        assertEquals("NLLB code must not be passed as a Whisper code", "ru", WhisperLanguageMap.codeFor("rus_Cyrl"))
    }

    @Test
    fun unsupportedWhisperLanguageFailsClosedButStaysInTheRegistry() {
        for (code in listOf("ace_Arab", "sat_Beng", "ckb_Arab", "kir_Cyrl")) {
            val route = OfflineSttRouter.route(code)
            assertEquals(OfflineSttEngine.WHISPER_SMALL_Q8, route.engine)
            assertNull("no fake Whisper code for $code", route.whisperLanguage)
            assertNotNull("the limitation must be explicit for $code", route.unsupportedReason)
            assertNotNull("the language must stay selectable for translation", NllbLanguageRegistry.find(code))
        }
    }

    @Test
    fun missingSourceFailsClosed() {
        assertTrue(runCatching { OfflineSttRouter.route(null) }.isFailure)
        assertTrue(runCatching { OfflineSttRouter.route("") }.isFailure)
    }

    @Test
    fun thirtyEngineSwitchesKeepOneResidentEngine() {
        var uzbekAvailable = true
        var whisperAvailable = true
        val router = OfflineSttSessionRouter({ uzbekAvailable }, { whisperAvailable })
        val sources = listOf("uzn_Latn", "rus_Cyrl", "eng_Latn", "hin_Deva", "uzn_Latn", "ben_Beng")
        var releases = 0
        for (i in 1..30) {
            val source = sources[(i - 1) % sources.size]
            val opened = router.open(i, source).getOrThrow()
            if (opened.previous != null) releases++
            assertEquals(OfflineSttRouter.engineFor(source), opened.route.engine)
            assertEquals(i, router.activeSession())
            assertEquals(1, router.residentEngine()?.let { 1 } ?: 0)
            router.close(i)
        }
        assertEquals("every switch releases exactly the previous engine", 29, releases)
        assertEquals(0, router.activeSession())
        assertEquals(OfflineSttEngine.WHISPER_SMALL_Q8, router.residentEngine())
    }

    @Test
    fun sameEngineSessionsDoNotReleaseTheEngine() {
        val router = OfflineSttSessionRouter({ true }, { true })
        val first = router.open(1, "uzn_Latn").getOrThrow()
        assertNull(first.previous)
        router.close(1)
        val second = router.open(2, "eng_Latn").getOrThrow()
        assertNull("both sources use Uzbek STT", second.previous)
        router.close(2)
        val third = router.open(3, "rus_Cyrl").getOrThrow()
        assertEquals(OfflineSttEngine.UZBEK_STT, third.previous)
    }

    @Test
    fun missingModelIsReportedWithoutDownloadOrEngineChange() {
        var downloads = 0
        val router = OfflineSttSessionRouter(
            uzbekAvailable = { false },
            whisperAvailable = { true }
        )
        val result = router.open(1, "uzn_Latn")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Avtomatik yuklab olish") == true)
        assertEquals(0, downloads)
        assertNull(router.residentEngine())
        assertNull(router.routeFor(1))
    }

    @Test
    fun unsupportedWhisperLanguageIsRejectedBeforeCapture() {
        val router = OfflineSttSessionRouter({ true }, { true })
        val result = router.open(1, "ace_Arab")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("qo‘llab-quvvatlamaydi") == true)
        assertNull(router.routeFor(1))
        assertNull(router.residentEngine())
    }

    @Test
    fun staleSessionCannotReturnARoute() {
        val router = OfflineSttSessionRouter({ true }, { true })
        router.open(1, "rus_Cyrl").getOrThrow()
        router.close(1)
        assertNull(router.routeFor(1))
        router.open(2, "uzn_Latn").getOrThrow()
        assertNull("an old session must never resolve after a newer one starts", router.routeFor(1))
        assertEquals(2, router.activeSession())
    }

    @Test
    fun changingLanguageDuringRecordingDoesNotRetargetTheOpenSession() {
        val router = OfflineSttSessionRouter({ true }, { true })
        val opened = router.open(7, "rus_Cyrl").getOrThrow()
        assertEquals("ru", opened.route.whisperLanguage)
        // The page may change offlineChoice now; the native session mapping stays at ru.
        assertEquals("ru", router.routeFor(7)?.whisperLanguage)
        assertEquals(OfflineSttEngine.WHISPER_SMALL_Q8, router.routeFor(7)?.engine)
    }
}
