package com.bookparser.app.architect

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import com.bookparser.app.AppLogger
import com.bookparser.app.MainActivity
import com.bookparser.app.api.OpenRouterClient
import com.bookparser.app.web.search.BookSearchManager
import com.bookparser.app.web.search.DohHttpClient
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the existing search → download → parse → duplicate-check → publish-prefill
 * pipeline (search.html / parser.html / forum WebView) from one entry point, driven by
 * architect.html. Reuses the existing JS logic as-is (parseOnMain(), searchOnForum(),
 * sendToForum(), scoreTopic()) instead of reimplementing FB2/EPUB parsing or forum DOM
 * automation — it only feeds bytes in and listens for the same callbacks parser.html already
 * produces. Always stops before the forum's Submit button; the user sends the post manually.
 */
class BookArchitectOrchestrator(private val activity: MainActivity) {

    companion object {
        private const val TAG = "ARCHITECT"

        // Above this score (see parser.html's scoreTopic()) a forum match is treated as
        // "likely the same book" and publishing is not prepared automatically.
        private const val DUPLICATE_SCORE_THRESHOLD = 5

        private val DEFAULT_DOMAINS = mapOf(
            "flibusta" to "http://flibusta.is",
            "annas" to "https://annas-archive.gd",
            "zlib" to "https://ru.z-lib.fm",
            "librain" to "https://librain.ru"
        )
    }

    private val searchManager = BookSearchManager()
    private val openRouterClient = OpenRouterClient()
    private var parsedDeferred: CompletableDeferred<String>? = null
    private var duplicateDeferred: CompletableDeferred<String>? = null

    /** Empty when the user hasn't configured a key in Settings — every AI step then no-ops. */
    private fun apiKey(): String =
        activity.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("openrouter_api_key", "")?.trim() ?: ""

    fun startSearch(query: String) {
        activity.lifecycleScope.launch {
            report(JSONObject().put("type", "searching"))
            try {
                val items = searchAllSites(query)
                val stepReport = JSONObject().put("type", "candidates").put("items", items)

                val key = apiKey()
                if (key.isNotEmpty() && items.length() > 0) {
                    val verdict = openRouterClient.rankCandidates(key, query, items)
                    if (verdict != null) {
                        stepReport.put("aiRecommendedIndex", verdict.opt("bestIndex") ?: JSONObject.NULL)
                        stepReport.put("aiReason", verdict.optString("reason"))
                    }
                }
                report(stepReport)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Search failed: ${e.message}")
                reportError("Ошибка поиска: ${e.message}")
            }
        }
    }

    fun selectCandidate(candidateJson: String) {
        activity.lifecycleScope.launch {
            try {
                val candidate = JSONObject(candidateJson)
                report(JSONObject().put("type", "downloading").put("candidate", candidate))
                AppLogger.i(TAG, "selectCandidate: ${candidate.optString("title")} / ${candidate.optString("formatLabel")} / ${candidate.optString("formatUrl")}")

                val formatUrl = candidate.optString("formatUrl")
                if (formatUrl.isEmpty()) {
                    reportError("У выбранного варианта нет ссылки на файл")
                    return@launch
                }
                val fileName = buildFileName(candidate)

                val bytes = downloadBytes(formatUrl)
                if (bytes == null || bytes.isEmpty()) {
                    reportError("Не удалось скачать файл (обе попытки — через DoH и напрямую — не дали годного содержимого)")
                    return@launch
                }
                AppLogger.i(TAG, "Downloaded ${bytes.size} bytes for $fileName")

                report(JSONObject().put("type", "parsing"))
                val bookJson = feedToParserAndAwait(bytes, fileName)
                if (bookJson == null) {
                    reportError("Не удалось извлечь метаданные из файла (тайм-аут разбора)")
                    return@launch
                }
                val book = JSONObject(bookJson)
                AppLogger.i(TAG, "Parsed: title='${book.optString("title")}' author='${book.optString("author")}'")

                if (book.optString("title").isBlank() && book.optString("author").isBlank()) {
                    reportError("Файл скачался, но парсер не нашёл ни названия, ни автора — похоже, скачан не тот файл (например, страница-заглушка вместо книги). Попробуйте другой источник/формат.")
                    return@launch
                }
                report(JSONObject().put("type", "extracted").put("book", book))

                val key = apiKey()

                report(JSONObject().put("type", "checking_duplicate"))
                val dupArray = runDuplicateCheck()
                val topScore = if (dupArray.length() > 0) dupArray.getJSONObject(0).optInt("score", 0) else 0
                var likelyDuplicate = topScore >= DUPLICATE_SCORE_THRESHOLD

                val dupResultReport = JSONObject()
                    .put("type", "duplicate_result")
                    .put("matches", dupArray)

                if (key.isNotEmpty() && dupArray.length() > 0) {
                    val verdict = openRouterClient.checkDuplicate(key, book, dupArray)
                    if (verdict != null) {
                        dupResultReport.put("aiVerdict", verdict)
                        if (verdict.optBoolean("isDuplicate", false)) likelyDuplicate = true
                    }
                }
                dupResultReport.put("likelyDuplicate", likelyDuplicate)
                report(dupResultReport)

                if (likelyDuplicate) {
                    report(JSONObject().put("type", "stopped_duplicate"))
                    return@launch
                }

                if (key.isNotEmpty()) {
                    report(JSONObject().put("type", "checking_correctness"))
                    val correctness = openRouterClient.checkCorrectness(key, book)
                    if (correctness != null) {
                        report(JSONObject().put("type", "correctness_result").put("result", correctness))
                        if (!correctness.optBoolean("ok", true)) {
                            report(JSONObject().put("type", "stopped_correctness"))
                            return@launch
                        }
                    }
                }

                requestPublishPrefill()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Pipeline failed: ${e.message}")
                reportError("Ошибка: ${e.message}")
            }
        }
    }

