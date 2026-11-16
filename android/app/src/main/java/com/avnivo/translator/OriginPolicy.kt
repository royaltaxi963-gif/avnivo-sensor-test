package com.avnivo.translator

/**
 * Security-relevant WebView policy, kept free of Android framework types so it can
 * be exercised by plain JVM unit tests.
 *
 * The interface is served from [LOCAL_ORIGIN] via WebViewAssetLoader. Only that
 * origin may be granted the microphone, and navigation to any other origin is
 * blocked so the shell cannot be redirected to untrusted content.
 */
object OriginPolicy {

    const val LOCAL_ORIGIN = "https://appassets.androidplatform.net"
    const val START_URL = "$LOCAL_ORIGIN/assets/index.html"

    /** Value of Android's `PermissionRequest.RESOURCE_AUDIO_CAPTURE`. */
    const val AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE"

    /** True when [url] stays within the bundled local origin (i.e. must not be overridden). */
    fun isLocalNavigation(url: String): Boolean =
        url.startsWith("$LOCAL_ORIGIN/") || url == LOCAL_ORIGIN

    /** WebView reports origins without a trailing slash; normalize before comparing. */
    fun normalizeOrigin(origin: String): String = origin.trimEnd('/')

    /** The microphone may be granted only to the trusted bundled origin. */
    fun isTrustedMicOrigin(origin: String): Boolean =
        normalizeOrigin(origin) == LOCAL_ORIGIN

    /** The microphone may be granted only for audio capture, and nothing else. */
    fun isAudioCaptureOnly(resources: Array<out String>): Boolean =
        resources.size == 1 && resources[0] == AUDIO_CAPTURE

    /** Combined gate used before granting a WebView permission request. */
    fun shouldGrantMic(origin: String, resources: Array<out String>): Boolean =
        isTrustedMicOrigin(origin) && isAudioCaptureOnly(resources)
}
