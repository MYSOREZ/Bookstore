package com.bookparser.app.web

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class DohWebViewClient(
    private val siteId: String,
    private val onExtract: (WebView?) -> Unit,
    private val onError: () -> Unit
) : WebViewClient() {

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val sslClient: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun resolveViaDoh(hostname: String): InetAddress? {
        return try {
            val url = "https://dns.google/resolve?name=$hostname&type=A"
            val request = Request.Builder().url(url).build()
            val response = dohClient.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return null
            for (i in 0 until answers.length()) {
                val answer = answers.getJSONObject(i)
                if (answer.optInt("type") == 1) {
                    return InetAddress.getByName(answer.getString("data"))
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchViaDoh(url: String): Pair<ByteArray, String>? {
        return try {
            val uri = Uri.parse(url)
            val hostname = uri.host ?: return null
            val ip = resolveViaDoh(hostname)
            val actualUrl = if (ip != null) {
                url.replace("://$hostname", "://${ip.hostAddress}")
            } else {
                url
            }
            val request = Request.Builder()
                .url(actualUrl)
                .header("Host", hostname)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            val response = sslClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes() ?: return null
                val contentType = response.header("Content-Type") ?: "text/html; charset=UTF-8"
                Pair(bytes, contentType)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request == null || !request.isForMainFrame) return null
        val url = request.url.toString()
        val result = fetchViaDoh(url) ?: return null
        val (bytes, contentType) = result
        val mime = contentType.split(";")[0].trim().ifEmpty { "text/html" }
        val encoding = if (contentType.contains("charset=")) {
            contentType.split("charset=")[1].trim().split("[;, ]")[0]
        } else "UTF-8"
        return WebResourceResponse(mime, encoding, ByteArrayInputStream(bytes))
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onExtract(view)
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
        if (request?.isForMainFrame == true) {
            onError()
        }
    }
}
