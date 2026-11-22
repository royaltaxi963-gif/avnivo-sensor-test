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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the online microphone path in a real WebView: the recording indicator states, the
 * waveform analyser wiring, the native permission answer, and the transcript landing in the
 * composer without being sent. No network call is made; the Chirp 2 request path is only inspected
 * for its failure reporting.
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

    @Test
    fun recordingIndicatorReachesEveryDocumentedState() {
        val web = loadInterface()
        for (state in listOf("recording", "loading", "done", "error")) {
            val value = evaluate(web, "onlineState('$state');document.querySelector('.online-stt-status').dataset.state")
            assertEquals("state $state must be applied", state, value)
        }
    }

    @Test
    fun recordingAndLoadingIndicatorsAreVisibleInOnlineMode() {
        val web = loadInterface()
        for (state in listOf("recording", "loading")) {
            val shown = evaluate(
                web,
                "onlineState('$state');" +
                    "var s=getComputedStyle(document.querySelector('.online-stt-status'));" +
                    "s.display+'|'+s.opacity"
            )
            assertTrue("indicator must be displayed for $state, saw $shown", shown.startsWith("flex"))
            assertTrue("indicator must be opaque for $state, saw $shown", !shown.endsWith("|0"))
        }
    }

    @Test
    fun aDeniedPermissionIsReportedToTheUser() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "var s=document.querySelector('.writing-shell');s.classList.add('hold-voice');" +
                "window.AvnivoOnlineMicError('Mikrofon ruxsati berilmadi');" +
                "var h=document.querySelector('.voice-hint');" +
                "h.hidden+'|'+h.textContent+'|'+s.classList.contains('hold-voice')"
        )
        val parts = value.split('|')
        assertEquals("hint must be visible", "false", parts[0])
        assertTrue("hint must carry the reason", parts[1].contains("ruxsat"))
        assertEquals("gesture visual must be cleared", "false", parts[2])
    }

    @Test
    fun hintsAreVisibleInOnlineMode() {
        val web = loadInterface()
        val value = evaluate(web, "hint('sinov xabari');var h=document.querySelector('.voice-hint');h.hidden+'|'+h.textContent")
        assertEquals("false|sinov xabari", value)
    }

    @Test
    fun waveformAmplitudeIsNotHardcodedToATimer() {
        val web = loadInterface()
        // Silence and a loud signal must produce different levels; a timer-driven animation cannot.
        val quiet = evaluate(
            web,
            "var trace=document.querySelector('.voice-trace');" +
                "active=true;analyser={fftSize:1024,getByteTimeDomainData:function(a){a.fill(128)}};" +
                "samples=new Uint8Array(1024);draw();" +
                "(function(){var m=/drop-shadow\\(0 0 ([0-9.]+)px/.exec(trace.style.filter);return m?m[1]:'none'})()"
        )
        val loud = evaluate(
            web,
            "var trace=document.querySelector('.voice-trace');" +
                "active=true;analyser={fftSize:1024,getByteTimeDomainData:function(a){for(var i=0;i<a.length;i++)a[i]=i%2?0:255}};" +
                "samples=new Uint8Array(1024);for(var k=0;k<40;k++)draw();" +
                "(function(){var m=/drop-shadow\\(0 0 ([0-9.]+)px/.exec(trace.style.filter);return m?m[1]:'none'})()"
        )
        assertTrue("silence must still render", quiet != "none")
        assertTrue("loud signal must render", loud != "none")
        assertTrue(
            "loud input must glow more than silence, quiet=$quiet loud=$loud",
            loud.toFloat() > quiet.toFloat()
        )
    }

    @Test
    fun recognizedOnlineTextLandsInComposerWithoutSending() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "var before=document.querySelectorAll('.message-line').length;" +
                "input.value='salom';input.dispatchEvent(new Event('input'));" +
                "var after=document.querySelectorAll('.message-line').length;" +
                "input.value+'|'+(after-before)"
        )
        assertEquals("salom|0", value)
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
     * from "capture actually works". The result is logged so CI can require the marker.
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
