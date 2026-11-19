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

    @Test fun catalogOnlyContainsRealEngines() {
        // Whisper Small, Whisper Large and NLLB are the verified downloadable modules.
        assertEquals(
            setOf("whisper-small-q8", "whisper-large-v3-turbo", "nllb-200-600m-int8"),
            OfflineModelSpecs.all.keys
        )
        assertTrue(OfflineModelSpecs.all.values.all { it.files.isNotEmpty() })
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

    @Test fun moduleWithoutRuntimeNeverDownloads() {
        // NLLB has no on-device runtime in this build, so its card must not fetch ~916 MB first.
        val spec = OfflineModelSpecs.all.getValue("nllb-200-600m-int8")
        assertFalse(spec.runnable)
        val manager = manager()
        manager.download(spec.id)
        manager.awaitIdle()

        assertEquals("not_installed", JSONObject(manager.status(spec.id)).getString("state"))
        assertFalse(spec.directory(temp.root).exists())
        assertTrue(messages.any { it.first == "AvnivoOfflineModuleError" && it.second.startsWith(spec.id) })
        manager.close()
    }

    @Test fun everyRunnableModuleHasAVerifiedEngineKind() {
        for (spec in OfflineModelSpecs.all.values.filter { it.runnable }) {
            assertEquals(OfflineEngineKind.WHISPER_GGML, spec.engine)
            assertTrue("${spec.id} needs a verified byte size and hash", spec.files.values.all { it.first > 0 && it.second.length == 64 })
        }
    }
}
