package com.avnivo.translator

/**
 * The single decision point for WebView audio capture. Deliberately free of Android types so the
 * rules can be exercised on the JVM: before the fix this decision silently returned `deny()` for
 * every online capture, which is why no permission dialog and no waveform ever appeared.
 */
internal object MicPermissionPolicy {

    /** Mirrors `PermissionRequest.RESOURCE_AUDIO_CAPTURE` without loading the framework class. */
    const val AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE"

    /** Only the packaged asset page is trusted; a remote origin must never inherit the grant. */
    fun isTrustedOrigin(scheme: String?, host: String?): Boolean =
        scheme == "file" && (host.isNullOrEmpty() || host == "android_asset")

    /**
     * The resources to grant, or null when the request must be denied. Returning an explicit
     * audio-only list keeps a mixed request (e.g. audio + video) from widening the grant.
     */
    fun grantableResources(scheme: String?, host: String?, resources: List<String>): List<String>? {
        if (!isTrustedOrigin(scheme, host)) return null
        if (!resources.contains(AUDIO_CAPTURE)) return null
        return listOf(AUDIO_CAPTURE)
    }
}
