package com.bookparser.app.web.search

import com.bookparser.app.AppLogger
import com.bookparser.app.web.search.parsers.AnnasArchiveParser
import com.bookparser.app.web.search.parsers.FlibustaParser
import com.bookparser.app.web.search.parsers.LibrainParser
import com.bookparser.app.web.search.parsers.ZLibraryParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class BookSearchManager(
    private val doh: DohHttpClient = DohHttpClient.INSTANCE
) {
    companion object {
        private const val TAG = "SEARCH_MGR"
    }

    private val parsers = mapOf(
        "flibusta" to FlibustaParser(),
        "annas" to AnnasArchiveParser(),
        "zlib" to ZLibraryParser(),
        "librain" to LibrainParser()
    )

    private val jobs = ConcurrentHashMap<String, Job>()

    fun search(
        siteId: String,
        query: String,
        domain: String,
        scope: CoroutineScope,
        onResult: (String, String) -> Unit,
        onError: (String, String) -> Unit
    ) {
        val parser = parsers[siteId]
        if (parser == null) {
            onError(siteId, "Неизвестный источник: $siteId")
            return
        }

        val job = scope.launch(Dispatchers.IO) {
            try {
                AppLogger.i(TAG, "[$siteId] Starting search on $domain")
                val startTime = System.currentTimeMillis()

                when (val outcome = parser.search(query, domain, doh)) {
                    is SearchOutcome.Success -> {
                        val elapsed = System.currentTimeMillis() - startTime
                        AppLogger.i(TAG, "[$siteId] Found ${outcome.items.size} results in ${elapsed}ms")
                        val json = serializeResults(outcome.items)
                        withContext(Dispatchers.Main) { onResult(siteId, json) }
                    }
                    is SearchOutcome.Empty -> {
                        val elapsed = System.currentTimeMillis() - startTime
                        AppLogger.i(TAG, "[$siteId] Empty results in ${elapsed}ms")
                        withContext(Dispatchers.Main) { onResult(siteId, "[]") }
                    }
                    is SearchOutcome.Failure -> {
                        AppLogger.e(TAG, "[$siteId] Failure: ${outcome.reason}")
                        withContext(Dispatchers.Main) { onError(siteId, outcome.reason) }
                    }
                }
            } catch (e: CancellationException) {
                AppLogger.i(TAG, "[$siteId] Search cancelled")
            } catch (e: Exception) {
                AppLogger.e(TAG, "[$siteId] Exception: ${e.message}")
                withContext(Dispatchers.Main) {
                    onError(siteId, e.message ?: "Неизвестная ошибка")
                }
            }
        }
        jobs[siteId] = job
    }

    fun stopAll() {
        AppLogger.i(TAG, "Stopping all searches (${jobs.size} active)")
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun serializeResults(items: List<SearchResult>): String {
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("title", item.title)
                put("author", item.author)
                put("pageUrl", item.pageUrl)
                put("formats", JSONArray().apply {
                    item.formats.forEach { fmt ->
                        put(JSONObject().apply {
                            put("label", fmt.label)
                            put("url", fmt.url)
                        })
                    }
                })
            }
            arr.put(obj)
        }
        return arr.toString()
    }
}
