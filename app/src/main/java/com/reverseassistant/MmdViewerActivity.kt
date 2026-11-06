package com.reverseassistant

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.File

/** Local MMD viewer. The selected PMX and its textures are served from app-private storage. */
class MmdViewerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODEL_PATH = "mmd_model_relative_path"
        const val EXTRA_MOTION_PATH = "mmd_motion_relative_path"
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH).orEmpty()
        val motionPath = intent.getStringExtra(EXTRA_MOTION_PATH).orEmpty()
        if (modelPath.isBlank() || modelPath.contains("..") || !modelPath.endsWith(".pmx", true)) {
            finish()
            return
        }
        val modelFile = File(filesDir, "imported-models/$modelPath").canonicalFile
        val root = File(filesDir, "imported-models").canonicalFile
        if (!modelFile.path.startsWith(root.path + File.separator) || !modelFile.isFile) {
            finish()
            return
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/models/", WebViewAssetLoader.InternalStoragePathHandler(this, root))
            .build()
        val view = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? = request?.url?.let { loader.shouldInterceptRequest(it) }

                override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? =
                    url?.let(Uri::parse)?.let { loader.shouldInterceptRequest(it) }
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        webView = view
        setContentView(view)
        val url = "https://appassets.androidplatform.net/assets/mmd_viewer.html" +
            "?model=" + Uri.encode(modelPath) + "&motion=" + Uri.encode(motionPath)
        view.loadUrl(url)
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
