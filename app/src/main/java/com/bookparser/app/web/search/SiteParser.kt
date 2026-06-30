package com.bookparser.app.web.search

interface SiteParser {
    suspend fun search(query: String, domain: String, doh: DohHttpClient): SearchOutcome
}
