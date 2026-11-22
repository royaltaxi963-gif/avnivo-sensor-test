package com.avnivo.translator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.util.concurrent.Executors

/** The real main AVNIVO interface with its own in-process offline recognizer. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val worker = Executors.newSingleThreadExecutor()
    private val modelDir get() = File(filesDir, "models/uzbek-stt")
    private var recorder: OfflinePcmRecorder? = null
    private var busy = false

    @Volatile private var state = OfflineModuleState.NOT_INSTALLED
    @Volatile private var percent = 0
    @Volatile private var recording = false

    /** The WebView capture request waiting on the Android permission dialog, if any. */
    private var pendingWebAudioRequest: PermissionRequest? = null

    /** Files present. Necessary but not sufficient for READY; the engine must also initialize. */
    private val modelInstalled get() = OfflineSpeechEngine.hasRequiredFiles(modelDir)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Re-activate a previously installed model after an app restart or process death.
                if (modelInstalled && !busy) worker.execute { verifyEngine() }
                // Re-initialize previously downloaded extra modules so their cards recover on restart.
                worker.execute { moduleManager.reactivateInstalled() }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleWebPermissionRequest(request) }
            }
        }
        web.addJavascriptInterface(Bridge(), "AvnivoOfflineSTT")
        web.addJavascriptInterface(Modules(), "AvnivoOfflineModules")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private val moduleManager by lazy { OfflineModuleManager(filesDir, applicationContext) { fn, v -> notifyJs(fn, v) } }

    /** Synthesis and playback run here so neither the UI nor the WebView thread is ever blocked. */
    private val ttsWorker = Executors.newSingleThreadExecutor()
    private val ttsPlayer = SileroTtsPlayer()

    /** The ready Whisper module id, if any; recognition prefers it over the Uzbek engine. */
    private fun readyWhisper(): String? = when {
        moduleManager.isOperational("whisper-small-q8") -> "whisper-small-q8"
        moduleManager.isOperational("whisper-large-v3-turbo") -> "whisper-large-v3-turbo"
        else -> null
    }

    /** Narrow bridge for the extra offline modules (Whisper Small/Large, NLLB). */
    inner class Modules {
        @JavascriptInterface fun status(id: String): String =
            moduleManager.status(id) ?: org.json.JSONObject().put("id", id).put("state", "unavailable").put("active", false).toString()
        @JavascriptInterface fun statusAll(): String = moduleManager.statusAll()
        @JavascriptInterface fun download(id: String) { if (moduleManager.hasModule(id)) moduleManager.download(id) else notifyJs("AvnivoOfflineError", "Noma’lum offline modul: $id") }

        /**
         * Offline translation. Runs on the worker and answers through `AvnivoOfflineTranslation`,
         * so a multi-second inference never blocks the WebView's JS thread.
         */
        @JavascriptInterface fun translate(text: String, source: String, target: String, requestId: String) {
            worker.execute {
                val payload = try {
                    org.json.JSONObject()
                        .put("requestId", requestId)
                        .put("text", moduleManager.translate(NLLB_MODULE, text, source, target))
                        .toString()
                } catch (e: Exception) {
                    org.json.JSONObject()
                        .put("requestId", requestId)
                        .put("error", e.message ?: "Tarjima bajarilmadi")
                        .toString()
                }
                notifyJs("AvnivoOfflineTranslation", payload)
            }
        }

        @JavascriptInterface fun supportsTranslation(source: String, target: String): Boolean =
            moduleManager.supportsTranslation(NLLB_MODULE, source, target)

        /**
         * The offline picker's only source of truth. `ready` is false unless the module passed
         * download, SHA-256 verification, native init and the smoke inference, and `languages` is
         * read from that loaded tokenizer, so an uninstalled module can never offer a fake list.
         */
        @JavascriptInterface fun offlineTranslationState(): String {
            val ready = moduleManager.isOperational(NLLB_MODULE)
            val status = moduleManager.status(NLLB_MODULE)
            return org.json.JSONObject()
                .put("ready", ready)
                .put("languages", org.json.JSONArray(moduleManager.supportedLanguages(NLLB_MODULE)))
                .put("status", status?.let { org.json.JSONObject(it) } ?: org.json.JSONObject())
                .toString()
        }

        /** True only when the Silero engine finished initializing and passed its smoke test. */
        @JavascriptInterface fun isSpeechReady(): Boolean = moduleManager.isOperational(SILERO_MODULE)

        /**
         * Synthesizes and plays [text] for the existing manual playback action.
         *
         * Answers through `AvnivoOfflineSpeech` with `{requestId, playing}` or `{requestId, error}`
         * once playback finishes or fails, so the WebView can clear its button state. Nothing is
         * sent, translated or auto-played here: this only renders audio for a button the user
         * already pressed.
         */
        @JavascriptInterface fun speak(text: String, requestId: String) {
            ttsWorker.execute {
                val result = try {
                    val pcm = moduleManager.synthesize(SILERO_MODULE, text)
                    org.json.JSONObject()
                        .put("requestId", requestId)
                        .put("playing", ttsPlayer.play(pcm))
                        .put("seconds", pcm.size.toDouble() / SileroTtsEngine.SAMPLE_RATE)
                        .toString()
                } catch (e: Exception) {
                    org.json.JSONObject()
                        .put("requestId", requestId)
                        .put("error", e.message ?: "Ovoz yaratilmadi")
                        .toString()
                }
                notifyJs("AvnivoOfflineSpeech", result)
            }
        }

        /** Cancels playback. Safe to call when nothing is playing. */
        @JavascriptInterface fun stopSpeech() {
            ttsPlayer.stop()
        }
    }

    private fun notifyJs(function: String, value: String) {
        val escaped = org.json.JSONObject.quote(value)
        runOnUiThread { web.evaluateJavascript("window.$function && window.$function($escaped)", null) }
    }

    /**
     * Grants `getUserMedia` only to this app's own bundled page, and only after the Android runtime
     * permission is actually held. WebView asks once per capture; when the permission is missing it
     * is requested and the same request is answered in [onRequestPermissionsResult].
     */
    private fun handleWebPermissionRequest(request: PermissionRequest) {
        val allowed = MicPermissionPolicy.grantableResources(
            request.origin?.scheme, request.origin?.host, request.resources.toList()
        )
        if (allowed == null) {
            request.deny()
            if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                notifyJs("AvnivoOnlineMicError", "Mikrofon faqat ilova sahifasidan ishlatiladi")
            }
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(allowed.toTypedArray())
            return
        }
        // WebView cancels a request that is answered later, so keep the latest one and answer it
        // from the permission callback instead of granting optimistically.
        pendingWebAudioRequest?.deny()
        pendingWebAudioRequest = request
        val permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) &&
            getPreferences(MODE_PRIVATE).getBoolean(KEY_MIC_REQUESTED, false)
        if (permanentlyDenied) {
            pendingWebAudioRequest = null
            request.deny()
            notifyJs(
                "AvnivoOnlineMicError",
                "Mikrofon ruxsati bloklangan. Sozlamalar → Ilovalar → AVNIVO → Ruxsatlar bo‘limidan yoqing"
            )
            return
        }
        getPreferences(MODE_PRIVATE).edit().putBoolean(KEY_MIC_REQUESTED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    /** Applies a state transition, rejecting illegal jumps (e.g. DOWNLOADING -> READY). */
    private fun transition(next: OfflineModuleState): Boolean {
        if (!state.canTransitionTo(next)) return false
        state = next
        if (next != OfflineModuleState.DOWNLOADING) percent = 0
        return true
    }

    /** Machine-readable state for diagnostics and future native module cards. */
    @JavascriptInterface
    fun moduleStatus(): String = org.json.JSONObject()
        .put("id", "uzbek-stt")
        .put("state", state.code)
        .put("percent", percent)
        .put("active", state.isOperational)
        .toString()

    /** Loads the native runtime and runs an inference smoke test; only then promotes to READY. */
    private fun verifyEngine() {
        if (!modelInstalled) {
            transition(OfflineModuleState.NOT_INSTALLED)
            return
        }
        transition(OfflineModuleState.INITIALIZING)
        notifyJs("AvnivoOfflineStatus", "Dvigatel ishga tushirilmoqda…")
        try {
            OfflineSpeechEngine(modelDir).use { engine ->
                engine.prepare()
                engine.smokeTest()
            }
            transition(OfflineModuleState.READY)
            notifyJs("AvnivoOfflineEngineReady", "O‘zbekcha ovoz tanish dvigateli tayyor")
        } catch (e: Exception) {
            transition(OfflineModuleState.INSTALLED)
            notifyJs("AvnivoOfflineError", "Dvigatelni ishga tushirish xatosi: ${e.message}")
        } catch (e: UnsatisfiedLinkError) {
            transition(OfflineModuleState.INSTALLED)
            notifyJs("AvnivoOfflineError", "Sherpa native kutubxonasi yuklanmadi")
        }
    }

    inner class Bridge {
        @JavascriptInterface fun status(): String = when {
            recording -> "Yozib olinmoqda…"
            busy -> "Jarayon bajarilmoqda…"
            state.isOperational -> "O‘zbekcha STT modeli yuklangan va dvigatel tayyor."
            modelInstalled -> "Model fayllari mavjud; dvigatel hali tayyor emas."
            else -> "O‘zbekcha STT modeli hali yuklanmagan (592 MB)."
        }
        @JavascriptInterface fun download() {
            runOnUiThread {
                if (busy) {
                    notifyJs("AvnivoOfflineStatus", "Oldingi jarayon tugashini kuting.")
                } else if (state.isOperational) {
                    notifyJs("AvnivoOfflineStatus", "Model allaqachon yuklangan.")
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("O‘zbekcha offline model")
                        .setMessage("592 MB modelni internet orqali yuklaysizmi?")
                        .setNegativeButton("Bekor qilish", null)
                        .setPositiveButton("Yuklash") { _, _ -> downloadModel() }
                        .show()
                }
            }
        }
        /** Begins a gesture-controlled recording session. Ignored if one is already running. */
        @JavascriptInterface fun start() {
            runOnUiThread {
                if (recording || busy) return@runOnUiThread
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                    return@runOnUiThread
                }
                if (!state.isOperational && readyWhisper() == null) {
                    notifyJs("AvnivoOfflineStatus", "Ovoz tanish dvigateli hali tayyor emas. Model yuklanishi kerak.")
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Offline o‘zbekcha ovoz tanish")
                        .setMessage("Ovoz tanish uchun 592 MB model yuklanishi va dvigatel tayyor bo‘lishi kerak. Wi-Fi tavsiya etiladi. Yuklaysizmi?")
                        .setNegativeButton("Bekor qilish", null)
                        .setPositiveButton("Yuklash") { _, _ -> downloadModel() }
                        .show()
                    return@runOnUiThread
                }
                beginRecording()
            }
        }
        /** Ends the active recording at the next buffer boundary (finger released). */
        @JavascriptInterface fun stop() {
            recorder?.stop()
        }
    }

    private fun downloadModel() {
        if (busy) return
        busy = true
        transition(OfflineModuleState.DOWNLOADING)
        worker.execute {
            try {
                VerifiedModelDownloader(modelDir).download(
                    { notifyJs("AvnivoOfflineStatus", it) },
                    { done, total ->
                        percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                        notifyJs("AvnivoOfflineProgress", "$done/$total")
                    }
                )
                transition(OfflineModuleState.VERIFYING)
                transition(OfflineModuleState.INSTALLED)
                verifyEngine()
            } catch (e: Exception) {
                transition(OfflineModuleState.ERROR)
                notifyJs("AvnivoOfflineError", "Model yuklash xatosi: ${e.message}")
            } finally { busy = false }
        }
    }

    /**
     * Records until [Bridge.stop] is called (finger released), streaming the live amplitude to the
     * existing waveform, then transcribes and returns the recognized text to the interface.
     */
    private fun beginRecording() {
        if (busy || recording) return
        busy = true
        recording = true
        val capture = OfflinePcmRecorder(this)
        recorder = capture
        worker.execute {
            var engine: OfflineSpeechEngine? = null
            try {
                val whisperId = readyWhisper()
                if (whisperId != null) {
                    // Prefer a ready multilingual Whisper engine (auto language detection).
                    val samples = capture.capture(30) { level -> notifyJs("AvnivoOfflineLevel", level.toString()) }
                    if (samples.size < MIN_SAMPLES) {
                        notifyJs("AvnivoOfflineResult", "")
                    } else {
                        notifyJs("AvnivoOfflineResult", moduleManager.transcribe(whisperId, samples))
                    }
                } else {
                    engine = OfflineSpeechEngine(modelDir)
                    engine.prepare()
                    val samples = capture.capture(30) { level -> notifyJs("AvnivoOfflineLevel", level.toString()) }
                    if (samples.size < MIN_SAMPLES) {
                        // Too short to contain speech: report no recognition rather than an error.
                        notifyJs("AvnivoOfflineResult", "")
                    } else {
                        notifyJs("AvnivoOfflineResult", engine.transcribe(samples))
                    }
                }
            } catch (e: Exception) {
                notifyJs("AvnivoOfflineError", e.message ?: "Offline STT xatosi")
            } catch (e: UnsatisfiedLinkError) {
                notifyJs("AvnivoOfflineError", "Sherpa native kutubxonasi yuklanmadi")
            } finally {
                capture.close()
                engine?.close()
                recorder = null
                recording = false
                busy = false
                notifyJs("AvnivoOfflineLevel", "0")
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != REQUEST_RECORD_AUDIO) return
        val granted = results.firstOrNull() == PackageManager.PERMISSION_GRANTED
        val pending = pendingWebAudioRequest
        pendingWebAudioRequest = null
        if (granted) {
            // The WebView may have cancelled while the dialog was open; granting a dead request is
            // harmless, and the page re-asks on the next press if it did.
            pending?.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            if (pending != null) notifyJs("AvnivoOnlineMicGranted", "")
            else notifyJs("AvnivoOfflineHint", "Mikrofon ruxsati berildi. Yozish uchun mikrofonni bosing va ushlab turing.")
        } else {
            pending?.deny()
            if (pending != null) notifyJs("AvnivoOnlineMicError", "Mikrofon ruxsati berilmadi. Ilova sozlamalaridan ruxsat bering")
            else notifyJs("AvnivoOfflineError", "Mikrofon ruxsati berilmadi")
        }
    }

    override fun onDestroy() {
        pendingWebAudioRequest?.deny()
        pendingWebAudioRequest = null
        recorder?.stop()
        ttsPlayer.close()
        ttsWorker.shutdown()
        moduleManager.close()
        worker.shutdown()
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val MIN_SAMPLES = 4800 // 0.3 s at 16 kHz: below this there is no usable speech.
        const val NLLB_MODULE = "nllb-200-600m-int8"
        const val SILERO_MODULE = "silero-tts"
        const val REQUEST_RECORD_AUDIO = 100
        const val KEY_MIC_REQUESTED = "avnivo.mic.requested.v1"
    }
}
