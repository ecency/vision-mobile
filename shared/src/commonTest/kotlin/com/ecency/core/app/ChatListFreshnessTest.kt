package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.l10n.L
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tester reports of 11-07: the chat badge counted messages the list did not show (the list kept its first load), and
 * the list said "No Internet Connection" while every other screen loaded (a momentary offline flag at load).
 */
class ChatListFreshnessTest {
    private fun list(f: ChatFixture): ChatsModel = f.env.chat.chatsModel().also { it.show(); f.idle() }

    private fun ChatsModel.row(title: String) = current.rows.first { it.title == title }

    private fun ChatsModel.rowSum() = current.rows.sumOf { it.unread }

    @Test
    fun theBadgeAndTheRowsReadTheSameChannelFetch() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = list(f)
        assertEquals(0, m.row("Photography").unread)
        assertEquals(5, f.env.badges.current.chats)
        repeat(3) { f.chat.incoming("ch-photo", "New photo $it", "spike-bob") }
        // what the app does on foreground and after a read: only the badge read is asked for
        f.env.chat.refreshUnread()
        f.idle()
        assertEquals(8, f.env.badges.current.chats)
        assertEquals(3, m.row("Photography").unread, "the row shows what the badge counts, without a pull")
        assertEquals(f.env.badges.current.chats.toLong(), m.rowSum())
    }

    @Test
    fun theRowsAddUpToTheBadgeForEveryKindOfChannel() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = list(f)
        f.before = { r -> if (r.method == "GET" && r.url.endsWith("/api/mattermost/channels")) HttpResult(200, MIXED) else null }
        f.env.chat.refreshUnread()
        f.idle()
        // the direct message 2, the group's 4 mentions over its 1 message, the open channel 3; the muted, the never
        // viewed and the not eligible channels count nothing
        assertEquals(9, f.env.badges.current.chats)
        assertEquals(setOf("m-dm", "m-group", "m-muted", "m-new", "m-open", "m-off"), m.current.rows.map { it.id }.toSet())
        assertEquals(9L, m.rowSum())
    }

    @Test
    fun oneLoadAppliesItsAnswerOnceAndLooksUpItsUsersOnce() = runTest {
        val f = ChatFixture(this)
        f.start()
        // a direct message whose other user the list does not know yet: the load asks for that user
        f.before = { r ->
            when {
                r.method == "GET" && r.url.endsWith("/api/mattermost/channels") -> HttpResult(
                    200,
                    """{"channels":[{"id":"m-dm","type":"D","name":"u-a__u-x","last_viewed_at":1,"message_count":2,"teammate_id":"u-x"}]}""",
                )
                r.url.endsWith("/api/mattermost/users/ids") -> HttpResult(200, """{"users":[{"id":"u-x","username":"spike-x"}]}""")
                else -> null
            }
        }
        list(f)
        val lookups = f.app.authBackend.sent.filter { it.url.endsWith("/api/mattermost/users/ids") }
        assertEquals(1, lookups.size, "one user lookup for one load")
    }

    @Test
    fun anotherAccountsChannelsNeverReachTheList() = runTest {
        val f = ChatFixture(this, users = listOf(ChatFixture.USER, "spike-yan"))
        f.start()
        val m = list(f)
        val rows = m.current.rows.map { it.id }
        val badge = f.env.badges.current.chats
        f.before = { r -> if (r.method == "GET" && r.url.endsWith("/api/mattermost/channels")) HttpResult(200, MIXED) else null }
        f.env.chat.fetchChannels("spike-yan")
        f.idle()
        assertEquals(rows, m.current.rows.map { it.id }, "the list stays the signed-in account's")
        assertEquals(badge, f.env.badges.current.chats)
    }

    @Test
    fun aChannelReadWhileAnOlderReadWasOnItsWayStaysRead() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = list(f)
        assertEquals(1, m.row("spike-alice").unread)
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.app.authBackend.gate = { if (it.url.endsWith("/api/mattermost/channels")) hold.await() }
        m.show()
        f.run()
        // the conversation is read on screen after that read of the list started (the server's answer still counts it)
        f.env.chat.markedViewed("ch-dm-alice", ChatFixture.USER)
        f.run()
        assertEquals(0, m.row("spike-alice").unread)
        hold.complete(Unit)
        f.app.authBackend.gate = null
        f.idle()
        assertEquals(0, m.row("spike-alice").unread, "the older answer does not bring the count back")
        assertEquals(f.env.badges.current.chats.toLong(), m.rowSum())
    }

    @Test
    fun theTabShownAgainRefreshesQuietly() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = list(f)
        repeat(2) { f.chat.incoming("ch-photo", "New photo $it", "spike-bob") }
        m.show()
        assertTrue(!m.current.isRefreshing && !m.current.isLoading, "no spinner for a quiet refresh")
        f.idle()
        assertEquals(2, m.row("Photography").unread)
        assertEquals(f.env.badges.current.chats.toLong(), m.rowSum())
        // a quiet refresh that fails keeps the rows and shows nothing
        f.before = { r -> if (r.url.endsWith("/api/mattermost/channels")) HttpResult(502, "") else null }
        m.show()
        f.idle()
        assertNull(m.current.error)
        assertEquals(2, m.row("Photography").unread)
    }

    @Test
    fun aMomentaryOfflineFlagNeverStrandsTheList() = runTest {
        val f = ChatFixture(this)
        f.start()
        // the platform's reachability flag reads offline for a moment as the tab opens; the network works
        f.env.connectivity.setOnline(false)
        val m = list(f)
        assertNull(m.current.error, "nothing failed, so nothing says there is no connection")
        f.env.connectivity.setOnline(true)
        f.idle()
        assertNull(m.current.error)
        assertTrue(m.current.rows.isNotEmpty())
        assertEquals(f.env.badges.current.chats.toLong(), m.rowSum())
    }

    @Test
    fun aListThatCouldNotConnectLoadsWhenTheConnectionReturns() = runTest {
        val f = ChatFixture(this)
        f.start()
        f.env.connectivity.setOnline(false)
        f.before = { r -> if ("/api/mattermost/" in r.url) throw IllegalStateException("offline") else null }
        val m = list(f)
        assertEquals(ChatsModel.NO_INTERNET, m.current.error, "a request failed for lack of connection")
        f.before = null
        f.env.connectivity.setOnline(true)
        f.idle()
        assertNull(m.current.error)
        assertTrue(m.current.rows.isNotEmpty())
    }

    @Test
    fun aBadgeReadThatBringsFreshRowsClearsAnEarlierLoadError() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = list(f)
        // a pull to refresh that fails keeps the rows and shows its error
        f.before = { r -> if (r.url.endsWith("/api/mattermost/channels")) HttpResult(502, "") else null }
        m.refresh()
        f.idle()
        assertNotNull(m.current.error)
        // the badge's next read answers: the rows are fresh, so the error is gone
        f.before = null
        f.chat.incoming("ch-photo", "New photo", "spike-bob")
        f.env.chat.refreshUnread()
        f.idle()
        assertEquals(1, m.row("Photography").unread)
        assertNull(m.current.error)
        // a channel action's failure is not the list's: the next badge read leaves it on screen
        f.before = { r -> if (r.url.endsWith("/favorite")) HttpResult(502, "") else null }
        m.perform(m.row("Photography"), ChatChannelAction.FAVORITE)
        f.idle()
        val actionError = assertNotNull(m.current.error)
        f.before = null
        f.env.chat.refreshUnread()
        f.idle()
        assertEquals(actionError, m.current.error)
    }

    @Test
    fun aBadgeReadRecoversAListWhoseFirstLoadFailed() = runTest {
        val f = ChatFixture(this)
        f.start()
        f.before = { r -> if (r.url.endsWith("/api/mattermost/channels")) HttpResult(502, "") else null }
        val m = list(f)
        assertNotNull(m.current.error)
        assertTrue(m.current.rows.isEmpty())
        f.before = null
        f.env.chat.refreshUnread()
        f.idle()
        assertNull(m.current.error)
        assertTrue(m.current.rows.isNotEmpty())
        assertEquals(f.env.badges.current.chats.toLong(), m.rowSum())
    }

    @Test
    fun aListWhoseSessionFailedIsConnectedAgainAfterAGoodBadgeRead() = runTest {
        val f = ChatFixture(this)
        // the chat bootstrap itself fails from the start, so the list never connected
        f.before = { r -> if ("/api/mattermost/" in r.url) HttpResult(502, "") else null }
        f.start()
        val m = list(f)
        assertNotNull(m.current.error)
        assertFalse(m.current.connected)
        f.before = null
        f.env.chat.refreshUnread()
        f.idle()
        assertNull(m.current.error)
        assertTrue(m.current.rows.isNotEmpty())
        assertTrue(m.current.connected, "connected: New group and the other session actions show")
        assertTrue(m.current.canStartGroup)
    }

    @Test
    fun aChannelsReadThatTimedOutIsNotNoInternet() = runTest {
        val f = ChatFixture(this)
        f.start()
        f.before = { r -> if (r.url.endsWith("/api/mattermost/channels")) throw com.ecency.core.api.EcencyTimeoutException() else null }
        val m = list(f)
        assertNotNull(m.current.error)
        assertNotEquals(ChatsModel.NO_INTERNET, m.current.error, "the server was slow, the connection was there")
    }

    @Test
    fun aRefusedChannelsReadShowsItsOwnFailureNotNoInternet() = runTest {
        val f = ChatFixture(this)
        val reporter = RecordingReporter()
        f.app.env.crashReporter = reporter
        f.start()
        f.before = { r -> if (r.url.endsWith("/api/mattermost/channels")) HttpResult(502, "") else null }
        val m = list(f)
        // a server failure: its status, as a lost connection is not what happened
        assertEquals(L.native.chatApi.requestFailed(status = "502"), m.current.error)
        // an answer the app did not expect, without words of the server: the status and the report's code
        f.before = { r -> if (r.url.endsWith("/api/mattermost/channels")) HttpResult(400, "") else null }
        m.refresh()
        f.idle()
        val code = assertNotNull(reporter.reports.last { it.tags["where"] == "chats.load" }.tags["error_code"])
        assertEquals(L.native.chatApi.requestFailed(status = "400") + "\n" + L.native.appError.errorCode(code = code), m.current.error)
        // refused twice (the second after a new bootstrap): the server's own words, no code
        f.before = { r -> if (r.url.endsWith("/api/mattermost/channels")) HttpResult(401, """{"error":"Chat session expired"}""") else null }
        m.refresh()
        f.idle()
        assertEquals("Chat session expired", m.current.error)
        assertNotEquals(ChatsModel.NO_INTERNET, m.current.error)
    }

    companion object {
        private val MIXED = """{"channels":[
            {"id":"m-dm","type":"D","name":"u-a__u-b","last_viewed_at":1,"message_count":2,"teammate_id":"u-spike-alice","directUser":{"id":"u-spike-alice","username":"spike-alice"}},
            {"id":"m-group","type":"G","display_name":"Group","last_viewed_at":1,"message_count":1,"mention_count":4},
            {"id":"m-muted","type":"O","display_name":"Muted","last_viewed_at":1,"message_count":5,"is_muted":true},
            {"id":"m-new","type":"O","display_name":"Never viewed","last_viewed_at":0,"message_count":7},
            {"id":"m-open","type":"O","display_name":"Open","last_viewed_at":1,"message_count":3,"mention_count":1},
            {"id":"m-off","type":"O","display_name":"Off","last_viewed_at":1,"message_count":2,"unread_eligible":false}
        ]}"""
    }
}

