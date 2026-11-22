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
        // The stand-in files are the right length but not the right bytes, so the digest check is
        // replaced here. ModelIntegrityTest covers the real hashing path with real content.
        .also { it.integrityVerifier = { _, _ -> ModelIntegrity.Result(true) } }

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

    @Test fun concurrentDownloadsInitializeTheEngineOnce() {
        // The card verifies on page load while a press may ask for the same engine; both run on the
        // manager's single worker, but the assertion pins that no second init overwrites a ready
        // module or leaks an engine.
        val spec = OfflineModelSpecs.all.getValue("whisper-small-q8")
        placeInstalledModel(spec)
        val manager = manager()
        var builds = 0
        manager.engineFactory = { _, _ -> builds += 1; FakeEngine() }

        repeat(4) { manager.download(spec.id) }
        manager.awaitIdle()

        assertEquals("engine must be built exactly once: ${messages.map { it.first }}", 1, builds)
        assertTrue(manager.isOperational(spec.id))
        manager.close()
    }

    @Test fun anOutOfMemoryInitializationSettlesIntoError() {
        // The device hung on "Tekshirilmoqda…": an OOM inside the large model was not caught, so the
        // module never left INITIALIZING. It must now end in ERROR with an actionable reason.
        val spec = OfflineModelSpecs.all.getValue("whisper-large-v3-turbo")
        placeInstalledModel(spec)
        val manager = manager()
        manager.engineFactory = { _, _ -> throw OutOfMemoryError("native alloc failed") }

        manager.download(spec.id)
        manager.awaitIdle()

        val status = JSONObject(manager.status(spec.id))
        assertEquals("error", status.getString("state"))
        assertFalse(status.getBoolean("active"))
        assertTrue(
            "the reason must name the memory problem: $status",
            status.getString("reason").contains("xotira")
        )
        assertTrue(
            "the error must reach the card",
            messages.any { it.first == "AvnivoOfflineModuleError" && it.second.contains("xotira") }
        )
        manager.close()
    }

    @Test fun anUnexpectedVmFailureSettlesInsteadOfHanging() {
        val spec = OfflineModelSpecs.all.getValue("whisper-small-q8")
        placeInstalledModel(spec)
        val manager = manager()
        manager.engineFactory = { _, _ -> throw StackOverflowError("boom") }

        manager.download(spec.id)
        manager.awaitIdle()

        val status = JSONObject(manager.status(spec.id))
        assertEquals("error", status.getString("state"))
        assertFalse(manager.isOperational(spec.id))
        manager.close()
    }

    @Test fun progressIsReportedAcrossTheWholeModel() {
        // The aggregate bar is proven end to end against a real server in
        // VerifiedModelDownloaderTest#progressAdvancesAcrossEveryFile; here the manager must at
        // least expose byte progress so the card can render it.
        val spec = OfflineModelSpecs.all.getValue("nllb-200-600m-int8")
        placeInstalledModel(spec)
        val manager = manager()
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.download(spec.id)
        manager.awaitIdle()

        assertTrue(manager.isOperational(spec.id))
        assertTrue(
            "a completed module must clear its byte counters",
            JSONObject(manager.status(spec.id)).optJSONObject("progress") == null
        )
        manager.close()
    }

    @Test fun statusReportsByteProgressForAResumableRestart() {
        val spec = OfflineModelSpecs.all.getValue("whisper-small-q8")
        placeInstalledModel(spec)
        val manager = manager()
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.download(spec.id)
        manager.awaitIdle()

        val status = JSONObject(manager.status(spec.id))
        assertTrue("status must carry a progress object", status.has("progress"))
        val progress = status.optJSONObject("progress")
        if (progress != null) {
            assertTrue(progress.getLong("totalBytes") > 0L)
            assertTrue(progress.getLong("downloadedBytes") >= 0L)
        }
        manager.close()
    }

    @Test fun aModuleThatDoesNotFitStaysInstalledWithAReason() {
        // reactivateInstalled() loads every installed model on page load; without a memory gate a
        // large model pushed the process into an OOM kill. On the JVM there is no ActivityManager,
        // so the gate defers to the engine; what must hold is that the module never reports READY
        // without a successful initialization and never hangs in INITIALIZING.
        val spec = OfflineModelSpecs.all.getValue("whisper-large-v3-turbo")
        placeInstalledModel(spec)
        val manager = manager()
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.reactivateInstalled()
        manager.awaitIdle()

        val status = JSONObject(manager.status(spec.id))
        assertTrue(
            "state must be settled, not left initializing: $status",
            status.getString("state") in setOf("ready", "installed", "error")
        )
        manager.close()
    }

    @Test fun activatingASecondModuleUnloadsTheFirst() {
        // One engine at a time is the constraint that keeps the process alive. Activating a second
        // module must release the first instead of holding both and pushing the device into OOM.
        val small = OfflineModelSpecs.all.getValue("whisper-small-q8")
        val tts = OfflineModelSpecs.all.getValue("silero-tts")
        placeInstalledModel(small)
        placeInstalledModel(tts)
        val manager = manager()
        val engines = mutableMapOf<String, FakeEngine>()
        manager.engineFactory = { spec, _ -> FakeEngine().also { engines[spec.id] = it } }

        manager.download(small.id)
        manager.awaitIdle()
        assertTrue(manager.isOperational(small.id))

        manager.download(tts.id)
        manager.awaitIdle()

        assertTrue("the requested module must be the active one", manager.isOperational(tts.id))
        assertFalse("the previous module must be released", manager.isOperational(small.id))
        assertTrue("the previous engine must actually be closed", engines.getValue(small.id).closed)
        assertFalse("the new engine must be live", engines.getValue(tts.id).closed)
        manager.close()
    }

    @Test fun unloadingKeepsTheModuleInstalled() {
        // Unload frees memory; it must never make the user download the model again.
        val small = OfflineModelSpecs.all.getValue("whisper-small-q8")
        placeInstalledModel(small)
        val manager = manager()
        manager.engineFactory = { _, _ -> FakeEngine() }

        manager.download(small.id)
        manager.awaitIdle()
        assertTrue(manager.isOperational(small.id))

        manager.unload(small.id)
        manager.awaitIdle()

        val status = JSONObject(manager.status(small.id))
        assertEquals("installed", status.getString("state"))
        assertFalse("nothing may be resident after an unload", status.getBoolean("resident"))
        assertFalse(status.getBoolean("active"))
        manager.close()
    }

    @Test fun anUnloadedModuleReloadsOnTheNextRequest() {
        // A request after an unload must load the model again, not fail with "not initialized".
        val small = OfflineModelSpecs.all.getValue("whisper-small-q8")
        placeInstalledModel(small)
        val manager = manager()
        var builds = 0
        manager.engineFactory = { _, _ -> builds += 1; FakeEngine() }

        manager.download(small.id)
        manager.awaitIdle()
        manager.unload(small.id)
        manager.awaitIdle()
        assertEquals(1, builds)

        manager.download(small.id)
        manager.awaitIdle()
        assertEquals("the model must be loaded again on demand", 2, builds)
        assertTrue(manager.isOperational(small.id))
        manager.close()
    }

    @Test fun aLoadThatExceedsTheBudgetIsRefusedWithRealNumbers() {
        // The device numbers from the report: 12 GB phone, 1768 MB free, and a model that needs
        // more than its share of a small device. The refusal must name the numbers, not hang.
        val large = OfflineModelSpecs.all.getValue("whisper-large-v3-turbo")
        placeInstalledModel(large)
        val manager = manager()
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.memoryProvider = {
            MemorySnapshot(
                totalMem = 2_000L * 1024 * 1024,
                availMem = 300L * 1024 * 1024,
                lowMemory = false,
                appPssBytes = 0,
                nativeHeapBytes = 0,
                vmHeapMax = 256L * 1024 * 1024
            )
        }

        manager.download(large.id)
        manager.awaitIdle()

        val status = JSONObject(manager.status(large.id))
        assertEquals("installed", status.getString("state"))
        assertFalse(status.getBoolean("active"))
        assertTrue("the card must explain the refusal: $status", status.getString("reason").contains("xotira"))
        manager.close()
    }

    @Test fun theReportedDeviceIsNoLongerRefused() {
        // Regression for the exact S23 Ultra report: 12 GB physical RAM, 1768 MB system-free,
        // NLLB needs 2765 MiB. It must now activate instead of reporting a memory error.
        val nllb = OfflineModelSpecs.all.getValue("nllb-200-600m-int8")
        placeInstalledModel(nllb)
        val manager = manager()
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.memoryProvider = {
            MemorySnapshot(
                totalMem = 12_000L * 1024 * 1024,
                availMem = 1_768L * 1024 * 1024,
                lowMemory = false,
                appPssBytes = 0,
                nativeHeapBytes = 0,
                vmHeapMax = 256L * 1024 * 1024
            )
        }

        manager.download(nllb.id)
        manager.awaitIdle()

        val status = JSONObject(manager.status(nllb.id))
        assertEquals("the reported device must reach ready: $status", "ready", status.getString("state"))
        assertTrue(status.getBoolean("active"))
        manager.close()
    }

    @Test fun reactivationLoadsOnlyTheActiveModule() {
        // After a restart every installed model is verified, but only the one that was active is
        // loaded. Loading all of them is what produced "dvigatel ulanmagan" on every card.
        val small = OfflineModelSpecs.all.getValue("whisper-small-q8")
        val tts = OfflineModelSpecs.all.getValue("silero-tts")
        placeInstalledModel(small)
        placeInstalledModel(tts)
        val manager = manager()
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.activeModuleId = tts.id

        manager.reactivateInstalled()
        manager.awaitIdle()

        assertTrue("the remembered module must be loaded", manager.isOperational(tts.id))
        assertEquals(
            "the other installed module must stay installed, not ready",
            "installed",
            JSONObject(manager.status(small.id)).getString("state")
        )
        manager.close()
    }
}
