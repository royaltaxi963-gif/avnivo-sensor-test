package com.avnivo.translator

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays synthesized PCM through [AudioTrack], with cancellation and replay.
 *
 * Playback runs on its own thread so a long utterance never blocks the WebView or the UI thread.
 * [stop] is safe to call from any thread and unblocks a write that is waiting for the mixer.
 */
internal class SileroTtsPlayer : Closeable {

    private val playing = AtomicBoolean(false)
    private var track: AudioTrack? = null

    val isPlaying: Boolean get() = playing.get()

    /** Plays [pcm] to completion. Returns false when playback was cancelled before it finished. */
    fun play(pcm: FloatArray): Boolean {
        if (pcm.isEmpty()) return false
        val samples = ShortArray(pcm.size) { i ->
            (pcm[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val bytes = samples.size * 2
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SileroTtsEngine.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes)
            .build()
        track = created
        playing.set(true)
        try {
            created.write(samples, 0, samples.size)
            created.play()
            // Wait for the mixer to drain, but stop immediately if cancellation arrives.
            val deadline = System.nanoTime() +
                (samples.size.toLong() * 1_000_000_000L / SileroTtsEngine.SAMPLE_RATE) + 200_000_000L
            while (playing.get() && System.nanoTime() < deadline) {
                if (created.playbackHeadPosition >= samples.size) break
                Thread.sleep(10)
            }
            return playing.get()
        } finally {
            playing.set(false)
            runCatching { created.stop() }
            runCatching { created.release() }
            track = null
        }
    }

    fun stop() {
        playing.set(false)
        track?.let { runCatching { it.pause() } }
    }

    override fun close() {
        stop()
    }
}
