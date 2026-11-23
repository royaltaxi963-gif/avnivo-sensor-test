package com.avnivo.translator

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/**
 * Device-side proof for the two lifecycle contracts that cannot be exercised by the JVM tests:
 * the persisted Whisper selection must survive process/activity restarts, and deleting one module
 * must leave a neighbour's files untouched. Native inference is covered separately by
 * OfflineWhisperInferenceTest; this test uses a fake engine because it is about state, files and
 * SharedPreferences, not whisper.cpp.
 */
@RunWith(AndroidJUnit4::class)
class OfflineModuleLifecycleInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("avnivo.offline", Context.MODE_PRIVATE)

    private class FakeEngine : Closeable {
        override fun close() = Unit
    }

    private fun spec(id: String, content: String): OfflineModelSpec {
        val bytes = content.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return OfflineModelSpec(
            id = id,
            engine = OfflineEngineKind.WHISPER_GGML,
            files = mapOf("model.bin" to (bytes.size.toLong() to hash)),
            requiredRamBytes = 1L
        )
    }

    private fun place(spec: OfflineModelSpec, content: String) {
        val directory = spec.directory(context.filesDir).apply { mkdirs() }
        File(directory, "model.bin").writeText(content)
    }

    private fun manager(): OfflineModuleManager =
        OfflineModuleManager(context.filesDir, context.applicationContext) { _, _ -> }
            .also { it.engineFactory = { _, _ -> FakeEngine() } }

    @Test
    fun selectedWhisperSurvivesTenManagerRestartsWithoutDownloadingAgain() {
        prefs.edit().clear().commit()
        val id = "test-selection-${System.nanoTime()}"
        val content = "persisted-selection-model"
        val model = spec(id, content)
        place(model, content)
        try {
            repeat(10) { restart ->
                val current = manager()
                current.registerForTest(model)
                if (restart == 0) current.activate(id) else current.reactivateInstalled()
                current.awaitIdle()
                val status = JSONObject(current.status(id) ?: "{}")
                assertEquals("restart $restart must restore READY", "ready", status.optString("state"))
                assertEquals(
                    "restart $restart must restore the selected module",
                    id,
                    current.activeModuleId
                )
                current.close()
            }
        } finally {
            val cleanup = manager()
            cleanup.registerForTest(model)
            cleanup.delete(id)
            cleanup.awaitIdle()
            cleanup.close()
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun deletingOneModuleKeepsTheNeighbourAndItsBytes() {
        prefs.edit().clear().commit()
        val first = spec("test-delete-first-${System.nanoTime()}", "first")
        val second = spec("test-delete-second-${System.nanoTime()}", "second")
        place(first, "first")
        place(second, "second")
        val manager = manager()
        try {
            manager.registerForTest(first)
            manager.registerForTest(second)
            manager.activate(first.id)
            manager.awaitIdle()
            manager.delete(first.id)
            manager.awaitIdle()

            assertEquals("not_installed", JSONObject(manager.status(first.id) ?: "{}").optString("state"))
            assertFalse(File(first.directory(context.filesDir), "model.bin").exists())
            assertTrue("neighbour file must remain", File(second.directory(context.filesDir), "model.bin").isFile)
            assertEquals("second", File(second.directory(context.filesDir), "model.bin").readText())
        } finally {
            manager.close()
            first.directory(context.filesDir).deleteRecursively()
            second.directory(context.filesDir).deleteRecursively()
            prefs.edit().clear().commit()
        }
    }
}
