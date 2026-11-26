package com.avnivo.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone grant rules, exercised without an Activity. This is the decision that silently
 * returned `deny()` for every online capture before the fix, so it is pinned here.
 */
class MicPermissionPolicyTest {

    private val audio = MicPermissionPolicy.AUDIO_CAPTURE
    private val video = "android.webkit.resource.VIDEO_CAPTURE"

    @Test fun bundledAssetPageIsTrusted() {
        assertTrue(MicPermissionPolicy.isTrustedOrigin("file", "android_asset"))
    }

    @Test fun fileOriginWithoutHostIsTrusted() {
        assertTrue(MicPermissionPolicy.isTrustedOrigin("file", ""))
        assertTrue(MicPermissionPolicy.isTrustedOrigin("file", null))
    }

    @Test fun remoteOriginsAreNotTrusted() {
        assertFalse(MicPermissionPolicy.isTrustedOrigin("https", "evil.example"))
        assertFalse(MicPermissionPolicy.isTrustedOrigin("https", "avnivo.example"))
        assertFalse(MicPermissionPolicy.isTrustedOrigin("http", "android_asset"))
        assertFalse(MicPermissionPolicy.isTrustedOrigin(null, "android_asset"))
    }

    @Test fun audioOnlyRequestIsGrantedAsAudioOnly() {
        assertEquals(
            listOf(audio),
            MicPermissionPolicy.grantableResources("file", "android_asset", listOf(audio))
        )
    }

    @Test fun aMixedRequestNeverWidensTheGrant() {
        assertEquals(
            "video capture must never be granted",
            listOf(audio),
            MicPermissionPolicy.grantableResources("file", "android_asset", listOf(audio, video))
        )
    }

    @Test fun videoOnlyRequestIsDenied() {
        assertNull(MicPermissionPolicy.grantableResources("file", "android_asset", listOf(video)))
    }

    @Test fun trustedOriginWithoutAudioIsDenied() {
        assertNull(MicPermissionPolicy.grantableResources("file", "android_asset", emptyList()))
    }

    @Test fun remoteOriginIsDeniedEvenForAudio() {
        assertNull(MicPermissionPolicy.grantableResources("https", "evil.example", listOf(audio)))
    }
}
