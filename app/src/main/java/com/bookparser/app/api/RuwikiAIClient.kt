package com.bookparser.app.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

class RuwikiAIClient {

    companion object {
        private const val BASE_URL = "https://ru.ruwiki.ru"
        private const val SEARCH_API = "$BASE_URL/api/ruwiki/rest_v1/assistant-gpt/guest/custom-search"
        private const val GPT_API = "$BASE_URL/api/ruwiki/rest_v1/assistant-gpt/guest/gpt"
        private const val CONNECT_TIMEOUT = 30000L // 30 sec
        private const val READ_TIMEOUT = 60000L    // 60 sec
    }

    // Simple CookieJar implementation to avoid external dependencies
    private class SimpleCookieJar : CookieJar {
        private val cookieStore = HashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: ArrayList()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
        .cookieJar(SimpleCookieJar())
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val messageHistory = mutableListOf<JSONObject>()
    private var isSessionInitialized = false

    /**
     * Initializes the session by visiting the home page to get cookies.
     */
    private suspend fun ensureSession() {
        if (isSessionInitialized) return

        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(BASE_URL)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        isSessionInitialized = true
                    }
                }
            } catch (e: Exception) {
                // Silent fail
            }
        }
    }

    /**
     * Запрос биографии автора с контекстом книги
     */
    suspend fun getBiography(
        authorName: String,
        bookTitle: String? = null,
        bookGenre: String? = null
    ): String? {
        val context = buildString {
            if (bookTitle != null) {
                append("Автор книги \"$bookTitle\"")
                if (bookGenre != null) {
                    append(" (жанр: $bookGenre)")
                }
                append(". ")
            }
        }

        val prompt = """
            ${context}Напиши краткую биографию писателя "$authorName" в одном абзаце (до 800 символов).
            
            Требования:
            - Начни с полного имени автора (с ударениями, если возможно)
            - Укажи даты жизни и место рождения
            - Опиши главные достижения и известные произведения
            - Напиши на каких языках изданы произведения
            - Используй формальный энциклопедический стиль
            - НЕ ИСПОЛЬЗУЙ цифры-ссылки на источники
            - НЕ ИСПОЛЬЗУЙ HTML-теги (кроме базовых если они есть в ответе, но лучше текст)
            - Ответ должен быть одним связным абзацем БЕЗ маркировки источников
        """.trimIndent()

        // Clear history before new biography request to have a fresh context
        messageHistory.clear()
        
        return ask(prompt)
    }

    /**
     * Sends a question to the assistant using the REST API.
     */
    suspend fun ask(question: String): String? = withContext(Dispatchers.IO) {
        try {
            ensureSession()

            // 1. (Optional) Search logic could be added here if needed, 
            // but the GPT endpoint seems to handle context well enough or just answers directly.
            // For now, we go straight to generation as validated by Python script.

            val url = GPT_API

            // Update history
            val userMsg = JSONObject().apply {
                put("content", question)
                put("role", "user")
            }
            messageHistory.add(userMsg)

            val messagesArray = JSONArray()
            messageHistory.forEach { messagesArray.put(it) }

            val jsonBody = JSONObject().apply {
                put("messages", messagesArray)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Referer", BASE_URL)
                .addHeader("Origin", BASE_URL)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
                }

                val responseBodyString = response.body?.string()
                if (responseBodyString.isNullOrEmpty()) {
                    return@withContext null
                }

                // Parse response
                val responseJson = JSONObject(responseBodyString)
                val assistantMessageHtml = responseJson.optString("message", "")
                
                if (assistantMessageHtml.isNotEmpty()) {
                    // Update history with assistant response
                    messageHistory.add(JSONObject().apply {
                        put("content", assistantMessageHtml)
                        put("role", "assistant")
                    })

                    val cleanText = cleanHtmlTags(assistantMessageHtml)
                    return@withContext cleanText
                } else {
                    return@withContext null
                }
            }

        } catch (e: Exception) {
            null
        }
    }

    /**
     * Очищает HTML теги и удаляет цифры источников
     */
    private fun cleanHtmlTags(html: String): String {
        var text = html

        // Заменяем <p> на переносы строк
        text = text.replace(Regex("<p>"), "")
        text = text.replace(Regex("</p>"), "\n\n")

        // Заменяем <br> на переносы
        text = text.replace(Regex("<br\\s*/?>"), "\n")
        
        // Убираем списки <ul>, <ol>, <li>
        text = text.replace(Regex("</?ul>"), "")
        text = text.replace(Regex("</?ol>"), "")
        text = text.replace("<li>", "• ")
        text = text.replace("</li>", "\n")

        // Убираем цифры источников типа " 1" или " [1]", а также spans с заметками
        text = text.replace(Regex("<span[^>]*class=\"[^\"]*note[^\"]*\"[^>]*>.*?</span>"), "")
        text = text.replace(Regex("\\s+\\d+\\s*"), " ")
        text = text.replace(Regex("\\[\\d+\\]"), "")

        // Убираем все оставшиеся HTML теги
        text = text.replace(Regex("<[^>]+>"), "")

        // Декодируем HTML-сущности
        text = text.replace("&nbsp;", " ")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

        // Убираем множественные переносы и пробелы
        text = text.replace(Regex("\n{3,}"), "\n\n")
        text = text.replace(Regex(" +"), " ")

        return text.trim()
    }

    fun clearHistory() {
        messageHistory.clear()
    }
}
