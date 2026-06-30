package com.bookparser.app.web.search

data class BookFormat(
    val label: String,
    val url: String
)

data class SearchResult(
    val title: String,
    val author: String = "",
    val pageUrl: String,
    val formats: List<BookFormat> = emptyList()
)

sealed class SearchOutcome {
    data class Success(val items: List<SearchResult>) : SearchOutcome()
    object Empty : SearchOutcome()
    data class Failure(val reason: String) : SearchOutcome()
}
