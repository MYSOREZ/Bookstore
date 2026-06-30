package com.bookparser.app.web.search

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bookparser.app.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WebViewSearcher(private val context: Context) {

    companion object {
        private const val TAG = "WV_FETCH"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
        private var INSTANCE: WebViewSearcher? = null

        fun init(context: Context) {
            if (INSTANCE == null) {
                INSTANCE = WebViewSearcher(context.applicationContext)
            }
        }

        fun get(): WebViewSearcher = INSTANCE
            ?: throw IllegalStateException("WebViewSearcher not initialized. Call init(context) first.")
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchHtml(
        url: String,
        waitForStableMs: Long = 5000,
        timeoutMs: Long = 30000
    ): String? {
        AppLogger.i(TAG, "Fetching: $url")
        return suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var stableTimer: Runnable? = null
            val startTime = System.currentTimeMillis()

            var timeoutRunnable: Runnable? = null

            fun cleanup() {
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                stableTimer?.let { mainHandler.removeCallbacks(it) }
                try { webView?.destroy() } catch (_: Exception) {}
                webView = null
            }

            timeoutRunnable = Runnable {
                AppLogger.w(TAG, "Timeout after ${timeoutMs}ms for $url")
                cleanup()
                if (cont.isActive) cont.resume(null)
            }
            mainHandler.postDelayed(timeoutRunnable!!, timeoutMs)

            fun extractHtml(wv: WebView, loadedUrl: String?) {
                val elapsed = System.currentTimeMillis() - startTime
                AppLogger.i(TAG, "Extracting HTML after ${elapsed}ms from $loadedUrl")
                wv.evaluateJavascript(
                    "(function(){ return document.documentElement.outerHTML; })()"
                ) { raw ->
                    val html = decodeJsString(raw)
                    AppLogger.i(TAG, "Got ${html?.length ?: 0} chars from $loadedUrl")
                    cleanup()
                    if (cont.isActive) cont.resume(html)
                }
            }

            fun scheduleExtract(wv: WebView, loadedUrl: String?) {
                stableTimer?.let { mainHandler.removeCallbacks(it) }
                stableTimer = Runnable {
                    if (cont.isActive) extractHtml(wv, loadedUrl)
                }
                mainHandler.postDelayed(stableTimer!!, waitForStableMs)
            }

            mainHandler.post {
                val wv = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.userAgentString = USER_AGENT
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                }
                webView = wv

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        AppLogger.i(TAG, "onPageFinished: $loadedUrl")
                        if (cont.isActive && view != null) {
                            scheduleExtract(view, loadedUrl)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            AppLogger.e(TAG, "Error loading ${request.url}: ${error?.description}")
                            cleanup()
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }

                wv.loadUrl(url)
            }

            cont.invokeOnCancellation { cleanup() }
        }
    }

    private fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        val unquoted = raw.removeSurrounding("\"")
        return unquoted
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u0026", "&")
            .replace("\\u0022", "\"")
            .replace("\\u0027", "'")
            .replace("\\n", "\n")
            .replace("\\r", "")
            .replace("\\t", "\t")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
    }
}