    fun requestPublishPrefill() {
        report(JSONObject().put("type", "filling_form"))
        activity.isArchitectPublishing = true
        activity.parserCallback("(function(){ if (typeof sendToForum === 'function') sendToForum(); })();")
        // Completion arrives later via onPublishComplete()/onError(), invoked from
        // MainActivity.processPendingPublication() once the forum form is filled.
    }

    fun onBookParsed(json: String) {
        parsedDeferred?.complete(json)
        parsedDeferred = null
    }

    fun onDuplicateCheckResult(json: String) {
        duplicateDeferred?.complete(json)
        duplicateDeferred = null
    }

    fun onPublishComplete() {
        report(JSONObject().put("type", "publish_ready"))
    }

    fun onError(message: String) {
        reportError(message)
    }

    private suspend fun searchAllSites(query: String): JSONArray {
        val savedDomains = try {
            JSONObject(
                activity.getSharedPreferences("book_search", Context.MODE_PRIVATE)
                    .getString("domains", "{}") ?: "{}"
            )
        } catch (e: Exception) {
            JSONObject()
        }

        val deferreds = DEFAULT_DOMAINS.map { (siteId, defaultDomain) ->
            val domain = savedDomains.optString(siteId).takeIf { it.isNotEmpty() } ?: defaultDomain
            val deferred = CompletableDeferred<JSONArray>()
            searchManager.search(
                siteId = siteId,
                query = query,
                domain = domain,
                scope = activity.lifecycleScope,
                onResult = { id, json ->
                    val arr = try { JSONArray(json) } catch (e: Exception) { JSONArray() }
                    for (i in 0 until arr.length()) arr.getJSONObject(i).put("source", id)
                    deferred.complete(arr)
                },
                onError = { _, _ -> deferred.complete(JSONArray()) }
            )
            deferred
        }

        val merged = JSONArray()
        deferreds.forEach { deferred ->
            val arr = withTimeoutOrNull(25_000) { deferred.await() } ?: JSONArray()
            for (i in 0 until arr.length()) merged.put(arr.getJSONObject(i))
        }
        return merged
    }

    private fun buildFileName(candidate: JSONObject): String {
        val author = candidate.optString("author").trim()
        val title = candidate.optString("title").trim()
        val label = candidate.optString("formatLabel", "fb2").lowercase()
        val base = (if (author.isNotEmpty()) "$author - $title" else title).ifBlank { "book" }
        return if (base.lowercase().endsWith(".$label")) base else "$base.$label"
    }

    /**
     * Some sources (flibusta in particular) are DNS-blocked for plain requests — search already
     * works around this via DohHttpClient's DNS-over-HTTPS resolution, so downloads need the same
     * workaround. DoH and a direct OkHttp request are raced concurrently rather than tried in
     * sequence, since which one actually works varies by source/network and sequential retries
     * cost up to a minute of dead waiting before falling back.
     */
    private suspend fun downloadBytes(url: String): ByteArray? = coroutineScope {
        val cookies = CookieManager.getInstance().getCookie(url)
        val cookieHeader: Map<String, String> =
            cookies?.takeIf { it.isNotEmpty() }?.let { mapOf("Cookie" to it) } ?: emptyMap()

        val dohAttempt = async {
            runCatching { DohHttpClient.INSTANCE.fetchViaDohBytes(url, cookieHeader)?.first }
                .getOrNull()
                .also { AppLogger.i(TAG, "Download via DoH: ${it?.size ?: 0} bytes") }
        }
        val directAttempt = async {
            runCatching { downloadDirect(url, cookieHeader) }
                .getOrNull()
                .also { AppLogger.i(TAG, "Download direct: ${it?.size ?: 0} bytes") }
        }

        // True race — whichever finishes first with a plausible file wins immediately instead of
        // always waiting for both (which used to mean eating DoH's full timeout even when the
        // direct request already succeeded in a couple of seconds).
        val first = select<ByteArray?> {
            dohAttempt.onAwait { it }
            directAttempt.onAwait { it }
        }
        if (isPlausibleBookFile(first)) {
            dohAttempt.cancel()
            directAttempt.cancel()
            return@coroutineScope first
        }
        val second = if (dohAttempt.isCompleted) directAttempt.await() else dohAttempt.await()
        second.takeIf { isPlausibleBookFile(it) }
    }

