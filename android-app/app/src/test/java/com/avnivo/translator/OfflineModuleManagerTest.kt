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
            setOf("whisper-small-q8", "whisper-large-v3-turbo", "nllb-200-600m-int8", "silero-tts"),
            OfflineModelSpecs.all.values.filter { it.runnable }.map { it.id }.toSet()
        )
        assertEquals(
            setOf("whisper-small-q8", "whisper-large-v3-turbo", "nllb-200-600m-int8", "navoiy-tts", "mms-tts", "silero-tts"),
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
        // A bare "error"/"installed" state is not diagnosable in the field, so the cause travels
        // with it.
        assertTrue(
            "failure reason must reach the status payload: $status",
            status.getString("reason").contains("boom")
        )
        manager.close()
    }

    @Test fun supportedLanguagesIsEmptyUntilTheModuleIsOperational() {
        // The picker must never be fed a list before the engine is really up. A model that is
        // merely present on disk, or one whose engine threw, still yields nothing.
        val spec = OfflineModelSpecs.all.getValue("nllb-200-600m-int8")
        val manager = manager()
        assertEquals(emptyList<String>(), manager.supportedLanguages(spec.id))

        placeInstalledModel(spec)
        val fresh = manager()
        assertEquals("installed", JSONObject(fresh.status(spec.id)).getString("state"))
        assertEquals(emptyList<String>(), fresh.supportedLanguages(spec.id))

        fresh.engineFactory = { _, _ -> throw IllegalStateException("boom") }
        fresh.download(spec.id)
        fresh.awaitIdle()
        assertFalse(fresh.isOperational(spec.id))
        assertEquals(emptyList<String>(), fresh.supportedLanguages(spec.id))
        manager.close()
        fresh.close()
    }

    @Test fun supportedLanguagesFailsClosedForUnknownAndForeignModules() {
        val manager = manager()
        assertEquals(emptyList<String>(), manager.supportedLanguages("does-not-exist"))
        // A non-NLLB engine must not be asked for a language list it cannot produce.
        val whisper = OfflineModelSpecs.all.getValue("whisper-small-q8")
        placeInstalledModel(whisper)
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.download(whisper.id)
        manager.awaitIdle()
        assertTrue(manager.isOperational(whisper.id))
        assertEquals(emptyList<String>(), manager.supportedLanguages(whisper.id))
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

    @Test fun sileroTtsIsARunnableMitLicensedUzbekEngine() {
        val spec = OfflineModelSpecs.all.getValue("silero-tts")
        assertEquals(OfflineEngineKind.SILERO_TTS, spec.engine)
        assertTrue("must ship the TorchScript artifact", spec.files.containsKey("silero_v21.ptl"))
        val (bytes, sha) = spec.files.getValue("silero_v21.ptl")
        assertEquals(91_988_758L, bytes)
        assertEquals("333128fb6c7476e7fceec562f3f54980cbb6a88bbe59ff4a8e67e6cddad7a861", sha)
        assertTrue("must declare a RAM floor", spec.requiredRamBytes > 0)
        assertNull("a runnable engine has no blocker", spec.blocker)
    }

    @Test fun sileroEngineFailureNeverReportsReady() {
        // The native interpreter is absent on the JVM, so this asserts the fail-closed path that a
        // device hits when the .ptl is corrupt or the runtime refuses to load it.
        val spec = OfflineModelSpecs.all.getValue("silero-tts")
        placeInstalledModel(spec)
        val manager = manager()
        manager.engineFactory = { _, _ -> throw IllegalStateException("interpreter rejected the model") }
        manager.download(spec.id)
        manager.awaitIdle()
        val status = JSONObject(manager.status(spec.id))
        assertEquals("installed", status.getString("state"))
        assertFalse("a failed init must not be operational", status.getBoolean("active"))
        assertFalse(manager.isOperational(spec.id))
        assertTrue(messages.any { it.first == "AvnivoOfflineModuleError" && it.second.startsWith("silero-tts|") })
        manager.close()
    }

    @Test fun sileroSynthesisRefusesAnUnreadyEngine() {
        val manager = manager()
        try {
            manager.synthesize("silero-tts", "salom")
            throw AssertionError("synthesis must not run before the engine is ready")
        } catch (expected: IllegalArgumentException) {
            // require() raises IllegalArgumentException; the point is that it refuses, not the type.
            assertTrue(expected.message!!.contains("not ready", ignoreCase = true))
        } finally {
            manager.close()
        }
    }

    @Test fun sileroSynthesisRejectsAModuleThatIsNotASynthesizer() {
        val spec = OfflineModelSpecs.all.getValue("whisper-small-q8")
        placeInstalledModel(spec)
        val manager = manager()
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.download(spec.id)
        manager.awaitIdle()
        try {
            manager.synthesize(spec.id, "salom")
            throw AssertionError("a recognizer must not answer a synthesis request")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not a speech synthesizer"))
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
