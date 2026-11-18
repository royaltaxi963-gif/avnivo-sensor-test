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
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        request.grant(request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }.toTypedArray())
                    else request.deny()
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "AvnivoOfflineSTT")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun notifyJs(function: String, value: String) {
        val escaped = org.json.JSONObject.quote(value)
        runOnUiThread { web.evaluateJavascript("window.$function && window.$function($escaped)", null) }
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
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
                    return@runOnUiThread
                }
                if (!state.isOperational) {
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
                engine = OfflineSpeechEngine(modelDir)
                engine.prepare()
                val samples = capture.capture(30) { level -> notifyJs("AvnivoOfflineLevel", level.toString()) }
                if (samples.size < MIN_SAMPLES) {
                    // Too short to contain speech: report no recognition rather than an error.
                    notifyJs("AvnivoOfflineResult", "")
                } else {
                    notifyJs("AvnivoOfflineResult", engine.transcribe(samples))
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
        if (code == 100 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            notifyJs("AvnivoOfflineHint", "Mikrofon ruxsati berildi. Yozish uchun mikrofonni bosing va ushlab turing.")
        else if (code == 100) notifyJs("AvnivoOfflineError", "Mikrofon ruxsati berilmadi")
    }

    override fun onDestroy() {
        recorder?.stop()
        worker.shutdown()
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val MIN_SAMPLES = 4800 // 0.3 s at 16 kHz: below this there is no usable speech.
    }
}
