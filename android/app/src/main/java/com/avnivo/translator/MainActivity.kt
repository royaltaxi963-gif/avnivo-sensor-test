package com.avnivo.translator

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Single-activity shell that renders the bundled AVNIVO web app inside a WebView.
 *
 * The web app is loaded from a trusted local origin ([OriginPolicy.LOCAL_ORIGIN])
 * served by [WebViewAssetLoader], so the 100-language interface, STT/TTS, and
 * online/offline modules behave exactly as they do on the web. Only that origin may
 * request the microphone; all external navigation is blocked.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingMicRequest: PermissionRequest? = null
    private var documentStartScriptSupported = false
    private var polyfill = ""

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        webView = WebView(this)
        setContentView(webView)
        configure(webView)
        if (state == null) {
            webView.loadUrl(START_URL)
        } else {
            webView.restoreState(state)
        }
    }

    private fun configure(view: WebView) {
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The interface is loaded from app assets only; block file and content
            // access so a compromised page cannot read the local filesystem.
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
        }
        WebView.setWebContentsDebuggingEnabled(false)

        // The bundled UI uses Element.replaceChildren (Chrome 86+). Older WebViews
        // would throw on first render, so polyfill it. Prefer document-start injection
        // (WebView 84+); on older WebViews fall back to injecting as the page starts.
        val polyfillSource = readAsset(POLYFILL_ASSET)
        polyfill = polyfillSource
        if (polyfillSource.isNotEmpty() &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                polyfillSource,
                setOf(OriginPolicy.LOCAL_ORIGIN)
            )
            documentStartScriptSupported = true
        } else {
            documentStartScriptSupported = false
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!documentStartScriptSupported && polyfill.isNotEmpty() &&
                    OriginPolicy.isLocalNavigation(url ?: "")
                ) {
                    view.evaluateJavascript(polyfill, null)
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = !OriginPolicy.isLocalNavigation(request.url.toString())
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleMicPermission(request) }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingMicRequest === request) pendingMicRequest = null
            }
        }
    }

    private fun handleMicPermission(request: PermissionRequest) {
        val origin = request.origin.toString()
        val resources = request.resources
        if (!OriginPolicy.shouldGrantMic(origin, resources)) {
            request.deny()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            return
        }
        pendingMicRequest?.deny()
        pendingMicRequest = request
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            MICROPHONE_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MICROPHONE_REQUEST_CODE) return
        val request = pendingMicRequest ?: return
        pendingMicRequest = null
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            request.deny()
        }
    }

    private fun readAsset(name: String): String = try {
        assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        ""
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        pendingMicRequest?.deny()
        pendingMicRequest = null
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val MICROPHONE_REQUEST_CODE = 41
        private const val START_URL = OriginPolicy.START_URL
        private const val POLYFILL_ASSET = "polyfill-replace-children.js"
    }
}
