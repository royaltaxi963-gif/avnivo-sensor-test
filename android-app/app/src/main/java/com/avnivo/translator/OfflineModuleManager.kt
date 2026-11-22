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

    private fun statusJson(id: String, module: Module): JSONObject = JSONObject()
        .put("id", id)
        .put("state", module.state.code)
        .put("percent", module.percent)
        .put("active", module.state.isOperational)
        .put("reason", module.reason ?: module.spec.blocker ?: JSONObject.NULL)

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
                initialize(module)
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
                        notify("AvnivoOfflineProgress", "$id|$done/$total")
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

    /** Loads the native runtime and runs a smoke inference; only then promotes to READY. */
    private fun initialize(module: Module) {
        val directory = module.spec.directory(filesDir)
        if (!module.spec.installed(directory)) {
            transition(module, OfflineModuleState.NOT_INSTALLED)
            return
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
        }
    }

    /** Overridable so JVM tests can exercise the lifecycle without the native runtime. */
    internal var engineFactory: (OfflineModelSpec, File) -> Closeable = ::buildEngine

    private fun buildEngine(spec: OfflineModelSpec, directory: File): Closeable = when (spec.engine) {
        OfflineEngineKind.WHISPER_GGML -> {
            requireRam(spec)
            val model = directory.listFiles()?.firstOrNull { it.name.endsWith(".bin") }
                ?: error("Missing ggml model")
            WhisperEngine(model, language = "auto").also { it.prepare(); it.smokeTest() }
        }
        OfflineEngineKind.NLLB_ONNX -> {
            requireRam(spec)
            NllbEngine(directory).also { it.prepare(); it.smokeTest() }
        }
        OfflineEngineKind.SILERO_TTS -> {
            requireRam(spec)
            val model = directory.listFiles()?.firstOrNull { it.name.endsWith(".ptl") }
                ?: error("Missing TorchScript model")
            SileroTtsEngine(model).also { it.prepare(); it.smokeTest() }
        }
        OfflineEngineKind.UZBEK_NEMO_CTC -> error("Uzbek engine is managed by the STT bridge")
        OfflineEngineKind.UNAVAILABLE -> error("No engine available for ${spec.id}")
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
        if (next != OfflineModuleState.DOWNLOADING) module.percent = 0
        notify("AvnivoOfflineModuleStatus", module.spec.id)
    }
}
