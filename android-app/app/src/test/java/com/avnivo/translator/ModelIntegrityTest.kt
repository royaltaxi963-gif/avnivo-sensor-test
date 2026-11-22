package com.avnivo.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Integrity is a digest, not a length. A truncated or overwritten model can keep a plausible size,
 * so these tests use real bytes and real hashes against a real temporary directory.
 */
class ModelIntegrityTest {

    @get:Rule val temp = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun spec(vararg files: Pair<String, ByteArray>): OfflineModelSpec = OfflineModelSpec(
        id = "test-model",
        engine = OfflineEngineKind.WHISPER_GGML,
        files = files.associate { (name, bytes) -> name to (bytes.size.toLong() to sha256(bytes)) }
    )

    private fun write(dir: File, name: String, bytes: ByteArray) {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    @Test fun matchingContentVerifies() {
        val dir = temp.newFolder("models", "test-model")
        val model = "model bytes".toByteArray()
        write(dir, "ggml.bin", model)

        val result = ModelIntegrity.verify(dir, spec("ggml.bin" to model))

        assertTrue("real content with the right digest must verify: $result", result.ok)
        assertEquals(1, result.hashedFiles)
    }

    @Test fun sameLengthDifferentBytesIsRejected() {
        // The exact failure a length check misses: a corrupt file that is still the right size.
        val dir = temp.newFolder("models", "test-model")
        val expected = "AAAAAAAA".toByteArray()
        write(dir, "ggml.bin", "BBBBBBBB".toByteArray())

        val result = ModelIntegrity.verify(dir, spec("ggml.bin" to expected))

        assertFalse("a wrong digest must be rejected", result.ok)
        assertTrue("the reason must name the digest: ${result.reason}", result.reason!!.contains("SHA-256"))
    }

    @Test fun aMissingFileIsRejectedByName() {
        val dir = temp.newFolder("models", "test-model")
        val result = ModelIntegrity.verify(dir, spec("ggml.bin" to "x".toByteArray()))

        assertFalse(result.ok)
        assertTrue("the reason must name the file: ${result.reason}", result.reason!!.contains("ggml.bin"))
    }

    @Test fun aTruncatedFileIsRejectedByLength() {
        val dir = temp.newFolder("models", "test-model")
        val expected = "0123456789".toByteArray()
        write(dir, "ggml.bin", "01234".toByteArray())

        val result = ModelIntegrity.verify(dir, spec("ggml.bin" to expected))

        assertFalse(result.ok)
        assertTrue("the reason must name the size: ${result.reason}", result.reason!!.contains("hajm"))
    }

    @Test fun aVerifiedModelIsNotRehashedOnTheNextPass() {
        // A 916 MB model cannot be hashed on every page load; the stamp must let a repeat pass
        // confirm the same files without reading them again.
        val dir = temp.newFolder("models", "test-model")
        val model = "stable content".toByteArray()
        write(dir, "ggml.bin", model)
        val modelSpec = spec("ggml.bin" to model)

        val first = ModelIntegrity.verify(dir, modelSpec)
        assertTrue(first.ok)
        assertEquals("the first pass must hash", 1, first.hashedFiles)
        assertTrue(ModelIntegrity.isStamped(dir, modelSpec))

        val second = ModelIntegrity.verify(dir, modelSpec)
        assertTrue(second.ok)
        assertEquals("the second pass must reuse the stamp", 0, second.hashedFiles)
    }

    @Test fun changingAFileInvalidatesItsStamp() {
        val dir = temp.newFolder("models", "test-model")
        val model = "content one".toByteArray()
        write(dir, "ggml.bin", model)
        val modelSpec = spec("ggml.bin" to model)
        assertTrue(ModelIntegrity.verify(dir, modelSpec).ok)

        // Same length, different bytes, and a newer timestamp: the stamp must not cover it.
        write(dir, "ggml.bin", "content two".toByteArray())
        assertFalse("a changed file must be hashed again", ModelIntegrity.isStamped(dir, modelSpec))
        assertFalse("and then rejected", ModelIntegrity.verify(dir, modelSpec).ok)
    }

    @Test fun invalidationForcesAFullRehash() {
        val dir = temp.newFolder("models", "test-model")
        val model = "content".toByteArray()
        write(dir, "ggml.bin", model)
        val modelSpec = spec("ggml.bin" to model)
        assertTrue(ModelIntegrity.verify(dir, modelSpec).ok)

        ModelIntegrity.invalidate(dir)

        assertEquals("the next pass must hash from scratch", 1, ModelIntegrity.verify(dir, modelSpec).hashedFiles)
    }

    @Test fun anEmptySpecNeverCountsAsInstalled() {
        val dir = temp.newFolder("models", "test-model")
        val result = ModelIntegrity.verify(dir, OfflineModelSpec("empty", OfflineEngineKind.UNAVAILABLE, emptyMap()))
        assertFalse(result.ok)
    }
}
