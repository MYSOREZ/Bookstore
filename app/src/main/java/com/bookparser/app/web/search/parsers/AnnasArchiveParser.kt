package com.bookparser.app.web.search.parsers

import com.bookparser.app.AppLogger
import com.bookparser.app.web.search.BookFormat
import com.bookparser.app.web.search.DohHttpClient
import com.bookparser.app.web.search.SearchOutcome
import com.bookparser.app.web.search.SearchResult
import com.bookparser.app.web.search.SiteParser
import org.jsoup.Jsoup

class AnnasArchiveParser : SiteParser {

    companion object {
        private const val TAG = "ANNAS"
        private val FALLBACK_DOMAINS = listOf(
            "annas-archive.gs",
            "annas-archive.se",
            "annas-archive.org",
            "annas-archive.gd"
        )
    }

    override suspend fun search(query: String, domain: String, doh: DohHttpClient): SearchOutcome {
        val cleanDomain = domain.removePrefix("https://").removePrefix("http://")
        val domainsToTry = mutableListOf(cleanDomain)
        FALLBACK_DOMAINS.forEach { if (!domainsToTry.contains(it)) domainsToTry.add(it) }

        var html: String? = null
        var successDomain = cleanDomain

        for (d in domainsToTry) {
            val url = "https://$d/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            AppLogger.i(TAG, "Trying: $url")

            val fetched = doh.fetchViaDoh(url)
            if (fetched != null && fetched.contains("Anna", ignoreCase = true)
                && !fetched.contains("Antibot solution", ignoreCase = true)) {
                html = fetched
                successDomain = d
                AppLogger.i(TAG, "Success from $d")
                break
            } else {
                AppLogger.i(TAG, "Domain $d returned invalid or antibot page")
            }
        }

        if (html == null) {
            return SearchOutcome.Failure("Anna's Archive недоступен (все зеркала не ответили)")
        }

        val doc = Jsoup.parse(html)
        val results = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()

        val cards = doc.select("a[href*=\"/md5/\"]")
        AppLogger.i(TAG, "Found ${cards.size} md5 links")

        val md5Regex = Regex("/md5/([a-f0-9]{32})")
        for (a in cards) {
            val href = a.attr("href")
            val md5Match = md5Regex.find(href) ?: continue
            val md5 = md5Match.groupValues[1]

            val bookUrl = "https://$successDomain/md5/$md5"

            val title = a.text().trim().split("\n")[0].trim()
            if (title.length < 2) continue
            if (seen.contains(bookUrl)) continue
            seen.add(bookUrl)

            val parent = a.parent()
            val siblingLinks = parent?.select("a[href*=\"/search?q=\"]") ?: emptyList()
            val author = if (siblingLinks.isNotEmpty()) siblingLinks[0].text().trim() else ""

            val fullText = parent?.text() ?: ""
            val formatMatch = Regex("\\b(pdf|epub|fb2|mobi|djvu|azw3|txt)\\b", RegexOption.IGNORE_CASE).find(fullText)
            val format = formatMatch?.groupValues?.get(1)?.uppercase() ?: ""

            val formats = if (format.isNotEmpty()) {
                listOf(BookFormat(format, bookUrl))
            } else {
                listOf(BookFormat("Открыть", bookUrl))
            }

            results.add(SearchResult(
                title = title,
                author = author,
                pageUrl = bookUrl,
                formats = formats
            ))

            if (results.size >= 30) break
        }

        AppLogger.i(TAG, "Parsed ${results.size} results")
        return if (results.isNotEmpty()) SearchOutcome.Success(results) else SearchOutcome.Empty
    }
}