/** Sentry `ChatApiException: Request failed (405)` at chatThread.roots: the server does not serve one post by id. */
class ChatThreadRootsTest {
    private fun thread(f: ChatFixture): ChatThreadModel {
        f.env.chat.hint("ch-ecency", ChatHint(null, "Ecency", emptyMap()), ChatFixture.USER)
        return f.env.chat.threadModel("ch-ecency", "O").also { it.start(); f.idle() }
    }

    private fun post(id: String, user: String, message: String, ago: Long, root: String = "") =
        """{"id":"$id","channel_id":"ch-ecency","user_id":"u-$user","message":"$message","type":"","create_at":${TestApp.NOW - ago},"update_at":${TestApp.NOW - ago},"root_id":"$root"}"""

    /** A first page of two replies whose roots are not on it; [roots] answers the reads of a root by id. */
    private fun page(roots: Map<String, HttpResult>): (AuthHttpRequest) -> HttpResult? = { r ->
        val path = r.url.substringAfter("/api/mattermost/")
        when {
            r.method != "GET" -> null
            path.startsWith("channels/ch-ecency/posts/") -> roots[path.substringAfterLast('/')]
            path == "channels/ch-ecency/posts" -> HttpResult(
                200,
                """{"order":["reply-2","reply-1"],"posts":{"reply-1":${post("reply-1", "spike-alice", "First reply", 60_000, "root-a")},"reply-2":${post("reply-2", "spike-carol", "Second reply", 30_000, "root-b")}}}""",
            )
            path.startsWith("channels/ch-ecency/posts?before=") -> HttpResult(200, """{"order":[],"posts":{}}""")
            else -> null
        }
    }

