package com.avnivo.translator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * Owns the lifecycle of every downloadable offline module: download, verify, initialize and
 * dispose. A module reaches READY only after its native runtime loads and a smoke inference
 * passes; a downloaded model on its own is never reported as operational.
 */
internal class OfflineModuleManager(
    private val filesDir: File,
    private val appContext: Context? = null,
    private val notify: (String, String) -> Unit
) {
    private class Module(val spec: OfflineModelSpec) {
        @Volatile var state: OfflineModuleState = OfflineModuleState.NOT_INSTALLED
        @Volatile var percent: Int = 0
        @Volatile var downloadedBytes: Long = 0L
        @Volatile var totalBytes: Long = 0L
        @Volatile var engine: Closeable? = null
        @Volatile var reason: String? = null

        /** True while a native load is running for this module; a second press must not stack one. */
        @Volatile var activating: Boolean = false

        /** Every file has been hashed against the manifest, not merely sized. */
        @Volatile var verified: Boolean = false

        /** Recency for eviction, so the model the user just used is the last one unloaded. */
        @Volatile var lastUsedAt: Long = 0L

        /**
         * The user's intent, which is not the same thing as the engine being in memory. Enabled
         * modules keep working after the process evicts them: the next request reloads the engine.
         * Only an explicit enable, deactivate or delete changes this.
         */
        @Volatile var enabled: Boolean = false
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val modules = LinkedHashMap<String, Module>()

    /**
     * Serializes the activation state machine, never the native load itself. Holding this across
     * `whisper_init` would freeze `deactivate`, `unload` and every other module behind one stuck
     * native call, which is exactly what the S23 Ultra report showed.
     */
    private val activationLock = Any()

    /**
     * Engines that are still inside a native call we gave up waiting for. They are never freed,
     * because `whisper_free` under a running `whisper_full` is undefined behaviour; they are only
     * kept alive so the object is not collected while native code still holds its address.
     */
    private val quarantined = java.util.Collections.synchronizedList(mutableListOf<Closeable>())

    init {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedEnabled = prefs?.all?.keys?.filter { it.startsWith(KEY_ENABLED) }.orEmpty()
        // Builds before this one remembered a single active module. Promote it once so an upgrade
        // does not silently turn the module the user had switched on back off.
        val legacyActive = prefs?.getString(KEY_ACTIVE, null)
        OfflineModelSpecs.all.values.forEach { spec ->
            val module = Module(spec)
            module.enabled = prefs != null && (
                prefs.getBoolean(KEY_ENABLED + spec.id, false) ||
                    (storedEnabled.isEmpty() && spec.id == legacyActive)
                )
            val directory = spec.directory(filesDir)
            // A file of the right length is only a candidate. INSTALLED is reserved for a model
            // whose bytes are covered by a SHA-256 stamp; anything else is verified on the worker
            // before the card may claim it is installed.
            if (spec.installed(directory)) {
                module.state = if (ModelIntegrity.isStamped(directory, spec)) {
                    module.verified = true
                    OfflineModuleState.INSTALLED
                } else {
                    OfflineModuleState.VERIFYING
                }
            }
            modules[spec.id] = module
        }
    }

    fun hasModule(id: String): Boolean = modules.containsKey(id)

    /** Adds a spec at runtime; only used by tests to exercise non-runnable engines. */
    internal fun registerForTest(spec: OfflineModelSpec) {
        val module = Module(spec)
        // Read the persisted intent like the constructor does, so a test can restart the manager
        // and see the same enabled modules a real process restart would restore.
        module.enabled = appContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(KEY_ENABLED + spec.id, false) == true
        modules[spec.id] = module
    }

    /** Machine-readable status for one module, or null if the id is unknown (fail closed). */
    fun status(id: String): String? {
        val module = modules[id] ?: return null
        return statusJson(id, module).toString()
    }

    fun statusAll(): String {
        val array = JSONArray()
        for ((id, module) in modules) array.put(statusJson(id, module))
        return array.toString()
    }

    fun isOperational(id: String): Boolean = modules[id]?.state?.isOperational == true

    /**
     * The Whisper module the user selected and left active, or null when no Whisper engine is
     * selected. STT routing uses this instead of guessing Small: selecting Large must never
     * silently fall back to Small (or vice versa), and deactivating a module clears the choice.
     */
    fun selectedWhisperEngine(): OfflineSttEngine? = when {
        modules[WHISPER_LARGE_ID]?.enabled == true -> OfflineSttEngine.WHISPER_LARGE_V3_TURBO
        modules[WHISPER_SMALL_ID]?.enabled == true -> OfflineSttEngine.WHISPER_SMALL_Q8
        else -> null
    }

    fun selectedWhisperModuleId(): String? = selectedWhisperEngine()?.moduleId

    /** Native model metadata, available only after a successful whisper.cpp load. */
    fun engineDescription(id: String): String? =
        (modules[id]?.engine as? WhisperEngine)?.modelDescription

    /**
     * Returns the loaded engine for [id], loading it on demand.
     *
     * An engine may have been released to make room for another module, or never loaded because
     * the process restarted. Both are normal: the model is still installed, so the request must
     * simply reload it rather than fail with "engine not initialized".
     */
    private fun ensureLoaded(id: String): Pair<Module, Closeable> {
        val module = modules[id] ?: error("Unknown module: $id")
        module.engine?.let { return module to it }
        if (!module.spec.installed(module.spec.directory(filesDir))) {
            error("Model hali yuklab olinmagan")
        }
        if (!module.verified) {
            val integrity = integrityVerifier(module.spec.directory(filesDir), module.spec)
            module.verified = integrity.ok
            if (!integrity.ok) error("Model buzilgan: ${integrity.reason}")
        }
        activate(module)
        val engine = module.engine ?: error(
            if (module.activating) "Dvigatel yuklanmoqda; bir necha soniyadan keyin yana urinib ko‘ring"
            else module.reason ?: "Dvigatel ishga tushmadi"
        )
        return module to engine
    }

    /** Runs recognition on the loaded engine, loading it first if it was unloaded. */
    fun transcribe(id: String, samples: FloatArray, language: String? = null): String {
        val (module, engine) = ensureLoaded(id)
        val stage = "engine-decode:$id"
        val budget = OfflineGuard.decodeBudgetMs(samples.size)
        try {
            return OfflineGuard.bounded(stage, budget, ::lifecycleLog) {
                synchronized(engine) {
                    when (engine) {
                        is WhisperEngine -> engine.transcribe(samples, language)
                        else -> error("Module is not a recognizer")
                    }
                }
            }
        } catch (t: OfflineGuard.StageTimeout) {
            // A decoder that never returns leaves the module unusable but the model intact: the
            // files stay verified and INSTALLED, the engine is quarantined, and the next press
            // loads a fresh one. Before this, the card stayed on TRANSCRIBING until force-stop.
            quarantine(module, engine, t)
            throw IllegalStateException("${module.spec.id}: ${t.message}", t)
        }
    }

    /**
     * Synthesizes Uzbek text with the Silero engine, loading it first if it was unloaded.
     * Returns mono PCM at [SileroTtsEngine.SAMPLE_RATE]; the caller plays it on the manual action.
     */
    fun synthesize(id: String, text: String): FloatArray {
        val (_, engine) = ensureLoaded(id)
        return synchronized(engine) {
            when (engine) {
                is SileroTtsEngine -> engine.synthesize(text)
                else -> error("Module is not a speech synthesizer")
            }
        }
    }

    /** Translates with the NLLB engine, loading it first if it was unloaded. */
    fun translate(id: String, text: String, source: String, target: String): String {
        val (_, engine) = ensureLoaded(id)
        return synchronized(engine) {
            when (engine) {
                is NllbEngine -> engine.translate(text, source, target)
                else -> error("Module is not a translator")
            }
        }
    }

    /**
     * Language codes the NLLB module can encode, read from the model's own tokenizer.
     *
     * Deliberately does not load the 916 MB ONNX sessions: this is called while rendering the
     * translation picker, and pulling the translator into memory just to list languages would
     * defeat lazy loading. The tokenizer is a 17 MB JSON file, so reading it is cheap — and it
     * keeps the picker working after the engine has been released for another module.
     */
    fun supportedLanguages(id: String): List<String> = tokenizerFor(id)?.languageCodes.orEmpty()

    /**
     * True when the module can translate this language pair. Answered from the model's tokenizer,
     * so it stays correct while the engine is released for another module.
     */
    fun supportsTranslation(id: String, source: String, target: String): Boolean {
        val tokenizer = tokenizerFor(id) ?: return false
        return tokenizer.supportsLanguage(source) && tokenizer.supportsLanguage(target)
    }

    /**
     * True when the model is on disk and has not been rejected, whether or not its engine is
     * currently resident. A module whose files failed verification sits in ERROR and reports false,
     * so a corrupt model is never offered as usable.
     */
    fun isInstalled(id: String): Boolean {
        val module = modules[id] ?: return false
        if (module.state == OfflineModuleState.ERROR) return false
        if (!module.spec.runnable) return false
        val directory = module.spec.directory(filesDir)
        if (!module.spec.installed(directory)) return false
        return module.verified || ModelIntegrity.isStamped(directory, module.spec)
    }

    /**
     * The NLLB tokenizer, preferring the loaded engine's copy and otherwise parsing the file once.
     * Cached because the picker asks for it on every render and parsing 17 MB of JSON repeatedly
     * would make opening a language list noticeably slow.
     */
    @Volatile private var cachedTokenizer: NllbTokenizer? = null

    private fun tokenizerFor(id: String): NllbTokenizer? {
        val engine = modules[id]?.engine
        if (engine is NllbEngine) return synchronized(engine) { engine.tokenizerOrNull() } ?: cachedTokenizer
        cachedTokenizer?.let { return it }
        val module = modules[id] ?: return null
        if (!module.spec.installed(module.spec.directory(filesDir))) return null
        val loaded = runCatching {
            NllbTokenizer.load(File(module.spec.directory(filesDir), "tokenizer.json"))
        }.getOrNull() ?: return null
        cachedTokenizer = loaded
        return loaded
    }

    /**
     * Per-call native inference timings for a translator module, as (text, milliseconds) pairs, so
     * the device test can separate real inference cost from the model download it also performs.
     */
    fun inferenceTimings(id: String): List<Pair<String, Long>> {
        val engine = modules[id]?.engine as? NllbEngine ?: return emptyList()
        return synchronized(engine) { engine.inferenceHistory.toList() }
    }

    private fun statusJson(id: String, module: Module): JSONObject {
        val progress = if (module.totalBytes > 0) JSONObject()
            .put("downloadedBytes", module.downloadedBytes)
            .put("totalBytes", module.totalBytes)
        else null
        return JSONObject()
            .put("id", id)
            .put("state", module.state.code)
            .put("percent", module.percent)
            .put("active", module.state.isOperational)
            .put("installed", isInstalled(id))
            .put("selected", activeModuleId == id && module.state.isOperational)
            .put("progress", progress ?: JSONObject.NULL)
            .put("verified", module.verified)
            .put("resident", module.engine != null)
            .put("enabled", module.enabled)
            .put("reason", module.reason ?: module.spec.blocker ?: JSONObject.NULL)
    }

    /**
     * Verifies what is already on disk and loads at most one engine. Called after a page load so a
     * previously installed module recovers following an app restart or process death.
     *
     * Only the module that was active when the process died is loaded; every other installed model
     * stays INSTALLED with its files verified. Loading them all was the direct cause of the device
     * report: Whisper Small and Whisper Large together need more than the process can hold, so the
     * remaining modules failed their memory check and every card read "dvigatel ulanmagan".
     */
    fun reactivateInstalled() {
        worker.execute {
            for (module in modules.values) {
                if (!module.spec.runnable) continue
                if (wasFailed(module.spec.id)) {
                    // A previous load had to be abandoned. Reloading it on every page load is what
                    // made a restart land on the same stuck card; only an explicit press retries.
                    lifecycleLog("REACTIVATE_SKIPPED id=${module.spec.id} reason=previous-load-failed")
                    continue
                }
                // A live engine is already the strongest evidence there is. Demoting it to
                // INSTALLED on every page load is what made a working Whisper Small card lose
                // "Faol" and look like it needed downloading again.
                if (module.state.isOperational && module.engine != null) continue
                if (module.state == OfflineModuleState.DOWNLOADING) continue
                val directory = module.spec.directory(filesDir)
                if (!module.spec.installed(directory)) {
                    if (module.enabled) {
                        module.enabled = false
                        rememberEnabled(module.spec.id, false)
                    }
                    if (module.state == OfflineModuleState.VERIFYING) {
                        module.reason = "model fayllari topilmadi"
                        transition(module, OfflineModuleState.NOT_INSTALLED)
                    }
                    continue
                }
                if (!module.verified) {
                    val integrity = integrityVerifier(directory, module.spec)
                    module.verified = integrity.ok
                    if (!integrity.ok) {
                        module.reason = "model buzilgan: ${integrity.reason}. Qayta yuklab oling."
                        transition(module, OfflineModuleState.ERROR)
                        notify("AvnivoOfflineModuleError", "${module.spec.id}|${module.reason}")
                        continue
                    }
                }
                module.reason = null
                transition(module, OfflineModuleState.INSTALLED)
                notify("AvnivoOfflineModuleStatus", module.spec.id)
            }
            // Reload the module the user last switched on so the microphone is ready on the first
            // press. Everything else stays enabled and loads on its first request, which keeps a
            // 12 GB phone from trying to hold Whisper, NLLB and TTS at the same time.
            activeModuleId?.let { id ->
                modules[id]?.let { module ->
                    if (module.verified && !module.state.isOperational) activate(module)
                }
            }
        }
    }

    /**
     * Makes [module] the one loaded engine, releasing whatever was resident before.
     *
     * One engine at a time is a deliberate constraint, not a limitation to work around: the models
     * are hundreds of megabytes each and Android gives one process a few gigabytes at most. Loading
     * a second model without unloading the first is what made the device fail, so activation always
     * frees the previous engine first and the state machine reports that honestly.
     */
    private fun activate(module: Module) {
        val directory = module.spec.directory(filesDir)
        var snapshot: MemorySnapshot?
        synchronized(activationLock) {
            if (module.state.isOperational && module.engine != null) {
                module.lastUsedAt = System.currentTimeMillis()
                return
            }
            if (module.activating) {
                lifecycleLog("ACTIVATE_SKIPPED id=${module.spec.id} reason=already-initializing")
                return
            }
            if (!module.spec.installed(directory)) {
                transition(module, OfflineModuleState.NOT_INSTALLED)
                return
            }
            module.activating = true
            rememberActive(module.spec.id)
            enable(module)
            snapshot = memoryProvider(appContext)
            lifecycleLog(
                "ACTIVATE_START id=${module.spec.id} bytes=${module.spec.files.values.sumOf { it.first }} " +
                    "required_mb=${MemorySnapshot.mb(module.spec.requiredRamBytes)} " +
                    "budget_mb=${snapshot?.let { MemorySnapshot.mb(it.modelBudgetBytes) } ?: -1} " +
                    "total_mb=${snapshot?.let { MemorySnapshot.mb(it.totalMem) } ?: -1} " +
                    "avail_mb=${snapshot?.let { MemorySnapshot.mb(it.availMem) } ?: -1} " +
                    "low=${snapshot?.lowMemory ?: false}"
            )
            if (snapshot != null && !fitsNextToResident(module, snapshot)) {
                // Another engine is in the way, not the user's choice: release memory, keep the
                // modules enabled, and let the next request reload whatever was dropped.
                makeRoomFor(module)
                snapshot = memoryProvider(appContext)
            }
            if (snapshot != null && !fitsNextToResident(module, snapshot)) {
                module.activating = false
                module.reason = "xotira yetarli emas: " + snapshot.describe(module.spec.requiredRamBytes) +
                    ". Modul yoqilgan holatda qoldi; ishlatilganda qayta uriniladi."
                transition(module, OfflineModuleState.INSTALLED)
                notify("AvnivoOfflineModuleStatus", module.spec.id)
                return
            }
            transition(module, OfflineModuleState.INITIALIZING)
        }
        try {
            // The native load runs outside the lock: a model that never finishes loading must not
            // freeze deactivation or any other module's state changes.
            val engine = OfflineGuard.boundedCloseable(
                stage = "engine-load:${module.spec.id}",
                budgetMs = OfflineGuard.loadBudgetMs,
                log = ::lifecycleLog
            ) { engineFactory(module.spec, directory) }
            synchronized(activationLock) {
                module.engine?.let { runCatching { it.close() } }
                module.engine = engine
                module.reason = null
                module.lastUsedAt = System.currentTimeMillis()
                rememberActive(module.spec.id)
                clearFailure(module.spec.id)
                transition(module, OfflineModuleState.READY)
            }
            DeviceMemory.log(appContext)
            val nativeAllocated = if (appContext != null) {
                runCatching { android.os.Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
            } else 0L
            lifecycleLog(
                "ACTIVATE_READY id=${module.spec.id} native_mb=${MemorySnapshot.mb(nativeAllocated)} " +
                    "engine=${engine::class.java.simpleName}"
            )
            notify("AvnivoOfflineModuleReady", module.spec.id)
        } catch (e: OfflineGuard.StageTimeout) {
            // Remember the failure so a restart does not immediately reload the same model and
            // land on the same card. Only an explicit activation clears it.
            failActivation(
                module,
                "dvigatel yuklanmadi: ${e.message}. Qayta urinish uchun modulni qayta yoqing.",
                e,
                OfflineModuleState.INSTALLED,
                remember = true
            )
        } catch (e: UnsatisfiedLinkError) {
            failActivation(module, "native kutubxonasi yuklanmadi", e, OfflineModuleState.INSTALLED, false)
        } catch (e: OutOfMemoryError) {
            // A large model can exhaust the process inside the native loader. Report the real cause
            // and release the handle instead of leaving the card on "Ishga tushmoqda…" forever.
            failActivation(
                module,
                "model uchun xotira yetarli emas (OutOfMemoryError): " +
                    memoryProvider(appContext)?.describe(module.spec.requiredRamBytes),
                e,
                OfflineModuleState.ERROR,
                remember = true
            )
        } catch (e: Exception) {
            failActivation(
                module,
                "dvigatel ishga tushmadi: ${e.message}",
                e,
                OfflineModuleState.INSTALLED,
                remember = true
            )
        } catch (e: Throwable) {
            // Any other VM-level failure must still settle the state machine instead of hanging.
            failActivation(
                module,
                "dvigatel ishga tushmadi: ${e::class.java.simpleName}",
                e,
                OfflineModuleState.ERROR,
                remember = true
            )
        } finally {
            module.activating = false
        }
    }

    private fun failActivation(
        module: Module,
        reason: String,
        error: Throwable?,
        settle: OfflineModuleState,
        remember: Boolean
    ) {
        synchronized(activationLock) {
            module.engine?.let { runCatching { it.close() } }
            module.engine = null
            module.reason = reason
            if (remember) rememberFailure(module.spec.id)
            transition(module, settle)
        }
        lifecycleLog(
            "ACTIVATE_FAILED id=${module.spec.id} type=${error?.let { it::class.java.simpleName }} reason=$reason",
            error
        )
        notify("AvnivoOfflineModuleError", "${module.spec.id}|$reason")
    }

    /**
     * Drops a decoder that is still inside a native call. It is not closed: the native frame may
     * still be using it. The module reports the failure, keeps its verified files, and the next
     * activation builds a fresh engine.
     */
    private fun quarantine(module: Module, engine: Closeable, error: Throwable) {
        synchronized(activationLock) {
            if (module.engine === engine) module.engine = null
            module.reason = "dvigatel javob bermadi: ${error.message}. Qayta urinish uchun modulni qayta yoqing."
            if (module.state.isOperational || module.state == OfflineModuleState.INITIALIZING) {
                transition(module, OfflineModuleState.INSTALLED)
            }
            if (activeModuleId == module.spec.id) rememberActive(null)
            quarantined += engine
            rememberFailure(module.spec.id)
        }
        lifecycleLog("ENGINE_QUARANTINED id=${module.spec.id} reason=${error.message}", error)
        notify("AvnivoOfflineModuleError", "${module.spec.id}|${module.reason}")
    }

    /**
     * True when [module] fits next to the engines that are already resident. The budget covers the
     * whole process, so a model that fits on its own can still push the next one over the edge;
     * counting what is already loaded is what makes the eviction decision honest.
     */
    private fun fitsNextToResident(module: Module, snapshot: MemorySnapshot): Boolean {
        val resident = modules.values
            .filter { it !== module && it.engine != null }
            .sumOf { it.spec.requiredRamBytes }
        return snapshot.fits(module.spec.requiredRamBytes + resident)
    }

    /**
     * Records that the user switched [module] on, and applies the only exclusion this app has.
     *
     * The two Whisper models share [OfflineModelSpec.exclusiveGroup]: they answer the same
     * question, so the card must never claim both are live. Everything else - Uzbek STT, NLLB and
     * TTS - is independent, and enabling one of them must not switch anything else off. The
     * device report that motivated this was NLLB and Whisper cancelling each other, which broke
     * the speech -> translation -> speech chain the user had configured.
     */
    private fun enable(module: Module) {
        val group = module.spec.exclusiveGroup
        if (group != null) {
            for (peer in modules.values) {
                if (peer === module || peer.spec.exclusiveGroup != group) continue
                if (!peer.enabled && peer.engine == null) continue
                peer.enabled = false
                rememberEnabled(peer.spec.id, false)
                peer.engine?.let { engine -> runCatching { engine.close() } }
                peer.engine = null
                if (peer.state.isOperational) transition(peer, OfflineModuleState.INSTALLED)
                peer.reason = "boshqa Whisper modeli tanlandi: ${module.spec.id}. Fayllar saqlangan."
                lifecycleLog("EXCLUSIVE_RELEASE id=${peer.spec.id} selected=${module.spec.id}")
                notify("AvnivoOfflineModuleStatus", peer.spec.id)
            }
        }
        module.enabled = true
        rememberEnabled(module.spec.id, true)
    }

    /**
     * Frees memory, never intent. When a model does not fit next to what is already loaded, the
     * least recently used engines are released one at a time until it does; those modules stay
     * enabled and reload on their next request, so the chain keeps working without the user
     * switching anything back on.
     */
    private fun makeRoomFor(module: Module) {
        var snapshot = memoryProvider(appContext) ?: return
        if (fitsNextToResident(module, snapshot)) return
        val candidates = modules.values
            .filter { it !== module && it.engine != null && !it.activating }
            .sortedBy { it.lastUsedAt }
            .toList()
        for (other in candidates) {
            if (fitsNextToResident(module, snapshot)) return
            val engine = other.engine ?: continue
            runCatching { engine.close() }
            other.engine = null
            if (other.state.isOperational) transition(other, OfflineModuleState.INSTALLED)
            other.reason = "xotiradan vaqtincha bo‘shatildi; ishlatilganda avtomatik yuklanadi"
            lifecycleLog("MEMORY_RELEASE id=${other.spec.id} for=${module.spec.id}")
            notify("AvnivoOfflineModuleStatus", other.spec.id)
            snapshot = memoryProvider(appContext) ?: return
        }
    }

    /**
     * Activates an already-installed module without touching the network. This is the explicit
     * activation path used by the card: it verifies local bytes if needed, loads the native engine,
     * and only reports READY after the engine factory's own smoke test succeeds. It never starts a
     * download and never falls back to another module.
     */
    fun activate(id: String) {
        val module = modules[id] ?: run {
            notify("AvnivoOfflineModuleError", "$id|Noma’lum offline modul")
            return
        }
        clearFailure(id)
        worker.execute { verifyAndActivate(module) }
    }

    /** Verifies local files when necessary, then runs the normal activation state machine. */
    private fun verifyAndActivate(module: Module) {
        if (!module.spec.runnable) {
            notify("AvnivoOfflineModuleError", "${module.spec.id}|${module.spec.blocker ?: "Dvigatel mavjud emas"}")
            return
        }
        val directory = module.spec.directory(filesDir)
        if (!module.spec.installed(directory)) {
            module.reason = "model fayllari topilmadi"
            module.enabled = false
            rememberEnabled(module.spec.id, false)
            transition(module, OfflineModuleState.NOT_INSTALLED)
            notify("AvnivoOfflineModuleError", "${module.spec.id}|${module.reason}")
            return
        }
        if (!module.verified) {
            transition(module, OfflineModuleState.VERIFYING)
            val integrity = integrityVerifier(directory, module.spec)
            module.verified = integrity.ok
            if (!integrity.ok) {
                module.reason = "model buzilgan: ${integrity.reason}. Qayta yuklab oling."
                transition(module, OfflineModuleState.ERROR)
                notify("AvnivoOfflineModuleError", "${module.spec.id}|${module.reason}")
                return
            }
        }
        module.reason = null
        if (!module.state.isOperational) transition(module, OfflineModuleState.INSTALLED)
        activate(module)
    }

    /**
     * User-requested deactivation. This is deliberately separate from [delete]: it releases only
     * the resident engine, leaves the verified files and stamp on disk, and clears the persisted
     * selection so a restart does not silently reload the module.
     */
    fun deactivate(id: String) {
        worker.execute {
            val module = modules[id] ?: return@execute
            val engine = module.engine
            if (engine != null) runCatching { engine.close() }
            module.engine = null
            module.enabled = false
            rememberEnabled(id, false)
            if (module.state.isOperational) transition(module, OfflineModuleState.INSTALLED)
            module.reason = null
            if (activeModuleId == id) rememberActive(null)
            clearFailure(id)
            lifecycleLog("DEACTIVATE id=$id")
            notify("AvnivoOfflineModuleStatus", id)
        }
    }

    /**
     * Permanently removes one module's files. The worker serializes this with inference and
     * activation, and the method refuses while a download or initialization is in flight. Only
     * this spec's directory, its known files, its temporary `.download` parts and its integrity
     * stamp are touched; another module's directory is never traversed.
     */
    fun delete(id: String) {
        val module = modules[id] ?: run {
            notify("AvnivoOfflineModuleDeleteError", "$id|Noma’lum offline modul")
            return
        }
        worker.execute {
            if (module.state == OfflineModuleState.DOWNLOADING ||
                module.state == OfflineModuleState.VERIFYING ||
                module.state == OfflineModuleState.INITIALIZING) {
                notify(
                    "AvnivoOfflineModuleDeleteError",
                    "$id|Yuklash yoki ishga tushirish tugashini kuting; model hozir o‘chirilmaydi."
                )
                return@execute
            }
            val directory = module.spec.directory(filesDir)
            runCatching { module.engine?.close() }
            module.engine = null
            module.enabled = false
            rememberEnabled(id, false)
            if (module.state.isOperational) transition(module, OfflineModuleState.INSTALLED)
            if (activeModuleId == id) rememberActive(null)
            ModelIntegrity.invalidate(directory)
            var failure: String? = null
            for (name in module.spec.files.keys) {
                val file = File(directory, name)
                if (file.exists() && !file.delete()) failure = "faylni o‘chirib bo‘lmadi: $name"
                val partial = File(directory, "$name.download")
                if (partial.exists() && !partial.delete() && failure == null) {
                    failure = "vaqtinchalik faylni o‘chirib bo‘lmadi: ${partial.name}"
                }
            }
            directory.listFiles()?.forEach { file ->
                if ((file.name.endsWith(".download") || file.name.endsWith(".tmp")) &&
                    file.exists() && !file.delete() && failure == null) {
                    failure = "vaqtinchalik faylni o‘chirib bo‘lmadi: ${file.name}"
                }
            }
            val remaining = module.spec.files.keys.filter { File(directory, it).exists() }
            if (remaining.isNotEmpty() && failure == null) {
                failure = "fayllar qoldi: ${remaining.joinToString()}"
            }
            if (failure != null) {
                module.verified = false
                module.reason = "o‘chirish muvaffaqiyatsiz: $failure"
                transition(module, OfflineModuleState.ERROR)
                notify("AvnivoOfflineModuleDeleteError", "$id|${module.reason}")
                return@execute
            }
            // A directory is only removed after every known file is gone. Unknown files, if any,
            // are left in place rather than deleting a path that might belong to another module.
            if (directory.isDirectory && directory.listFiles()?.isEmpty() == true) directory.delete()
            module.verified = false
            module.reason = null
            transition(module, OfflineModuleState.NOT_INSTALLED)
            notify("AvnivoOfflineModuleDeleted", id)
        }
    }

    /**
     * Releases the loaded engine so another module can use the memory. The model stays installed:
     * unload only changes what is resident, never what the user has downloaded.
     */
    fun unload(id: String) {
        worker.execute {
            val module = modules[id] ?: return@execute
            val engine = module.engine ?: return@execute
            runCatching { engine.close() }
            module.engine = null
            if (module.state.isOperational) transition(module, OfflineModuleState.INSTALLED)
            module.reason = "xotiradan bo‘shatildi; qayta ishlatishda avtomatik yuklanadi"
            notify("AvnivoOfflineModuleStatus", id)
        }
    }

    /**
     * Called when the platform reports memory pressure. The engine that is not in active use is
     * released so the process survives; the model itself is untouched.
     */
    fun onTrimMemory() {
        worker.execute {
            for (module in modules.values) {
                val engine = module.engine ?: continue
                runCatching { engine.close() }
                module.engine = null
                if (module.state.isOperational) transition(module, OfflineModuleState.INSTALLED)
                module.reason = "xotiradan vaqtincha bo‘shatildi; ishlatilganda avtomatik yuklanadi"
                notify("AvnivoOfflineModuleStatus", module.spec.id)
            }
        }
    }

    /** Starts (or resumes) download+verify+initialize. Reuses an already-installed model. */
    fun download(id: String) {
        val module = modules[id] ?: run {
            notify("AvnivoOfflineModuleError", "$id|Noma’lum offline modul")
            return
        }
        if (!module.spec.runnable) {
            notify(
                "AvnivoOfflineModuleError",
                "$id|${module.spec.blocker ?: "Bu modul uchun ijro dvigateli hali ulanmagan"}"
            )
            return
        }
        worker.execute {
            val directory = module.spec.directory(filesDir)
            if (module.state.isOperational && module.engine != null) {
                // A ready engine is already the finished result; rebuilding it would drop a working
                // decoder for no reason and briefly report a state that is not true.
                notify("AvnivoOfflineModuleReady", module.spec.id)
                return@execute
            }
            if (module.spec.installed(directory)) {
                // Files are there: prove they are intact before claiming anything about them.
                if (!module.verified) transition(module, OfflineModuleState.VERIFYING)
                val integrity = integrityVerifier(directory, module.spec)
                module.verified = integrity.ok
                if (!integrity.ok) {
                    // A corrupt file with the expected length can never be repaired by re-hashing
                    // it. Delete it so the same press can fetch clean bytes instead of leaving the
                    // user in an ERROR loop that says "download again" and then refuses to.
                    ModelIntegrity.invalidate(directory)
                    module.spec.files.keys.forEach { File(directory, it).delete() }
                    module.verified = false
                    module.reason = "model buzilgan: ${integrity.reason}. Qayta yuklanmoqda."
                    transition(module, OfflineModuleState.NOT_INSTALLED)
                } else {
                    module.reason = null
                    transition(module, OfflineModuleState.INSTALLED)
                    activate(module)
                    return@execute
                }
            } else if (module.state == OfflineModuleState.INSTALLED ||
                module.state == OfflineModuleState.READY ||
                module.state == OfflineModuleState.VERIFYING) {
                // The state said installed but the bytes are gone (manual cleanup, failed
                // migration, or a partial restore). Reset before starting a real download so the
                // card cannot stay on "installed" while bytes are being fetched.
                ModelIntegrity.invalidate(directory)
                module.verified = false
                transition(module, OfflineModuleState.NOT_INSTALLED)
            }
            try {
                transition(module, OfflineModuleState.DOWNLOADING)
                notify("AvnivoOfflineStatus", "${module.spec.id} yuklanmoqda…")
                downloaderFactory(directory, module.spec.id, module.spec.files).download(
                    { notify("AvnivoOfflineStatus", it) },
                    { done, total ->
                        module.percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                        module.downloadedBytes = done
                        module.totalBytes = total
                        // Must match the handler the settings card listens on, otherwise progress
                        // never reaches the module that is downloading and the card sits at 0%.
                        notify("AvnivoOfflineModuleProgress", "$id|$done/$total")
                    }
                )
                transition(module, OfflineModuleState.VERIFYING)
                // The downloader hashes every byte it writes; record that so the next page load
                // does not re-hash 916 MB just to answer "is this still installed?".
                val verified = integrityVerifier(directory, module.spec).ok
                module.verified = verified
                if (!verified) {
                    ModelIntegrity.invalidate(directory)
                    module.reason = "model tekshiruvdan o‘tmadi (SHA-256). Qayta yuklab oling."
                    transition(module, OfflineModuleState.ERROR)
                    notify("AvnivoOfflineModuleError", "$id|${module.reason}")
                    return@execute
                }
                module.reason = null
                transition(module, OfflineModuleState.INSTALLED)
                activate(module)
            } catch (e: Exception) {
                module.reason = e.message ?: e::class.java.simpleName
                transition(module, OfflineModuleState.ERROR)
                notify("AvnivoOfflineModuleError", "$id|${e.message}")
            }
        }
    }

    /** Android logging is unavailable in JVM tests, so lifecycle diagnostics are device-only. */
    private fun lifecycleLog(message: String, error: Throwable? = null) {
        if (appContext == null) return
        OfflineDiagnostics.error(OfflineDiagnostics.TAG_MODULE, message, error)
    }

    /**
     * A load or decode that had to be abandoned is remembered across restarts.
     *
     * Without this, `reactivateInstalled()` reloads the same model on every page load and the phone
     * lands on the same stuck card immediately after every restart, which is exactly what the S23
     * Ultra report described. An explicit activation clears the mark.
     */
    private fun rememberFailure(id: String) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("$KEY_FAILED$id", true)?.apply()
    }

    private fun clearFailure(id: String) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove("$KEY_FAILED$id")?.apply()
    }

    private fun wasFailed(id: String): Boolean = appContext
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ?.getBoolean("$KEY_FAILED$id", false) == true

    /** Overridable so JVM tests can exercise the lifecycle without the native runtime. */
    internal var engineFactory: (OfflineModelSpec, File) -> Closeable = ::buildEngine

    /** Overridable so lifecycle tests can prove progress and verification without the network. */
    internal var downloaderFactory: (File, String, Map<String, Pair<Long, String>>) -> ModelDownloader =
        { directory, id, expected ->
            VerifiedModelDownloader(directory = directory, modelId = id, expected = expected)
        }

    /** Overridable so tests can present the memory picture of a specific phone. */
    internal var memoryProvider: (Context?) -> MemorySnapshot? = DeviceMemory::snapshot

    /** Overridable so lifecycle tests can use stand-in model files; the real path hashes bytes. */
    internal var integrityVerifier: (File, OfflineModelSpec) -> ModelIntegrity.Result =
        ModelIntegrity::verify

    /**
     * The module whose engine is currently resident. Restored from disk on construction so a
     * restart reloads exactly one engine instead of every installed model.
     */
    @Volatile internal var activeModuleId: String? = appContext
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ?.getString(KEY_ACTIVE, null)

    private fun rememberEnabled(id: String, enabled: Boolean) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(KEY_ENABLED + id, enabled)?.apply()
    }

    private fun rememberActive(id: String?) {
        activeModuleId = id
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY_ACTIVE, id)?.apply()
    }

    private fun buildEngine(spec: OfflineModelSpec, directory: File): Closeable = when (spec.engine) {
        OfflineEngineKind.WHISPER_GGML -> {
            // Use the spec's exact file name. Picking the first *.bin in the directory could load a
            // stale or unrelated file if a previous interrupted download left one behind.
            val modelName = spec.files.keys.firstOrNull { it.endsWith(".bin") }
                ?: error("Missing ggml model")
            val model = File(directory, modelName)
            if (!model.isFile) error("Missing ggml model: $modelName")
            loadVerified(WhisperEngine(model, language = "auto"))
        }
        OfflineEngineKind.NLLB_ONNX -> loadVerified(NllbEngine(directory))
        OfflineEngineKind.SILERO_TTS -> {
            val model = directory.listFiles()?.firstOrNull { it.name.endsWith(".ptl") }
                ?: error("Missing TorchScript model")
            loadVerified(SileroTtsEngine(model))
        }
        OfflineEngineKind.UZBEK_NEMO_CTC -> error("Uzbek engine is managed by the STT bridge")
        OfflineEngineKind.UNAVAILABLE -> error("No engine available for ${spec.id}")
    }

    /** Prepares and smoke-tests an engine, releasing it if either step fails. */
    private fun <T : Closeable> loadVerified(engine: T): T {
        try {
            when (engine) {
                is WhisperEngine -> { engine.prepare(); engine.smokeTest() }
                is NllbEngine -> { engine.prepare(); engine.smokeTest() }
                is SileroTtsEngine -> { engine.prepare(); engine.smokeTest() }
                else -> error("Unsupported engine type")
            }
        } catch (t: Throwable) {
            runCatching { engine.close() }
            throw t
        }
        return engine
    }

    fun close() {
        modules.values.forEach {
            it.engine?.let { engine -> runCatching { engine.close() } }
            it.engine = null
        }
        // Quarantined engines are deliberately not closed: native code may still be running
        // inside them, and `whisper_free` under a live `whisper_full` would crash the process.
        if (quarantined.isNotEmpty()) {
            lifecycleLog("ENGINE_QUARANTINE_KEPT count=${quarantined.size}")
        }
        worker.shutdown()
    }

    private companion object {
        const val PREFS = "avnivo.offline"
        const val KEY_ACTIVE = "active.module"
        const val KEY_ENABLED = "enabled."
        const val WHISPER_SMALL_ID = "whisper-small-q8"
        const val WHISPER_LARGE_ID = "whisper-large-v3-turbo"
        const val KEY_FAILED = "failed."
    }

    /** Blocks until all queued work finishes. Used by tests and shutdown paths. */
    internal fun awaitIdle() {
        runCatching { worker.submit {}.get() }
    }

    private fun transition(module: Module, next: OfflineModuleState) {
        if (module.state == next) return
        if (!module.state.canTransitionTo(next)) return
        module.state = next
        if (next != OfflineModuleState.DOWNLOADING) {
            module.percent = 0
            module.downloadedBytes = 0L
            module.totalBytes = 0L
        }
        notify("AvnivoOfflineModuleStatus", module.spec.id)
    }
}
