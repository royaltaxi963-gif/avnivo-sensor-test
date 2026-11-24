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
    fun rejectionClearsGestureStateWithoutInsertingText() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "var s=document.querySelector('.writing-shell');s.classList.add('hold-voice');" +
                "window.AvnivoOfflineRejected('Model allaqachon yuklangan.');" +
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

    /**
     * The acceptance criterion: one press, one transcript.
     *
     * Drives the real gesture with a stubbed bridge, then delivers that press's result. The text
     * must appear after the first press alone, with no second attempt.
     */
    @Test
    fun theFirstPressProducesItsOwnTranscript() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOfflineSTT={start:function(s){window.__s=s},stop:function(){}};" +
                "var m=document.querySelector('.mic-icon');" +
                "m.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:1,button:0,isPrimary:true}));" +
                "var session=window.__s;" +
                "m.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,pointerId:1,button:0,isPrimary:true}));" +
                "window.AvnivoOfflineResult('birinchi yozuv',session);" +
                "document.querySelector('.writing-area').value"
        )
        assertEquals("birinchi yozuv", value)
    }

    /**
     * The reported defect: the second press showed the first recording's text. A result stamped
     * with an older session must be refused, and the newer press must keep the composer clean.
     */
    @Test
    fun aStaleResultCannotLandInANewerPress() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOfflineSTT={start:function(s){window.__s=s},stop:function(){}};" +
                "var m=document.querySelector('.mic-icon');" +
                "m.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:1,button:0,isPrimary:true}));" +
                "var first=window.__s;" +
                "m.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,pointerId:1,button:0,isPrimary:true}));" +
                "m.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:2,button:0,isPrimary:true}));" +
                "var second=window.__s;" +
                "m.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,pointerId:2,button:0,isPrimary:true}));" +
                "window.AvnivoOfflineResult('eski natija',first);" +
                "var afterStale=document.querySelector('.writing-area').value;" +
                "window.AvnivoOfflineResult('yangi natija',second);" +
                "afterStale+'|'+document.querySelector('.writing-area').value"
        )
        assertEquals("the stale result must be dropped: $value", "|yangi natija", value)
    }

    /** The processing stage must be visible between release and result, not skipped. */
    @Test
    fun processingIsVisibleBetweenReleaseAndResult() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOfflineSTT={start:function(s){window.__s=s},stop:function(){}};" +
                "var m=document.querySelector('.mic-icon');" +
                "m.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:1,button:0,isPrimary:true}));" +
                "var session=window.__s;" +
                "m.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,pointerId:1,button:0,isPrimary:true}));" +
                "var indicator=document.querySelector('.online-stt-status');" +
                "var during=indicator.dataset.state;" +
                "var shown=getComputedStyle(indicator).display;" +
                "window.AvnivoOfflineResult('tayyor',session);" +
                "during+'|'+shown"
        )
        assertEquals("loading|flex", value)
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

    /**
     * Thirty presses in a row must produce thirty transcripts, each belonging to its own press.
     * This is the automated counterpart of the "30 sequential recordings" acceptance run.
     */
    @Test
    fun thirtyPressesProduceThirtyOwnTranscripts() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOfflineSTT={start:function(s){window.__s=s},stop:function(){},cancel:function(){}};" +
                "var m=document.querySelector('.mic-icon'),ta=document.querySelector('.writing-area'),out=[];" +
                "for(var i=0;i<30;i++){" +
                "ta.value='';" +
                "m.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:i+1,button:0,isPrimary:true}));" +
                "var s=window.__s;" +
                "m.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,pointerId:i+1,button:0,isPrimary:true}));" +
                "window.AvnivoOfflineResult('natija'+i,s);" +
                "out.push(ta.value);" +
                "}" +
                "out.join(',')"
        )
        val expected = (0 until 30).joinToString(",") { "natija$it" }
        assertEquals("every press must insert exactly its own transcript", expected, value)
    }

    /**
     * Ten mode switches must never remove the Uzbek module card or drop it out of its ready
     * appearance. The card is part of the original settings interface and must survive mode churn.
     */
    @Test
    fun tenModeSwitchesKeepTheCardReady() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOfflineSTT={moduleStatus:function(){return JSON.stringify({id:'uzbek-stt',state:'ready',readyForSpeech:true,reason:null,session:0})},start:function(){},stop:function(){},cancel:function(){}};" +
                "var out=[];" +
                "for(var i=0;i<10;i++){" +
                "document.documentElement.dataset.avnivoMode=i%2?'offline':'online';" +
                "window.avnivoSyncUzbekSttCard();" +
                "var row=document.querySelector('[data-module-id=\"uz-stt\"]');" +
                "out.push(row?(row.querySelector('.offline-module-action').classList.contains('is-ready')?'ready':'present'):'missing');" +
                "}" +
                "out.join(',')"
        )
        assertEquals("the card must stay present and ready through every switch",
            List(10) { "ready" }.joinToString(","), value)
    }

    /** The native state callback is the single source of truth for the card and its reason line. */
    @Test
    fun nativeStateCallbackDrivesTheCard() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOfflineState(JSON.stringify({id:'uzbek-stt',state:'recording',reason:null}),0);" +
                "var row=document.querySelector('[data-module-id=\"uz-stt\"]');" +
                "var recording=row.querySelector('.offline-module-action').classList.contains('is-ready');" +
                "var recordingText=row.querySelector('.offline-module-state').textContent.length>0;" +
                "window.AvnivoOfflineState(JSON.stringify({id:'uzbek-stt',state:'error',reason:'sinov xatosi'}),0);" +
                "var note=row.querySelector('.offline-module-reason');" +
                "[recording,recordingText,note.hidden,note.textContent].join('|')"
        )
        assertEquals("true|true|false|sinov xatosi", value)
    }

    /**
     * The reported S23 Ultra card state: after release the native controller must publish
     * TRANSCRIBING, and the card must stop saying "Ovoz yozilmoqda...". A slow Uzbek reload after
     * Whisper Large used to leave this card on recording while inference was already pending.
     */
    @Test
    fun releasedCaptureMovesTheCardFromRecordingToProcessing() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOfflineState(JSON.stringify({id:'uzbek-stt',state:'recording',reason:null}),4);" +
                "var row=document.querySelector('[data-module-id=\"uz-stt\"]');" +
                "var status=row.querySelector('.offline-module-state');" +
                "var recording=status.textContent;" +
                "window.AvnivoOfflineState(JSON.stringify({id:'uzbek-stt',state:'transcribing',reason:null}),4);" +
                "var processing=status.textContent;" +
                "[recording,processing,recording!==processing,processing.includes('yozilmoqda')].join('|')"
        )
        val parts = value.split("|")
        assertEquals("the card must first show recording: $value", true, parts[0].isNotEmpty())
        assertEquals("the card must show processing after release: $value", true, parts[1].isNotEmpty())
        assertEquals("the card text must change: $value", "true", parts[2])
        assertEquals("processing must not still say recording: $value", "false", parts[3])
    }

    /**
     * A progress message must not end a live recording. The old code guessed from the wording of
     * every status string, which could drop the press mid-capture. Only an explicit rejection ends
     * it.
     */
    @Test
    fun progressStatusDoesNotEndALiveRecording() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOfflineSTT={start:function(s){window.__s=s},stop:function(){},cancel:function(){}};" +
                "var m=document.querySelector('.mic-icon'),s=document.querySelector('.writing-shell');" +
                "m.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:1,button:0,isPrimary:true}));" +
                "var during=s.classList.contains('hold-voice');" +
                "window.AvnivoOfflineStatus('Dvigatel ishga tushirilmoqda…');" +
                "var afterStatus=s.classList.contains('hold-voice');" +
                "window.AvnivoOfflineRejected('Model tayyor emas',window.__s);" +
                "var afterReject=s.classList.contains('hold-voice');" +
                "[during,afterStatus,afterReject].join('|')"
        )
        assertEquals("true|true|false", value)
    }

    /** A cancelled press must not insert the transcript its capture still produces. */
    @Test
    fun aCancelledPressDropsItsLateResult() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.__cancelled=0;" +
                "window.AvnivoOfflineSTT={start:function(s){window.__s=s},stop:function(){},cancel:function(s){window.__cancelled=s}};" +
                "var m=document.querySelector('.mic-icon'),s=document.querySelector('.writing-shell'),ta=document.querySelector('.writing-area');" +
                "ta.value='';" +
                "m.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:1,button:0,isPrimary:true}));" +
                "var session=window.__s;" +
                "m.dispatchEvent(new PointerEvent('pointercancel',{bubbles:true,pointerId:1,button:0,isPrimary:true}));" +
                "window.AvnivoOfflineResult('kechikkan natija',session);" +
                "[ta.value,window.__cancelled===session,s.classList.contains('hold-voice')].join('|')"
        )
        assertEquals("|true|false", value)
    }
}
