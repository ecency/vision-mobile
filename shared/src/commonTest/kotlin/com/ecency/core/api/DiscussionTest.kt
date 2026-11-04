package com.ecency.core.api

import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.CommentSort
import com.ecency.core.api.repository.Discussion
import com.ecency.core.api.repository.PostRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscussionTest {
    private val root = "alice" to "post"

    /** A synthetic thread: three top-level comments, one bot comment, a reply chain four deep. */
    private fun discussionJson(pinnedReply: String? = null, extra: List<String> = emptyList()): String {
        val rootMeta = pinnedReply?.let { """{"tags":["test"],"pinned_reply":"$it"}""" } ?: """{"tags":["test"]}"""
        val entries = listOf(
            entry("alice", "post", metadata = rootMeta, replies = listOf("bob/c1", "dave/c2", "carol/c3", "botty/bot"), children = 8),
            entry("bob", "c1", created = "2026-10-01T01:00:00", parent = root, pending = "0.500 HBD", reputation = 70.0, replies = listOf("erin/r1", "erin/r1b")),
            entry("dave", "c2", created = "2026-10-01T02:00:00", parent = root, pending = "2.000 HBD", reputation = 40.0, netRshares = -5, votes = listOf("x", "y", "z")),
            entry("carol", "c3", created = "2026-10-01T03:00:00", parent = root, pending = "0.100 HBD", reputation = 60.0, votes = listOf("x", "y")),
            entry("botty", "bot", created = "2026-10-01T04:00:00", parent = root),
            entry("erin", "r1", created = "2026-10-01T05:00:00", parent = "bob" to "c1", depth = 2, replies = listOf("frank/r2")),
            entry("frank", "r2", created = "2026-10-01T06:00:00", parent = "erin" to "r1", depth = 3, replies = listOf("gina/r3")),
            entry("gina", "r3", created = "2026-10-01T07:00:00", parent = "frank" to "r2", depth = 4),
            entry("erin", "r1b", created = "2026-10-01T08:00:00", parent = "bob" to "c1", depth = 2),
        ) + extra
        // The bridge returns an object keyed "author/permlink".
        return entries.joinToString(",", "{", "}") { e ->
            val author = Regex(""""author":"([^"]+)"""").find(e)!!.groupValues[1]
            val permlink = Regex(""""permlink":"([^"]+)"""").find(e)!!.groupValues[1]
            "\"$author/$permlink\":$e"
        }
    }

    private fun repoWith(vararg bodies: String): Pair<PostRepository, FakeTransport> {
        val queue = ArrayDeque(bodies.toList())
        val transport = FakeTransport { FakeTransport.result(it, if (queue.size > 1) queue.removeFirst() else queue.first()) }
        return PostRepository(HiveApi(clientWith(transport))) to transport
    }

    private fun Discussion.order() = threads.map { it.comment.permlink }

    @Test
    fun requestUsesTheDefaultObserverWhenLoggedOut() = runTest {
        val (repo, transport) = repoWith(discussionJson())
        repo.getDiscussion("alice", "post")
        assertEquals("bridge.get_discussion", transport.sent.single().method)
        assertEquals("""{"author":"alice","permlink":"post","observer":"ecency"}""", transport.sent.single().params.toString())
        repo.getDiscussion("alice", "post", observer = "bob")
        assertEquals("""{"author":"alice","permlink":"post","observer":"bob"}""", transport.sent.last().params.toString())
    }

    @Test
    fun topLevelOrderPerSort() = runTest {
        val (repo, transport) = repoWith(discussionJson())
        val bots = setOf("botty")
        // Downvoted c2 goes last despite the highest payout.
        assertEquals(listOf("c1", "c3", "c2"), repo.getDiscussion("alice", "post", sort = CommentSort.TRENDING, botAuthors = bots).order())
        assertEquals(listOf("c3", "c1", "c2"), repo.getDiscussion("alice", "post", sort = CommentSort.AGE, botAuthors = bots).order())
        assertEquals(listOf("c2", "c3", "c1"), repo.getDiscussion("alice", "post", sort = CommentSort.VOTES, botAuthors = bots).order())
        assertEquals(listOf("c1", "c3", "c2"), repo.getDiscussion("alice", "post", sort = CommentSort.REPUTATION, botAuthors = bots).order())
        assertEquals(1, transport.sent.size) // re-sorting is served from the cache
    }

    @Test
    fun botCommentsAreSeparated() = runTest {
        val (repo, _) = repoWith(discussionJson())
        val d = repo.getDiscussion("alice", "post", botAuthors = setOf("botty"))
        assertEquals(listOf("bot"), d.botThreads.map { it.comment.permlink })
        assertFalse("bot" in d.order())
        assertEquals(listOf("c1", "c3", "bot", "c2"), repo.getDiscussion("alice", "post").order())
    }

    @Test
    fun pinnedReplyComesFirst() = runTest {
        val (repo, _) = repoWith(discussionJson(pinnedReply = "dave/c2"))
        assertEquals(listOf("c2", "c1", "c3"), repo.getDiscussion("alice", "post", botAuthors = setOf("botty")).order())
    }

    @Test
    fun repliesAreFlattenedDepthFirstAndCutAtLevelThree() = runTest {
        val (repo, _) = repoWith(discussionJson())
        val d = repo.getDiscussion("alice", "post")
        val thread = d.threads.first { it.comment.permlink == "c1" }
        assertEquals(
            listOf("r1" to 2, "r2" to 3, "r1b" to 2),
            thread.replies.map { it.comment.permlink to it.level },
        )
        assertEquals(listOf(false, true, false), thread.replies.map { it.hasHiddenReplies })
        assertEquals("post", d.root?.permlink)
        assertEquals(9, d.entries.size)

        val deeper = d.threadFrom(PostKey("frank", "r2"))!!
        assertEquals(listOf("r3" to 2), deeper.replies.map { it.comment.permlink to it.level })
        assertNull(d.threadFrom(PostKey("nobody", "x")))
    }

    @Test
    fun unknownPostGivesAnEmptyDiscussion() = runTest {
        val transport = FakeTransport { FakeTransport.error(it, -32602, "Assert Exception:Post alice/none does not exist") }
        val d = PostRepository(HiveApi(clientWith(transport))).getDiscussion("alice", "none")
        assertNull(d.root)
        assertTrue(d.threads.isEmpty())
    }

    @Test
    fun localCommentShowsUntilTheBridgeReturnsIt() = runTest {
        val mine = """{"author":"me","permlink":"re-c1","parent_author":"bob","parent_permlink":"c1","body":"hi","created":"2026-10-01T09:00:00"}"""
        val (repo, _) = repoWith(discussionJson(), discussionJson(extra = listOf(mine)), discussionJson())
        val local = Post(author = "me", permlink = "re-c1", parentAuthor = "bob", parentPermlink = "c1", body = "hi", depth = 2)
        repo.addLocalComment(PostKey("alice", "post"), local)

        val before = repo.getDiscussion("alice", "post")
        val c1 = before.threads.first { it.comment.permlink == "c1" }
        assertEquals(listOf("r1", "r2", "r1b", "re-c1"), c1.replies.map { it.comment.permlink })
        assertEquals(3, c1.comment.children)

        // Once the bridge has it, the fetched copy is used and the local one is forgotten.
        val after = repo.getDiscussion("alice", "post", refresh = true)
        assertEquals("2026-10-01T09:00:00", after.entries[PostKey("me", "re-c1")]?.created)
        // A later refresh without it (a lagging node) no longer resurrects the local copy.
        val later = repo.getDiscussion("alice", "post", refresh = true)
        assertNull(later.entries[PostKey("me", "re-c1")])
    }

    @Test
    fun manyDownvotedCommentsSortWithoutContractViolations() {
        val comments = List(200) { i ->
            Post(author = "u$i", permlink = "p$i", netRshares = if (i % 3 == 0) -1 else 1, created = "2026-10-01T00:00:${(i % 60).toString().padStart(2, '0')}", pendingPayoutValue = "${i % 7}.000 HBD")
        }
        CommentSort.entries.forEach { sort ->
            val sorted = Discussion.sortComments(comments.shuffled(), sort, null)
            assertEquals(200, sorted.size)
            if (sort == CommentSort.TRENDING || sort == CommentSort.AGE) {
                assertTrue(sorted.dropWhile { !it.isDownvoted }.all { it.isDownvoted }, "downvoted last for $sort")
            }
        }
    }

    @Test
    fun localCommentIsOnTopExpiresAndConcurrentAddsAreKept() = runTest {
        var now = 0L
        val transport = FakeTransport { FakeTransport.result(it, discussionJson()) }
        val repo = PostRepository(HiveApi(clientWith(transport)), ttlMillis = 0, localCommentTtlMillis = 60_000, nowMillis = { now })
        val root = PostKey("alice", "post")
        // A fresh top-level reply has no payout yet: under TRENDING it would sort last.
        repo.addLocalComment(root, Post(author = "me", permlink = "re-new", parentAuthor = "alice", parentPermlink = "post"))
        assertEquals(listOf("re-new", "c1", "c3", "bot", "c2"), repo.getDiscussion("alice", "post").order())
        // ...but the pinned reply still comes first.
        val pinnedRepo = PostRepository(HiveApi(clientWith(FakeTransport { FakeTransport.result(it, discussionJson(pinnedReply = "carol/c3")) })))
        pinnedRepo.addLocalComment(root, Post(author = "me", permlink = "re-new", parentAuthor = "alice", parentPermlink = "post"))
        assertEquals(listOf("c3", "re-new", "c1", "bot", "c2"), pinnedRepo.getDiscussion("alice", "post").order())

        now += 60_001 // never indexed: the broadcast most likely failed, stop showing it
        assertFalse("re-new" in repo.getDiscussion("alice", "post").order())

        coroutineScope {
            repeat(50) { i ->
                launch(Dispatchers.Default) {
                    repo.addLocalComment(root, Post(author = "me", permlink = "re-$i", parentAuthor = "alice", parentPermlink = "post"))
                }
            }
        }
        assertEquals(50, repo.getDiscussion("alice", "post").order().count { it.startsWith("re-") })
    }

    @Test
    fun invalidatingACommentRefreshesItsDiscussion() = runTest {
        val (repo, transport) = repoWith(discussionJson())
        repo.getDiscussion("alice", "post")
        repo.getDiscussion("alice", "post")
        assertEquals(1, transport.sent.size)
        repo.invalidate(PostKey("erin", "r1")) // voted on a nested reply
        repo.getDiscussion("alice", "post")
        assertEquals(2, transport.sent.size)
        repo.invalidate(PostKey("nobody", "else"))
        repo.getDiscussion("alice", "post")
        assertEquals(2, transport.sent.size)
    }
}
