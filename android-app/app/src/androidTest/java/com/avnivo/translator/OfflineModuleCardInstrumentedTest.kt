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
 * Drives the real packaged settings card for Whisper Small Q8 through the native bridge callbacks.
 *
 * The S23 Ultra reported a percentage that appeared but did not move, a false "Faol" before the
 * model was loaded, and "Faol" falling back to "Yuklash". These assertions pin the card contract:
 * progress is rendered from real bytes, INSTALLED is verified but not ready, INITIALIZING has its
 * own wording and no progress bar, and only the native READY callback turns the card green.
 */
@RunWith(AndroidJUnit4::class)
class OfflineModuleCardInstrumentedTest {

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
        evaluate(
            web.get(),
            "localStorage.clear();localStorage.setItem('avnivo.ui.lang.v1','uz');" +
                "document.documentElement.dataset.avnivoMode='offline';" +
                "if(window.AVNIVO_I18N)window.AVNIVO_I18N.setLanguage('uz');" +
                "window.AvnivoOfflineModules={statusAll:function(){return '[]'},status:function(){return JSON.stringify(window.__status||{})},download:function(){}};'ok'"
        )
        // The cards assert exact Uzbek state text, so wait for the pinned bundle to apply instead
        // of inheriting a locale saved by an earlier run.
        repeat(30) {
            if (evaluate(web.get(), "(window.AVNIVO_I18N&&window.AVNIVO_I18N.getLanguage())||''") == "uz") return web.get()
            Thread.sleep(100)
        }
        return web.get()
    }

    private fun cardState(web: WebView): String = evaluate(
        web,
        "var r=document.querySelector('[data-module-id=\"multilingual-stt\"]');" +
            "var b=r.querySelector('.offline-module-action');" +
            "[b.classList.contains('is-ready'),b.classList.contains('is-verified'),b.disabled," +
            "r.querySelector('.offline-module-progress').hidden," +
            "r.querySelector('.offline-module-state').textContent].join('|')"
    )

    @Test
    fun progressIsRenderedFromRealBytes() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOfflineModuleProgress('whisper-small-q8|132232304/264464607');" +
                "var r=document.querySelector('[data-module-id=\"multilingual-stt\"]');" +
                "[r.querySelector('.offline-module-percent').textContent," +
                "r.querySelector('.offline-module-mb').textContent," +
                "r.querySelector('.offline-module-progress').hidden," +
                "r.querySelector('.offline-module-state').textContent].join('|')"
        )
        assertEquals("50%|132,2 MB / 264,5 MB|false|Yuklab olinmoqda…", value)
    }

    @Test
    fun installedIsVerifiedButNotReady() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.__status={id:'whisper-small-q8',state:'installed',percent:0,active:false,progress:null,verified:true,resident:false,reason:null};" +
                "window.AvnivoOfflineModuleStatus('whisper-small-q8');" +
                "var r=document.querySelector('[data-module-id=\"multilingual-stt\"]');" +
                "[r.querySelector('.offline-module-action').classList.contains('is-ready')," +
                "r.querySelector('.offline-module-action').classList.contains('is-verified')," +
                "r.querySelector('.offline-module-progress').hidden," +
                "r.querySelector('.offline-module-state').textContent].join('|')"
        )
        assertEquals("false|true|true|O‘rnatilgan va tekshirilgan · ishlatilganda yuklanadi", value)
    }

    @Test
    fun initializingHasItsOwnWordingAndNoProgressBar() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOfflineModuleProgress('whisper-small-q8|132232303/264464607');" +
                "window.__status={id:'whisper-small-q8',state:'initializing',percent:0,active:false,progress:null,verified:true,resident:false,reason:null};" +
                "window.AvnivoOfflineModuleStatus('whisper-small-q8');" +
                "var r=document.querySelector('[data-module-id=\"multilingual-stt\"]');" +
                "[r.querySelector('.offline-module-action').classList.contains('is-ready')," +
                "r.querySelector('.offline-module-action').disabled," +
                "r.querySelector('.offline-module-progress').hidden," +
                "r.querySelector('.offline-module-state').textContent].join('|')"
        )
        assertEquals("false|true|true|Ishga tushmoqda…", value)
    }

    @Test
    fun onlyTheNativeReadyCallbackTurnsTheCardGreen() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.__status={id:'whisper-small-q8',state:'ready',percent:0,active:true,progress:null,verified:true,resident:true,reason:null};" +
                "window.AvnivoOfflineModuleStatus('whisper-small-q8');" +
                "var r=document.querySelector('[data-module-id=\"multilingual-stt\"]');" +
                "[r.querySelector('.offline-module-action').classList.contains('is-ready')," +
                "r.querySelector('.offline-module-state').textContent].join('|')"
        )
        assertEquals("true|Faol — nutqni tanishga tayyor", value)
    }

    @Test
    fun aStaleZeroPercentDoesNotSurviveInstalled() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOfflineModuleProgress('whisper-small-q8|0/264464607');" +
                "window.__status={id:'whisper-small-q8',state:'installed',percent:0,active:false,progress:null,verified:true,resident:false,reason:null};" +
                "window.AvnivoOfflineModuleStatus('whisper-small-q8');" +
                "var r=document.querySelector('[data-module-id=\"multilingual-stt\"]');" +
                "[r.querySelector('.offline-module-progress').hidden,String(r.avnivoLastProgress)].join('|')"
        )
        assertEquals("true|null", value)
    }

    @Test
    fun pageshowReconcilesTheCardWithNativeState() {
        val web = loadInterface()
        val value = evaluate(
            web,
            "window.AvnivoOfflineModules={statusAll:function(){return JSON.stringify([{id:'whisper-small-q8',state:'installed',percent:0,active:false,progress:null,verified:true,resident:false,reason:null}])},status:function(){return '{}'},download:function(){}};" +
                "window.dispatchEvent(new Event('pageshow'));" +
                "var r=document.querySelector('[data-module-id=\"multilingual-stt\"]');" +
                "[r.querySelector('.offline-module-action').classList.contains('is-ready')," +
                "r.querySelector('.offline-module-action').classList.contains('is-verified')," +
                "r.querySelector('.offline-module-state').textContent].join('|')"
        )
        assertEquals("false|true|O‘rnatilgan va tekshirilgan · ishlatilganda yuklanadi", value)
    }

    /**
     * The S23 Ultra picker was gated on NLLB being READY and on the Whisper card's language list.
     * This drives the real sheet with the real registry and no NLLB module: all 202 languages must
     * be searchable, selectable and persisted, while translation stays gated.
     */
    @Test
    fun offlinePickerUsesTheFullRegistryWithoutNllbInstalled() {
        val web = loadInterface()
        val registry = org.json.JSONObject.quote(NllbLanguageRegistry.json())
        evaluate(
            web,
            "window.AvnivoOfflineModules={" +
                "offlineLanguageRegistry:function(){return $registry}," +
                "offlineTranslationState:function(){return JSON.stringify({ready:false,languages:[],status:{state:'not_installed'}})}," +
                "statusAll:function(){return '[]'},status:function(){return '{}'},download:function(){}};'ok'"
        )
        evaluate(
            web,
            "document.querySelector('.source-language').dispatchEvent(new MouseEvent('pointerdown',{button:0,bubbles:true}));'ok'"
        )
        Thread.sleep(1400)
        assertEquals("202", evaluate(web, "document.querySelectorAll('.online-language-results .online-language-item').length"))
        assertTrue(
            "header must show the registry count",
            evaluate(web, "document.querySelector('.online-language-head strong').textContent").contains("202")
        )
        evaluate(
            web,
            "var s=document.querySelector('.online-language-search');s.value='Russian';s.dispatchEvent(new Event('input',{bubbles:true}));'ok'"
        )
        assertEquals("1", evaluate(web, "document.querySelectorAll('.online-language-results .online-language-item').length"))
        evaluate(web, "document.querySelector('.online-language-results .online-language-item').click();'ok'")
        assertEquals("Russian", evaluate(web, "document.querySelector('.source-language .language-name').textContent"))
        assertEquals("RUS", evaluate(web, "document.querySelector('.source-language .language-code').textContent"))
        assertTrue(
            "the selected code must be persisted",
            evaluate(web, "localStorage.getItem('avnivo.offline.language-pair.v1')||''").contains("rus_Cyrl")
        )
    }
}
