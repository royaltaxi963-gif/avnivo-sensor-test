package com.avnivo.translator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Gesture-controlled microphone capture. [capture] runs on a worker thread and returns as soon as
 * [stop] is called, so recording lasts exactly as long as the user holds the microphone button.
 * [onAmplitude] receives a 0..1 loudness value per audio buffer for the live waveform.
 */
class OfflinePcmRecorder(private val context: Context) : Closeable {
    @Volatile private var stopped = false
    @Volatile private var recorder: AudioRecord? = null
    private val sampleRate = 16000

    @SuppressLint("MissingPermission")
    fun capture(maxSeconds: Int = 30, onAmplitude: ((Float) -> Unit)? = null): FloatArray {
        require(maxSeconds in 1..120)
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission required"
        }
        val minBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBytes > 0) { "16 kHz PCM microphone unsupported" }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes * 2, 3200)
        )
        recorder = audio
        // Primitive buffer avoids boxing hundreds of thousands of PCM samples.
        val data = FloatArray(sampleRate * maxSeconds)
        var count = 0
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
            audio.startRecording()
            val buffer = ShortArray(1600)
            val limit = sampleRate * maxSeconds
            while (!stopped && count < limit) {
                val read = audio.read(buffer, 0, minOf(buffer.size, limit - count))
                if (read < 0) error("AudioRecord read error $read")
                var sum = 0.0
                for (i in 0 until read) {
                    val value = buffer[i] / 32768f
                    data[count++] = value
                    sum += value.toDouble() * value
                }
                if (onAmplitude != null && read > 0) onAmplitude(amplitude(sum, read))
            }
            return data.copyOf(count)
        } finally {
            stopped = true
            try { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() } catch (_: Exception) {}
            audio.release()
            recorder = null
        }
    }

    /** Requests capture to end at the next buffer boundary. Safe to call from the UI thread. */
    fun stop() {
        stopped = true
    }

    override fun close() = stop()

    companion object {
        /** Mean-square to 0..1 loudness, matching the browser waveform's amplitude scaling. */
        fun amplitude(squaredSum: Double, sampleCount: Int): Float {
            if (sampleCount <= 0) return 0f
            return min(1.0, sqrt(squaredSum / sampleCount) * 9.0).toFloat()
        }
    }
}
