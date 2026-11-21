package com.avnivo.translator

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads the packaged interface in a real WebView and exercises the offline bridge callbacks the
 * native recorder emits. Verifies the composer integration contract without touching the UI:
 * recognized text is inserted, the recording visual state is cleared on every terminal event, and
 * the waveform amplitude callback is accepted.
 */
@RunWith(AndroidJUnit4::class)
class OfflineMicGestureInstrumentedTest {

    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(script) { value -> result.set(value); latch.countDown() }
        }
        assertTrue("JS did not return: $script", latch.await(10, TimeUnit.SECONDS))
        return result.get().orEmpty().trim('"')
    }

    private fun loadInterface(): WebView {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val html = context.assets.open("index.html").use { it.readBytes().decodeToString() }
        val web = AtomicReference<WebView>()
        val loaded = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = WebView(context)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) = loaded.countDown()
            }
            view.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
            web.set(view)
        }
        assertTrue("interface did not load", loaded.await(30, TimeUnit.SECONDS))
        // Offline mode is what the native bridge targets; start from a clean composer.
        evaluate(web.get(), "localStorage.clear();document.documentElement.dataset.avnivoMode='offline';document.querySelector('.writing-area').value='';'ok'")
        return web.get()
    }

    @Test
    fun recognizedTextIsInsertedAndGestureStateCleared() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "var s=document.querySelector('.writing-shell');s.classList.add('hold-voice');" +
                "window.AvnivoOfflineResult('salom dunyo');" +
                "document.querySelector('.writing-area').value+'|'+s.classList.contains('hold-voice')"
        )
        assertEquals("salom dunyo|false", value)
    }

    @Test
    fun terminalStatusClearsGestureStateWithoutInsertingText() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "var s=document.querySelector('.writing-shell');s.classList.add('hold-voice');" +
                "window.AvnivoOfflineStatus('Model allaqachon yuklangan.');" +
                "s.classList.contains('hold-voice')+'|'+document.querySelector('.writing-area').value"
        )
        assertEquals("false|", value)
    }

    @Test
    fun amplitudeCallbackIsAccepted() {
        val web = loadInterface()
        val value = evaluate(web, "window.AvnivoOfflineLevel('0.7');window.AvnivoOfflineLevel('2');'ok'")
        assertEquals("ok", value)
    }

    @Test
    fun errorClearsGestureStateAndKeepsComposerEmpty() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "var s=document.querySelector('.writing-shell');s.classList.add('hold-voice');" +
                "window.AvnivoOfflineError('test');s.classList.contains('hold-voice')+'|'" +
                "+document.querySelector('.writing-area').value"
        )
        assertEquals("false|", value)
    }

    /**
     * The reported Samsung defect: pressing the mic started recording but releasing the finger did
     * not stop it. This drives the real gesture path (pointerdown then pointerup on the button) with
     * a stubbed native bridge, so it fails if release stops short of clearing the recording state.
     */
    @Test
    fun releasingTheFingerEndsRecordingWithoutAResultCallback() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.__started=0;window.__stopped=0;" +
                "window.AvnivoOfflineSTT={start:function(){window.__started++},stop:function(){window.__stopped++}};" +
                "var m=document.querySelector('.mic-icon');" +
                "var s=document.querySelector('.writing-shell');" +
                "m.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:1,button:0,isPrimary:true}));" +
                "var during=s.classList.contains('hold-voice');" +
                "m.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,pointerId:1,button:0,isPrimary:true}));" +
                "[during,s.classList.contains('hold-voice'),window.__started,window.__stopped," +
                "document.querySelector('.writing-area').value].join('|')"
        )
        assertEquals("press must start recording: $value", "true|false|1|1|", value)
    }

    /** A release with no matching press must not start or stop anything, and must not throw. */
    @Test
    fun strayPointerUpIsIgnored() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.__started=0;window.__stopped=0;" +
                "window.AvnivoOfflineSTT={start:function(){window.__started++},stop:function(){window.__stopped++}};" +
                "var m=document.querySelector('.mic-icon');" +
                "m.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,pointerId:7,button:0,isPrimary:true}));" +
                "window.__started+'|'+window.__stopped"
        )
        assertEquals("0|0", value)
    }
}
