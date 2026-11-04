package com.ecency.core.api.search

import com.ecency.core.l10n.L
import com.ecency.core.render.js.Js

/** SDK `SearchType`: the `type:` token. */
enum class SearchType(val value: String) {
    ALL(""),
    POST("post"),
    COMMENT("comment"),
    ;

    companion object {
        fun of(value: String): SearchType? = entries.firstOrNull { it.value == value }
    }
}

/** RN searchFilters SearchDateOption: the window the API gets as an absolute `since`. */
enum class SearchDate(val value: String, val days: Int, private val labelText: () -> String) {
    ALL("all", 0, { L.searchResult.filters.dateAll }),
    WEEK("week", 7, { L.searchResult.filters.dateWeek }),
    MONTH("month", 30, { L.searchResult.filters.dateMonth }),
    YEAR("year", 365, { L.searchResult.filters.dateYear }),
    ;

    val label: String get() = labelText()
}

/** RN searchFilters SearchSortOption (the API's `sort`). */
enum class SearchSort(val value: String, private val labelText: () -> String) {
    RELEVANCE("relevance", { L.searchResult.postResultFilter.relevance }),
    POPULARITY("popularity", { L.searchResult.postResultFilter.popularity }),
    NEWEST("newest", { L.searchResult.postResultFilter.newest }),
    ;

    val label: String get() = labelText()
}

/** The posts tab's filters (RN `SearchFilters`); [EMPTY] is RN's EMPTY_SEARCH_FILTERS (type Posts, not All). */
data class SearchFilters(
    val author: String = "",
    val category: String = "",
    /** Raw user input ("a, b #c"). */
    val tags: String = "",
    val type: SearchType = SearchType.POST,
    val date: SearchDate = SearchDate.ALL,
    val sort: SearchSort = SearchSort.RELEVANCE,
) {
    /** RN activeSearchFilterCount: fields that differ from [EMPTY]. */
    val activeCount: Int
        get() = listOf(author != EMPTY.author, category != EMPTY.category, tags != EMPTY.tags, type != EMPTY.type, date != EMPTY.date, sort != EMPTY.sort)
            .count { it }

    val hasActive: Boolean get() = activeCount > 0

    companion object {
        val EMPTY = SearchFilters()
    }
}

/** SDK `BuiltSearchQuery`: the `q` the API parses back into filters, plus the normalized parts. */
data class BuiltSearchQuery(
    val q: String,
    val search: String,
    val author: String,
    val type: SearchType,
    val category: String,
    val tags: List<String>,
)

/** RN validateSearchQuery's verdict, with RN's en-US copy. */
enum class SearchQueryError(private val messageText: () -> String) {
    NEEDS_CRITERIA({ L.searchResult.filters.needsCriteria }),
    TOO_MANY_TAGS({ L.native.plurals.searchResult.filters.tooManyTags(n = SearchQueries.MAX_SEARCH_TAGS) }),
    TOO_LONG({ L.native.plurals.searchResult.filters.tooLong(n = SearchQueries.MAX_SEARCH_QUERY_LENGTH) }),
    ;

    val message: String get() = messageText()
}

/** SDK `SearchQuery`: the filters read back out of a `q` string. */
data class ParsedSearchQuery(val search: String, val author: String, val type: SearchType, val category: String, val tags: List<String>)

/**
 * Port of @ecency/sdk `modules/search/query-builder.ts` (buildSearchQuery, SearchQuery, the normalizers) and RN's
 * `validateSearchQuery`, which mirrors the search API's own query validator. Golden vectors: `CommunityGoldenVectors`.
 */
object SearchQueries {
    const val MAX_SEARCH_TAGS = 5
    const val MAX_SEARCH_QUERY_LENGTH = 100

    // A token only counts at the start or after whitespace (JS `\s`), as the API's parser.
    private val AUTHOR = token("author")
    private val TYPE = token("type")
    private val CATEGORY = token("category")
    private val TAG = token("tag")

    private fun token(name: String) = Regex("(^|[$JS_SPACE])$name:([^$JS_SPACE]+)")

    private fun firstToken(value: String): String = Js.splitSpaces(Js.trim(value)).firstOrNull().orEmpty()

    /** SDK normalizeSearchAuthor. */
    fun normalizeAuthor(value: String): String = Js.toLowerCase(firstToken(value).trimStart('@'))

