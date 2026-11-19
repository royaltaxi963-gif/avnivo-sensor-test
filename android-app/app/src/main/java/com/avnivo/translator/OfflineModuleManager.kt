package com.avnivo.translator

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
    private val notify: (String, String) -> Unit
) {
    private class Module(val spec: OfflineModelSpec) {
        @Volatile var state: OfflineModuleState = OfflineModuleState.NOT_INSTALLED
        @Volatile var percent: Int = 0
        @Volatile var engine: Closeable? = null
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

    private fun statusJson(id: String, module: Module): JSONObject = JSONObject()
        .put("id", id)
        .put("state", module.state.code)
        .put("percent", module.percent)
        .put("active", module.state.isOperational)

    /** Starts (or resumes) download+verify+initialize. Reuses an already-installed model. */
    fun download(id: String) {
        val module = modules[id] ?: run {
            notify("AvnivoOfflineModuleError", "$id|Noma’lum offline modul")
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
            transition(module, OfflineModuleState.READY)
            notify("AvnivoOfflineModuleReady", module.spec.id)
        } catch (e: Exception) {
            transition(module, OfflineModuleState.INSTALLED)
            notify("AvnivoOfflineModuleError", "${module.spec.id}|dvigatel ishga tushmadi: ${e.message}")
        } catch (e: UnsatisfiedLinkError) {
            transition(module, OfflineModuleState.INSTALLED)
            notify("AvnivoOfflineModuleError", "${module.spec.id}|native kutubxonasi yuklanmadi")
        }
    }

    /** Overridable so JVM tests can exercise the lifecycle without the native runtime. */
    internal var engineFactory: (OfflineModelSpec, File) -> Closeable = ::buildEngine

    private fun buildEngine(spec: OfflineModelSpec, directory: File): Closeable = when (spec.engine) {
        OfflineEngineKind.WHISPER_GGML -> {
            val model = directory.listFiles()?.firstOrNull { it.name.endsWith(".bin") }
                ?: error("Missing ggml model")
            WhisperEngine(model, language = "auto").also { it.prepare(); it.smokeTest() }
        }
        OfflineEngineKind.NLLB_ONNX -> error("NLLB runtime not available in this build")
        OfflineEngineKind.UZBEK_NEMO_CTC -> error("Uzbek engine is managed by the STT bridge")
        OfflineEngineKind.UNAVAILABLE -> error("No engine available for ${spec.id}")
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
