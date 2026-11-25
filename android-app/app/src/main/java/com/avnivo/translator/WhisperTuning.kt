package com.avnivo.translator

/**
 * Decode knobs for a resident Whisper engine.
 *
 * Only two things are set here: how many threads ggml may use, and how much of the encoder window
 * the recording actually needs. Everything else stays at whisper.cpp's defaults - in particular the
 * temperature ladder, which is what catches the repetition loops a short encoder window can cause.
 *
 * The window matters because whisper.cpp always encodes 30 s, whether the recording is three
 * seconds or twenty-five: measured on a 5 s clip, the encoder was 37.5 s of a 37.7 s decode, while
 * the decoder took 79 ms. Encoding only the positions that carry audio is therefore the whole
 * optimisation; the decoder is noise.
 */
internal data class WhisperTuning(
    val threads: Int = defaultThreads(),
    val maxAudioCtx: Int = MAX_AUDIO_CTX,
) {
    /** Encoder positions this recording needs, never more than the model has. */
    fun audioContextFor(samples: Int): Int = audioContextFor(samples, maxAudioCtx)

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_AUDIO_CTX = 1500

        /** whisper.cpp consumes 100 mel frames per second and halves them in the encoder conv. */
        private const val ENCODER_POSITIONS_PER_SECOND = 50

        /**
         * Trailing silence left after the speech, in encoder positions (160 = 1.6 s).
         *
         * Whisper is trained on 30 s windows that are mostly silence, and cutting the window down
         * to the exact speech length takes it out of that distribution: a 3 s clip decoded at 214
         * positions looped on "and so my fellow Americans" until the token limit, while the same
         * clip at 288 positions - 1.4 s of padding - decoded correctly. 160 is that threshold plus
         * a margin. Clips that still trip a loop are caught by whisper's temperature ladder.
         */
        private const val MIN_TRAILING_PADDING = 160

        /** Below this the encoder is not worth calling with less; measured cost is ~25 ms/position. */
        private const val MIN_AUDIO_CTX = 320

        /** Mobile ceiling: ggml spins on a barrier, so a little core in the pool stalls the rest. */
        const val MAX_THREADS = 4

        fun defaultThreads(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)

        fun audioContextFor(samples: Int, maxAudioCtx: Int = MAX_AUDIO_CTX): Int {
            if (samples <= 0) return MIN_AUDIO_CTX.coerceAtMost(maxAudioCtx)
            val positions = (samples.toLong() * ENCODER_POSITIONS_PER_SECOND / SAMPLE_RATE).toInt()
            val padding = maxOf(MIN_TRAILING_PADDING, positions / 2)
            return (positions + padding).coerceIn(MIN_AUDIO_CTX, maxAudioCtx)
        }
    }
}
