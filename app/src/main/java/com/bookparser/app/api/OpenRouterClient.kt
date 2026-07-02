package com.bookparser.app.api

import com.bookparser.app.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for the AI-architect's optional reasoning steps (duplicate verdicts,
 * candidate ranking, final correctness check) via OpenRouter's OpenAI-compatible API.
 * Every public method degrades to `null` (never throws) when no key is configured or the
 * request fails, so the orchestrator can always fall back to its built-in heuristics.
 */
class OpenRouterClient {

    companion object {
        private const val TAG = "OPENROUTER"
        private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val MODEL = "nvidia/nemotron-3-ultra-550b-a55b:free"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Sends one user prompt, expects the model to reply with a single JSON object. */
    private suspend fun completeJson(apiKey: String, prompt: String): JSONObject? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }
                ))
            }
            val request = Request.Builder()
                .url(API_URL)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "https://4pda.to")
                .addHeader("X-Title", "Bookstore AI Architect")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.e(TAG, "HTTP ${response.code}: ${response.body?.string()?.take(300)}")
                    return@withContext null
                }
                val raw = response.body?.string() ?: return@withContext null
                val content = JSONObject(raw)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?: return@withContext null
                extractJson(content)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Request failed: ${e.message}")
            null
        }
    }

    /** Models often wrap JSON in ```json fences or add prose around it — pull out the object. */
    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (e: Exception) { null }
    }

    /**
     * Judges whether any of the found forum topics is the same book already in Книгохранилище.
     * Returns {"isDuplicate": bool, "matchIndex": int|null, "reason": string}.
     */
    suspend fun checkDuplicate(apiKey: String, book: JSONObject, matches: JSONArray): JSONObject? {
        val matchLines = StringBuilder()
        for (i in 0 until matches.length()) {
            val m = matches.getJSONObject(i)
            matchLines.append("$i. \"${m.optString("title")}\" (эвристический score: ${m.optInt("score")})\n")
        }
        val prompt = """
            Ты помогаешь модератору книжного раздела форума решить, не публиковалась ли уже книга.

            Книга-кандидат для публикации:
            Автор: ${book.optString("author")}
            Название: ${book.optString("title")}
            Серия: ${book.optString("series")} ${book.optString("seriesNum")}

            Найденные на форуме темы (в разделе книг), возможные совпадения:
            $matchLines

            Определи, есть ли среди этих тем ТА ЖЕ САМАЯ книга (не просто другая книга того же автора).
            Ответь СТРОГО в виде JSON без пояснений вокруг:
            {"isDuplicate": true|false, "matchIndex": <номер темы из списка выше или null>, "reason": "краткое обоснование по-русски"}
        """.trimIndent()
        return completeJson(apiKey, prompt)
    }

    /**
     * Suggests which search candidate best matches what the user typed.
     * Returns {"bestIndex": int|null, "reason": string}. Advisory only — the user still picks.
     */
    suspend fun rankCandidates(apiKey: String, query: String, candidates: JSONArray): JSONObject? {
        val lines = StringBuilder()
        for (i in 0 until candidates.length()) {
            val c = candidates.getJSONObject(i)
            lines.append("$i. \"${c.optString("title")}\" — ${c.optString("author")} (источник: ${c.optString("source")})\n")
        }
        val prompt = """
            Пользователь ищет книгу по запросу: "$query"

            Найденные варианты:
            $lines

            Какой вариант точнее всего соответствует запросу (правильное издание/автор, без опечаток и путаницы с одноимёнными книгами)?
            Это только подсказка — пользователь выберет вариант сам.
            Ответь СТРОГО в виде JSON:
            {"bestIndex": <номер варианта из списка или null, если неочевидно>, "reason": "краткое обоснование по-русски"}
        """.trimIndent()
        return completeJson(apiKey, prompt)
    }

    /**
     * Final sanity check of extracted metadata before the publish form is filled.
     * Returns {"ok": bool, "warnings": [string], "summary": string}.
     */
    suspend fun checkCorrectness(apiKey: String, book: JSONObject): JSONObject? {
        val prompt = """
            Перед публикацией книги на форуме проверь корректность и полноту извлечённых данных:
            Автор: ${book.optString("author")}
            Название: ${book.optString("title")}
            Серия: ${book.optString("series")} ${book.optString("seriesNum")}
            Год: ${book.optString("year")}
            Жанр: ${book.optString("genre")}
            Язык: ${book.optString("lang")}
            Аннотация: ${book.optString("annotation").take(1500)}

            Проверь: не пустые ли ключевые поля (автор, название), не выглядит ли аннотация обрезанной
            или "мусорной" (артефакты парсинга), похож ли жанр на реальный жанр художественной/нон-фикшн литературы.
            Ответь СТРОГО в виде JSON:
            {"ok": true|false, "warnings": ["список конкретных проблем по-русски, если есть"], "summary": "одна короткая фраза-вывод"}
        """.trimIndent()
        return completeJson(apiKey, prompt)
    }
}
