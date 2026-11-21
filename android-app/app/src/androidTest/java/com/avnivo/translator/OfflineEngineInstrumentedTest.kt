package com.avnivo.translator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device checks that cannot be proven on the JVM: the sherpa-onnx native runtime loads on the
 * real device ABI, and the engine refuses to activate without a verified model (fail-closed).
 */
@RunWith(AndroidJUnit4::class)
class OfflineEngineInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeSherpaRuntimeLoads() {
        System.loadLibrary("sherpa-onnx-jni")
    }

    @Test
    fun engineRefusesToInitializeWithoutModel() {
        val emptyDir = File(context.cacheDir, "no-model-${System.nanoTime()}").apply { mkdirs() }
        assertFalse(OfflineSpeechEngine.hasRequiredFiles(emptyDir))
        val engine = OfflineSpeechEngine(emptyDir)
        val failed = try {
            engine.prepare()
            false
        } catch (expected: Exception) {
            true
        }
        engine.close()
        assertTrue("prepare() must fail closed when the model is missing", failed)
    }
}
