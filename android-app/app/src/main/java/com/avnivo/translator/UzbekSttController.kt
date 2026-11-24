package com.avnivo.translator

import org.json.JSONObject
import java.io.Closeable

/** The recognizer surface the controller needs, so its lifecycle is testable off-device. */
internal interface SttRecognizer : Closeable {
    val isPrepared: Boolean
    fun prepare()
    fun transcribe(samples: FloatArray): String
    fun smokeTest(): String
}

/**
 * Owns the Uzbek STT module state and the recognizer lifetime.
 *
 * Every state is derived from a real event: the model files on disk, a prepare plus smoke decode,
 * a capture opening or closing, or a decode finishing. READY is never granted from a download
 * completion or a timer, and the recognizer is prepared exactly once until it is released.
 */
internal class UzbekSttController(
    private val recognizerFactory: () -> SttRecognizer,
    private val modelPresent: () -> Boolean,
    private val onStateChanged: (OfflineModuleState, String?, Int) -> Unit = { _, _, _ -> }
) : Closeable {

    @Volatile var state: OfflineModuleState = OfflineModuleState.NOT_INSTALLED
        private set

    @Volatile var reason: String? = null
        private set

    /** True only after prepare() and a real smoke decode both succeeded. */
    @Volatile var recognizerVerified = false
        private set

    @Volatile var lastSession = 0
        private set

    /** The session whose microphone capture is open, or 0 when the finger is not down. */
    @Volatile var openSession = 0
        private set

    /** The session currently being decoded, or 0 when no decode is running. */
    @Volatile var transcribingSession = 0
        private set

    private var recognizer: SttRecognizer? = null
    private val lock = Any()

    /** Operational means a verified engine is loaded and the module is not in a failed state. */
    val isOperational: Boolean
        get() = recognizerVerified &&
            (state == OfflineModuleState.READY ||
                state == OfflineModuleState.RECORDING ||
                state == OfflineModuleState.TRANSCRIBING)

    val isRecording: Boolean get() = openSession != 0

    /** The downloader started writing files. */
    fun markDownloading() {
        synchronized(lock) { setState(OfflineModuleState.DOWNLOADING, null, 0) }
    }

    /** The download finished and its bytes are being hashed. */
    fun markVerifying() {
        synchronized(lock) { setState(OfflineModuleState.VERIFYING, null, 0) }
    }

    /** The verified files are on disk; the engine is not loaded yet. */
    fun markInstalled() {
        synchronized(lock) { setState(OfflineModuleState.INSTALLED, null, 0) }
    }

    /** A download or verification failure with its concrete reason. */
    fun markError(message: String) {
        synchronized(lock) { setState(OfflineModuleState.ERROR, message, 0) }
    }

    /** Reconciles the state with the files on disk without touching a loaded engine. */
    fun refreshInstalled() {
        synchronized(lock) {
            if (!modelPresent()) {
                closeRecognizerLocked()
                setState(OfflineModuleState.NOT_INSTALLED, null, 0)
            } else if (state == OfflineModuleState.NOT_INSTALLED) {
                setState(OfflineModuleState.INSTALLED, null, 0)
            }
        }
    }

    /**
     * Loads and smoke-tests the recognizer exactly once. A second call returns the verified
     * instance instead of rebuilding it, so a press never pays the NeMo load cost again.
     */
    fun ensureReady(session: Int = 0): SttRecognizer = synchronized(lock) {
        if (!modelPresent()) {
            setState(OfflineModuleState.NOT_INSTALLED, "Model hali yuklab olinmagan", session)
            throw IllegalStateException("Model hali yuklab olinmagan")
        }
        recognizer?.let { if (recognizerVerified) return it }
        val capturing = state == OfflineModuleState.RECORDING || state == OfflineModuleState.TRANSCRIBING
        if (!capturing) setState(OfflineModuleState.INITIALIZING, null, session)
        val engine = recognizer ?: recognizerFactory().also { recognizer = it }
        try {
            engine.prepare()
            engine.smokeTest()
            recognizerVerified = true
            if (!capturing) setState(OfflineModuleState.READY, null, session)
            engine
        } catch (t: Throwable) {
            closeRecognizerLocked()
            setState(OfflineModuleState.ERROR, describe(t), session)
            throw t
        }
    }

    /** Releases the engine under memory pressure. Refused while a capture or decode is active. */
    fun releaseForMemory(): Boolean = synchronized(lock) {
        if (openSession != 0 || transcribingSession != 0) return false
        if (recognizer == null) return false
        closeRecognizerLocked()
        setState(
            OfflineModuleState.INSTALLED,
            "xotiradan bo‘shatildi; keyingi ishlatishda avtomatik yuklanadi",
            0
        )
        true
    }

    /**
     * Opens a capture. Returns the adopted session id, or 0 when another capture is still open.
     * A decode that is still running does not block the next recording.
     */
    fun beginCapture(requested: Int): Int = synchronized(lock) {
        if (openSession != 0) return 0
        val current = if (requested > 0) requested else lastSession + 1
        lastSession = maxOf(lastSession, current)
        openSession = current
        if (state != OfflineModuleState.NOT_INSTALLED &&
            state != OfflineModuleState.DOWNLOADING &&
            state != OfflineModuleState.VERIFYING
        ) {
            setState(OfflineModuleState.RECORDING, null, current)
        }
        current
    }

    /** True when [session] owns the open capture, so a stale release cannot stop a newer press. */
    fun ownsCapture(session: Int): Boolean = synchronized(lock) {
        openSession != 0 && (session <= 0 || session == openSession)
    }

    /**
     * The microphone loop has ended. Capture ownership is released immediately and the session
     * enters the processing state before the worker prepares a recognizer or decodes anything.
     *
     * This transition is the fix for the S23 Ultra report: endCapture used to clear only
     * openSession, so a slow Uzbek engine reload (after switching from Whisper Large) left the
     * card on "Ovoz yozilmoqda…" while transcribingEngine was already set. A new press then hit
     * the cross-engine guard and appeared permanently blocked.
     */
    fun endCapture(session: Int) {
        synchronized(lock) {
            if (openSession != session) return
            openSession = 0
            // This session is now the newest pending decode. An older decode may still be
            // finishing on sttWorker; complete()/fail() must not settle the state for it.
            transcribingSession = session
            if (state == OfflineModuleState.RECORDING) {
                setState(OfflineModuleState.TRANSCRIBING, null, session)
            }
        }
    }

    fun transcribe(session: Int, samples: FloatArray): String {
        // Claim the session and publish PROCESSING before ensureReady(). A recognizer that was
        // released for Whisper Large can take seconds to reload; the UI must never show
        // "recording" during that work, and releaseForMemory must refuse while it is pending.
        synchronized(lock) {
            transcribingSession = session
            if (state == OfflineModuleState.RECORDING || state == OfflineModuleState.READY) {
                setState(OfflineModuleState.TRANSCRIBING, null, session)
            }
        }
        val engine = try {
            ensureReady(session)
        } catch (t: Throwable) {
            synchronized(lock) {
                if (transcribingSession == session) transcribingSession = 0
            }
            throw t
        }
        try {
            return engine.transcribe(samples)
        } catch (t: Throwable) {
            // A decoder that threw is in an unknown state; drop it so the next press rebuilds a
            // clean one. A newer recording keeps its own state and simply reloads on its decode.
            synchronized(lock) {
                if (transcribingSession == session) transcribingSession = 0
                closeRecognizerLocked()
                if (transcribingSession == 0 && (openSession == 0 || openSession == session)) {
                    setState(OfflineModuleState.ERROR, describe(t), session)
                }
            }
            throw t
        }
    }

    /** The decode for [session] finished; settle back to READY unless a newer capture is active. */
    fun complete(session: Int) {
        synchronized(lock) {
            if (transcribingSession == session) transcribingSession = 0
            // A newer capture or decode owns the state now; an older completion must not promote
            // the module to READY while that newer session is still pending.
            if (transcribingSession != 0) return
            if (openSession != 0 && openSession != session) return
            if (state == OfflineModuleState.RECORDING || state == OfflineModuleState.TRANSCRIBING) {
                setState(
                    when {
                        recognizerVerified -> OfflineModuleState.READY
                        modelPresent() -> OfflineModuleState.INSTALLED
                        else -> OfflineModuleState.NOT_INSTALLED
                    },
                    null,
                    session
                )
            }
        }
    }

    /** The decode for [session] failed; keep a newer recording's state untouched. */
    fun fail(session: Int, message: String) {
        synchronized(lock) {
            if (transcribingSession == session) transcribingSession = 0
            // An older failure must not overwrite a newer recording/decode that is still alive.
            if (transcribingSession != 0) return
            if (openSession != 0 && openSession != session) return
            setState(OfflineModuleState.ERROR, message, session)
        }
    }

    fun statusJson(session: Int = 0): JSONObject = JSONObject()
        .put("id", "uzbek-stt")
        .put("state", state.code)
        .put("active", isOperational)
        .put("readyForSpeech", state == OfflineModuleState.READY && recognizerVerified)
        .put("verified", recognizerVerified)
        .put("resident", recognizer != null)
        .put("recording", state == OfflineModuleState.RECORDING)
        .put("transcribing", state == OfflineModuleState.TRANSCRIBING)
        .put("reason", reason ?: JSONObject.NULL)
        .put("session", if (session > 0) session else lastSession)

    override fun close() {
        synchronized(lock) {
            closeRecognizerLocked()
            openSession = 0
            transcribingSession = 0
        }
    }

    private fun setState(next: OfflineModuleState, why: String?, session: Int) {
        val previous = state
        if (next != previous) {
            if (!previous.canTransitionTo(next)) return
            state = next
        }
        reason = why
        if (next != previous || why != null) onStateChanged(next, why, session)
    }

    private fun closeRecognizerLocked() {
        recognizer?.let { runCatching { it.close() } }
        recognizer = null
        recognizerVerified = false
    }

    private fun describe(t: Throwable): String = when (t) {
        is OutOfMemoryError -> "Ovoz tanish uchun xotira yetarli emas"
        is UnsatisfiedLinkError -> "Sherpa native kutubxonasi yuklanmadi"
        else -> t.message ?: t::class.java.simpleName
    }
}
