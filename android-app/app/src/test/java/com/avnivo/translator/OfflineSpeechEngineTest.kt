package com.avnivo.translator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Verifies the file-system readiness gate that guards engine initialization. */
class OfflineSpeechEngineTest {

    @get:Rule val temp = TemporaryFolder()

    private fun install(dir: File, modelBytes: Int = 10, tokensBytes: Int = 4) {
        dir.mkdirs()
        File(dir, OfflineSpeechEngine.MODEL_FILE).writeBytes(ByteArray(modelBytes))
        File(dir, OfflineSpeechEngine.TOKENS_FILE).writeBytes(ByteArray(tokensBytes))
    }

    @Test fun readyWhenBothFilesPresentAndNonEmpty() {
        val dir = temp.newFolder("both")
        install(dir)
        assertTrue(OfflineSpeechEngine.hasRequiredFiles(dir))
    }

    @Test fun notReadyWhenModelMissing() {
        val dir = temp.newFolder("nomodel")
        dir.mkdirs()
        File(dir, OfflineSpeechEngine.TOKENS_FILE).writeBytes(ByteArray(4))
        assertFalse(OfflineSpeechEngine.hasRequiredFiles(dir))
    }

    @Test fun notReadyWhenTokensMissing() {
        val dir = temp.newFolder("notokens")
        dir.mkdirs()
        File(dir, OfflineSpeechEngine.MODEL_FILE).writeBytes(ByteArray(10))
        assertFalse(OfflineSpeechEngine.hasRequiredFiles(dir))
    }

    @Test fun notReadyWhenModelEmpty() {
        val dir = temp.newFolder("emptymodel")
        install(dir, modelBytes = 0)
        assertFalse(OfflineSpeechEngine.hasRequiredFiles(dir))
    }

    @Test fun notReadyWhenDirectoryMissing() {
        assertFalse(OfflineSpeechEngine.hasRequiredFiles(File(temp.root, "absent")))
    }
}
