package com.avnivo.translator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable

/** One-shot bounded microphone capture. Invoke capture() from a worker thread. */
class OfflinePcmRecorder(private val context: Context) : Closeable {
    @Volatile private var stopped = false
    @Volatile private var recorder: AudioRecord? = null
    private val sampleRate = 16000

    @SuppressLint("MissingPermission")
    fun capture(maxSeconds: Int = 30): FloatArray {
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
                for (i in 0 until read) data[count++] = buffer[i] / 32768f
            }
            return data.copyOf(count)
        } finally {
            stopped = true
            try { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() } catch (_: Exception) {}
            audio.release()
            recorder = null
        }
    }

    fun stop() {
        stopped = true
        // Do not release from UI thread while read() is active; capture() owns release.
        try { recorder?.stop() } catch (_: Exception) {}
    }

    override fun close() = stop()
}
