package com.avnivo.translator

/** The two offline recognizers the app may use. Only one is active in a recording session. */
internal enum class OfflineSttEngine(val moduleId: String) {
    UZBEK_STT("uzbek-stt"),
    WHISPER_SMALL_Q8("whisper-small-q8")
}

/** NLLB codes are not Whisper codes; every translation goes through this explicit map. */
internal object WhisperLanguageMap {
    fun codeFor(nllbCode: String?): String? = NllbLanguageRegistry.whisperCode(nllbCode)
}

internal data class SttRoute(
    val engine: OfflineSttEngine,
    val whisperLanguage: String?,
    val unsupportedReason: String? = null,
    val sourceLanguage: String? = null
)

/**
 * The user's fixed routing rule:
 *   source == Uzbek or English -> Uzbek STT
 *   every other NLLB source     -> Whisper Small Q8
 * The target language never participates. This is intentionally separate from the NLLB registry:
 * an STT engine's language coverage must not remove or reorder offline translation languages.
 */
internal object OfflineSttRouter {
    const val UZBEK = "uzn_Latn"
    const val ENGLISH = "eng_Latn"

    fun engineFor(sourceLanguage: String?): OfflineSttEngine =
        if (sourceLanguage == UZBEK || sourceLanguage == ENGLISH) {
            OfflineSttEngine.UZBEK_STT
        } else {
            OfflineSttEngine.WHISPER_SMALL_Q8
        }

    fun route(sourceLanguage: String?): SttRoute {
        require(!sourceLanguage.isNullOrBlank()) { "Manba tili tanlanmagan" }
        val engine = engineFor(sourceLanguage)
        if (engine == OfflineSttEngine.UZBEK_STT) return SttRoute(engine, null, sourceLanguage = sourceLanguage)
        val whisper = WhisperLanguageMap.codeFor(sourceLanguage)
        return if (whisper == null) {
            SttRoute(
                engine,
                null,
                "Whisper Small bu tilni qo‘llab-quvvatlamaydi: $sourceLanguage. " +
                    "Til offline tarjimada qoladi, lekin nutqni tanish uchun mos STT modeli yo‘q.",
                sourceLanguage = sourceLanguage
            )
        } else {
            SttRoute(engine, whisper, sourceLanguage = sourceLanguage)
        }
    }
}

/**
 * Keeps the one-engine-per-session invariant and the session -> language mapping. It deliberately
 * performs no I/O: the caller releases the previous engine through [Opened.previous] and owns the
 * native recognizers. Tests exercise the same lifecycle with fakes.
 */
internal class OfflineSttSessionRouter(
    private val uzbekAvailable: () -> Boolean,
    private val whisperAvailable: () -> Boolean
) {
    data class Opened(val session: Int, val route: SttRoute, val previous: OfflineSttEngine?)

    private val routes = LinkedHashMap<Int, SttRoute>()
    private var activeSession = 0
    private var residentEngine: OfflineSttEngine? = null
    private var lastSession = 0

    @Synchronized
    fun open(session: Int, sourceLanguage: String?): Result<Opened> {
        if (session <= 0) return Result.failure(IllegalArgumentException("Yozuv sessiyasi raqami noto‘g‘ri"))
        if (activeSession != 0 && activeSession != session) {
            return Result.failure(IllegalStateException("Oldingi yozuv sessiyasi hali yopilmagan"))
        }
        val route = runCatching { OfflineSttRouter.route(sourceLanguage) }
            .getOrElse { return Result.failure(it) }
        if (route.unsupportedReason != null) {
            return Result.failure(IllegalArgumentException(route.unsupportedReason))
        }
        val available = when (route.engine) {
            OfflineSttEngine.UZBEK_STT -> uzbekAvailable()
            OfflineSttEngine.WHISPER_SMALL_Q8 -> whisperAvailable()
        }
        if (!available) {
            val name = if (route.engine == OfflineSttEngine.UZBEK_STT) "O‘zbek STT" else "Whisper Small Q8"
            return Result.failure(
                IllegalStateException("$name modeli yuklab olinmagan. Avtomatik yuklab olish boshlanmadi.")
            )
        }
        val previous = residentEngine?.takeIf { it != route.engine }
        residentEngine = route.engine
        activeSession = session
        lastSession = maxOf(lastSession, session)
        routes[session] = route
        return Result.success(Opened(session, route, previous))
    }

    @Synchronized
    fun routeFor(session: Int): SttRoute? = routes[session]

    /** The capture/result for this session is finished; stale results can no longer find a route. */
    @Synchronized
    fun close(session: Int) {
        routes.remove(session)
        if (activeSession == session) activeSession = 0
    }

    /** Releases all session state, but the resident engine is owned by the native layer. */
    @Synchronized
    fun closeAll() {
        routes.clear()
        activeSession = 0
        residentEngine = null
    }

    @Synchronized
    fun activeSession(): Int = activeSession

    @Synchronized
    fun residentEngine(): OfflineSttEngine? = residentEngine
}
