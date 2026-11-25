package com.avnivo.translator

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs one native call on its own thread and refuses to wait for it forever.
 *
 * A native frame cannot be interrupted, so a load or decode that never returns cannot be killed.
 * What can be fixed is everything around it: the caller gets a stage-named failure instead of
 * blocking for the lifetime of the process, the state machine settles on ERROR, and the engine
 * that is still inside native code is quarantined instead of being freed under a running call.
 *
 * The device report this exists for: Whisper Large V3 Turbo stayed on «Ishga tushmoqda…» and the
 * Uzbek card stayed on «Nutq matnga aylantirilmoqda…» until the app was force-stopped, and neither
 * card could be deactivated afterwards, because both waits were unbounded and both were reached
 * while holding the only lock that could have released them.
 */
internal object OfflineGuard {

    /** A native call that did not return within its budget. Carries the stage for the card text. */
    class StageTimeout(
        val stage: String,
        val elapsedMs: Long,
        val budgetMs: Long
    ) : IllegalStateException(
        "$stage bosqichida javob bermadi (${elapsedMs / 1000}s, chegara ${budgetMs / 1000}s)"
    )

    /**
     * Loading 574 MB of quantized weights plus a smoke decode. A healthy phone needs seconds; the
     * budget only has to be long enough that a slow but working load is never cut short.
     */
    @Volatile var loadBudgetMs: Long = 180_000

    /** Uzbek NeMo: ONNX session creation plus a one-second smoke decode. */
    @Volatile var prepareBudgetMs: Long = 120_000

    /** Decoding always costs more than the audio it reads; these bound the pathological case. */
    @Volatile var decodeFloorMs: Long = 45_000
    @Volatile var decodePerAudioSecondMs: Long = 20_000
    @Volatile var decodeCeilingMs: Long = 600_000

    fun decodeBudgetMs(audioSamples: Int): Long {
        val audioSeconds = audioSamples / 16_000.0
        val budget = decodeFloorMs + (audioSeconds * decodePerAudioSecondMs).toLong()
        return budget.coerceAtMost(decodeCeilingMs)
    }

    /**
     * Runs [block] on a daemon thread and waits at most [budgetMs].
     *
     * [onLateResult] is called on the worker thread if the call finishes after the caller already
     * gave up, so a resource created by an abandoned call is still released.
     */
    fun <T> bounded(
        stage: String,
        budgetMs: Long,
        log: (String) -> Unit,
        onLateResult: (T) -> Unit = {},
        block: () -> T
    ): T {
        val outcome = AtomicReference<Result<T>?>(null)
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        val startedAt = System.currentTimeMillis()
        log("STAGE_START $stage budget_ms=$budgetMs")
        val worker = Thread({
            val result = runCatching(block)
            val elapsed = System.currentTimeMillis() - startedAt
            outcome.set(result)
            if (abandoned.get()) {
                result.getOrNull()?.let { late -> runCatching { onLateResult(late) } }
            }
            log(
                "STAGE_END $stage elapsed_ms=$elapsed ok=${result.isSuccess} late=${abandoned.get()}" +
                    (result.exceptionOrNull()?.let { " error=${it::class.java.simpleName}:${it.message}" } ?: "")
            )
        }, "avnivo-$stage")
        worker.isDaemon = true
        worker.start()
        worker.join(budgetMs)
        var done = outcome.get()
        if (done == null) {
            abandoned.set(true)
            // The call may have finished between join() returning and the flag being set. That
            // result is still released, because this caller has already stopped waiting for it.
            done = outcome.get()
            val elapsed = System.currentTimeMillis() - startedAt
            done?.getOrNull()?.let { late -> runCatching { onLateResult(late) } }
            log("STAGE_TIMEOUT $stage elapsed_ms=$elapsed budget_ms=$budgetMs")
            throw StageTimeout(stage, elapsed, budgetMs)
        }
        return done.getOrThrow()
    }

    /**
     * [bounded] for a call that produces a [Closeable] engine. An engine that arrives after the
     * caller gave up is closed here, so an abandoned load cannot leak a 574 MB model.
     */
    fun <T : Closeable> boundedCloseable(
        stage: String,
        budgetMs: Long,
        log: (String) -> Unit,
        block: () -> T
    ): T = bounded(stage, budgetMs, log, onLateResult = { late -> runCatching { late.close() } }, block = block)
}
