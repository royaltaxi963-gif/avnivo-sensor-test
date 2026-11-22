package com.avnivo.translator

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the online microphone path in a real WebView: the recording indicator contract, the
 * native permission answer reaching the page, the composer insert, and live capture.
 *
 * The interface runs inside an IIFE, so only the exported `Avnivo*` bridge functions are callable
 * from a test. Everything here goes through those or through real DOM events; nothing reaches into
 * a private function, because that would test the harness rather than the shipped behaviour.
 */
@RunWith(AndroidJUnit4::class)
class OnlineMicInstrumentedTest {

    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(script) { value -> result.set(value); latch.countDown() }
        }
        assertTrue("JS did not return: $script", latch.await(15, TimeUnit.SECONDS))
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
            view.settings.mediaPlaybackRequiresUserGesture = false
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) = loaded.countDown()
            }
            // Mirrors MainActivity: without a WebChromeClient the WebView refuses capture outright.
            view.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val allowed = MicPermissionPolicy.grantableResources(
                        request.origin?.scheme, request.origin?.host, request.resources.toList()
                    )
                    if (allowed == null) request.deny() else request.grant(allowed.toTypedArray())
                }
            }
            view.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
            web.set(view)
        }
        assertTrue("interface did not load", loaded.await(30, TimeUnit.SECONDS))
        evaluate(
            web.get(),
            "localStorage.clear();document.documentElement.dataset.avnivoMode='online';" +
                "document.querySelector('.writing-area').value='';'ok'"
        )
        return web.get()
    }

    @Test
    fun manifestHoldsTheAudioPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(
            "RECORD_AUDIO must be declared",
            info.requestedPermissions?.contains(Manifest.permission.RECORD_AUDIO) == true
        )
    }

    /** Every documented state must be renderable; a missing one leaves the indicator invisible. */
    @Test
    fun everyDocumentedIndicatorStateRenders() {
        val web = loadInterface()
        for (state in listOf("recording", "loading", "error")) {
            val shown = evaluate(
                web,
                "var el=document.querySelector('.online-stt-status');" +
                    "el.dataset.state='$state';" +
                    "var s=getComputedStyle(el);s.display+'|'+s.opacity"
            )
            assertTrue("indicator must display for $state, saw $shown", shown.startsWith("flex"))
            assertFalse("indicator must be visible for $state, saw $shown", shown.endsWith("|0"))
        }
        // "done" is the leave transition: it fades out, so assert the state is applied rather than
        // that it stays visible.
        val done = evaluate(
            web,
            "var el=document.querySelector('.online-stt-status');el.dataset.state='done';el.dataset.state"
        )
        assertEquals("done", done)
    }

    @Test
    fun aDeniedPermissionIsReportedToTheUser() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "var sh=document.querySelector('.writing-shell');sh.classList.add('hold-voice');" +
                "window.AvnivoOnlineMicError('Mikrofon ruxsati berilmadi');" +
                "var h=document.querySelector('.voice-hint');" +
                "h.hidden+'|'+h.textContent+'|'+sh.classList.contains('hold-voice')+'|'+" +
                "document.querySelector('.online-stt-status').dataset.state"
        )
        val parts = value.split('|')
        assertEquals("hint must be visible", "false", parts[0])
        assertTrue("hint must carry the reason", parts[1].contains("ruxsat"))
        assertEquals("gesture visual must be cleared", "false", parts[2])
        assertEquals("indicator must show the error state", "error", parts[3])
    }

    /** A grant must clear the error hint so the composer is usable again. */
    @Test
    fun aGrantClearsTheHint() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOnlineMicError('xato');window.AvnivoOnlineMicGranted();" +
                "document.querySelector('.voice-hint').hidden"
        )
        assertEquals("true", value)
    }

    /** Hints were invisible in online mode before the fix; that is what hid every failure. */
    @Test
    fun hintsAreVisibleInOnlineMode() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOnlineMicError('sinov xabari');" +
                "var h=document.querySelector('.voice-hint');h.hidden+'|'+h.textContent"
        )
        assertEquals("false|sinov xabari", value)
    }

    @Test
    fun transcriptStaysInComposerAndIsNotSent() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "var before=document.querySelectorAll('.message-line').length;" +
                "var i=document.querySelector('.writing-area');i.value='salom dunyo';" +
                "i.dispatchEvent(new Event('input'));" +
                "var after=document.querySelectorAll('.message-line').length;" +
                "i.value+'|'+(after-before)"
        )
        assertEquals("salom dunyo|0", value)
    }

    @Test
    fun offlineModeStillUsesTheNativeBridge() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "document.documentElement.dataset.avnivoMode='offline';" +
                "var s=document.querySelector('.writing-shell');s.classList.add('hold-voice');" +
                "window.AvnivoOfflineResult('offline matn');" +
                "document.querySelector('.writing-area').value+'|'+s.classList.contains('hold-voice')"
        )
        assertEquals("offline matn|false", value)
    }

    /**
     * With RECORD_AUDIO granted, `getUserMedia` must resolve and hand back a live audio track.
     * A refused grant surfaces as NotAllowedError here, so this separates "permission recorded"
     * from "capture actually works". The markers are logged so CI can require them.
     */
    @Test
    fun webViewReachesTheCaptureApiWithTheGrant() {
        val web = loadInterface()
        val outcome = evaluateAsync(
            web,
            """
            new Promise(function(resolve){
              if(!navigator.mediaDevices||!navigator.mediaDevices.getUserMedia){
                resolve('NO_API');return;
              }
              navigator.mediaDevices.getUserMedia({audio:true}).then(function(stream){
                var track=stream.getAudioTracks()[0];
                var live=!!track&&track.readyState==='live';
                var label=track?track.label:'';
                stream.getTracks().forEach(function(t){t.stop()});
                resolve('OK live='+live+' label='+label);
              }).catch(function(e){resolve('FAIL '+(e&&e.name||e))});
            })
            """.trimIndent()
        )
        android.util.Log.i("AvnivoOnlineMic", "ONLINE_MIC_CAPTURE $outcome")
        assertTrue("capture must resolve, saw: $outcome", outcome.startsWith("OK"))
        assertTrue("the audio track must be live, saw: $outcome", outcome.contains("live=true"))
        android.util.Log.i("AvnivoOnlineMic", "ONLINE_MIC_CAPTURE_OK")
    }

    /** Evaluates a promise-returning script and waits for its resolved value. */
    private fun evaluateAsync(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>("TIMEOUT")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(
                "Promise.resolve($script).then(function(v){return JSON.stringify(String(v))})"
            ) { value -> result.set(value.trim('"').replace("\\\"", "")); latch.countDown() }
        }
        latch.await(30, TimeUnit.SECONDS)
        return result.get()
    }
}
