package com.ecency.core.api

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.FakeAuthHttp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** SDK getSimilarEntriesQueryOptions' filter: not the post itself, no nsfw tag, one per author, at most 4. */
class SimilarEntriesTest {
    private fun result(author: String, permlink: String, vararg tags: String) =
        """{"author":"$author","permlink":"$permlink","title":"T $permlink","tags":[${tags.joinToString(",") { "\"$it\"" }}],"created_at":"2026-11-01T00:00:00"}"""

    @Test
    fun theNsfwTagInAnyCaseOrSpacingLeavesAnEntryOut() = runTest {
        val rows = listOf(
            result("alice", "self", "photo"),
            result("bob", "upper", "NSFW"),
            result("carol", "spaced", " nsfw "),
            result("dave", "plain", "nsfw"),
            result("erin", "one", "photo"),
            result("erin", "two", "photo"),
            result("frank", "art", "nsfwart"),
        )
        val http = FakeAuthHttp { HttpResult(200, """{"results":[${rows.joinToString(",")}]}""") }
        val found = EcencyRestApi(http).similarEntries("alice", "self", "t", "b", listOf("photo"), "2026-01-01T00:00:00")
        // the post itself, every nsfw-tagged entry and a second one of erin's are left out; a tag that only contains
        // the word stays
        assertEquals(listOf("erin/one", "frank/art"), found.map { "${it.author}/${it.permlink}" })
    }
}
