package com.bookparser.app.web.search

import com.bookparser.app.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class DohHttpClient private constructor() {

    companion object {
        val INSTANCE = DohHttpClient()
        private const val TAG = "DOH_HTTP"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
    }

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
            AppLogger.e(TAG, "DoH resolve failed for $hostname: ${e.message}")
            null
        }
    }

    suspend fun fetchViaDoh(url: String, extraHeaders: Map<String, String> = emptyMap()): String? = withContext(Dispatchers.IO) {
        try {
            val uri = java.net.URI(url)
            val hostname = uri.host ?: return@withContext null
            val ip = resolveViaDoh(hostname)
            val actualUrl = if (ip != null) {
                url.replace("://$hostname", "://${ip.hostAddress}")
            } else {
                url
            }
            AppLogger.i(TAG, "Fetching: $url (IP: ${ip?.hostAddress ?: "direct"})")
            val builder = Request.Builder()
                .url(actualUrl)
                .header("Host", hostname)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            val request = builder.build()
            val response = sslClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes() ?: return@withContext null
                AppLogger.i(TAG, "OK: ${bytes.size} bytes from $hostname")
                String(bytes, Charsets.UTF_8)
            } else {
                AppLogger.e(TAG, "HTTP ${response.code} from $hostname")
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Fetch failed for $url: ${e.message}")
            null
        }
    }

    suspend fun fetchViaDohBytes(url: String, extraHeaders: Map<String, String> = emptyMap()): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        try {
            val uri = java.net.URI(url)
            val hostname = uri.host ?: return@withContext null
            val ip = resolveViaDoh(hostname)
            val actualUrl = if (ip != null) {
                url.replace("://$hostname", "://${ip.hostAddress}")
            } else {
                url
            }
            val builder = Request.Builder()
                .url(actualUrl)
                .header("Host", hostname)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            val request = builder.build()
            val response = sslClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes() ?: return@withContext null
                val contentType = response.header("Content-Type") ?: "text/html; charset=UTF-8"
                Pair(bytes, contentType)
            } else null
        } catch (e: Exception) {
            AppLogger.e(TAG, "FetchBytes failed for $url: ${e.message}")
            null
        }
    }
}
