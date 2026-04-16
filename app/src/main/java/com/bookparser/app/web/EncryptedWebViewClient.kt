package com.bookparser.app.web

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Properties

open class EncryptedWebViewClient(private val context: Context) : WebViewClient() {

    private val KEY = "MySecretKey123".toByteArray() // В реальном приложении ключ можно обфусцировать

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null

        if (url.startsWith("https://app-assets/")) {
            val fileName = url.substringAfter("https://app-assets/")
            return getEncryptedAsset(fileName)
        }
        
        // Также перехватываем стандартные file:///android_asset/ если нужно, 
        // но лучше перевести загрузку на кастомную схему https:// для безопасности.
        if (url.contains("android_asset/")) {
            val fileName = url.substringAfter("android_asset/")
            return getEncryptedAsset(fileName)
        }

        return super.shouldInterceptRequest(view, request)
    }

    private fun getEncryptedAsset(fileName: String): WebResourceResponse? {
        return try {
            val encryptedFileName = if (fileName.endsWith(".enc")) fileName else "$fileName.enc"
            val inputStream = context.assets.open(encryptedFileName)
            val decryptedData = decrypt(inputStream)
            
            val mimeType = getMimeType(fileName)
            WebResourceResponse(mimeType, "UTF-8", ByteArrayInputStream(decryptedData))
        } catch (e: Exception) {
            null
        }
    }

    private fun decrypt(inputStream: InputStream): ByteArray {
        val encryptedData = inputStream.readBytes()
        val decryptedData = ByteArray(encryptedData.size)
        for (i in encryptedData.indices) {
            decryptedData[i] = (encryptedData[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        }
        return decryptedData
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".html") || fileName.endsWith(".html.enc") -> "text/html"
            fileName.endsWith(".js") || fileName.endsWith(".js.enc") -> "application/javascript"
            fileName.endsWith(".css") || fileName.endsWith(".css.enc") -> "text/css"
            fileName.endsWith(".png") || fileName.endsWith(".png.enc") -> "image/png"
            fileName.endsWith(".jpg") || fileName.endsWith(".jpg.enc") || fileName.endsWith(".jpeg") -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }
}
