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
    /** Model download and module activation. Never used for capture: it can run for minutes. */
    private val worker = Executors.newSingleThreadExecutor()
    /** Microphone capture runs alone so a release is never queued behind other work. */
    private val captureWorker = Executors.newSingleThreadExecutor()
    /** Recognition runs here so a recording is transcribed as soon as it ends. */
    private val sttWorker = Executors.newSingleThreadExecutor()
    private val modelDir get() = File(filesDir, "models/uzbek-stt")
    private var recorder: OfflinePcmRecorder? = null
    private var busy = false

    /**
     * The Uzbek recognizer's lifecycle and evidence-based state. One instance per activity, so the
     * decoder is prepared once and reused across every press, session and mode switch.
     */
    private val sttController: UzbekSttController by lazy {
        UzbekSttController(
            recognizerFactory = { OfflineSpeechEngine(modelDir) },
            modelPresent = { OfflineSpeechEngine.hasRequiredFiles(modelDir) },
            onStateChanged = { _, _, session -> announceSttState(session) }
        )
    }

    /** Publishes the controller state to the page. Kept out of the initializer to avoid a self-reference. */
    private fun announceSttState(session: Int) {
        notifyJs("AvnivoOfflineState", sttController.statusJson(session).toString(), session)
    }

    @Volatile private var percent = 0

    /** A cancelled press; its audio must never be decoded into a later session. */
    @Volatile private var discardSession = 0

    /** A release that arrived before the microphone object existed, applied when capture starts. */
    @Volatile private var stopRequestedSession = 0

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
                sttController.refreshInstalled()
                if (modelInstalled && !busy && sttController.state != OfflineModuleState.READY) {
                    worker.execute { verifyEngine() }
                }
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

    /**
     * The ready Whisper module id, if any. Uzbek STT keeps priority: whenever the Uzbek model is
     * installed, speech is routed to it even while its engine is being prepared, because silently
     * sending the user's Uzbek audio to a multilingual model they did not choose is wrong.
     */
    private fun readyWhisper(): String? = when {
        modelInstalled -> null
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
         * The offline picker's only source of truth.
         *
         * `ready` means the model is installed and verified — not that its 916 MB engine is
         * currently resident. Loading is lazy, so an installed model that was released to make
         * room for another module still answers with its real language list, and the engine loads
         * on the first translation. `active` reports residency separately for diagnostics.
         */
        @JavascriptInterface fun offlineTranslationState(): String {
            val installed = moduleManager.isInstalled(NLLB_MODULE)
            val resident = moduleManager.isOperational(NLLB_MODULE)
            val status = moduleManager.status(NLLB_MODULE)
            return org.json.JSONObject()
                .put("ready", installed)
                .put("active", resident)
                .put("languages", org.json.JSONArray(moduleManager.supportedLanguages(NLLB_MODULE)))
                .put("status", status?.let { org.json.JSONObject(it) } ?: org.json.JSONObject())
                .toString()
        }

        /**
         * True when the Silero model is installed, so the speak button works from the first press
         * whether or not the engine is already resident.
         */
        @JavascriptInterface fun isSpeechReady(): Boolean = moduleManager.isInstalled(SILERO_MODULE)

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

    /**
     * Calls a page bridge function. When [session] is non-zero the page receives it as a second
     * argument, so a result from an earlier press can be rejected instead of landing in a later one.
     */
    private fun notifyJs(function: String, value: String, session: Int = 0) {
        val escaped = org.json.JSONObject.quote(value)
        val call = if (session > 0) "window.$function && window.$function($escaped,$session)"
                   else "window.$function && window.$function($escaped)"
        runOnUiThread { web.evaluateJavascript(call, null) }
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

    /**
     * Loads the native runtime and runs an inference smoke test; the controller promotes to READY
     * only after both succeed. Repeated calls reuse the verified recognizer, so this never rebuilds
     * a decoder that is already loaded.
     */
    private fun verifyEngine() {
        if (!modelInstalled) {
            sttController.refreshInstalled()
            return
        }
        notifyJs("AvnivoOfflineStatus", "Dvigatel ishga tushirilmoqda…")
        try {
            sttController.ensureReady()
        } catch (e: OutOfMemoryError) {
            notifyJs("AvnivoOfflineError", "Ovoz tanish uchun xotira yetarli emas. Boshqa modulni yoping.")
        } catch (e: UnsatisfiedLinkError) {
            notifyJs("AvnivoOfflineError", "Sherpa native kutubxonasi yuklanmadi")
        } catch (e: Exception) {
            notifyJs("AvnivoOfflineError", "Dvigatelni ishga tushirish xatosi: ${e.message}")
        }
    }

    inner class Bridge {
        /**
         * Machine-readable module state. This lives on the registered bridge object because only
         * methods of the object passed to addJavascriptInterface are reachable from the page; the
         * previous copy on the activity was never exposed, so the card could not read it.
         */
        @JavascriptInterface fun moduleStatus(): String =
            sttController.statusJson().put("percent", percent).toString()

        @JavascriptInterface fun status(): String = when {
            sttController.isRecording -> "Yozib olinmoqda…"
            busy -> "Jarayon bajarilmoqda…"
            sttController.isOperational -> "O‘zbekcha STT modeli yuklangan va dvigatel tayyor."
            modelInstalled -> "Model fayllari mavjud; dvigatel hali tayyor emas."
            else -> "O‘zbekcha STT modeli hali yuklanmagan (592 MB)."
        }
        @JavascriptInterface fun download() {
            runOnUiThread {
                if (busy) {
                    notifyJs("AvnivoOfflineStatus", "Oldingi jarayon tugashini kuting.")
                } else if (sttController.isOperational) {
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
        /**
         * Opens a new capture session. A press is only refused while another capture is still
         * open: a previous recording that is still being transcribed must not block the next one,
         * because that is exactly what made the first result appear only on the second attempt.
         */
        @JavascriptInterface fun start(session: Int = 0) {
            runOnUiThread {
                if (sttController.isRecording) {
                    // Report which session owns the microphone instead of silently ignoring the press.
                    notifyJs("AvnivoOfflineRejected", "Yozib olish allaqachon davom etmoqda.", sttController.openSession)
                    return@runOnUiThread
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    // Release the page's recording state now; after the grant the user presses again.
                    notifyJs("AvnivoOfflineRejected", "Mikrofon ruxsati so‘ralmoqda. Ruxsat bergach yana bosing.", session)
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                    return@runOnUiThread
                }
                if (!sttController.isOperational && readyWhisper() == null) {
                    if (modelInstalled) {
                        // Files are on disk, so the microphone must open now: the engine is
                        // prepared on the worker during capture. Asking the user to download a
                        // model they already have was a dead end that made every press do nothing.
                        notifyJs("AvnivoOfflineStatus", "Ovoz tanish dvigateli tayyorlanmoqda…")
                        worker.execute {
                            if (!sttController.isOperational) {
                                runCatching { verifyEngine() }
                                    .onFailure { logStt(session, "verify-failed", it.message ?: it::class.java.simpleName) }
                            }
                        }
                    } else {
                        notifyJs("AvnivoOfflineRejected", "Ovoz tanish dvigateli hali tayyor emas. Model yuklanishi kerak.")
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Offline o\u2018zbekcha ovoz tanish")
                            .setMessage("Ovoz tanish uchun 592 MB model yuklanishi va dvigatel tayyor bo\u2018lishi kerak. Wi-Fi tavsiya etiladi. Yuklaysizmi?")
                            .setNegativeButton("Bekor qilish", null)
                            .setPositiveButton("Yuklash") { _, _ -> downloadModel() }
                            .show()
                        return@runOnUiThread
                    }
                }
                // The page owns the session number so both sides always agree; a bridge call
                // without one still gets a valid id.
                val current = sttController.beginCapture(session)
                if (current == 0) {
                    notifyJs("AvnivoOfflineRejected", "Yozib olish allaqachon davom etmoqda.", sttController.openSession)
                    return@runOnUiThread
                }
                discardSession = 0
                beginRecording(current)
            }
        }

        /** Ends the open capture at the next buffer boundary (finger released). */
        @JavascriptInterface fun stop(session: Int = 0) {
            // A release that belongs to an earlier press must not cut the current one short.
            if (!sttController.ownsCapture(session)) return
            val capture = recorder
            if (capture != null) capture.stop()
            else stopRequestedSession = if (session > 0) session else sttController.openSession
        }

        /** Ends the capture and discards its audio; a cancelled press must not be transcribed. */
        @JavascriptInterface fun cancel(session: Int = 0) {
            if (!sttController.ownsCapture(session)) return
            val target = if (session > 0) session else sttController.openSession
            discardSession = target
            val capture = recorder
            if (capture != null) capture.stop() else stopRequestedSession = target
        }
    }

    private fun downloadModel() {
        if (busy) return
        busy = true
        percent = 0
        sttController.markDownloading()
        worker.execute {
            try {
                // Refuse before writing anything if the phone cannot hold the model plus working
                // space; failing halfway leaves a partial install that reads as "download error".
                val needed = VerifiedModelDownloader.EXPECTED.values.sumOf { it.first } + 64L * 1024 * 1024
                val free = DeviceMemory.freeBytes(filesDir)
                if (free in 1 until needed) {
                    throw IllegalStateException(
                        "Disk joyi yetarli emas: ${MemorySnapshot.mb(needed)} MB kerak, " +
                            "${MemorySnapshot.mb(free)} MB bo‘sh"
                    )
                }
                VerifiedModelDownloader(modelDir).download(
                    { notifyJs("AvnivoOfflineStatus", it) },
                    { done, total ->
                        percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                        notifyJs("AvnivoOfflineProgress", "$done/$total")
                    }
                )
                sttController.markVerifying()
                sttController.markInstalled()
                percent = 0
                verifyEngine()
            } catch (e: Exception) {
                percent = 0
                sttController.markError("Model yuklash xatosi: ${e.message}")
                notifyJs("AvnivoOfflineError", "Model yuklash xatosi: ${e.message}")
            } finally { busy = false }
        }
    }

    /**
     * Records until [Bridge.stop] is called (finger released), streaming the live amplitude to the
     * existing waveform, then transcribes that exact audio and returns the text to the interface.
     *
     * Capture runs on its own thread and starts immediately. Inference runs on a second thread
     * afterwards, so a press never waits behind model loading or another recognition, and the
     * release of one recording is all that is ever needed to transcribe it.
     */
    private fun beginRecording(session: Int) {
        val capture = OfflinePcmRecorder(this)
        recorder = capture
        // The finger may already be up (a very short press, or a release that raced the bridge
        // call). Apply that release before the loop starts so recording stops at the 0.3 s floor
        // instead of running to the 30 s limit.
        if (stopRequestedSession == session) capture.stop()
        stopRequestedSession = 0
        captureWorker.execute {
            try {
                val samples = capture.capture(30) { level ->
                    notifyJs("AvnivoOfflineLevel", level.toString(), session)
                }
                // The microphone is closed; the session no longer holds it, so the next press may
                // open its own capture while this one is being decoded.
                sttController.endCapture(session)
                // The finger is up: tell the page to show processing before any decoding starts.
                notifyJs("AvnivoOfflineProcessing", "Matnga aylantirilmoqda…", session)
                logStt(session, "captured", "samples=${samples.size} seconds=${"%.3f".format(samples.size / 16_000.0)}")
                transcribeCaptured(session, samples)
            } catch (e: OutOfMemoryError) {
                sttController.endCapture(session)
                sttController.fail(session, "Ovoz yozish uchun xotira yetarli emas")
                logStt(session, "capture-oom", e.message ?: "OutOfMemoryError")
                notifyJs("AvnivoOfflineError", "Ovoz yozish uchun xotira yetarli emas. Boshqa modulni yoping.", session)
            } catch (e: Exception) {
                sttController.endCapture(session)
                sttController.fail(session, "Yozib olish xatosi: ${e.message}")
                logStt(session, "capture-failed", e.message ?: e::class.java.simpleName)
                notifyJs("AvnivoOfflineError", "Yozib olish xatosi: ${e.message}", session)
            } finally {
                capture.close()
                if (recorder === capture) recorder = null
                notifyJs("AvnivoOfflineLevel", "0", session)
            }
        }
    }

    /** Runs the recognizer for one finished capture and reports the result for that session only. */
    private fun transcribeCaptured(session: Int, samples: FloatArray) {
        if (discardSession == session) {
            // The press was cancelled; never decode its audio into any session.
            discardSession = 0
            sttController.complete(session)
            logStt(session, "cancelled", "discarded")
            notifyJs("AvnivoOfflineStatus", "Yozuv bekor qilindi.", session)
            return
        }
        if (samples.size < MIN_SAMPLES) {
            // Too short to contain speech. Say so, with the measured length, instead of returning a
            // blank result that looks like a failed transcription.
            sttController.complete(session)
            logStt(session, "too-short", "samples=${samples.size} min=$MIN_SAMPLES")
            notifyJs("AvnivoOfflineStatus", "Ovoz juda qisqa (${samples.size / 16} ms). Mikrofonni bosib, gapiring.", session)
            notifyJs("AvnivoOfflineResult", "", session)
            return
        }
        sttWorker.execute {
            val whisperId = readyWhisper()
            try {
                val text = if (whisperId != null) {
                    moduleManager.transcribe(whisperId, samples)
                } else {
                    sttController.transcribe(session, samples)
                }
                logStt(session, "transcribed", "engine=${whisperId ?: "uzbek-stt"} chars=${text.length} text=${text.take(80)}")
                sttController.complete(session)
                if (text.isBlank()) {
                    notifyJs("AvnivoOfflineStatus", "Nutq aniqlanmadi. Qaytadan urinib ko‘ring.", session)
                }
                notifyJs("AvnivoOfflineResult", text, session)
            } catch (e: OutOfMemoryError) {
                logStt(session, "inference-oom", e.message ?: "OutOfMemoryError")
                sttController.fail(session, "Ovoz tanish uchun xotira yetarli emas")
                notifyJs("AvnivoOfflineError", "Ovoz tanish uchun xotira yetarli emas. Boshqa modulni yoping.", session)
            } catch (e: UnsatisfiedLinkError) {
                logStt(session, "native-failed", "UnsatisfiedLinkError")
                sttController.fail(session, "Sherpa native kutubxonasi yuklanmadi")
                notifyJs("AvnivoOfflineError", "Sherpa native kutubxonasi yuklanmadi", session)
            } catch (e: Exception) {
                logStt(session, "inference-failed", e.message ?: e::class.java.simpleName)
                // The controller closes a recognizer that threw, so the next press rebuilds it.
                sttController.fail(session, e.message ?: "Offline STT xatosi")
                notifyJs("AvnivoOfflineError", e.message ?: "Offline STT xatosi", session)
            }
        }
    }

    /** Session-tagged diagnostic line, so a stale or dropped segment can be traced in logcat. */
    private fun logStt(session: Int, stage: String, detail: String) {
        android.util.Log.i("AvnivoOfflineStt", "session=$session stage=$stage $detail")
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

    /**
     * The system is short on memory. Release the offline engines that are not in active use so the
     * process survives; the downloaded models are untouched and reload on the next request.
     *
     * The Uzbek recognizer is only released when no capture or decode is running, and the next
     * press reloads it automatically through [UzbekSttController.ensureReady].
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            DeviceMemory.log(this, "AvnivoMemoryTrim")
            moduleManager.onTrimMemory()
        }
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL && sttController.releaseForMemory()) {
            notifyJs(
                "AvnivoOfflineStatus",
                "Xotira tejash uchun STT dvigateli bo‘shatildi; keyingi ishlatishda avtomatik yuklanadi."
            )
        }
    }

    /**
     * Re-announce the real module state after the screen unlocks or the app returns to the
     * foreground. A card must never stay blank or stale because a callback was missed while the
     * page was hidden.
     */
    override fun onResume() {
        super.onResume()
        sttController.refreshInstalled()
        notifyJs("AvnivoOfflineState", sttController.statusJson().toString())
    }

    override fun onDestroy() {
        pendingWebAudioRequest?.deny()
        pendingWebAudioRequest = null
        recorder?.stop()
        ttsPlayer.close()
        ttsWorker.shutdown()
        moduleManager.close()
        sttController.close()
        captureWorker.shutdown()
        sttWorker.shutdown()
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
