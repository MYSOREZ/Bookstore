package com.bookparser.app.web.search.parsers

import com.bookparser.app.AppLogger
import com.bookparser.app.web.search.BookFormat
import com.bookparser.app.web.search.DohHttpClient
import com.bookparser.app.web.search.SearchOutcome
import com.bookparser.app.web.search.SearchResult
import com.bookparser.app.web.search.SiteParser
import org.jsoup.Jsoup

class FlibustaParser : SiteParser {

    companion object {
        private const val TAG = "FLIBUSTA"
    }

    override suspend fun search(query: String, domain: String, doh: DohHttpClient): SearchOutcome {
        val cleanDomain = domain.removePrefix("https://").removePrefix("http://")
        val scheme = if (domain.startsWith("https")) "https" else "http"
        val url = "$scheme://$cleanDomain/booksearch?ask=${java.net.URLEncoder.encode(query, "UTF-8")}&chb=1"
        AppLogger.i(TAG, "Search URL: $url")

        val html = doh.fetchViaDoh(url)
            ?: return SearchOutcome.Failure("Сайт недоступен")

        val doc = Jsoup.parse(html)
        val results = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()

        var items = doc.select("#main ul li")
        if (items.isEmpty()) {
            items = doc.select("ul li")
        }
        AppLogger.i(TAG, "Found ${items.size} <li> elements")

        for (li in items) {
            val bookLink = li.selectFirst("a[href*=/b/]")
            if (bookLink == null) continue

            val href = bookLink.attr("href")
            val bookIdMatch = Regex("/b/(\\d+)").find(href) ?: continue
            val bookId = bookIdMatch.groupValues[1]
            val base = "$domain/b/$bookId"

            if (seen.contains(base)) continue
            seen.add(base)

            val title = bookLink.text().trim()
            if (title.isEmpty()) continue

            val authorLink = li.selectFirst("a[href*=/a/]")
            val author = authorLink?.text()?.trim() ?: ""

            val formats = listOf(
                BookFormat("FB2", "$base/fb2"),
                BookFormat("EPUB", "$base/epub")
            )

            results.add(SearchResult(
                title = title,
                author = author,
                pageUrl = base,
                formats = formats
            ))

            if (results.size >= 30) break
        }

        AppLogger.i(TAG, "Parsed ${results.size} results")
        return if (results.isNotEmpty()) SearchOutcome.Success(results) else SearchOutcome.Empty
    }
}
