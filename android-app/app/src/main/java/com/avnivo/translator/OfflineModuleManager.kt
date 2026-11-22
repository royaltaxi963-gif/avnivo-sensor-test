package com.avnivo.translator

import android.app.ActivityManager
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
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val modules = LinkedHashMap<String, Module>()

    init {
        OfflineModelSpecs.all.values.forEach { spec ->
            val module = Module(spec)
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

    /** Runs recognition on an already-initialized engine. Reuses the loaded model (no reload). */
    fun transcribe(id: String, samples: FloatArray): String {
        val module = modules[id] ?: error("Unknown module: $id")
        require(module.state.isOperational) { "Engine not ready" }
        val engine = module.engine ?: error("Engine not initialized")
        return synchronized(engine) {
            when (engine) {
                is WhisperEngine -> engine.transcribe(samples)
                else -> error("Module is not a recognizer")
            }
        }
    }

    /**
     * Synthesizes Uzbek text with an already-initialized Silero engine, reusing the loaded model.
     * Returns mono PCM at [SileroTtsEngine.SAMPLE_RATE]; the caller plays it on the manual action.
     */
    fun synthesize(id: String, text: String): FloatArray {
        val module = modules[id] ?: error("Unknown module: $id")
        require(module.state.isOperational) { "Engine not ready" }
        val engine = module.engine ?: error("Engine not initialized")
        return synchronized(engine) {
            when (engine) {
                is SileroTtsEngine -> engine.synthesize(text)
                else -> error("Module is not a speech synthesizer")
            }
        }
    }

    /** Translates with an already-initialized NLLB engine, reusing the loaded model. */
    fun translate(id: String, text: String, source: String, target: String): String {
        val module = modules[id] ?: error("Unknown module: $id")
        require(module.state.isOperational) { "Engine not ready" }
        val engine = module.engine ?: error("Engine not initialized")
        return synchronized(engine) {
            when (engine) {
                is NllbEngine -> engine.translate(text, source, target)
                else -> error("Module is not a translator")
            }
        }
    }

    /**
     * Language codes the initialized NLLB module can actually encode, or an empty list when it is
     * not operational. Never a hardcoded pair: the answer comes from the loaded tokenizer.
     */
    fun supportedLanguages(id: String): List<String> {
        val module = modules[id] ?: return emptyList()
        if (!module.state.isOperational) return emptyList()
        val engine = module.engine as? NllbEngine ?: return emptyList()
        return synchronized(engine) { engine.supportedLanguages() }
    }

    /** True when the initialized engine can translate this language pair. */
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
            .put("reason", module.reason ?: module.spec.blocker ?: JSONObject.NULL)
    }

    /**
     * Re-initializes every module whose files are already on disk. Called after a page load so a
     * previously installed engine becomes READY again following an app restart or process death.
     */
    fun reactivateInstalled() {
        worker.execute {
            for (module in modules.values) {
                if (module.state.isOperational) continue
                val directory = module.spec.directory(filesDir)
                if (!module.spec.runnable || !module.spec.installed(directory)) continue
                transition(module, OfflineModuleState.INSTALLED)
                // Page load must not load every installed model at once: the Uzbek, Whisper Small,
                // Whisper Large and NLLB models together need several GB of native memory, and the
                // resulting OOM left the largest card spinning on "Tekshirilmoqda…" forever. A
                // model that does not fit stays INSTALLED with the reason shown on its card.
                initialize(module, allowEvict = false)
            }
        }
    }

    /** Free memory reported by the system, or null when the platform cannot tell us. */
    private fun availableMemoryBytes(): Long? {
        val activityManager = appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.availMem
    }

    /**
     * Releases other ready engines so [spec] can load. Only used for an explicit activation, where
     * the user asked for this engine and it must not fail just because another model is resident.
     */
    private fun evictOtherEngines(keep: Module) {
        for (other in modules.values) {
            if (other === keep || !other.state.isOperational) continue
            runCatching { other.engine?.close() }
            other.engine = null
            transition(other, OfflineModuleState.INSTALLED)
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
                transition(module, OfflineModuleState.INSTALLED)
                initialize(module)
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
                transition(module, OfflineModuleState.INSTALLED)
                initialize(module)
            } catch (e: Exception) {
                module.reason = e.message ?: e::class.java.simpleName
                transition(module, OfflineModuleState.ERROR)
                notify("AvnivoOfflineModuleError", "$id|${e.message}")
            }
        }
    }

    /**
     * Loads the native runtime and runs a smoke inference; only then promotes to READY.
     *
     * [allowEvict] is true for an explicit activation, where a competing engine may be released so
     * the requested model fits. It is false on page load, where an unaffordable model must simply
     * report why instead of unloading a different working engine.
     */
    private fun initialize(module: Module, allowEvict: Boolean = true) {
        val directory = module.spec.directory(filesDir)
        if (!module.spec.installed(directory)) {
            transition(module, OfflineModuleState.NOT_INSTALLED)
            return
        }
        val available = availableMemoryBytes()
        if (available != null && module.spec.requiredRamBytes > 0 && available < module.spec.requiredRamBytes) {
            if (allowEvict) {
                evictOtherEngines(module)
            }
            val after = availableMemoryBytes()
            if (after != null && after < module.spec.requiredRamBytes) {
                module.reason = "xotira yetarli emas: ${module.spec.requiredRamBytes / 1024 / 1024} MB kerak, " +
                    "${after / 1024 / 1024} MB mavjud"
                transition(module, OfflineModuleState.INSTALLED)
                notify("AvnivoOfflineModuleError", "${module.spec.id}|${module.reason}")
                return
            }
        }
        transition(module, OfflineModuleState.INITIALIZING)
        try {
            val engine = engineFactory(module.spec, directory)
            module.engine?.let { runCatching { it.close() } }
            module.engine = engine
            module.reason = null
            transition(module, OfflineModuleState.READY)
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
            // A large model can exhaust the heap inside the native loader. Leaving the module in
            // INITIALIZING made the card spin on "Tekshirilmoqda…" forever; report the real cause.
            module.reason = "model uchun xotira yetarli emas (OutOfMemoryError)"
            transition(module, OfflineModuleState.ERROR)
            notify(
                "AvnivoOfflineModuleError",
                "${module.spec.id}|Model uchun xotira yetarli emas: ${module.spec.requiredRamBytes / 1024 / 1024} MB kerak"
            )
        } catch (e: Throwable) {
            // Any other VM-level failure must still settle the state machine instead of hanging.
            module.reason = "dvigatel ishga tushmadi: ${e::class.java.simpleName}"
            transition(module, OfflineModuleState.ERROR)
            notify("AvnivoOfflineModuleError", "${module.spec.id}|dvigatel ishga tushmadi: ${e::class.java.simpleName}")
        }
    }

    /** Overridable so JVM tests can exercise the lifecycle without the native runtime. */
    internal var engineFactory: (OfflineModelSpec, File) -> Closeable = ::buildEngine

    private fun buildEngine(spec: OfflineModelSpec, directory: File): Closeable = when (spec.engine) {
        OfflineEngineKind.WHISPER_GGML -> {
            requireRam(spec)
            val model = directory.listFiles()?.firstOrNull { it.name.endsWith(".bin") }
                ?: error("Missing ggml model")
            loadVerified(WhisperEngine(model, language = "auto"))
        }
        OfflineEngineKind.NLLB_ONNX -> {
            requireRam(spec)
            loadVerified(NllbEngine(directory))
        }
        OfflineEngineKind.SILERO_TTS -> {
            requireRam(spec)
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

    /**
     * Refuses to load a model that cannot fit, so a large model reports a clear error instead of
     * being killed by the low-memory killer mid-inference.
     */
    private fun requireRam(spec: OfflineModelSpec) {
        if (spec.requiredRamBytes <= 0) return
        val activityManager = appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val available = activityManager?.let { am ->
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem
        } ?: return
        require(available >= spec.requiredRamBytes) {
            "Not enough memory: needs ${spec.requiredRamBytes / 1024 / 1024} MB, ${available / 1024 / 1024} MB available"
        }
    }

    fun close() {
        modules.values.forEach {
            it.engine?.let { engine -> runCatching { engine.close() } }
            it.engine = null
        }
        worker.shutdown()
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
