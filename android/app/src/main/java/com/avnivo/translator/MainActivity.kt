package com.avnivo.translator

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var pendingMicrophoneRequest: PermissionRequest? = null
    private val localOrigin = "https://appassets.androidplatform.net"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.origin.toString().removeSuffix("/") != localOrigin ||
                        !request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        request.deny()
                        return@runOnUiThread
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingMicrophoneRequest?.deny()
                        pendingMicrophoneRequest = request
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                    }
                }
            }
            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingMicrophoneRequest === request) pendingMicrophoneRequest = null
            }
        }
        webView.loadUrl("$localOrigin/assets/index.html")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            val pending = pendingMicrophoneRequest
            pendingMicrophoneRequest = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                pending?.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else pending?.deny()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        pendingMicrophoneRequest?.deny()
        pendingMicrophoneRequest = null
        webView.destroy()
        super.onDestroy()
    }
}
