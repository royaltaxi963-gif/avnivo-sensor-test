package com.avnivo.translator

import java.io.Closeable
import java.io.File

/** Offline Whisper (ggml/whisper.cpp) recognizer. All calls must run off the UI thread. */
internal class WhisperEngine(private val modelFile: File, private val language: String) : Closeable {
    private var handle: Long = 0L

    /** Model metadata returned by whisper.cpp after a successful load, for device diagnostics. */
    @Volatile var modelDescription: String? = null
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

    @Synchronized
    fun transcribe(samples: FloatArray, languageOverride: String? = null): String {
        check(handle != 0L) { "Call prepare() first" }
        require(samples.isNotEmpty()) { "Empty recording" }
        // The source language belongs to the recording session, not to the model instance: one
        // resident Whisper engine serves every non-Uzbek/English source in turn.
        val startedAt = System.currentTimeMillis()
        OfflineDiagnostics.log(
            OfflineDiagnostics.TAG_WHISPER,
            "WHISPER_DECODE_START samples=${samples.size} " +
                "seconds=${"%.2f".format(samples.size / 16_000.0)} language=${languageOverride ?: language} " +
                "thread=${Thread.currentThread().name}"
        )
        try {
            return nativeTranscribe(handle, samples, languageOverride ?: language)
        } finally {
            OfflineDiagnostics.log(
                OfflineDiagnostics.TAG_WHISPER,
                "WHISPER_DECODE_END elapsed_ms=${System.currentTimeMillis() - startedAt}"
            )
        }
    }

    /**
     * One second of silence through the full pipeline proves the model and runtime work. The JNI
     * layer throws when whisper_full returns a non-zero status, so a successful smoke test means
     * the decoder really ran, not merely that the file was mapped.
     */
    fun smokeTest(): String {
        val text = transcribe(FloatArray(SAMPLE_RATE))
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
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeModelInfo(handle: Long): String
    private external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String): String
    private external fun nativeRelease(handle: Long)
}
