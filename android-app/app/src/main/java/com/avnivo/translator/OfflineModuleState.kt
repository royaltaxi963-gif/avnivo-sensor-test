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
    ERROR;

    /** Lowercase wire code exposed to the WebView bridge. */
    val code: String get() = name.lowercase()

    /** True only when the engine is genuinely operational. */
    val isOperational: Boolean get() = this == READY

    /**
     * Allowed forward/recovery transitions. Any transition that would promote a module straight
     * from a download step to [READY] is rejected, so a completed download alone can never be
     * reported as an active engine.
     */
    fun canTransitionTo(next: OfflineModuleState): Boolean = when (this) {
        NOT_INSTALLED -> next in setOf(DOWNLOADING, INITIALIZING, ERROR)
        DOWNLOADING -> next in setOf(VERIFYING, NOT_INSTALLED, ERROR)
        VERIFYING -> next in setOf(INSTALLED, NOT_INSTALLED, ERROR)
        INSTALLED -> next in setOf(INITIALIZING, NOT_INSTALLED, ERROR)
        INITIALIZING -> next in setOf(READY, INSTALLED, ERROR)
        READY -> next in setOf(INITIALIZING, INSTALLED, NOT_INSTALLED, ERROR)
        ERROR -> next in setOf(DOWNLOADING, INITIALIZING, INSTALLED, NOT_INSTALLED)
    }
}
