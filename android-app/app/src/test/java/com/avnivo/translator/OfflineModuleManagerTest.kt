package com.avnivo.translator

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Exercises the module lifecycle with real files on disk and a real (non-mocked) downloader; only
 * the native engine is substituted through the factory seam, because the JVM has no NDK runtime.
 */
class OfflineModuleManagerTest {

    @get:Rule val temp = TemporaryFolder()

    private val messages = mutableListOf<Pair<String, String>>()

    private fun manager() = OfflineModuleManager(temp.root) { fn, v -> synchronized(messages) { messages += fn to v } }

    private fun placeInstalledModel(spec: OfflineModelSpec) {
        val dir = spec.directory(temp.root).apply { mkdirs() }
        for ((name, sizeAndHash) in spec.files) {
            val file = File(dir, name)
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { it.setLength(sizeAndHash.first) }
        }
    }

    private class FakeEngine : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun unknownModuleFailsClosed() {
        val manager = manager()
        assertNull(manager.status("does-not-exist"))
        assertFalse(manager.hasModule("does-not-exist"))
        manager.close()
    }

    @Test fun aSpecWithNoFilesIsNeverInstalled() {
        // An empty file list must not read as "everything present": that would mark a blocked
        // module INSTALLED at startup and let it look like a downloaded model.
        val blocked = OfflineModelSpecs.all.getValue("navoiy-tts")
        assertFalse(blocked.installed(File(temp.root, "navoiy-tts")))
        assertEquals("not_installed", JSONObject(manager().status("navoiy-tts")).getString("state"))
    }

    @Test fun catalogOnlyContainsRealEngines() {
        // The settings cards must all be backed by a native spec, and every spec that can run
        // must be one of the verified downloadable engines.
        assertEquals(
            setOf("whisper-small-q8", "whisper-large-v3-turbo", "nllb-200-600m-int8"),
            OfflineModelSpecs.all.values.filter { it.runnable }.map { it.id }.toSet()
        )
        assertEquals(
            setOf("whisper-small-q8", "whisper-large-v3-turbo", "nllb-200-600m-int8", "navoiy-tts", "mms-tts"),
            OfflineModelSpecs.all.keys
        )
    }

    @Test fun installedModelInitializesToReady() {
        val spec = OfflineModelSpecs.all.getValue("whisper-small-q8")
        placeInstalledModel(spec)
        val manager = manager()
        val fake = FakeEngine()
        manager.engineFactory = { _, _ -> fake }
        assertEquals("installed", JSONObject(manager.status(spec.id)).getString("state"))

        manager.download(spec.id)
        manager.awaitIdle()

        val status = JSONObject(manager.status(spec.id))
        assertEquals("ready", status.getString("state"))
        assertTrue(status.getBoolean("active"))
        assertTrue(manager.isOperational(spec.id))
        manager.close()
        assertTrue("engine must be released on close", fake.closed)
    }

    @Test fun engineFailureNeverReportsReady() {
        val spec = OfflineModelSpecs.all.getValue("whisper-small-q8")
        placeInstalledModel(spec)
        val manager = manager()
        manager.engineFactory = { _, _ -> throw IllegalStateException("boom") }

        manager.download(spec.id)
        manager.awaitIdle()

        val status = JSONObject(manager.status(spec.id))
        assertEquals("installed", status.getString("state"))
        assertFalse(status.getBoolean("active"))
        assertFalse(manager.isOperational(spec.id))
        manager.close()
    }

    @Test fun wrongSizeIsNotInstalled() {
        val spec = OfflineModelSpecs.all.getValue("whisper-small-q8")
        val dir = spec.directory(temp.root).apply { mkdirs() }
        val (name, sizeAndHash) = spec.files.entries.first()
        File(dir, name).writeBytes(ByteArray(16)) // far smaller than the real model
        assertFalse(spec.installed(dir))
        val manager = manager()
        assertEquals("not_installed", JSONObject(manager.status(spec.id)).getString("state"))
        manager.close()
    }

    @Test fun statusAllReportsEveryModule() {
        val manager = manager()
        val array = org.json.JSONArray(manager.statusAll())
        assertEquals(OfflineModelSpecs.all.size, array.length())
        manager.close()
    }

    @Test fun unavailableEngineNeverDownloads() {
        // A module whose engine is missing must refuse the transfer instead of fetching ~900 MB.
        val spec = OfflineModelSpecs.all.getValue("nllb-200-600m-int8")
            .copy(engine = OfflineEngineKind.UNAVAILABLE)
        val manager = OfflineModuleManager(temp.root, null) { fn, v -> synchronized(messages) { messages += fn to v } }
        manager.registerForTest(spec)
        manager.download(spec.id)
        manager.awaitIdle()

        assertEquals("not_installed", JSONObject(manager.status(spec.id)).getString("state"))
        assertFalse(spec.directory(temp.root).exists())
        assertTrue(messages.any { it.first == "AvnivoOfflineModuleError" && it.second.startsWith(spec.id) })
        manager.close()
    }

    @Test fun everyRunnableModuleHasVerifiedBytesAndHashes() {
        for (spec in OfflineModelSpecs.all.values.filter { it.runnable }) {
            assertTrue("${spec.id} needs files", spec.files.isNotEmpty())
            assertTrue(
                "${spec.id} needs a real byte size and SHA-256",
                spec.files.values.all { it.first > 0 && it.second.matches(Regex("[0-9a-f]{64}")) }
            )
        }
    }

    @Test fun everyRunnableModuleDeclaresARamRequirement() {
        for (spec in OfflineModelSpecs.all.values.filter { it.runnable }) {
            assertTrue("${spec.id} must refuse to load without headroom", spec.requiredRamBytes > 0)
        }
    }

    @Test fun blockedModulesExplainWhyAndShipNoFiles() {
        val blocked = OfflineModelSpecs.all.values.filter { !it.runnable }
        assertTrue("the PyTorch-only TTS modules must be registered", blocked.map { it.id }.containsAll(listOf("navoiy-tts", "mms-tts")))
        for (spec in blocked) {
            assertEquals("${spec.id} must be UNAVAILABLE", OfflineEngineKind.UNAVAILABLE, spec.engine)
            assertTrue("${spec.id} must explain its blocker", (spec.blocker?.length ?: 0) > 20)
            assertTrue("${spec.id} must not offer files to download", spec.files.isEmpty())
        }
    }

    @Test fun aBlockedModuleSurfacesItsBlockerInsteadOfDownloading() {
        val manager = manager()
        try {
            manager.download("navoiy-tts")
            manager.awaitIdle()
            assertEquals("not_installed", JSONObject(manager.status("navoiy-tts")).getString("state"))
            assertFalse(File(temp.root, "models/navoiy-tts").exists())
            val message = messages.first { it.first == "AvnivoOfflineModuleError" }.second
            assertTrue("must name the module", message.startsWith("navoiy-tts|"))
            assertTrue("must mention PyTorch", message.contains("PyTorch"))
        } finally {
            manager.close()
        }
    }

    @Test fun nllbModelIsRunnableNow() {
        val spec = OfflineModelSpecs.all.getValue("nllb-200-600m-int8")
        assertEquals(OfflineEngineKind.NLLB_ONNX, spec.engine)
        assertTrue(spec.files.containsKey("onnx/encoder_model_quantized.onnx"))
        assertTrue(spec.files.containsKey("tokenizer.json"))
    }
}
