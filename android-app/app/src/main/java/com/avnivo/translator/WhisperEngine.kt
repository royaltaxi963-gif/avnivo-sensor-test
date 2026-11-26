package com.avnivo.translator

import java.io.Closeable
import java.io.File

/** Offline Whisper (ggml/whisper.cpp) recognizer. All calls must run off the UI thread. */
internal class WhisperEngine(
    private val modelFile: File,
    private val language: String,
    private val tuning: WhisperTuning = WhisperTuning(),
) : Closeable {
    private var handle: Long = 0L

    /** Model metadata returned by whisper.cpp after a successful load, for device diagnostics. */
    @Volatile var modelDescription: String? = null
        private set

    /** whisper_print_timings() of the last decode: mel, encoder, decoder, fallbacks. */
    @Volatile var lastTimings: String = ""
        private set

    companion object {
        const val SAMPLE_RATE = 16000

        @Volatile private var loaded = false

        /** Loads the native library once; false means the runtime is unavailable on this device. */
        @Synchronized
        fun ensureNativeLoaded(): Boolean {
            if (loaded) return true
            return try {
                System.loadLibrary("avnivo_whisper")
                loaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }

        fun hasRequiredFile(file: File): Boolean = file.isFile && file.length() > 0L
    }

    @Synchronized
    fun prepare() {
        if (handle != 0L) return
        require(ensureNativeLoaded()) { "Whisper native library is unavailable" }
        require(modelFile.isFile && modelFile.length() > 0L) {
            "Missing model file: ${modelFile.name} (${modelFile.length()} bytes)"
        }
        val startedAt = System.currentTimeMillis()
        OfflineDiagnostics.log(
            OfflineDiagnostics.TAG_WHISPER,
            "WHISPER_LOAD_START file=${modelFile.name} bytes=${modelFile.length()} " +
                "thread=${Thread.currentThread().name} native_mb=" +
                MemorySnapshot.mb(android.os.Debug.getNativeHeapAllocatedSize())
        )
        handle = nativeInit(modelFile.absolutePath)
        OfflineDiagnostics.log(
            OfflineDiagnostics.TAG_WHISPER,
            "WHISPER_LOAD_END file=${modelFile.name} elapsed_ms=${System.currentTimeMillis() - startedAt} " +
                "loaded=${handle != 0L} native_mb=" +
                MemorySnapshot.mb(android.os.Debug.getNativeHeapAllocatedSize())
        )
        require(handle != 0L) {
            "Whisper model failed to load: ${modelFile.name} (${modelFile.length()} bytes)"
        }
        try {
            val info = nativeModelInfo(handle)
            require(info.isNotBlank()) { "Whisper loaded without model metadata" }
            modelDescription = info
            OfflineDiagnostics.log(
                OfflineDiagnostics.TAG_WHISPER,
                "WHISPER_MODEL_LOADED file=${modelFile.name} bytes=${modelFile.length()} info=$info"
            )
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    /**
     * Transcribes one recording.
     *
     * Silence is answered without decoding: an accidental press used to cost a full 30 s encoder
     * pass to produce an empty string.
     */
    @Synchronized
    fun transcribe(samples: FloatArray, languageOverride: String? = null): String {
        check(handle != 0L) { "Call prepare() first" }
        require(samples.isNotEmpty()) { "Empty recording" }
        val trimmed = AudioTrim.trim(samples)
        if (AudioTrim.isSilent(trimmed)) {
            OfflineDiagnostics.log(
                OfflineDiagnostics.TAG_WHISPER,
                "WHISPER_DECODE_SKIPPED reason=silence samples=${samples.size}"
            )
            return ""
        }
        return decode(trimmed, samples.size, languageOverride)
    }

    private fun decode(
        samples: FloatArray,
        originalSamples: Int,
        languageOverride: String?,
    ): String {
        // The source language belongs to the recording session, not to the model instance: one
        // resident Whisper engine serves every non-Uzbek/English source in turn.
        val audioCtx = tuning.audioContextFor(samples.size)
        val startedAt = System.currentTimeMillis()
        OfflineDiagnostics.log(
            OfflineDiagnostics.TAG_WHISPER,
            "WHISPER_DECODE_START samples=${samples.size} " +
                "seconds=${"%.2f".format(samples.size / 16_000.0)} " +
                "trimmed=${originalSamples - samples.size} language=${languageOverride ?: language} " +
                "threads=${tuning.threads} audio_ctx=$audioCtx " +
                "thread=${Thread.currentThread().name}"
        )
        try {
            return nativeTranscribe(
                handle,
                samples,
                languageOverride ?: language,
                tuning.threads,
                audioCtx,
            )
        } finally {
            val elapsed = System.currentTimeMillis() - startedAt
            OfflineDiagnostics.log(
                OfflineDiagnostics.TAG_WHISPER,
                "WHISPER_DECODE_END elapsed_ms=$elapsed audio_ctx=$audioCtx"
            )
            runCatching {
                lastTimings = nativeLastTimings(handle)
                OfflineDiagnostics.log(
                    OfflineDiagnostics.TAG_WHISPER,
                    "WHISPER_STAGE_TIMING elapsed_ms=$elapsed audio_ctx=$audioCtx $lastTimings"
                )
            }
        }
    }

    /**
     * One second of silence through the full pipeline proves the model and runtime work. The JNI
     * layer throws when whisper_full returns a non-zero status, so a successful smoke test means
     * the decoder really ran, not merely that the file was mapped. Silence is not trimmed, so the
     * smoke test costs one encoder pass over the minimum context instead of the full 30 s window.
     */
    fun smokeTest(): String {
        check(handle != 0L) { "Call prepare() first" }
        // Deliberately bypasses transcribe(): silence is short-circuited there, and the point of
        // the smoke test is that whisper_full really ran on this device.
        val text = decode(FloatArray(SAMPLE_RATE), SAMPLE_RATE, null)
        android.util.Log.i(
            "AvnivoWhisper",
            "WHISPER_SMOKE_OK file=${modelFile.name} chars=${text.length} info=${modelDescription ?: "-"}"
        )
        return text
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
        modelDescription = null
        lastTimings = ""
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeModelInfo(handle: Long): String
    private external fun nativeLastTimings(handle: Long): String
    private external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        language: String,
        threads: Int,
        audioCtx: Int,
    ): String
    private external fun nativeRelease(handle: Long)
}