    private suspend fun downloadDirect(url: String, cookieHeader: Map<String, String>): ByteArray? =
        withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", MainActivity.MOBILE_UA)
            cookieHeader["Cookie"]?.let { requestBuilder.header("Cookie", it) }
            val response = client.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        }

    /**
     * Anti-bot/challenge/login pages come back as HTTP 200 with real bytes, so a plain
     * null/empty check isn't enough — sniff for an HTML error page masquerading as the book file
     * (same check used elsewhere in the app for forum attachment downloads).
     */
    private fun isPlausibleBookFile(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 64) return false
        val head = try {
            String(bytes.copyOfRange(0, minOf(200, bytes.size)), Charsets.UTF_8).lowercase()
        } catch (e: Exception) {
            return true // binary content (e.g. epub/zip) that isn't valid UTF-8 text is fine
        }
        return !head.contains("<!doctype html") && !head.contains("<html")
    }

    /** Feeds bytes into parser.html the same way parseOnMain() already does, then awaits addBook(). */
    private suspend fun feedToParserAndAwait(bytes: ByteArray, fileName: String): String? {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val deferred = CompletableDeferred<String>()
        parsedDeferred = deferred
        val js = """
            (function(){
                window._architectMode = true;
                window._parsedFiles = [{ fileBase64: ${JSONObject.quote(base64)}, fileName: ${JSONObject.quote(fileName)} }];
                if (window.AndroidBridge && AndroidBridge.logFromJs) {
                    AndroidBridge.logFromJs('ARCHITECT parseOnMain: fileName=${JSONObject.quote(fileName)}, base64Len=' + ${base64.length});
                }
                parseOnMain().then(function(){
                    if (window.AndroidBridge && AndroidBridge.logFromJs) {
                        AndroidBridge.logFromJs('ARCHITECT parsed currentBook: title=' + (currentBook && currentBook.title) + ' author=' + (currentBook && currentBook.author));
                    }
                    if (window.AndroidBridge && AndroidBridge.notifyBookParsed) {
                        AndroidBridge.notifyBookParsed(JSON.stringify(currentBook));
                    }
                }).catch(function(e){
                    if (window.AndroidBridge && AndroidBridge.notifyArchitectError) {
                        AndroidBridge.notifyArchitectError('parse: ' + (e && e.message ? e.message : e));
                    }
                });
            })();
        """.trimIndent()
        activity.parserCallback(js)
        return withTimeoutOrNull(60_000) { deferred.await() }
    }

    /** Reuses parser.html's searchOnForum() — same query derivation & scoreTopic() ranking as manual search. */
    private suspend fun runDuplicateCheck(): JSONArray {
        val deferred = CompletableDeferred<String>()
        duplicateDeferred = deferred
        activity.parserCallback("""
            (function(){
                if (window.AndroidBridge && AndroidBridge.logFromJs) {
                    AndroidBridge.logFromJs('ARCHITECT dup-check for: author=' + (currentBook && currentBook.author) + ' title=' + (currentBook && currentBook.title));
                }
                if (typeof searchOnForum === 'function') searchOnForum();
                else if (window.AndroidBridge && AndroidBridge.notifyArchitectError) AndroidBridge.notifyArchitectError('dupcheck: searchOnForum not found');
            })();
        """.trimIndent())
        val json = withTimeoutOrNull(30_000) { deferred.await() }
        if (json == null) AppLogger.e(TAG, "Duplicate check timed out")
        return try { JSONArray(json ?: "[]") } catch (e: Exception) { JSONArray() }
    }

    private fun report(json: JSONObject) {
        activity.architectCallback("window.onArchitectStep && window.onArchitectStep(${json});")
    }

    private fun reportError(message: String) {
        report(JSONObject().put("type", "error").put("message", message))
    }
}
