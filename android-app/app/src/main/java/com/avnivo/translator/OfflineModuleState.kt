package com.avnivo.translator

/**
 * Activation lifecycle for one offline module.
 *
 * A downloaded model is not an operational engine: [READY] is only reachable after the native
 * runtime initializes and a functional inference smoke test passes.
 */
internal enum class OfflineModuleState {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    INSTALLED,
    INITIALIZING,
    READY,
    RECORDING,
    TRANSCRIBING,
    ERROR;

    /** Lowercase wire code exposed to the WebView bridge. */
    val code: String get() = name.lowercase()

    /** True while a verified engine is resident: ready, capturing audio, or decoding it. */
    val isOperational: Boolean
        get() = this == READY || this == RECORDING || this == TRANSCRIBING

    /**
     * Allowed forward/recovery transitions. Any transition that would promote a module straight
     * from a download step to [READY] is rejected, so a completed download alone can never be
     * reported as an active engine. Capture and decode states are only reachable from a loaded
     * engine path (INSTALLED/INITIALIZING/READY) and always settle back to READY or ERROR.
     */
    fun canTransitionTo(next: OfflineModuleState): Boolean = when (this) {
        NOT_INSTALLED -> next in setOf(DOWNLOADING, INSTALLED, INITIALIZING, ERROR)
        DOWNLOADING -> next in setOf(VERIFYING, NOT_INSTALLED, ERROR)
        VERIFYING -> next in setOf(INSTALLED, NOT_INSTALLED, ERROR)
        INSTALLED -> next in setOf(INITIALIZING, RECORDING, NOT_INSTALLED, ERROR)
        INITIALIZING -> next in setOf(READY, RECORDING, INSTALLED, ERROR)
        READY -> next in setOf(INITIALIZING, RECORDING, TRANSCRIBING, INSTALLED, NOT_INSTALLED, ERROR)
        RECORDING -> next in setOf(TRANSCRIBING, READY, INSTALLED, ERROR)
        TRANSCRIBING -> next in setOf(READY, RECORDING, INSTALLED, ERROR)
        ERROR -> next in setOf(DOWNLOADING, INITIALIZING, RECORDING, INSTALLED, NOT_INSTALLED)
    }
}
