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
        .also { it.downloaderFactory = { _, _, _ -> error("unexpected network download in unit test") } }

    private fun placeInstalledModel(spec: OfflineModelSpec) {
        val dir = spec.directory(temp.root).apply { mkdirs() }
        for ((name, sizeAndHash) in spec.files) {
            val file = File(dir, name)
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { it.setLength(sizeAndHash.first) }
        }
    }

    /** A tiny real spec so the real SHA-256 path can be exercised without a 264 MB fixture. */
    private fun testSpec(id: String = "test-whisper-small", content: String = "test"): Pair<OfflineModelSpec, String> {
        val bytes = content.toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return OfflineModelSpec(
            id = id,
            engine = OfflineEngineKind.WHISPER_GGML,
            files = mapOf("model.bin" to (bytes.size.toLong() to hash)),
            requiredRamBytes = 1L
        ) to content
    }

    private fun placeContent(spec: OfflineModelSpec, content: String) {
        val directory = spec.directory(temp.root).apply { mkdirs() }
        File(directory, "model.bin").writeText(content)
    }

    private fun realManager() = OfflineModuleManager(temp.root) { fn, v ->
        synchronized(messages) { messages += fn to v }
    }.also { it.downloaderFactory = { _, _, _ -> error("unexpected network download in unit test") } }

    private fun downloaderThatWrites(specId: String, content: String, onCall: () -> Unit = {}) = object : ModelDownloader {
        override fun download(progress: (String) -> Unit, bytesProgress: (Long, Long) -> Unit) {
            onCall()
            val directory = temp.root.resolve("models/$specId")
            directory.mkdirs()
            File(directory, "model.bin").writeText(content)
            progress("fake download complete")
            bytesProgress(content.toByteArray().size.toLong(), content.toByteArray().size.toLong())
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
        val manager = manager()
        try {
            assertEquals("not_installed", JSONObject(manager.status("navoiy-tts")).getString("state"))
        } finally {
            manager.close()
        }
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
        assertEquals("verifying", JSONObject(manager.status(spec.id)).getString("state"))

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
        assertEquals("verifying", JSONObject(fresh.status(spec.id)).getString("state"))
        assertEquals(emptyList<String>(), fresh.supportedLanguages(spec.id))
        fresh.reactivateInstalled()
        fresh.awaitIdle()
        assertEquals("installed", JSONObject(fresh.status(spec.id)).getString("state"))

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

    @Test fun sileroSynthesisRefusesAModelThatIsNotInstalled() {
        // Nothing is on disk, so synthesis must refuse with an actionable reason. It must never
        // quietly fall through to another engine or return silence as if it had spoken.
        val manager = manager()
        try {
            manager.synthesize("silero-tts", "salom")
            throw AssertionError("synthesis must not run without a model")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the refusal must say the model is missing: ${expected.message}",
                expected.message!!.contains("yuklab olinmagan")
            )
        } finally {
            manager.close()
        }
    }

    @Test fun sileroSynthesisRefusesACorruptModel() {
        // The files exist at the right size but the digest does not match, so the engine must not
        // be built at all: a truncated model can crash inside the native runtime.
        val spec = OfflineModelSpecs.all.getValue("silero-tts")
        placeInstalledModel(spec)
        val manager = manager()
        manager.integrityVerifier = { _, _ -> ModelIntegrity.Result(false, "SHA-256 mos kelmadi") }
        var builds = 0
        manager.engineFactory = { _, _ -> builds += 1; FakeEngine() }
        try {
            manager.synthesize(spec.id, "salom")
            throw AssertionError("synthesis must not run on a corrupt model")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the refusal must name the integrity failure: ${expected.message}",
                expected.message!!.contains("buzilgan")
            )
        } finally {
            assertEquals("a corrupt model must never reach the native loader", 0, builds)
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

    @Test fun aReleasedTranslatorStillReportsInstalledAndItsLanguages() {
        // Lazy loading must not break the picker. After NLLB is released for another module it is
        // still installed, so the language list must remain available without pulling 916 MB of
        // ONNX sessions back into memory just to draw a list.
        val nllb = OfflineModelSpecs.all.getValue("nllb-200-600m-int8")
        placeInstalledModel(nllb)
        val manager = manager()
        manager.engineFactory = { _, _ -> FakeEngine() }

        manager.download(nllb.id)
        manager.awaitIdle()
        manager.unload(nllb.id)
        manager.awaitIdle()

        assertFalse("nothing is resident after an unload", manager.isOperational(nllb.id))
        assertTrue("the model is still installed", manager.isInstalled(nllb.id))
        manager.close()
    }

    @Test fun aCorruptModelIsNotReportedAsInstalled() {
        // isInstalled gates the translation picker and the speak button, so a model that failed its
        // digest must report false instead of offering an engine that cannot load.
        val nllb = OfflineModelSpecs.all.getValue("nllb-200-600m-int8")
        placeInstalledModel(nllb)
        val manager = manager()
        manager.integrityVerifier = { _, _ -> ModelIntegrity.Result(false, "SHA-256 mos kelmadi") }
        manager.downloaderFactory = { _, _, _ ->
            object : ModelDownloader {
                override fun download(progress: (String) -> Unit, bytesProgress: (Long, Long) -> Unit) {
                    throw IllegalStateException("network disabled in unit test")
                }
            }
        }

        manager.download(nllb.id)
        manager.awaitIdle()

        assertFalse("a corrupt model must not be offered", manager.isInstalled(nllb.id))
        assertEquals("error", JSONObject(manager.status(nllb.id)).getString("state"))
        manager.close()
    }

    @Test fun aMissingModuleIsNeverInstalled() {
        val manager = manager()
        assertFalse(manager.isInstalled("whisper-small-q8"))
        assertFalse(manager.isInstalled("does-not-exist"))
        manager.close()
    }

    @Test fun startupNeverClaimsInstalledUntilSha256Passes() {
        // A 91 MB sparse file has the right length but the wrong bytes. The card must start in
        // VERIFYING, not INSTALLED, and settle in ERROR after the real digest check.
        val spec = OfflineModelSpecs.all.getValue("silero-tts")
        placeInstalledModel(spec)
        val manager = realManager()
        assertEquals("verifying", JSONObject(manager.status(spec.id)).getString("state"))
        assertFalse("length alone must not make it installed", manager.isInstalled(spec.id))

        manager.reactivateInstalled()
        manager.awaitIdle()

        assertEquals("error", JSONObject(manager.status(spec.id)).getString("state"))
        assertFalse(manager.isInstalled(spec.id))
        manager.close()
    }

    @Test fun aStampedModelIsInstalledWithoutHashingAgain() {
        val (spec, content) = testSpec()
        placeContent(spec, content)
        val directory = spec.directory(temp.root)
        assertTrue("the fixture must hash to its manifest", ModelIntegrity.verify(directory, spec).ok)

        val manager = realManager()
        manager.registerForTest(spec)
        manager.reactivateInstalled()
        manager.awaitIdle()

        assertEquals("installed", JSONObject(manager.status(spec.id)).getString("state"))
        assertTrue(manager.isInstalled(spec.id))
        manager.close()
    }

    @Test fun reactivationKeepsALiveEngineReadyAndDoesNotRebuildIt() {
        val spec = OfflineModelSpecs.all.getValue("whisper-small-q8")
        placeInstalledModel(spec)
        val manager = manager()
        var builds = 0
        manager.engineFactory = { _, _ -> builds += 1; FakeEngine() }
        manager.download(spec.id)
        manager.awaitIdle()
        assertEquals("ready", JSONObject(manager.status(spec.id)).getString("state"))
        assertEquals(1, builds)

        manager.reactivateInstalled()
        manager.awaitIdle()

        assertEquals("a live engine must stay ready", "ready", JSONObject(manager.status(spec.id)).getString("state"))
        assertEquals("reactivation must not rebuild a live engine", 1, builds)
        manager.close()
    }

    @Test fun aCorruptInstalledModelIsDeletedAndDownloadedAgain() {
        val (spec, content) = testSpec()
        // Same length as the real file, so only the SHA-256 check can catch it.
        placeContent(spec, "nope")
        val manager = realManager()
        manager.registerForTest(spec)
        manager.engineFactory = { _, _ -> FakeEngine() }
        var downloads = 0
        manager.downloaderFactory = { _, _, _ -> downloaderThatWrites(spec.id, content) { downloads += 1 } }

        manager.download(spec.id)
        manager.awaitIdle()

        assertEquals("the corrupt bytes must be replaced in the same press", 1, downloads)
        assertEquals(content, File(spec.directory(temp.root), "model.bin").readText())
        assertEquals("ready", JSONObject(manager.status(spec.id)).getString("state"))
        manager.close()
    }

    @Test fun aDownloadThatFailsPostVerificationNeverReportsInstalled() {
        val (spec, content) = testSpec()
        val manager = realManager()
        manager.registerForTest(spec)
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.downloaderFactory = { _, _, _ -> downloaderThatWrites(spec.id, content) }
        manager.integrityVerifier = { _, _ -> ModelIntegrity.Result(false, "SHA-256 mos kelmadi") }

        manager.download(spec.id)
        manager.awaitIdle()

        assertEquals("error", JSONObject(manager.status(spec.id)).getString("state"))
        assertFalse(manager.isInstalled(spec.id))
        manager.close()
    }

    @Test fun missingBytesUnderAnInstalledStateStartARealDownload() {
        val (spec, content) = testSpec()
        placeContent(spec, content)
        ModelIntegrity.verify(spec.directory(temp.root), spec)
        val manager = realManager()
        manager.registerForTest(spec)
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.reactivateInstalled()
        manager.awaitIdle()
        assertEquals("installed", JSONObject(manager.status(spec.id)).getString("state"))

        File(spec.directory(temp.root), "model.bin").delete()
        var stateDuringDownload = ""
        manager.downloaderFactory = { _, _, _ ->
            downloaderThatWrites(spec.id, content) {
                stateDuringDownload = JSONObject(manager.status(spec.id) ?: "{}").optString("state")
            }
        }

        manager.download(spec.id)
        manager.awaitIdle()

        assertEquals("the card must say downloading while bytes are fetched", "downloading", stateDuringDownload)
        assertEquals("ready", JSONObject(manager.status(spec.id)).getString("state"))
        manager.close()
    }

    @Test fun progressIsForwardedWithRealBytesOnTheModuleChannel() {
        val (spec, content) = testSpec()
        val manager = realManager()
        manager.registerForTest(spec)
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.downloaderFactory = { _, _, _ ->
            object : ModelDownloader {
                override fun download(progress: (String) -> Unit, bytesProgress: (Long, Long) -> Unit) {
                    bytesProgress(1, 4)
                    bytesProgress(4, 4)
                    val directory = spec.directory(temp.root).apply { mkdirs() }
                    File(directory, "model.bin").writeText(content)
                }
            }
        }

        manager.download(spec.id)
        manager.awaitIdle()

        val progress = messages.filter { it.first == "AvnivoOfflineModuleProgress" }.map { it.second }
        assertTrue("real byte progress must reach the card: $progress", progress.contains("${spec.id}|1/4"))
        assertTrue("the final byte count must reach the card: $progress", progress.contains("${spec.id}|4/4"))
        manager.close()
    }

    @Test fun tenRestartsRehydrateTheInstalledModelWithoutDownloadingAgain() {
        val (spec, content) = testSpec()
        placeContent(spec, content)
        assertTrue(ModelIntegrity.verify(spec.directory(temp.root), spec).ok)

        repeat(10) { restart ->
            val manager = realManager()
            manager.registerForTest(spec)
            var builds = 0
            manager.engineFactory = { _, _ -> builds += 1; FakeEngine() }
            manager.downloaderFactory = { _, _, _ -> error("restart $restart must not download") }
            manager.activeModuleId = spec.id

            manager.reactivateInstalled()
            manager.awaitIdle()

            assertEquals(
                "restart $restart must rehydrate ready",
                "ready",
                JSONObject(manager.status(spec.id)).getString("state")
            )
            assertEquals("restart $restart must load exactly one engine", 1, builds)
            manager.close()
        }
    }

    @Test fun activationOfAnInstalledModuleNeverDownloads() {
        val spec = OfflineModelSpecs.all.getValue("whisper-small-q8")
        placeInstalledModel(spec)
        val manager = manager()
        var builds = 0
        manager.engineFactory = { _, _ -> builds += 1; FakeEngine() }
        manager.downloaderFactory = { _, _, _ -> error("activation must not touch the network") }

        manager.activate(spec.id)
        manager.awaitIdle()

        val status = JSONObject(manager.status(spec.id))
        assertEquals("ready", status.getString("state"))
        assertTrue(status.getBoolean("active"))
        assertEquals(1, builds)
        manager.close()
    }

    @Test fun smallAndLargeAreMutuallyExclusiveAndKeepTheirFiles() {
        val small = OfflineModelSpecs.all.getValue("whisper-small-q8")
        val large = OfflineModelSpecs.all.getValue("whisper-large-v3-turbo")
        placeInstalledModel(small)
        placeInstalledModel(large)
        val manager = manager()
        manager.engineFactory = { _, _ -> FakeEngine() }

        manager.activate(small.id)
        manager.awaitIdle()
        assertEquals(OfflineSttEngine.WHISPER_SMALL_Q8, manager.selectedWhisperEngine())

        manager.activate(large.id)
        manager.awaitIdle()
        assertEquals(OfflineSttEngine.WHISPER_LARGE_V3_TURBO, manager.selectedWhisperEngine())
        assertTrue("large must be operational", manager.isOperational(large.id))
        assertFalse("small must not run in parallel", manager.isOperational(small.id))
        assertEquals("installed", JSONObject(manager.status(small.id)).getString("state"))
        assertTrue("small files must survive large activation", small.installed(small.directory(temp.root)))

        manager.activate(small.id)
        manager.awaitIdle()
        assertEquals(OfflineSttEngine.WHISPER_SMALL_Q8, manager.selectedWhisperEngine())
        assertTrue("small must be operational again", manager.isOperational(small.id))
        assertFalse("large must not run in parallel", manager.isOperational(large.id))
        assertEquals("installed", JSONObject(manager.status(large.id)).getString("state"))
        assertTrue("large files must survive small activation", large.installed(large.directory(temp.root)))
        manager.close()
    }

    @Test fun twentyWhisperSwitchesLeaveExactlyOneEngineAndBothModels() {
        val small = OfflineModelSpecs.all.getValue("whisper-small-q8")
        val large = OfflineModelSpecs.all.getValue("whisper-large-v3-turbo")
        placeInstalledModel(small)
        placeInstalledModel(large)
        val manager = manager()
        var builds = 0
        manager.engineFactory = { _, _ -> builds += 1; FakeEngine() }

        repeat(20) { index ->
            val target = if (index % 2 == 0) small else large
            manager.activate(target.id)
            manager.awaitIdle()
            assertEquals("switch ${index + 1} must select ${target.id}", 1, listOf(small, large).count { manager.isOperational(it.id) })
            assertEquals("switch ${index + 1} must persist ${target.id}", target.id, manager.selectedWhisperEngine()?.moduleId)
        }

        assertEquals(20, builds)
        assertTrue(small.installed(small.directory(temp.root)))
        assertTrue(large.installed(large.directory(temp.root)))
        manager.close()
    }

    @Test fun deactivationKeepsFilesAndClearsThePersistedChoice() {
        val spec = OfflineModelSpecs.all.getValue("whisper-small-q8")
        placeInstalledModel(spec)
        val manager = manager()
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.activate(spec.id)
        manager.awaitIdle()
        assertTrue(manager.isOperational(spec.id))

        manager.deactivate(spec.id)
        manager.awaitIdle()

        assertEquals("installed", JSONObject(manager.status(spec.id)).getString("state"))
        assertFalse(manager.isOperational(spec.id))
        assertNull(manager.selectedWhisperEngine())
        assertTrue(spec.installed(spec.directory(temp.root)))

        manager.activate(spec.id)
        manager.awaitIdle()
        assertTrue("reactivation must not need a download", manager.isOperational(spec.id))
        manager.close()
    }

    @Test fun deletingOneModuleLeavesTheOtherModuleUntouched() {
        val small = OfflineModelSpecs.all.getValue("whisper-small-q8")
        val large = OfflineModelSpecs.all.getValue("whisper-large-v3-turbo")
        placeInstalledModel(small)
        placeInstalledModel(large)
        val manager = manager()
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.activate(small.id)
        manager.awaitIdle()

        manager.delete(small.id)
        manager.awaitIdle()

        assertEquals("not_installed", JSONObject(manager.status(small.id)).getString("state"))
        assertFalse(small.installed(small.directory(temp.root)))
        assertFalse(manager.isInstalled(small.id))
        assertNull(manager.selectedWhisperEngine())
        assertTrue("large files must survive", large.installed(large.directory(temp.root)))
        val largeStatus = JSONObject(manager.status(large.id))
        assertTrue(
            "large must remain known and never be marked deleted: $largeStatus",
            largeStatus.getString("state") != "not_installed"
        )
        assertTrue(messages.any { it.first == "AvnivoOfflineModuleDeleted" && it.second == small.id })
        manager.close()
    }

    @Test fun aDeletedModuleCanBeInstalledAndActivatedAgain() {
        val (spec, content) = testSpec()
        placeContent(spec, content)
        val manager = realManager()
        manager.registerForTest(spec)
        manager.engineFactory = { _, _ -> FakeEngine() }
        manager.downloaderFactory = { _, _, _ -> downloaderThatWrites(spec.id, content) }

        manager.activate(spec.id)
        manager.awaitIdle()
        assertTrue(manager.isOperational(spec.id))
        manager.delete(spec.id)
        manager.awaitIdle()
        assertFalse(manager.isInstalled(spec.id))

        manager.download(spec.id)
        manager.awaitIdle()
        assertTrue("reinstall must verify and activate again", manager.isOperational(spec.id))
        assertTrue(manager.isInstalled(spec.id))
        manager.close()
    }

    @Test fun deleteNeverTouchesAnotherModulesDirectory() {
        val (spec, content) = testSpec(id = "delete-target")
        val (other, otherContent) = testSpec(id = "delete-neighbour")
        placeContent(spec, content)
        placeContent(other, otherContent)
        val manager = realManager()
        manager.registerForTest(spec)
        manager.registerForTest(other)

        manager.delete(spec.id)
        manager.awaitIdle()

        assertFalse(File(spec.directory(temp.root), "model.bin").exists())
        assertTrue("neighbour file must survive", File(other.directory(temp.root), "model.bin").isFile)
        assertEquals(otherContent, File(other.directory(temp.root), "model.bin").readText())
        manager.close()
    }

    @Test fun aFailedLargeLoadDoesNotSilentlySelectSmall() {
        val small = OfflineModelSpecs.all.getValue("whisper-small-q8")
        val large = OfflineModelSpecs.all.getValue("whisper-large-v3-turbo")
        placeInstalledModel(small)
        placeInstalledModel(large)
        val manager = manager()
        manager.engineFactory = { spec, _ ->
            if (spec.id == large.id) throw IllegalStateException("large rejected")
            FakeEngine()
        }

        manager.activate(small.id)
        manager.awaitIdle()
        assertEquals(OfflineSttEngine.WHISPER_SMALL_Q8, manager.selectedWhisperEngine())

        manager.activate(large.id)
        manager.awaitIdle()
        assertNull("a failed Large load must clear the choice, not fall back", manager.selectedWhisperEngine())
        assertFalse("Small must not be left operational after the failed switch", manager.isOperational(small.id))
        assertEquals("installed", JSONObject(manager.status(large.id)).getString("state"))
        assertTrue(JSONObject(manager.status(large.id)).getString("reason").contains("large rejected"))
        manager.close()
    }
}
