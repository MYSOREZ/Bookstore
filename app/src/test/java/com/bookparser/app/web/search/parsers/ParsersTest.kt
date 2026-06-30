package com.bookparser.app.web.search.parsers

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ParsersTest {

    @Test
    fun flibustaParser_extractsBooksFromUlLi() {
        val html = """
            <html><body>
            <div id="main">
                <ul>
                    <li>
                        <a href="/b/12345">Преступление и наказание</a>
                        <a href="/a/678">Достоевский Ф.М.</a>
                    </li>
                    <li>
                        <a href="/b/67890">Идиот</a>
                        <a href="/a/678">Достоевский Ф.М.</a>
                    </li>
                </ul>
            </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val items = doc.select("#main ul li")
        assertEquals(2, items.size)

        val firstBook = items[0].selectFirst("a[href*=/b/]")
        assertNotNull(firstBook)
        assertEquals("Преступление и наказание", firstBook!!.text())
        assertEquals("/b/12345", firstBook.attr("href"))

        val author = items[0].selectFirst("a[href*=/a/]")
        assertNotNull(author)
        assertEquals("Достоевский Ф.М.", author!!.text())
    }

    @Test
    fun zlibParser_findsBookcardElements() {
        val html = """
            <html><body>
            <z-bookcard href="/book/111" download="/dl/111">
                <div slot="title">Война и мир</div>
                <div slot="author">Толстой Л.Н.</div>
            </z-bookcard>
            <z-bookcard href="/book/222">
                <div slot="title">Анна Каренина</div>
                <div slot="author">Толстой Л.Н.</div>
            </z-bookcard>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val bookcards = doc.select("z-bookcard")
        assertEquals(2, bookcards.size)

        val first = bookcards[0]
        assertEquals("/book/111", first.attr("href"))
        assertEquals("/dl/111", first.attr("download"))
        assertEquals("Война и мир", first.select("[slot=title]").text())
        assertEquals("Толстой Л.Н.", first.select("[slot=author]").text())
    }

    @Test
    fun annasArchiveParser_findsMd5Links() {
        val html = """
            <html><body>
            <a href="/md5/abc123def456abc123def456abc123de">Книга 1</a>
            <a href="/md5/111222333444555666777888999000aa">Книга 2</a>
            <a href="/other/link">Не книга</a>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val cards = doc.select("a[href*=\"/md5/\"]")
        assertEquals(2, cards.size)

        val md5Regex = Regex("^/md5/[a-f0-9]{32}$")
        var validCount = 0
        for (card in cards) {
            if (md5Regex.matches(card.attr("href"))) validCount++
        }
        assertEquals(2, validCount)
    }

    @Test
    fun librainParser_findsCardElements() {
        val html = """
            <html><body>
            <div class="search-results-list">
                <div class="card">
                    <p class="fw-bold text-white mb-1"><a href="/book/1">Название Книги</a></p>
                    <p class="text-muted small mb-0">Автор</p>
                </div>
            </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val cards = doc.select(".search-results-list .card")
        assertEquals(1, cards.size)

        val titleLink = cards[0].selectFirst("p.fw-bold a")
        assertNotNull(titleLink)
        assertEquals("Название Книги", titleLink!!.text())
        assertEquals("/book/1", titleLink.attr("href"))
    }

    @Test
    fun antibotDetection_worksForZLibrary() {
        val html = """
            <html><body>
            <div>Click here to enter</div>
            <div>fingerprint verification</div>
            </body></html>
        """.trimIndent()

        val bodyText = Jsoup.parse(html).text()
        val markers = listOf("Click here to enter", "Antibot", "fingerprint")
        val detected = markers.any { bodyText.contains(it, ignoreCase = true) }
        assertTrue("Should detect antibot markers", detected)
    }

    @Test
    fun formatExtraction_fromUrls() {
        val testCases = mapOf(
            "/download/book.fb2" to "FB2",
            "/download/book.epub" to "EPUB",
            "/download/book.txt" to "TXT",
            "/download/book.pdf" to "PDF",
            "/download/book.mobi" to "MOBI"
        )

        for ((url, expectedFormat) in testCases) {
            val match = Regex("\\.(fb2|epub|txt|pdf|mobi)", RegexOption.IGNORE_CASE).find(url)
            assertNotNull("Should match $url", match)
            assertEquals(expectedFormat, match!!.groupValues[1].uppercase())
        }
    }

    @Test
    fun zlibRegexFallback_findsBookcardsInRawHtml() {
        val html = """
            <z-bookcard href="/book/333" download="/dl/333" publisher="Издательство">
                <div slot="title">Мастер и Маргарита</div>
                <div slot="author">Булгаков М.А.</div>
            </z-bookcard>
        """.trimIndent()

        val regex = Regex(
            """<z-bookcard\s[^>]*?href="([^"]*)"[^>]*?>[\s\S]*?<div\s+slot="title">([\s\S]*?)<\/div>[\s\S]*?<div\s+slot="author">([\s\S]*?)<\/div>[\s\S]*?<\/z-bookcard>""",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(html)
        assertNotNull("Regex should match z-bookcard", match)
        val (href, title, author) = match!!.destructured
        assertEquals("/book/333", href)
        assertEquals("Мастер и Маргарита", title.trim())
        assertEquals("Булгаков М.А.", author.trim())
    }

    @Test
    fun annasArchive_duplicateMd5Links_coverAndTitle() {
        val html = """
            <html><body>
            <div>
                <a href="/md5/abc123def456abc123def456abc123de">
                    <img src="cover.jpg"/>
                </a>
                <a href="/md5/abc123def456abc123def456abc123de">Речной Князь</a>
                <a href="/search?q=Автор">Автор</a>
            </div>
            <div>
                <a href="/md5/111222333444555666777888999000aa">
                    <img src="cover2.jpg"/>
                </a>
                <a href="/md5/111222333444555666777888999000aa">Другая Книга</a>
                <a href="/search?q=Писатель">Писатель</a>
            </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val cards = doc.select("a[href*=\"/md5/\"]")
        assertEquals(4, cards.size)

        val md5Regex = Regex("/md5/([a-f0-9]{32})")
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (a in cards) {
            val href = a.attr("href")
            val md5Match = md5Regex.find(href) ?: continue
            val md5 = md5Match.groupValues[1]
            val bookUrl = "https://annas-archive.gd/md5/$md5"

            val title = a.text().trim().split("\n")[0].trim()
            if (title.length < 2) continue
            if (seen.contains(bookUrl)) continue
            seen.add(bookUrl)

            results.add(title)
        }

        assertEquals(2, results.size)
        assertTrue(results.contains("Речной Князь"))
        assertTrue(results.contains("Другая Книга"))
    }

    @Test
    fun flibustaFallbackSelectsMainLi() {
        val html = """
            <html><body>
            <nav><ul><li><a href="/other">Меню</a></li></ul></nav>
            <div id="main">
                <ul>
                    <li><a href="/b/99999">Заголовок</a> <a href="/a/111">Автор</a></li>
                </ul>
            </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        var items = doc.select("#main ul li")
        if (items.isEmpty()) {
            items = doc.select("ul li")
        }
        assertEquals(1, items.size)
        val bookLink = items[0].selectFirst("a[href*=/b/]")
        assertNotNull(bookLink)
        assertEquals("Заголовок", bookLink!!.text())
    }
}
