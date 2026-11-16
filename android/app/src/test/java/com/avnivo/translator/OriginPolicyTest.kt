package com.avnivo.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real JVM tests (no Android framework, no mocks) for the security-relevant WebView
 * policy that decides which origin may navigate and which may use the microphone.
 */
class OriginPolicyTest {

    @Test
    fun localOriginPathsAreTreatedAsLocal() {
        assertTrue(OriginPolicy.isLocalNavigation("https://appassets.androidplatform.net/assets/index.html"))
        assertTrue(OriginPolicy.isLocalNavigation("https://appassets.androidplatform.net/"))
        assertTrue(OriginPolicy.isLocalNavigation(OriginPolicy.LOCAL_ORIGIN))
    }

    @Test
    fun externalOriginsAreBlocked() {
        assertFalse(OriginPolicy.isLocalNavigation("https://evil.example.com/"))
        assertFalse(OriginPolicy.isLocalNavigation("http://appassets.androidplatform.net/assets/index.html"))
        assertFalse(OriginPolicy.isLocalNavigation("https://appassets.androidplatform.net.evil.com/"))
        assertFalse(OriginPolicy.isLocalNavigation("file:///etc/passwd"))
    }

    @Test
    fun microphoneGrantedOnlyToTrustedOrigin() {
        assertTrue(OriginPolicy.isTrustedMicOrigin("https://appassets.androidplatform.net"))
        assertTrue(OriginPolicy.isTrustedMicOrigin("https://appassets.androidplatform.net/"))
        assertFalse(OriginPolicy.isTrustedMicOrigin("https://evil.example.com"))
        assertFalse(OriginPolicy.isTrustedMicOrigin("https://appassets.androidplatform.net.evil.com"))
    }

    @Test
    fun microphoneGrantedOnlyForAudioCapture() {
        assertTrue(OriginPolicy.isAudioCaptureOnly(arrayOf(OriginPolicy.AUDIO_CAPTURE)))
        assertFalse(OriginPolicy.isAudioCaptureOnly(arrayOf("android.webkit.resource.VIDEO_CAPTURE")))
        assertFalse(
            OriginPolicy.isAudioCaptureOnly(
                arrayOf(OriginPolicy.AUDIO_CAPTURE, "android.webkit.resource.VIDEO_CAPTURE")
            )
        )
        assertFalse(OriginPolicy.isAudioCaptureOnly(emptyArray()))
    }

    @Test
    fun shouldGrantMicCombinesBothGates() {
        val ok = arrayOf(OriginPolicy.AUDIO_CAPTURE)
        assertTrue(OriginPolicy.shouldGrantMic("https://appassets.androidplatform.net", ok))
        assertFalse(OriginPolicy.shouldGrantMic("https://evil.example.com", ok))
        assertFalse(
            OriginPolicy.shouldGrantMic(
                "https://appassets.androidplatform.net",
                arrayOf("android.webkit.resource.VIDEO_CAPTURE")
            )
        )
    }

    @Test
    fun startUrlStaysInsideTrustedOrigin() {
        assertTrue(OriginPolicy.isLocalNavigation(OriginPolicy.START_URL))
        assertEquals("https://appassets.androidplatform.net/assets/index.html", OriginPolicy.START_URL)
    }
}
