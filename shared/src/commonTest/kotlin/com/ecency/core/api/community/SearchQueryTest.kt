package com.ecency.core.api.community

import com.ecency.core.api.search.PostUrlParser
import com.ecency.core.api.search.SearchDate
import com.ecency.core.api.search.SearchFilters
import com.ecency.core.api.search.SearchQueries
import com.ecency.core.api.search.SearchQueryError
import com.ecency.core.api.search.SearchSort
import com.ecency.core.api.search.SearchType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The search query builder, parser and validator, and RN's post URL parser, against their JS originals. */
class SearchQueryTest {
    @Test
    fun buildMatchesTheSdk() {
        CommunityGoldenVectors.builds.forEach { v ->
            val built = SearchQueries.build(v.search, v.author, SearchType.of(v.type)!!, v.category, v.tags)
            assertEquals(v.q, built.q, v.q)
            assertEquals(v.outSearch, built.search, v.q)
            assertEquals(v.outAuthor, built.author, v.q)
            assertEquals(v.outCategory, built.category, v.q)
            assertEquals(v.outTags, built.tags, v.q)
            val error = SearchQueries.validate(built.q)
            assertEquals(v.error, error?.name?.lowercase().orEmpty(), v.q)
        }
    }

    @Test
    fun parseMatchesTheSdk() {
        CommunityGoldenVectors.parses.forEach { v ->
            val p = SearchQueries.parse(v.q)
            assertEquals(v.search, p.search, v.q)
            assertEquals(v.author, p.author, v.q)
            assertEquals(v.type, p.type.value, v.q)
            assertEquals(v.category, p.category, v.q)
            assertEquals(v.tags, p.tags, v.q)
        }
    }

    @Test
    fun postUrlParserMatchesRn() {
        CommunityGoldenVectors.urls.forEach { v ->
            val r = PostUrlParser.parse(v.input)
            assertEquals(v.author, r?.author, v.input)
            assertEquals(v.permlink, r?.permlink, v.input)
            assertEquals(v.feedType, r?.feedType, v.input)
        }
    }

    @Test
    fun communityNamesMatchTheSdk() {
        CommunityGoldenVectors.communityNames.forEach { (name, expected) -> assertEquals(expected, CommunityNames.isCommunity(name), name) }
    }

    @Test
    fun rnCommunityAccountRule() {
        // RN communityValidation: unanchored start, hive-1xxxx..hive-3xxxxxx
        listOf("hive-125125", "xhive-125125", "hive-3999999").forEach { assertEquals(true, CommunityNames.looksLikeCommunityAccount(it), it) }
        listOf("hive-1", "hive-412345", "ecency", "hive-125125a").forEach { assertEquals(false, CommunityNames.looksLikeCommunityAccount(it), it) }
    }

    @Test
    fun filtersDefaultsAndActiveCount() {
        val empty = SearchFilters.EMPTY
        assertEquals(SearchType.POST, empty.type) // the posts tab searches posts only until changed
        assertEquals(0, empty.activeCount)
        assertEquals(2, empty.copy(type = SearchType.ALL, sort = SearchSort.NEWEST).activeCount)
        // a type change alone keeps the query valid only with search text
        assertEquals(SearchQueryError.NEEDS_CRITERIA, SearchQueries.validate(SearchQueries.build("", empty.copy(date = SearchDate.WEEK)).q))
        assertNull(SearchQueries.validate(SearchQueries.build("", empty.copy(author = "alice")).q))
    }

    @Test
    fun sinceIsAnAbsoluteUtcTimestampWithoutMillis() {
        val now = 1_791_288_000_123L // 2026-10-06T12:00:00.123Z
        assertNull(SearchQueries.since(SearchDate.ALL, now))
        assertEquals("2026-09-29T12:00:00", SearchQueries.since(SearchDate.WEEK, now))
        assertEquals("2026-09-06T12:00:00", SearchQueries.since(SearchDate.MONTH, now))
        assertEquals("2025-10-06T12:00:00", SearchQueries.since(SearchDate.YEAR, now))
        assertEquals("2024-02-29T23:59:59", SearchQueries.isoSeconds(1_709_251_199_999L))
        assertEquals("1970-01-01T00:00:00", SearchQueries.isoSeconds(0))
    }

    @Test
    fun clipDropsOnePrefixAndLowercases() {
        assertEquals("alice", SearchQueries.clip("@Alice "))
        assertEquals("@alice", SearchQueries.clip("  @Alice ")) // RN checks the prefix before trimming
        assertEquals("travel", SearchQueries.clip("#Travel"))
        assertEquals("hello world", SearchQueries.clip(" Hello World "))
    }
}
