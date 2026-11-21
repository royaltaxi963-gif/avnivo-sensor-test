package com.avnivo.translator

import java.io.Closeable
import java.io.File

/** Offline Whisper (ggml/whisper.cpp) recognizer. All calls must run off the UI thread. */
internal class WhisperEngine(private val modelFile: File, private val language: String) : Closeable {
    private var handle: Long = 0L

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

    fun prepare() {
        check(handle == 0L) { "Recognizer already initialized" }
        require(ensureNativeLoaded()) { "Whisper native library is unavailable" }
        require(modelFile.isFile && modelFile.length() > 0L) { "Missing model file" }
        handle = nativeInit(modelFile.absolutePath)
        require(handle != 0L) { "Whisper model failed to load" }
    }

    fun transcribe(samples: FloatArray): String {
        check(handle != 0L) { "Call prepare() first" }
        require(samples.isNotEmpty()) { "Empty recording" }
        return nativeTranscribe(handle, samples, language)
    }

    /** One second of silence through the full pipeline proves the model and runtime work. */
    fun smokeTest(): String = transcribe(FloatArray(SAMPLE_RATE))

    override fun close() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String): String
    private external fun nativeRelease(handle: Long)
}
