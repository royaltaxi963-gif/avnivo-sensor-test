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
    private val modelReady get() = File(modelDir, "model.int8.onnx").length() == 591649160L &&
        File(modelDir, "tokens.txt").length() == 411L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webViewClient = WebViewClient()
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

    inner class Bridge {
        @JavascriptInterface fun status(): String = when {
            busy -> "Jarayon bajarilmoqda…"
            modelReady -> "O‘zbekcha STT modeli yuklangan (fayllar mavjud)."
            else -> "O‘zbekcha STT modeli hali yuklanmagan (592 MB)."
        }
        @JavascriptInterface fun download() {
            runOnUiThread {
                if (busy) {
                    notifyJs("AvnivoOfflineStatus", "Oldingi jarayon tugashini kuting.")
                } else if (modelReady) {
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
        @JavascriptInterface fun start() {
            runOnUiThread {
                if (busy) { recorder?.stop(); return@runOnUiThread }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
                    return@runOnUiThread
                }
                if (!modelReady) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Offline o‘zbekcha ovoz tanish")
                        .setMessage("Ovoz tanish uchun 592 MB model yuklanishi kerak. Wi-Fi tavsiya etiladi. Yuklaysizmi?")
                        .setNegativeButton("Bekor qilish", null)
                        .setPositiveButton("Yuklash") { _, _ -> downloadModel() }
                        .show()
                    return@runOnUiThread
                }
                recognize()
            }
        }
    }

    private fun downloadModel() {
        if (busy) return
        busy = true
        worker.execute {
            try {
                VerifiedModelDownloader(modelDir).download { notifyJs("AvnivoOfflineStatus", it) }
                notifyJs("AvnivoOfflineStatus", "Model yuklandi. Mikrofonni qayta bosing.")
            } catch (e: Exception) {
                notifyJs("AvnivoOfflineError", "Model yuklash xatosi: ${e.message}")
            } finally { busy = false }
        }
    }

    private fun recognize() {
        if (busy) return
        busy = true
        val capture = OfflinePcmRecorder(this)
        recorder = capture
        worker.execute {
            var engine: OfflineSpeechEngine? = null
            try {
                engine = OfflineSpeechEngine(modelDir)
                engine.prepare()
                val samples = capture.capture(12)
                val result = engine.transcribe(samples)
                notifyJs("AvnivoOfflineResult", result)
            } catch (e: Exception) {
                notifyJs("AvnivoOfflineError", e.message ?: "Offline STT xatosi")
            } catch (e: UnsatisfiedLinkError) {
                notifyJs("AvnivoOfflineError", "Sherpa native kutubxonasi yuklanmadi")
            } finally {
                capture.close()
                engine?.close()
                recorder = null
                busy = false
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 100 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            if (modelReady) recognize() else notifyJs("AvnivoOfflineError", "Modelni yuklash uchun mikrofonni qayta bosing.")
        else if (code == 100) notifyJs("AvnivoOfflineError", "Mikrofon ruxsati berilmadi")
    }

    override fun onDestroy() {
        recorder?.stop()
        worker.shutdown()
        web.destroy()
        super.onDestroy()
    }
}
