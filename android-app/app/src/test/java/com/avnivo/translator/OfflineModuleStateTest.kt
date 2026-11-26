package com.avnivo.translator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the activation state machine never lets a download jump straight to READY. */
class OfflineModuleStateTest {

    @Test fun happyPathIsAllowed() {
        assertTrue(OfflineModuleState.NOT_INSTALLED.canTransitionTo(OfflineModuleState.DOWNLOADING))
        assertTrue(OfflineModuleState.DOWNLOADING.canTransitionTo(OfflineModuleState.VERIFYING))
        assertTrue(OfflineModuleState.VERIFYING.canTransitionTo(OfflineModuleState.INSTALLED))
        assertTrue(OfflineModuleState.INSTALLED.canTransitionTo(OfflineModuleState.INITIALIZING))
        assertTrue(OfflineModuleState.INITIALIZING.canTransitionTo(OfflineModuleState.READY))
    }

    @Test fun downloadCannotBecomeReadyDirectly() {
        assertFalse(OfflineModuleState.NOT_INSTALLED.canTransitionTo(OfflineModuleState.READY))
        assertFalse(OfflineModuleState.DOWNLOADING.canTransitionTo(OfflineModuleState.READY))
        assertFalse(OfflineModuleState.VERIFYING.canTransitionTo(OfflineModuleState.READY))
        assertFalse(OfflineModuleState.INSTALLED.canTransitionTo(OfflineModuleState.READY))
    }

    @Test fun onlyLoadedEngineStatesAreOperational() {
        OfflineModuleState.entries.forEach {
            val expected = it == OfflineModuleState.READY ||
                it == OfflineModuleState.RECORDING ||
                it == OfflineModuleState.TRANSCRIBING
            assertTrue("$it operational=${it.isOperational}", it.isOperational == expected)
        }
    }

    @Test fun captureAndDecodeFollowTheReadyPath() {
        assertTrue(OfflineModuleState.READY.canTransitionTo(OfflineModuleState.RECORDING))
        assertTrue(OfflineModuleState.RECORDING.canTransitionTo(OfflineModuleState.TRANSCRIBING))
        assertTrue(OfflineModuleState.TRANSCRIBING.canTransitionTo(OfflineModuleState.READY))
        assertTrue("a new press may start while the previous decode finishes",
            OfflineModuleState.TRANSCRIBING.canTransitionTo(OfflineModuleState.RECORDING))
        assertFalse("a download can never start a capture",
            OfflineModuleState.DOWNLOADING.canTransitionTo(OfflineModuleState.RECORDING))
        assertFalse("an uninstalled module can never start a capture",
            OfflineModuleState.NOT_INSTALLED.canTransitionTo(OfflineModuleState.RECORDING))
    }

    @Test fun failureStatesAreReachableFromEveryStage() {
        OfflineModuleState.entries.filter { it != OfflineModuleState.ERROR }.forEach {
            assertTrue("$it must be able to fail", it.canTransitionTo(OfflineModuleState.ERROR))
        }
    }

    @Test fun errorCanRecover() {
        assertTrue(OfflineModuleState.ERROR.canTransitionTo(OfflineModuleState.DOWNLOADING))
        assertTrue(OfflineModuleState.ERROR.canTransitionTo(OfflineModuleState.INITIALIZING))
    }

    @Test fun wireCodesMatchBridgeContract() {
        assertTrue(OfflineModuleState.READY.code == "ready")
        assertTrue(OfflineModuleState.NOT_INSTALLED.code == "not_installed")
        assertTrue(OfflineModuleState.RECORDING.code == "recording")
        assertTrue(OfflineModuleState.TRANSCRIBING.code == "transcribing")
        assertTrue(OfflineModuleState.INITIALIZING.code == "initializing")
    }
}
