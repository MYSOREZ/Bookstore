package com.bookparser.app.web.search.parsers

import com.bookparser.app.AppLogger
import com.bookparser.app.web.search.BookFormat
import com.bookparser.app.web.search.DohHttpClient
import com.bookparser.app.web.search.SearchOutcome
import com.bookparser.app.web.search.SearchResult
import com.bookparser.app.web.search.SiteParser
import org.jsoup.Jsoup

class LibrainParser : SiteParser {

    companion object {
        private const val TAG = "LIBRAIN"
    }

    override suspend fun search(query: String, domain: String, doh: DohHttpClient): SearchOutcome {
        val cleanDomain = domain.removePrefix("https://").removePrefix("http://")
        val url = "https://$cleanDomain/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        AppLogger.i(TAG, "Search URL: $url")

        val html = doh.fetchViaDoh(url)
            ?: return SearchOutcome.Failure("Librain недоступен")

        val doc = Jsoup.parse(html)
        val results = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()

        val cards = doc.select(".search-results-list .card")
        AppLogger.i(TAG, "Found ${cards.size} cards")

        for (el in cards) {
            val titleLink = el.selectFirst("p.fw-bold a, a.fw-bold")
            if (titleLink == null) continue

            val title = titleLink.text().trim()
            val bookUrl = titleLink.attr("href")
            if (title.isEmpty() || bookUrl.isEmpty()) continue
            if (seen.contains(bookUrl)) continue
            seen.add(bookUrl)

            val authorEl = el.selectFirst("p.text-muted.small, .text-muted")
            val author = authorEl?.text()?.replace("bi-person", "")?.trim() ?: ""

            results.add(SearchResult(
                title = title,
                author = author,
                pageUrl = bookUrl,
                formats = listOf(BookFormat("Открыть", bookUrl))
            ))

            if (results.size >= 30) break
        }

        AppLogger.i(TAG, "Parsed ${results.size} results")
        return if (results.isNotEmpty()) SearchOutcome.Success(results) else SearchOutcome.Empty
    }
}