    /** SDK normalizeSearchCategory. */
    fun normalizeCategory(value: String): String = Js.toLowerCase(firstToken(value).trimStart('#'))

    /** SDK normalizeSearchTags: split on whitespace and commas, leading # dropped, lowercased, deduped in order. */
    fun normalizeTags(value: String): List<String> {
        val seen = LinkedHashSet<String>()
        value.split(Regex("[$JS_SPACE,]+")).forEach { raw ->
            val tag = Js.toLowerCase(raw.trimStart('#'))
            if (tag.isNotEmpty()) seen += tag
        }
        return seen.toList()
    }

    /** SDK buildSearchQuery. */
    fun build(search: String = "", author: String = "", type: SearchType = SearchType.ALL, category: String = "", tags: String = ""): BuiltSearchQuery {
        val s = Js.collapseSpaces(Js.trim(search))
        val a = normalizeAuthor(author)
        val c = normalizeCategory(category)
        val t = normalizeTags(tags)
        val parts = mutableListOf(s)
        if (a.isNotEmpty()) parts += "author:$a"
        if (type != SearchType.ALL) parts += "type:${type.value}"
        if (c.isNotEmpty()) parts += "category:$c"
        if (t.isNotEmpty()) parts += "tag:${t.joinToString(",")}"
        return BuiltSearchQuery(parts.filter { it.isNotEmpty() }.joinToString(" "), s, a, type, c, t)
    }

    /** buildSearchQuery from the search bar text and the posts tab filters (RN postsResultsContainer). */
    fun build(search: String, filters: SearchFilters): BuiltSearchQuery =
        build(search, filters.author, filters.type, filters.category, filters.tags)

    /** SDK `new SearchQuery(q)`. */
    fun parse(q: String): ParsedSearchQuery {
        fun first(re: Regex) = re.find(q)?.groupValues?.get(2)?.let(Js::trim).orEmpty()
        val type = SearchType.of(first(TYPE)) ?: SearchType.ALL
        val seen = LinkedHashSet<String>()
        TAG.findAll(q).forEach { m -> m.groupValues[2].split(',').map(Js::trim).filter { it.isNotEmpty() }.forEach { seen += it } }
        var search = q
        listOf(AUTHOR, TYPE, CATEGORY, TAG).forEach { re -> search = re.replace(search) { it.groupValues[1] } }
        while (search.contains("  ")) search = search.replaceFirst("  ", " ")
        return ParsedSearchQuery(Js.trim(search), first(AUTHOR), type, first(CATEGORY), seen.toList())
    }

    /** RN validateSearchQuery for the exact [q] being sent; null when the API would take it. */
    fun validate(q: String): SearchQueryError? {
        val p = parse(q)
        return when {
            p.search.isEmpty() && p.author.isEmpty() && p.category.isEmpty() && p.tags.isEmpty() -> SearchQueryError.NEEDS_CRITERIA
            p.tags.size > MAX_SEARCH_TAGS -> SearchQueryError.TOO_MANY_TAGS
            q.length > MAX_SEARCH_QUERY_LENGTH -> SearchQueryError.TOO_LONG
            else -> null
        }
    }

    /** RN sinceFor: the window start as "YYYY-MM-DDTHH:MM:SS" (UTC, no millis), null for all time. */
    fun since(date: SearchDate, nowMillis: Long): String? {
        if (date.days == 0) return null
        return isoSeconds(nowMillis - date.days * 86_400_000L)
    }

    internal fun isoSeconds(millis: Long): String {
        val totalSeconds = millis.floorDiv(1000L)
        val days = totalSeconds.floorDiv(86_400L)
        val secs = totalSeconds - days * 86_400L
        // civil from days (Howard Hinnant)
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2L) 1L else 0L)
        fun p2(v: Long) = v.toString().padStart(2, '0')
        return "${y.toString().padStart(4, '0')}-${p2(m)}-${p2(d)}T${p2(secs / 3600)}:${p2(secs % 3600 / 60)}:${p2(secs % 60)}"
    }

    /** RN clipSearchValue: a leading @ or # is a prefix for the people and topics tabs, not part of the term. */
    fun clip(value: String): String =
        Js.toLowerCase(Js.trim(if (value.startsWith("#") || value.startsWith("@")) value.substring(1) else value))

    /** JS `\s` as a character class body. */
    private const val JS_SPACE = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
}
