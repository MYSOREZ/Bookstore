package com.bookparser.app.web.search.parsers

import com.bookparser.app.AppLogger
import com.bookparser.app.web.search.BookFormat
import com.bookparser.app.web.search.DohHttpClient
import com.bookparser.app.web.search.SearchOutcome
import com.bookparser.app.web.search.SearchResult
import com.bookparser.app.web.search.SiteParser
import com.bookparser.app.web.search.WebViewSearcher
import org.jsoup.Jsoup

class ZLibraryParser : SiteParser {

    companion object {
        private const val TAG = "ZLIB"
        private val FALLBACK_DOMAINS = listOf(
            "ru.z-lib.fm",
            "z-lib.fm",
            "z-lib.org",
            "z-library.sk",
            "z-lib.is"
        )
    }

    override suspend fun search(query: String, domain: String, doh: DohHttpClient): SearchOutcome {
        val cleanDomain = domain.removePrefix("https://").removePrefix("http://")
        val domainsToTry = mutableListOf(cleanDomain)
        FALLBACK_DOMAINS.forEach { if (!domainsToTry.contains(it)) domainsToTry.add(it) }

        val wv = WebViewSearcher.get()

        for (d in domainsToTry) {
            val searchUrl = "https://$d/s/${java.net.URLEncoder.encode(query, "UTF-8")}"
            AppLogger.i(TAG, "WebView search URL: $searchUrl")

            val html = wv.fetchHtml(searchUrl, waitForStableMs = 6000, timeoutMs = 30000)
            if (html == null) {
                AppLogger.i(TAG, "Domain $d returned null, trying next")
                continue
            }

            AppLogger.i(TAG, "HTML length from $d: ${html.length}")

            if (html.length < 2000) {
                AppLogger.i(TAG, "HTML too small (${html.length}), trying next")
                continue
            }

            val results = parseHtml(html, d)
            if (results.isNotEmpty()) {
                return SearchOutcome.Success(results)
            }
        }

        return SearchOutcome.Empty
    }

    private fun cleanHref(raw: String): String {
        return raw.replace("\\\"", "").replace("\"", "").trim()
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace("+", " ").replace("%20", " ").trim()
    }

    private fun parseHtml(html: String, usedDomain: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()
        val doc = Jsoup.parse(html)

        val bookcards = doc.select("z-bookcard")
        AppLogger.i(TAG, "Found ${bookcards.size} z-bookcard elements")

        for (el in bookcards) {
            val href = cleanHref(el.attr("href"))
            if (href.isEmpty()) continue

            val fullUrl = if (href.startsWith("http")) href else "https://$usedDomain$href"
            if (seen.contains(fullUrl)) continue
            seen.add(fullUrl)

            val title = cleanTitle(el.select("[slot=title]").text())
                .ifEmpty { cleanTitle(el.select("div").first()?.text() ?: "") }
            if (title.length < 2) continue

            val author = el.select("[slot=author]").text().trim()
                .replace(";", ",")

            val downloadPath = cleanHref(el.attr("download"))
            val formats = mutableListOf<BookFormat>()
            if (downloadPath.isNotEmpty()) {
                val dlUrl = if (downloadPath.startsWith("http")) downloadPath else "https://$usedDomain$downloadPath"
                formats.add(BookFormat("Скачать", dlUrl))
            }
            formats.add(BookFormat("Страница", fullUrl))

            results.add(SearchResult(
                title = title,
                author = author,
                pageUrl = fullUrl,
                formats = formats
            ))

            if (results.size >= 30) break
        }

        if (results.isEmpty()) {
            val regex = Regex(
                """<z-bookcard\s[^>]*?href="([^"]*)"[^>]*?>[\s\S]*?<div\s+slot="title">([\s\S]*?)<\/div>[\s\S]*?<div\s+slot="author">([\s\S]*?)<\/div>[\s\S]*?<\/z-bookcard>""",
                RegexOption.IGNORE_CASE
            )
            for (match in regex.findAll(html)) {
                val (href, title, author) = match.destructured
                val cleanTitle = cleanTitle(title)
                if (cleanTitle.length < 2) continue

                val cleanHref = cleanHref(href)
                val fullUrl = if (cleanHref.startsWith("http")) cleanHref else "https://$usedDomain$cleanHref"
                if (seen.contains(fullUrl)) continue
                seen.add(fullUrl)

                val formats = mutableListOf<BookFormat>()
                formats.add(BookFormat("Страница", fullUrl))

                results.add(SearchResult(
                    title = cleanTitle,
                    author = author.trim().replace(";", ","),
                    pageUrl = fullUrl,
                    formats = formats
                ))

                if (results.size >= 30) break
            }
        }

        if (results.isEmpty()) {
            val cards = doc.select(".book-card, .book-item, article, a[href*=/book/]")
            for (el in cards) {
                val a = el.selectFirst("a[href]") ?: continue
                val href = cleanHref(a.attr("href"))
                if (href.isEmpty()) continue
                val fullUrl = if (href.startsWith("http")) href else "https://$usedDomain$href"
                if (seen.contains(fullUrl)) continue
                seen.add(fullUrl)

                val title = (el.selectFirst("h3, h2, [class*=title]") ?: a).text().trim()
                if (title.length < 2) continue

                val authorEl = el.selectFirst(".authors, .author, [class*=author]")
                val author = authorEl?.text()?.trim() ?: ""

                results.add(SearchResult(
                    title = title,
                    author = author,
                    pageUrl = fullUrl,
                    formats = listOf(BookFormat("Открыть", fullUrl))
                ))

                if (results.size >= 30) break
            }
        }

        return results
    }
}
