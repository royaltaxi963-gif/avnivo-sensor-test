package com.avnivo.translator

/** The two offline recognizers the app may use. Only one is active in a recording session. */
internal enum class OfflineSttEngine(val moduleId: String) {
    UZBEK_STT("uzbek-stt"),
    WHISPER_SMALL_Q8("whisper-small-q8"),
    WHISPER_LARGE_V3_TURBO("whisper-large-v3-turbo");

    val isWhisper: Boolean get() = this != UZBEK_STT
    val displayName: String
        get() = when (this) {
            UZBEK_STT -> "O‘zbek STT"
            WHISPER_SMALL_Q8 -> "Whisper Small Q8"
            WHISPER_LARGE_V3_TURBO -> "Whisper Large V3 Turbo"
        }
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
 *   every other NLLB source     -> the Whisper module the user selected (Small or Large)
 * The target language never participates. This is intentionally separate from the NLLB registry:
 * an STT engine's language coverage must not remove or reorder offline translation languages.
 */
internal object OfflineSttRouter {
    const val UZBEK = "uzn_Latn"
    const val ENGLISH = "eng_Latn"

    fun engineFor(
        sourceLanguage: String?,
        whisperEngine: OfflineSttEngine = OfflineSttEngine.WHISPER_SMALL_Q8
    ): OfflineSttEngine {
        val selected = whisperEngine.takeIf { it.isWhisper } ?: OfflineSttEngine.WHISPER_SMALL_Q8
        return if (sourceLanguage == UZBEK || sourceLanguage == ENGLISH) {
            OfflineSttEngine.UZBEK_STT
        } else {
            selected
        }
    }

    fun route(
        sourceLanguage: String?,
        whisperEngine: OfflineSttEngine = OfflineSttEngine.WHISPER_SMALL_Q8
    ): SttRoute {
        require(!sourceLanguage.isNullOrBlank()) { "Manba tili tanlanmagan" }
        val engine = engineFor(sourceLanguage, whisperEngine)
        if (engine == OfflineSttEngine.UZBEK_STT) return SttRoute(engine, null, sourceLanguage = sourceLanguage)
        val whisper = WhisperLanguageMap.codeFor(sourceLanguage)
        return if (whisper == null) {
            SttRoute(
                engine,
                null,
                "${engine.displayName} bu tilni qo‘llab-quvvatlamaydi: $sourceLanguage. " +
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
    private val whisperAvailable: () -> Boolean,
    /** The Whisper module selected in offline settings; never silently falls back. */
    private val whisperEngine: () -> OfflineSttEngine = { OfflineSttEngine.WHISPER_SMALL_Q8 }
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
        val route = runCatching { OfflineSttRouter.route(sourceLanguage, whisperEngine()) }
            .getOrElse { return Result.failure(it) }
        if (route.unsupportedReason != null) {
            return Result.failure(IllegalArgumentException(route.unsupportedReason))
        }
        val available = when (route.engine) {
            OfflineSttEngine.UZBEK_STT -> uzbekAvailable()
            else -> whisperAvailable()
        }
        if (!available) {
            return Result.failure(
                IllegalStateException(
                    "${route.engine.displayName} modeli yuklab olinmagan yoki yoqilmagan. " +
                        "Avtomatik yuklab olish yoki boshqa modelga o‘tish boshlanmadi."
                )
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
