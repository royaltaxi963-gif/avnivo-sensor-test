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

        /** Every file has been hashed against the manifest, not merely sized. */
        @Volatile var verified: Boolean = false

        /** Recency for eviction, so the model the user just used is the last one unloaded. */
        @Volatile var lastUsedAt: Long = 0L
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val modules = LinkedHashMap<String, Module>()

    init {
        OfflineModelSpecs.all.values.forEach { spec ->
            val module = Module(spec)
            // Length is enough to know a model is a candidate for loading. Verification (SHA-256)
            // runs on the worker before the module is reported as installed, so a truncated file
            // is never presented as ready.
            if (spec.installed(spec.directory(filesDir))) module.state = OfflineModuleState.INSTALLED
            modules[spec.id] = module
        }
    }

    fun hasModule(id: String): Boolean = modules.containsKey(id)

    /** Adds a spec at runtime; only used by tests to exercise non-runnable engines. */
    internal fun registerForTest(spec: OfflineModelSpec) {
        modules[spec.id] = Module(spec)
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
        val engine = module.engine ?: error(module.reason ?: "Dvigatel ishga tushmadi")
        return module to engine
    }

    /** Runs recognition on the loaded engine, loading it first if it was unloaded. */
    fun transcribe(id: String, samples: FloatArray): String {
        val (_, engine) = ensureLoaded(id)
        return synchronized(engine) {
            when (engine) {
                is WhisperEngine -> engine.transcribe(samples)
                else -> error("Module is not a recognizer")
            }
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
     * Language codes the NLLB module can encode, or an empty list when its engine is not resident.
     *
     * Deliberately does not load the model: this is called while rendering the settings card, and
     * pulling a 916 MB translator into memory just to draw a list would defeat lazy loading.
     */
    fun supportedLanguages(id: String): List<String> {
        val engine = modules[id]?.engine as? NllbEngine ?: return emptyList()
        return synchronized(engine) { engine.supportedLanguages() }
    }

    /** True when the loaded engine can translate this language pair. */
    fun supportsTranslation(id: String, source: String, target: String): Boolean {
        val engine = modules[id]?.engine as? NllbEngine ?: return false
        return synchronized(engine) { engine.supportsLanguage(source) && engine.supportsLanguage(target) }
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
            .put("progress", progress ?: JSONObject.NULL)
            .put("verified", module.verified)
            .put("resident", module.engine != null)
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
                val directory = module.spec.directory(filesDir)
                if (!module.spec.installed(directory)) continue
                val integrity = integrityVerifier(directory, module.spec)
                module.verified = integrity.ok
                if (!integrity.ok) {
                    module.reason = "model buzilgan: ${integrity.reason}. Qayta yuklab oling."
                    transition(module, OfflineModuleState.ERROR)
                    notify("AvnivoOfflineModuleError", "${module.spec.id}|${module.reason}")
                    continue
                }
                transition(module, OfflineModuleState.INSTALLED)
                module.reason = null
                notify("AvnivoOfflineModuleStatus", module.spec.id)
            }
            // Restore the single engine that was active, and only that one.
            activeModuleId?.let { id ->
                modules[id]?.let { module ->
                    if (module.verified) activate(module)
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
    @Synchronized
    private fun activate(module: Module) {
        if (module.state.isOperational && module.engine != null) {
            module.lastUsedAt = System.currentTimeMillis()
            return
        }
        val directory = module.spec.directory(filesDir)
        if (!module.spec.installed(directory)) {
            transition(module, OfflineModuleState.NOT_INSTALLED)
            return
        }
        unloadOthers(module)
        val snapshot = memoryProvider(appContext)
        if (snapshot != null && !snapshot.fits(module.spec.requiredRamBytes)) {
            module.reason = "xotira yetarli emas: " + snapshot.describe(module.spec.requiredRamBytes) +
                ". Boshqa modulni yoping yoki qurilmani qayta ishga tushiring."
            transition(module, OfflineModuleState.INSTALLED)
            notify("AvnivoOfflineModuleError", "${module.spec.id}|${module.reason}")
            return
        }
        transition(module, OfflineModuleState.INITIALIZING)
        try {
            val engine = engineFactory(module.spec, directory)
            module.engine?.let { runCatching { it.close() } }
            module.engine = engine
            module.reason = null
            module.lastUsedAt = System.currentTimeMillis()
            rememberActive(module.spec.id)
            transition(module, OfflineModuleState.READY)
            DeviceMemory.log(appContext)
            notify("AvnivoOfflineModuleReady", module.spec.id)
        } catch (e: Exception) {
            module.reason = "dvigatel ishga tushmadi: ${e.message}"
            transition(module, OfflineModuleState.INSTALLED)
            notify("AvnivoOfflineModuleError", "${module.spec.id}|dvigatel ishga tushmadi: ${e.message}")
        } catch (e: UnsatisfiedLinkError) {
            module.reason = "native kutubxonasi yuklanmadi"
            transition(module, OfflineModuleState.INSTALLED)
            notify("AvnivoOfflineModuleError", "${module.spec.id}|native kutubxonasi yuklanmadi")
        } catch (e: OutOfMemoryError) {
            // A large model can exhaust the process inside the native loader. Report the real cause
            // and release the handle instead of leaving the card on "Tekshirilmoqda…" forever.
            module.engine?.let { runCatching { it.close() } }
            module.engine = null
            module.reason = "model uchun xotira yetarli emas (OutOfMemoryError): " +
                memoryProvider(appContext)?.describe(module.spec.requiredRamBytes)
            transition(module, OfflineModuleState.ERROR)
            notify("AvnivoOfflineModuleError", "${module.spec.id}|${module.reason}")
        } catch (e: Throwable) {
            // Any other VM-level failure must still settle the state machine instead of hanging.
            module.engine?.let { runCatching { it.close() } }
            module.engine = null
            module.reason = "dvigatel ishga tushmadi: ${e::class.java.simpleName}"
            transition(module, OfflineModuleState.ERROR)
            notify("AvnivoOfflineModuleError", "${module.spec.id}|dvigatel ishga tushmadi: ${e::class.java.simpleName}")
        }
    }

    /** Releases every loaded engine except [keep], so only one model is ever resident. */
    private fun unloadOthers(keep: Module) {
        for (other in modules.values) {
            if (other === keep) continue
            val engine = other.engine ?: continue
            runCatching { engine.close() }
            other.engine = null
            if (other.state.isOperational) transition(other, OfflineModuleState.INSTALLED)
            notify("AvnivoOfflineModuleStatus", other.spec.id)
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
            if (activeModuleId == id) rememberActive(null)
            notify("AvnivoOfflineModuleStatus", id)
        }
    }

    /**
     * Called when the platform reports memory pressure. The engine that is not in active use is
     * released so the process survives; the model itself is untouched.
     */
    fun onTrimMemory() {
        worker.execute {
            val keep = activeModuleId
            for (module in modules.values) {
                if (module.spec.id == keep) continue
                val engine = module.engine ?: continue
                runCatching { engine.close() }
                module.engine = null
                if (module.state.isOperational) transition(module, OfflineModuleState.INSTALLED)
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
            if (module.state.isOperational) {
                // A ready engine is already the finished result; rebuilding it would drop a working
                // decoder for no reason and briefly report a state that is not true.
                notify("AvnivoOfflineModuleReady", module.spec.id)
                return@execute
            }
            if (module.spec.installed(directory)) {
                // Files are there: prove they are intact before claiming anything about them.
                val integrity = integrityVerifier(directory, module.spec)
                module.verified = integrity.ok
                if (!integrity.ok) {
                    ModelIntegrity.invalidate(directory)
                    module.reason = "model buzilgan: ${integrity.reason}. Qayta yuklab oling."
                    transition(module, OfflineModuleState.ERROR)
                    notify("AvnivoOfflineModuleError", "${module.spec.id}|${module.reason}")
                    return@execute
                }
                transition(module, OfflineModuleState.INSTALLED)
                activate(module)
                return@execute
            }
            try {
                transition(module, OfflineModuleState.DOWNLOADING)
                notify("AvnivoOfflineStatus", "${module.spec.id} yuklanmoqda…")
                VerifiedModelDownloader(
                    directory = directory,
                    modelId = module.spec.id,
                    expected = module.spec.files
                ).download(
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
                module.verified = integrityVerifier(directory, module.spec).ok
                transition(module, OfflineModuleState.INSTALLED)
                activate(module)
            } catch (e: Exception) {
                module.reason = e.message ?: e::class.java.simpleName
                transition(module, OfflineModuleState.ERROR)
                notify("AvnivoOfflineModuleError", "$id|${e.message}")
            }
        }
    }

    /** Overridable so JVM tests can exercise the lifecycle without the native runtime. */
    internal var engineFactory: (OfflineModelSpec, File) -> Closeable = ::buildEngine

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

    private fun rememberActive(id: String?) {
        activeModuleId = id
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY_ACTIVE, id)?.apply()
    }

    private fun buildEngine(spec: OfflineModelSpec, directory: File): Closeable = when (spec.engine) {
        OfflineEngineKind.WHISPER_GGML -> {
            val model = directory.listFiles()?.firstOrNull { it.name.endsWith(".bin") }
                ?: error("Missing ggml model")
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
        worker.shutdown()
    }

    private companion object {
        const val PREFS = "avnivo.offline"
        const val KEY_ACTIVE = "active.module"
    }

    /** Blocks until all queued work finishes. Used by tests and shutdown paths. */
    internal fun awaitIdle() {
        runCatching { worker.submit {}.get() }
    }

    private fun transition(module: Module, next: OfflineModuleState) {
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