    private fun rootReads(f: ChatFixture) = f.app.authBackend.sent.filter { it.method == "GET" && "/channels/ch-ecency/posts/root-" in it.url }

    private fun ChatThreadModel.reply(id: String) = current.items.filterIsInstance<ChatMessageItem>().first { it.id == id }

    @Test
    fun aRootTheServerDoesNotServeIsSkippedWithoutAReportAndNotAskedAgain() = runTest {
        val f = ChatFixture(this)
        val reporter = RecordingReporter()
        f.app.env.crashReporter = reporter
        f.start()
        f.before = page(mapOf("root-a" to HttpResult(405, ""), "root-b" to HttpResult(405, "")))
        val m = thread(f)
        assertEquals(1, rootReads(f).size, "after a 405 no other root is asked for")
        assertTrue(reporter.reports.none { it.tags["where"] == "chatThread.roots" }, "a 405 is not a bug of the app")
        assertNull(m.current.error)
        assertNull(m.reply("reply-1").quote)
        assertNull(m.reply("reply-2").quote)
        m.loadMore()
        f.idle()
        assertEquals(1, rootReads(f).size, "nor on the next page")
        m.close()
    }

    @Test
    fun aMissingRootIsSkippedAndAServedOneStillFillsTheQuote() = runTest {
        val f = ChatFixture(this)
        val reporter = RecordingReporter()
        f.app.env.crashReporter = reporter
        f.start()
        f.before = page(mapOf("root-a" to HttpResult(404, """{"error":"post not found"}"""), "root-b" to HttpResult(200, post("root-b", "spike-bob", "The root message", 90_000))))
        val m = thread(f)
        assertEquals(2, rootReads(f).size, "a 404 skips that root only")
        assertTrue(reporter.reports.none { it.tags["where"] == "chatThread.roots" })
        assertNull(m.reply("reply-1").quote)
        assertEquals("The root message", m.reply("reply-2").quote?.text)
        m.close()
    }

    @Test
    fun anotherFailureOfARootReadIsStillReportedQuietly() = runTest {
        val f = ChatFixture(this)
        val reporter = RecordingReporter()
        f.app.env.crashReporter = reporter
        f.start()
        f.before = page(mapOf("root-a" to HttpResult(403, """{"error":"forbidden"}"""), "root-b" to HttpResult(200, post("root-b", "spike-bob", "The root message", 90_000))))
        val m = thread(f)
        val report = reporter.reports.single { it.tags["where"] == "chatThread.roots" }
        assertEquals("true", report.tags["quiet"])
        assertEquals("The root message", m.reply("reply-2").quote?.text, "the next root is still read")
        assertNull(m.current.error)
        m.close()
    }
}
