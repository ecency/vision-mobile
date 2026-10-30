package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.chat.ChatApi
import com.ecency.core.api.chat.ChatChannel
import com.ecency.core.api.chat.ChatApiException
import com.ecency.core.api.chat.DmPrivacy
import com.ecency.core.api.ecency.ImageUploadTransport
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthException
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.HasSocketFactory
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatFixture(
    private val scope: TestScope,
    users: List<String> = listOf(USER),
    pin: String? = null,
    val team: List<Pair<String, String>> = emptyList(),
) {
    val app = TestApp(scope) { request ->
        TestApp.chain(request) ?: when (request.method) {
            "bridge.get_post" -> FakeTransport.result(
                request,
                """{"author":"spike-alice","permlink":"fake-post-1","title":"Fixture post","body":"A synthetic body of the fixture post.","json_metadata":{"description":"Made-up summary"},"created":"2026-10-01T00:00:00","category":"hive"}""",
            )
            "bridge.get_community" -> FakeTransport.result(
                request,
                """{"name":"hive-125125","title":"Ecency","team":[${team.joinToString(",") { "[\"${it.first}\",\"${it.second}\",\"\"]" }}]}""",
            )
            else -> HttpResult(500, "")
        }
    }
    val chat = DemoChat { app.clock() }
    val env get() = app.env

    /** Answers before [chat] (a test refuses one call); null passes the request on. */
    var before: ((AuthHttpRequest) -> HttpResult?)? = null

    init {
        val vault = KeyVault(app.store)
        users.forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$it")) }
        users.firstOrNull()?.let { vault.setCurrentUsername(it) }
        if (pin != null) PinLock(vault, 16).setPin(pin)
        app.authBackend.rest = { request -> before?.invoke(request) ?: chat.http(request) ?: HttpResult(503, "{}") }
        app.relay.chat = HasSocketFactory { chat.socket() }
        app.clock = { TestApp.NOW + scope.testScheduler.currentTime }
    }

    fun start() {
        env.session.start()
        env.appLock.start()
        env.chat.start()
        idle()
    }

    fun idle() = scope.testScheduler.advanceUntilIdle()

    /** Moves the clock by [millis] and runs what is due (an open websocket keeps the scheduler busy forever). */
    fun run(millis: Long = 0) {
        scope.testScheduler.advanceTimeBy(millis)
        scope.testScheduler.runCurrent()
    }

    fun chatRequests(): List<AuthHttpRequest> = chat.sent.toList()

    fun paths(): List<String> = chatRequests().map { "${it.method} ${it.url.substringAfter("/api/mattermost/")}" }

    fun body(r: AuthHttpRequest) = Json.parseToJsonElement(r.body ?: "{}").jsonObject

    companion object {
        const val USER = "spike-zed"
    }
}

class ChatSessionTest {
    @Test
    fun bootstrapSendsTheAccessTokenToEcencyAndIsReusedFor30Seconds() = runTest {
        val f = ChatFixture(this)
        f.start()
        f.env.chat.session(ChatFixture.USER)
        val boot = f.chatRequests().last { it.url.endsWith("/bootstrap") }
        assertEquals("https://ecency.com/api/mattermost/bootstrap", boot.url)
        assertEquals("token-${ChatFixture.USER}", f.body(boot)["accessToken"]!!.jsonPrimitive.content)
        assertEquals(ChatFixture.USER, f.body(boot)["username"]!!.jsonPrimitive.content)
        assertNull(boot.headers[ChatApi.TOKEN_HEADER], "the bootstrap carries no chat token")
        val made = f.env.chat.bootstraps
        f.env.chat.session(ChatFixture.USER)
        assertEquals(made, f.env.chat.bootstraps, "reused within 30 s")
        f.run(ChatService.CACHE_MILLIS + 1)
        f.env.chat.session(ChatFixture.USER)
        assertEquals(made + 1, f.env.chat.bootstraps, "asked again after 30 s")
        // every other chat call carries the token, to Ecency only
        assertTrue(f.chatRequests().filter { !it.url.endsWith("/bootstrap") }.all { it.headers[ChatApi.TOKEN_HEADER] == "demo-chat-token" })
        assertTrue(f.chatRequests().all { it.url.startsWith("https://ecency.com/api/mattermost/") })
    }

    @Test
    fun concurrentCallersShareOneBootstrapAndALogoutVoidsIt() = runTest {
        val f = ChatFixture(this)
        val gate = CompletableDeferred<Unit>()
        f.app.authBackend.gate = { if (it.url.endsWith("/bootstrap")) gate.await() }
        f.env.session.start()
        f.env.appLock.start()
        f.idle()
        val a = async { f.env.chat.session(ChatFixture.USER) }
        val b = async { f.env.chat.session(ChatFixture.USER) }
        f.run()
        gate.complete(Unit)
        assertEquals(a.await().token, b.await().token)
        assertEquals(1, f.env.chat.bootstraps)

        // a logout while a bootstrap is on the way: its token is neither used nor kept
        val gate2 = CompletableDeferred<Unit>()
        f.app.authBackend.gate = { if (it.url.endsWith("/bootstrap")) gate2.await() }
        f.run(ChatService.CACHE_MILLIS + 1)
        val c = async { runCatching { f.env.chat.session(ChatFixture.USER) } }
        f.run()
        f.env.chat.forget(listOf(ChatFixture.USER))
        gate2.complete(Unit)
        assertTrue(c.await().exceptionOrNull() is AuthException.NotSignedIn)

        // two callers wait on one bootstrap and the account logs out meanwhile: neither gets the token
        val gate3 = CompletableDeferred<Unit>()
        f.app.authBackend.gate = { if (it.url.endsWith("/bootstrap")) gate3.await() }
        val first = async { runCatching { f.env.chat.session(ChatFixture.USER) } }
        val joined = async { runCatching { f.env.chat.session(ChatFixture.USER) } }
        f.run()
        f.env.chat.forget(listOf(ChatFixture.USER))
        gate3.complete(Unit)
        assertTrue(first.await().exceptionOrNull() is AuthException.NotSignedIn)
        assertTrue(joined.await().exceptionOrNull() is AuthException.NotSignedIn, "the joined waiter got a voided token")
        f.app.authBackend.gate = null
        val made = f.env.chat.bootstraps
        f.env.chat.session(ChatFixture.USER)
        assertEquals(made + 1, f.env.chat.bootstraps, "nothing of the voided answer was cached")
    }

    @Test
    fun aLoggedOutAccountsChatTokenIsGone() = runTest {
        val f = ChatFixture(this, users = listOf(ChatFixture.USER, "spike-yan"))
        f.start()
        f.env.chat.session("spike-yan")
        f.env.chat.hint("ch-dm-x", ChatHint(null, "Seen by spike-yan", emptyMap()), "spike-yan")
        f.env.chat.dismissWarning("ch-dm-x", "spike-yan")
        f.env.chat.rememberRename("ch-group", "Named by spike-yan", "spike-yan")
        // what the account that stays saw
        f.env.chat.hint("ch-dm-mine", ChatHint(null, "Seen by ${ChatFixture.USER}", emptyMap()), ChatFixture.USER)
        f.env.chat.dismissWarning("ch-dm-mine", ChatFixture.USER)
        f.env.chat.rememberRename("ch-mine", "Named by ${ChatFixture.USER}", ChatFixture.USER)
        f.env.session.logout("spike-yan")
        f.idle()
        // what the departed account saw of its channels goes with it
        assertEquals(setOf("ch-dm-mine"), f.env.chat.hints.keys)
        assertTrue(!f.env.chat.warningDismissed("ch-dm-x"))
        assertEquals(null, f.env.chat.withNewerRenames(listOf(ChatChannel("ch-group")), 0).single().groupName)
        // the account that stays keeps its own
        assertTrue(f.env.chat.warningDismissed("ch-dm-mine"))
        assertEquals("Named by ${ChatFixture.USER}", f.env.chat.withNewerRenames(listOf(ChatChannel("ch-mine")), 0).single().groupName)
        // the cached token went with the account: nothing answers for it any more
        assertFailsWith<ChatApiException> { f.env.chat.session("spike-yan") }
    }

    @Test
    fun aRequestFinishingAfterASwitchBelongsToTheAccountItStartedFor() = runTest {
        val f = ChatFixture(this, users = listOf(ChatFixture.USER, "spike-yan"))
        f.start()
        val answer = CompletableDeferred<Unit>()
        f.app.authBackend.gate = { if (it.url.endsWith("/api/mattermost/direct")) answer.await() }
        val opened = async { f.env.chat.openDirect("spike-bob") }
        f.idle()
        f.env.session.switchTo("spike-yan")
        f.idle()
        answer.complete(Unit)
        val id = opened.await()
        f.app.authBackend.gate = null
        assertTrue(id in f.env.chat.hints)
        // the hint is the first account's: it leaves with that account, not with the one current when it arrived
        f.env.session.logout(ChatFixture.USER)
        f.idle()
        assertTrue(id !in f.env.chat.hints, "what the departed account's request found went with it")
    }

    @Test
    fun aRefusedTokenBootstrapsAgainOnce() = runTest {
        val f = ChatFixture(this)
        f.start()
        var refusals = 1
        f.before = { r -> if (r.url.endsWith("/channels") && refusals-- > 0) HttpResult(401, """{"error":"unauthorized"}""") else null }
        val made = f.env.chat.bootstraps
        val channels = f.env.chat.call(ChatFixture.USER) { f.env.chat.api.channels(it.token) }
        assertTrue(channels.isNotEmpty())
        assertEquals(made + 1, f.env.chat.bootstraps)
        // a second refusal in a row is the answer
        refusals = 2
        assertFailsWith<ChatApiException> { f.env.chat.call(ChatFixture.USER) { f.env.chat.api.channels(it.token) } }
    }

    @Test
    fun noTokenLeavesForAHostThatIsNotEcencys() = runTest {
        val f = ChatFixture(this)
        f.env.setChatHost("https://chat.example.org")
        assertEquals("https://ecency.com", f.env.chatHost)
        f.env.setChatHost("http://ecency.com")
        assertEquals("https://ecency.com", f.env.chatHost)
        f.env.setChatHost("https://api.ecency.com/")
        assertEquals("https://api.ecency.com", f.env.chatHost)
        f.env.setChatHost("https://ECENCY.com")
        assertEquals("https://ecency.com", f.env.chatHost, "lower case")
        // only a bare https host: no port (not even the default one), path, query, fragment or user info
        for (h in listOf(
            "https://api.ecency.com:443", "https://api.ecency.com:0443", "https://api.ecency.com:8443", "https://api.ecency.com/path",
            "https://api.ecency.com/api/", "https://api.ecency.com?x", "https://api.ecency.com#frag", "https://user@api.ecency.com",
            "https://api.ecency.com@evil.com", "https://api.ecency.com.", "https://api.ecеncy.com", "wss://api.ecency.com",
        )) {
            f.env.setChatHost(h)
            assertEquals("https://ecency.com", f.env.chatHost, "accepted: $h")
            assertFalse(ChatApi(f.app.authBackend.http, h).trusted, "trusted: $h")
        }
        val api = ChatApi(f.app.authBackend.http, "https://chat.example.org")
        assertFalse(api.trusted)
        assertNull(api.websocketUrl("t"))
        val sent = f.chatRequests().size
        assertFailsWith<ChatApiException> { api.bootstrap("secret", null, "x") }
        assertFailsWith<ChatApiException> { api.channels("t") }
        assertEquals(sent, f.chatRequests().size, "nothing was sent")
        assertEquals("wss://ecency.com/api/mattermost/websocket?token=a%2Bb", ChatApi(f.app.authBackend.http, "https://ecency.com").websocketUrl("a+b"))
    }

    @Test
    fun theBadgeCountsUnreadChannelsOnlyWhileUnlockedAndFollowsTheAccount() = runTest {
        val f = ChatFixture(this, users = listOf(ChatFixture.USER, "spike-yan"), pin = "1234")
        f.start()
        assertTrue(f.chatRequests().isEmpty(), "nothing is asked while locked")
        assertEquals(0, f.env.badges.current.chats)
        "1234".forEach { f.env.appLock.enterDigit(it.digitToInt()) }
        f.idle()
        // Ecency 3, the direct message 1, the group 1; the favourite is read, the muted channel never counts
        assertEquals(5, f.env.badges.current.chats)
        // a failed read keeps the count
        f.before = { r -> if (r.url.endsWith("/channels")) HttpResult(502, "") else null }
        f.env.chat.onForeground()
        f.idle()
        assertEquals(5, f.env.badges.current.chats)
        f.before = null
        f.env.session.switchTo("spike-yan")
        f.idle()
        assertEquals(5, f.env.badges.current.chats, "the other account's own count")
        assertEquals("spike-yan", f.body(f.chatRequests().last { it.url.endsWith("/bootstrap") })["username"]!!.jsonPrimitive.content)
    }
}

class ChatsListTest {
    private fun list(f: ChatFixture): ChatsModel = f.env.chat.chatsModel().also { it.show(); f.idle() }

    @Test
    fun rowsAreSortedAndTitledAsRnDoes() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = list(f)
        val s = m.current
        assertTrue(s.connected)
        assertEquals(listOf("Photography", "Book club", "spike-alice", "Ecency", "Off topic"), s.rows.map { it.title })
        assertEquals(ChatAvatar.Pair("spike-alice", "spike-bob"), s.rows[1].avatar)
        assertEquals(ChatAvatar.Account("spike-alice"), s.rows[2].avatar)
        assertEquals(ChatAvatar.Account("hive-125125"), s.rows[3].avatar)
        assertEquals(listOf(0L, 1L, 1L, 3L, 0L), s.rows.map { it.unread })
        assertTrue(s.rows.last().muted && s.rows.first().favorite)
        assertEquals("3 minutes ago", s.rows[3].time)
        m.toggleSort()
        assertEquals(listOf("Book club", "Ecency", "Off topic", "Photography", "spike-alice"), m.current.rows.map { it.title })
    }

    @Test
    fun optionsMarkReadFavoriteMuteAndLeave() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = list(f)
        val ecency = m.current.rows.first { it.title == "Ecency" }
        val group = m.current.rows.first { it.title == "Book club" }
        assertEquals(listOf(ChatChannelAction.MARK_READ, ChatChannelAction.FAVORITE, ChatChannelAction.MUTE, ChatChannelAction.LEAVE_CHANNEL), m.actions(ecency))
        assertEquals(
            listOf(ChatChannelAction.MARK_READ, ChatChannelAction.RENAME, ChatChannelAction.FAVORITE, ChatChannelAction.MUTE, ChatChannelAction.LEAVE_CONVERSATION),
            m.actions(group),
        )
        m.perform(ecency, ChatChannelAction.MARK_READ)
        f.idle()
        assertTrue("POST channels/ch-ecency/view" in f.paths())
        assertEquals(0, m.current.rows.first { it.title == "Ecency" }.unread)
        assertEquals(2, f.env.badges.current.chats, "the badge is read again")
        m.perform(m.current.rows.first { it.title == "Ecency" }, ChatChannelAction.FAVORITE)
        f.idle()
        assertEquals("true", f.body(f.chatRequests().last { it.url.endsWith("/favorite") })["favorite"].toString())
        assertEquals("Ecency", m.current.rows.first().title, "favourites first")
        m.perform(m.current.rows.first { it.title == "Book club" }, ChatChannelAction.MUTE)
        f.idle()
        assertTrue(m.current.rows.first { it.title == "Book club" }.muted)
        m.perform(m.current.rows.first { it.title == "spike-alice" }, ChatChannelAction.LEAVE_CONVERSATION)
        f.idle()
        assertTrue("POST channels/ch-dm-alice/leave" in f.paths())
        assertTrue(m.current.rows.none { it.title == "spike-alice" })
        // a refusal shows the server's text
        f.before = { r -> if (r.url.endsWith("/mute")) HttpResult(403, """{"message":"Not allowed here"}""") else null }
        m.perform(m.current.rows.first { it.title == "Ecency" }, ChatChannelAction.MUTE)
        f.idle()
        assertEquals("Not allowed here", m.current.error)
    }

    @Test
    fun searchJoinAndDirectMessagesOpenTheConversation() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = list(f)
        val sent = f.chatRequests().size
        m.setQuery("H")
        f.idle()
        assertEquals(sent, f.chatRequests().size, "one character asks nothing")
        m.setQuery("HIVE")
        assertEquals("hive", m.current.query)
        f.idle()
        val titles = m.current.channelResults.map { it.title }
        assertTrue("Hive Devs" in titles && "Ecency" in titles, "local and server channels: $titles")
        assertEquals(titles.size, titles.toSet().size)
        m.join(m.current.channelResults.first { it.title == "Hive Devs" })
        f.idle()
        assertEquals(listOf("POST channels/ch-public/join", "POST channels/ch-public/view"), f.paths().filter { "ch-public" in it })
        assertEquals(ChatOpen("ch-public", "O"), m.current.open)
        assertTrue(m.current.rows.any { it.title == "Hive Devs" })
        m.consumeOpen()
        m.setQuery("bob")
        f.idle()
        val bob = m.current.userResults.single()
        assertEquals("spike-bob", bob.title)
        m.startDirect(bob)
        f.idle()
        val direct = f.chatRequests().last { it.url.endsWith("/direct") }
        assertEquals("u-spike-bob", f.body(direct)["userId"]!!.jsonPrimitive.content)
        assertEquals(ChatOpen("ch-dm-spike-bob", "D"), m.current.open)
    }

    @Test
    fun aNewGroupOpensAndTheServersRefusalShows() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = list(f)
        val g = f.env.chat.newGroupModel()
        assertFalse(g.current.canCreate)
        g.setTerm("spike")
        f.idle()
        assertEquals(listOf("spike-alice", "spike-bob", "spike-carol", "spike-dave"), g.visible.map { it.username })
        g.add("spike-bob")
        assertEquals("", g.current.term)
        g.add("spike-ghost")
        assertTrue(g.current.canCreate)
        g.create()
        f.idle()
        assertEquals("spike-ghost is not on chat yet.", g.current.error)
        g.remove("spike-ghost")
        g.add("spike-carol")
        g.create()
        f.idle()
        val id = assertNotNull(g.current.createdChannelId)
        m.groupCreated(id)
        f.idle()
        assertEquals(ChatOpen(id, "G"), m.current.open)
        assertTrue(m.current.rows.any { it.id == id })
    }

    @Test
    fun aGroupTakesAtMostSevenOthersAndNeverTheViewer() = runTest {
        val f = ChatFixture(this, users = listOf("spike-bob"))
        f.start()
        val g = f.env.chat.newGroupModel()
        g.setTerm("spike")
        f.idle()
        assertTrue(g.visible.none { it.username == "spike-bob" }, "the viewer is not offered")
        (1..8).forEach { g.add("spike-p$it") }
        assertEquals(7, g.current.selected.size)
        assertTrue(g.current.atLimit)
        assertEquals("A group can have up to 7 other people.", g.current.placeholder)
        val sent = f.chatRequests().size
        g.setTerm("spike")
        f.idle()
        assertEquals(sent, f.chatRequests().size, "a full group searches no more")
    }

    @Test
    fun renamesReachTheListAndRefusalsShowRnsText() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = list(f)
        val r = f.env.chat.renameModel("ch-group", "Book club")
        assertFalse(r.current.canSave, "unchanged")
        r.setValue("🙂".repeat(65))
        assertTrue(r.current.tooLong)
        assertEquals("A group name can be up to 64 characters.", r.current.message)
        assertFalse(r.current.canSave)
        val before = f.chatRequests().size
        r.save()
        f.idle()
        assertEquals(before, f.chatRequests().size, "a name over 64 characters is never sent")
        r.setValue(" Readers ")
        f.before = { req -> if (req.url.endsWith("/name")) HttpResult(403, """{"error":"forbidden","code":"not_owner"}""") else null }
        r.save()
        f.idle()
        assertEquals(ChatRenameState.NOT_OWNER, r.current.error)
        f.before = { req -> if (req.url.endsWith("/name")) HttpResult(500, "{}") else null }
        r.save()
        f.idle()
        assertEquals(ChatRenameState.FAILED, r.current.error)
        f.before = null
        r.save()
        f.idle()
        assertEquals("Readers", r.current.saved)
        assertEquals("Readers", f.body(f.chatRequests().last { it.url.endsWith("/name") })["name"]!!.jsonPrimitive.content)
        assertTrue(m.current.rows.any { it.title == "Readers" })
        // a list fetch that started before the rename keeps the new name
        m.refresh()
        f.idle()
        assertTrue(m.current.rows.any { it.title == "Readers" })
    }

    @Test
    fun anAccountChangeDropsTheRowsAndPrivacyFollowsRn() = runTest {
        val f = ChatFixture(this, users = listOf(ChatFixture.USER, "spike-yan"))
        f.start()
        val m = list(f)
        assertTrue(m.current.rows.isNotEmpty())
        val p = f.env.chat.privacyModel()
        p.start()
        f.idle()
        assertEquals(DmPrivacy.ALL, p.current.value)
        p.select(DmPrivacy.FOLLOWERS)
        f.idle()
        assertEquals("followers", f.body(f.chatRequests().last { it.method == "PUT" })["privacy"]!!.jsonPrimitive.content)
        assertEquals(DmPrivacy.FOLLOWERS, p.current.value)
        assertEquals(ChatPrivacyModel.UPDATED, f.env.ui.current.toast?.text)
        f.before = { r -> if (r.method == "PUT") HttpResult(500, "{}") else null }
        p.select(DmPrivacy.NONE)
        f.idle()
        assertEquals(DmPrivacy.FOLLOWERS, p.current.value)
        f.before = null
        f.env.session.switchTo("spike-yan")
        f.run()
        assertEquals("spike-yan", m.current.account)
        assertTrue(m.current.rows.isEmpty() || m.current.account == "spike-yan")
        f.idle()
        assertTrue(m.current.loaded)
    }

    @Test
    fun aProfileOpensItsDirectMessageOrSaysTheUserIsNotOnChat() = runTest {
        val f = ChatFixture(this)
        f.start()
        assertEquals("ch-dm-spike-bob", f.env.chat.openDirect("spike-bob"))
        val e = assertFailsWith<ChatApiException> { f.env.chat.openDirect("spike-ghost") }
        assertEquals(ChatService.DM_ERROR, e.message)
    }
}

class ChatThreadTest {
    private fun thread(f: ChatFixture, id: String, type: String, title: String = ""): ChatThreadModel {
        f.env.chat.hint(id, ChatHint(null, title, emptyMap()), ChatFixture.USER)
        return f.env.chat.threadModel(id, type).also { it.start(); f.idle() }
    }

    @Test
    fun theFirstPageShowsMessagesNoticesAndTheUnreadMarkerAndIsMarkedRead() = runTest {
        val f = ChatFixture(this)
        f.start()
        val list = f.env.chat.chatsModel().also { it.show(); f.idle() }
        val row = list.current.rows.first { it.title == "Ecency" }
        val t = f.env.chat.threadModel(row.id, row.channel.type).also { list.open(row) }
        f.env.chat.hint(row.id, ChatHint(row.channel, row.title, emptyMap()), ChatFixture.USER)
        val m = f.env.chat.threadModel(row.id, "O").also { it.start(); f.idle() }
        t.close()
        val s = m.current
        assertEquals("Ecency", s.title)
        assertEquals(5, s.memberCount)
        val texts = s.items.map { item ->
            when (item) {
                is ChatMessageItem -> item.spans.joinToString("") { it.text }
                is ChatJoinGroupItem -> item.text
                else -> item.id
            }
        }
        assertEquals("2 people joined", texts.last())
        assertEquals("Welcome to the Ecency chat! Say hi 👋", texts[texts.size - 2])
        val welcome = s.items[s.items.size - 2] as ChatMessageItem
        assertTrue(welcome.spans.first { it.text == "Ecency" }.bold)
        val reply = s.items.filterIsInstance<ChatMessageItem>().first { it.quote != null }
        assertEquals(ChatQuote(reply.post.parentId!!, "spike-bob", "Has anyone tried the _new_ editor? It feels `fast`."), reply.quote)
        assertEquals(listOf("+1" to 2, "heart" to 1), reply.reactions.map { it.emojiName to it.count })
        val linked = s.items.filterIsInstance<ChatMessageItem>().first { it.card != null }
        assertEquals("Fixture post", linked.card!!.title)
        // the oldest message after the last read time carries "New messages", scrolled to once
        assertEquals(reply.id, s.unreadItemId)
        assertEquals(reply.id, s.scrollTo)
        m.consumeScroll()
        assertTrue("POST channels/ch-ecency/view" in f.paths())
    }

    @Test
    fun pagesGoBackWithBeforeAndANonMemberJoinsFirst() = runTest {
        val f = ChatFixture(this)
        f.start()
        f.chat.pageSize = 3
        var refused = false
        f.before = { r -> if (r.method == "GET" && r.url.endsWith("/ch-public/posts") && !refused) { refused = true; HttpResult(403, """{"error":"not a member"}""") } else null }
        val m = thread(f, "ch-ecency", "O", "Ecency")
        val pages = f.chatRequests().filter { it.method == "GET" && "/channels/ch-ecency/posts" in it.url }.map { it.url.substringAfter("posts") }
            .filter { !it.startsWith("/") }
        assertEquals("", pages.first())
        assertTrue(pages.drop(1).all { it.startsWith("?before=") }, "$pages")
        assertFalse(m.current.hasMore)
        assertEquals(5, m.current.items.filterIsInstance<ChatMessageItem>().size)
        assertEquals(6, m.current.items.size, "five messages and the grouped joins")
        val pub = thread(f, "ch-public", "O")
        assertTrue("POST channels/ch-public/join" in f.paths())
        assertEquals(1, pub.current.items.size)
    }

    @Test
    fun aReplySendsTheAuthorsNameWhateverTheLanguage() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        val question = m.current.items.filterIsInstance<ChatMessageItem>().first { it.post.message.startsWith("Has anyone") }
        m.replyTo(question)
        try {
            // the language changes between quoting and sending: the chat still gets the account name, never display text
            com.ecency.core.l10n.PseudoLocales.install(com.ecency.core.l10n.PseudoLocales.ACCENTED)
            m.setText("me too")
            m.send()
            f.idle()
        } finally {
            com.ecency.core.l10n.L10n.setLanguage(com.ecency.core.l10n.L10n.SOURCE)
        }
        val body = f.body(f.chatRequests().last { it.method == "POST" && it.url.endsWith("/ch-ecency/posts") })
        assertEquals("spike-bob", body["props"]!!.jsonObject["parent_username"]!!.jsonPrimitive.content)
        // an unknown author is shown in the current language and never sent as a name
        val unknown = ChatQuote("p1", null, "hi")
        assertEquals("Unknown user", unknown.author)
        assertNull(unknown.username)
    }

    @Test
    fun sendingEditingReactingPinningAndRemoving() = runTest {
        val f = ChatFixture(this, team = listOf(ChatFixture.USER to "mod"))
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        assertTrue(m.current.canModerate && m.current.canPin)
        m.setText("hi :smile:")
        assertTrue(m.current.canSend)
        m.send()
        f.idle()
        val sent = f.chatRequests().last { it.method == "POST" && it.url.endsWith("/ch-ecency/posts") }
        val body = f.body(sent)
        assertEquals("hi 😄", body["message"]!!.jsonPrimitive.content)
        assertEquals("", body["rootId"]!!.jsonPrimitive.content)
        assertTrue(body["pendingPostId"]!!.jsonPrimitive.content.startsWith("u-${ChatFixture.USER}_"))
        assertEquals("", m.current.text)
        val mine = m.current.items.first() as ChatMessageItem
        assertTrue(mine.own)
        assertEquals("hi 😄", mine.spans.joinToString("") { it.text })
        assertEquals(listOf(ChatMessageAction.REPLY, ChatMessageAction.COPY, ChatMessageAction.SHARE, ChatMessageAction.TRANSLATE, ChatMessageAction.EDIT, ChatMessageAction.PIN, ChatMessageAction.REMOVE), m.actions(mine))

        // a reply carries the quote
        val question = m.current.items.filterIsInstance<ChatMessageItem>().first { it.post.message.startsWith("Has anyone") }
        assertEquals(listOf(ChatMessageAction.REPLY, ChatMessageAction.COPY, ChatMessageAction.SHARE, ChatMessageAction.TRANSLATE, ChatMessageAction.PIN, ChatMessageAction.REMOVE), m.actions(question))
        m.replyTo(question)
        m.setText("me too")
        m.send()
        f.idle()
        val replyBody = f.body(f.chatRequests().last { it.method == "POST" && it.url.endsWith("/ch-ecency/posts") })
        assertEquals(question.id, replyBody["rootId"]!!.jsonPrimitive.content)
        assertEquals("spike-bob", replyBody["props"]!!.jsonObject["parent_username"]!!.jsonPrimitive.content)
        assertNull(m.current.replyTo)

        // edit
        val own = m.current.items.filterIsInstance<ChatMessageItem>().first { it.own && it.post.message == "hi 😄" }
        m.startEdit(own)
        assertEquals("hi 😄", m.current.text)
        assertEquals(own.id, m.current.editingId)
        m.setText("hi again")
        m.send()
        f.idle()
        assertEquals("hi again", f.body(f.chatRequests().last { it.method == "PATCH" })["message"]!!.jsonPrimitive.content)
        assertTrue((m.current.items.first { it.id == own.id } as ChatMessageItem).edited)

        // react, then take it back
        m.react(question, "heart")
        f.idle()
        assertEquals("POST", f.chatRequests().last { it.url.endsWith("/reactions") }.method)
        assertTrue((m.current.items.first { it.id == question.id } as ChatMessageItem).reactions.single().reacted)
        m.react(m.current.items.first { it.id == question.id } as ChatMessageItem, "heart")
        f.idle()
        assertEquals("DELETE", f.chatRequests().last { it.url.endsWith("/reactions") }.method)
        assertTrue((m.current.items.first { it.id == question.id } as ChatMessageItem).reactions.isEmpty())
        f.before = { r -> if (r.url.endsWith("/reactions")) HttpResult(500, "{}") else null }
        m.react(question, "heart")
        f.idle()
        assertEquals(ChatThreadModel.REACTION_ERROR, f.env.ui.current.toast?.text)
        f.before = null

        // pin, then remove a message of someone else (a moderator may)
        m.setPinned(question.id, true)
        f.idle()
        assertEquals(1, m.current.pinnedCount)
        val pinned = m.pinnedModel().also { it.start(); f.idle() }
        assertEquals(listOf(question.id), pinned.current.items.map { it.post.id })
        pinned.unpin(pinned.current.items.single())
        f.idle()
        assertTrue(pinned.current.items.isEmpty())
        assertEquals(0, m.current.pinnedCount)
        m.remove(question)
        f.idle()
        assertTrue(m.current.items.none { it.id == question.id })
    }

    @Test
    fun aRebuildParsesOnlyNewAndEditedMessages() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        val parsed = m.spanParses
        assertTrue(parsed > 0)
        val question = m.current.items.filterIsInstance<ChatMessageItem>().first { it.post.message.startsWith("Has anyone") }
        m.react(question, "heart")
        f.idle()
        assertEquals(parsed, m.spanParses, "a reaction rebuilds the rows without parsing any message again")
        // the quote keeps its markdown as typed, the message itself shows the marks
        val spans = (m.current.items.first { it.id == question.id } as ChatMessageItem).spans
        assertTrue(spans.any { it.italic && it.text == "new" } && spans.any { it.code && it.text == "fast" }, "$spans")
        m.setText("one more")
        m.send()
        f.idle()
        assertEquals(parsed + 1, m.spanParses)
    }

    @Test
    fun onlyASendTheServerTookBumpsTheSentVersion() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        assertEquals(0, m.current.sentVersion)
        // a refused send ends sending without a success
        f.before = { r -> if (r.method == "POST" && r.url.endsWith("/ch-ecency/posts")) HttpResult(500, "{}") else null }
        m.setText("refused")
        m.send()
        f.idle()
        assertFalse(m.current.sending)
        assertEquals(0, m.current.sentVersion, "a failed send is no success")
        assertEquals("refused", m.current.text)
        f.before = null

        // a slow send: the bound frees the button, the success only comes when the server answers
        val held = CompletableDeferred<Unit>()
        f.app.authBackend.gate = { r -> if (r.method == "POST" && r.url.endsWith("/ch-ecency/posts")) held.await() }
        m.send()
        f.run(1)
        assertTrue(m.current.sending)
        f.run(ChatThreadModel.SEND_BOUND_MILLIS + 1)
        assertFalse(m.current.sending, "the bound frees the button")
        assertEquals(0, m.current.sentVersion, "the bound is no success")
        held.complete(Unit)
        f.idle()
        f.app.authBackend.gate = null
        assertEquals(1, m.current.sentVersion)
        assertEquals("", m.current.text)

        // a thread whose load failed still counts a send that went out
        f.before = { r -> if (r.method == "GET" && r.url.contains("/ch-photo/posts")) HttpResult(502, "") else null }
        val failedLoad = thread(f, "ch-photo", "O", "Photo")
        assertNotNull(failedLoad.current.error)
        f.before = null
        failedLoad.setText("still here")
        failedLoad.send()
        f.idle()
        assertEquals(1, failedLoad.current.sentVersion)
    }

    /** Every create held: the n-th POST waits for its answer, then goes through (true) or fails (false). */
    private class HeldCreates {
        val answers = mutableListOf<CompletableDeferred<Boolean>>()
        val ids = mutableListOf<String>()
    }

    private fun holdCreates(f: ChatFixture, edits: Boolean = false): HeldCreates {
        val held = HeldCreates()
        f.app.authBackend.gate = { r ->
            val create = r.method == "POST" && r.url.endsWith("/ch-ecency/posts")
            if (create || (edits && r.method == "PATCH")) {
                held.ids += f.body(r)["pendingPostId"]?.jsonPrimitive?.content.orEmpty()
                val mine = CompletableDeferred<Boolean>().also { held.answers += it }
                if (!mine.await()) throw IllegalStateException("offline")
            }
        }
        return held
    }

    @Test
    fun anEchoBeforeTheAnswerCountsTheSendOnce() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        try {
            m.setActive(true)
            f.run(1)
            m.consumeScroll()
            val held = holdCreates(f)
            val answers = held.answers
            m.setText("echo first")
            m.send()
            f.run(1)
            assertEquals(1, answers.size)
            f.chat.echoOwn("ch-ecency", "echo first", held.ids.single())
            f.run(1)
            assertEquals(1, m.current.sentVersion, "the echo proves the send")
            assertFalse(m.current.sending)
            assertEquals("", m.current.text)
            // the thread may sit at the unread marker: the sent message is scrolled to, whichever of echo and answer lands first
            val sent = m.current.items.filterIsInstance<ChatMessageItem>().single { it.post.message == "echo first" }
            assertEquals(sent.id, m.current.scrollTo, "the echo scrolls to the sent message")
            m.consumeScroll()
            answers.single().complete(true)
            f.run(1)
            assertEquals(1, m.current.sentVersion, "the answer after the echo is the same send")
            assertNull(m.current.scrollTo, "and does not scroll again")
            assertEquals(1, m.current.items.count { (it as? ChatMessageItem)?.post?.message == "echo first" })
            // the usual order (answer and echo from the server): once
            f.app.authBackend.gate = null
            m.setText("answer first")
            m.send()
            f.run(1)
            f.run(1)
            assertEquals(2, m.current.sentVersion)
        } finally {
            f.app.authBackend.gate = null
            m.close()
        }
    }

    @Test
    fun aSuccessClearsWhatItSentWithButKeepsTextTypedSince() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        val question = m.current.items.filterIsInstance<ChatMessageItem>().first { it.post.message.startsWith("Has anyone") }

        // a reply: the quote goes, the next text stays
        var held = holdCreates(f)
        m.replyTo(question)
        m.setText("me too")
        m.send()
        f.run(1)
        m.setText("next")
        held.answers.single().complete(true)
        f.run(1)
        assertNull(m.current.replyTo, "the next message is no reply")
        assertEquals("next", m.current.text)
        assertEquals(1, m.current.sentVersion)

        // a link card
        m.setText("see https://ecency.com/@spike-alice/fake-post-1")
        f.idle()
        assertNotNull(m.current.preview)
        held = holdCreates(f)
        m.send()
        f.run(1)
        m.setText("plain")
        held.answers.single().complete(true)
        f.run(1)
        assertNull(m.current.preview, "the card went with the message")
        assertEquals("plain", m.current.text)

        // an edit: the next tap sends a new message, not the same edit again
        f.app.authBackend.gate = null
        f.idle()
        val own = m.current.items.filterIsInstance<ChatMessageItem>().first { it.own }
        m.startEdit(own)
        held = holdCreates(f, edits = true)
        m.setText("edited")
        m.send()
        f.run(1)
        m.setText("new one")
        held.answers.single().complete(true)
        f.run(1)
        assertNull(m.current.editingId)
        assertEquals("new one", m.current.text)

        // a reply quote chosen while the send ran is the next message's: it stays
        held = holdCreates(f)
        m.send()
        f.run(1)
        m.replyTo(question)
        held.answers.single().complete(true)
        f.run(1)
        assertEquals(question.id, m.current.replyTo?.postId)
        f.app.authBackend.gate = null
    }

    @Test
    fun aSupersededSendsLateEchoStillCounts() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        try {
            m.setActive(true)
            f.run(1)
            val held = holdCreates(f)
            m.setText("first")
            m.send()
            f.run(ChatThreadModel.SEND_BOUND_MILLIS + 1)
            m.setText("second")
            m.send()
            f.run(1)
            // the first one's answer never comes, its echo does
            f.chat.echoOwn("ch-ecency", "first", held.ids[0])
            f.run(1)
            assertEquals(1, m.current.sentVersion)
            assertTrue(m.current.sending, "the second send still holds the button")
            assertEquals("second", m.current.text)
            assertEquals(1, m.current.items.count { (it as? ChatMessageItem)?.post?.message == "first" })
            held.answers.forEach { it.complete(true) }
            f.run(1)
            assertEquals(2, m.current.sentVersion)
            assertFalse(m.current.sending)
        } finally {
            f.app.authBackend.gate = null
            m.close()
        }
    }

    @Test
    fun aRetrysEchoWithoutItsIdStillMatches() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        try {
            m.setActive(true)
            f.run(1)
            holdCreates(f)
            m.setText("again")
            m.send()
            f.run(ChatThreadModel.SEND_BOUND_MILLIS + 1)
            m.send()
            f.run(1)
            // a server that echoes without the pending id: matched by text within the window of the retry
            f.chat.echoOwn("ch-ecency", "again", "")
            f.run(1)
            assertEquals(1, m.current.sentVersion)
            assertFalse(m.current.sending)
        } finally {
            f.app.authBackend.gate = null
            m.close()
        }
    }

    @Test
    fun aReTapAfterTheBoundIsTheSameCreate() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        val held = holdCreates(f)
        val answers = held.answers
        m.setText("slow one")
        m.send()
        f.run(ChatThreadModel.SEND_BOUND_MILLIS + 1)
        assertFalse(m.current.sending)
        m.send()
        f.run(1)
        assertTrue(m.current.sending)
        answers.forEach { it.complete(true) }
        f.run(1)
        f.idle()
        f.app.authBackend.gate = null
        val ids = held.ids
        assertEquals(2, ids.size)
        assertEquals(1, ids.toSet().size, "one pending post id: the server keeps one post")
        assertEquals(1, m.current.items.count { (it as? ChatMessageItem)?.post?.message == "slow one" })
        assertEquals(1, m.current.sentVersion)
        assertFalse(m.current.sending)
    }

    @Test
    fun aSlowSendEndingDuringTheNextOneLeavesItAlone() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        try {
            m.setActive(true)
            f.run(1)
            val held = holdCreates(f)
            val answers = held.answers
            m.setText("first")
            m.send()
            f.run(ChatThreadModel.SEND_BOUND_MILLIS + 1)
            m.setText("second")
            m.send()
            f.run(1)
            val (first, second) = held.ids[0] to held.ids[1]
            assertTrue(first != second)
            // the first one lands while the second is in flight: counted, but the button and the composer stay the second's
            answers[0].complete(true)
            f.run(1)
            assertEquals(1, m.current.sentVersion)
            assertTrue(m.current.sending, "the second send still holds the button")
            assertEquals("second", m.current.text)
            // the second one's echo is still recognised
            f.chat.echoOwn("ch-ecency", "second", second)
            f.run(1)
            assertEquals(2, m.current.sentVersion)
            assertFalse(m.current.sending)
            assertEquals("", m.current.text)
            answers[1].complete(true)
            f.run(1)
            assertEquals(2, m.current.sentVersion)
            assertEquals(1, m.current.items.count { (it as? ChatMessageItem)?.post?.message == "second" })

            // a slow send that fails during the next one: its toast, nothing taken from the next
            m.setText("third")
            m.send()
            f.run(ChatThreadModel.SEND_BOUND_MILLIS + 1)
            m.setText("fourth")
            m.send()
            f.run(1)
            val fourth = held.ids.last()
            answers[2].complete(false)
            f.run(1)
            assertNotNull(f.env.ui.current.toast, "the third one's failure is told")
            assertTrue(m.current.sending)
            assertEquals("fourth", m.current.text)
            f.chat.echoOwn("ch-ecency", "fourth", fourth)
            f.run(1)
            assertEquals(3, m.current.sentVersion, "the fourth's echo still matches")
            assertFalse(m.current.sending)
            answers[3].complete(true)
            f.run(1)
        } finally {
            f.app.authBackend.gate = null
            m.close()
        }
    }

    @Test
    fun aBanShowsAStandingNoticeAndAnExpiryLessBanAToast() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        val until = f.app.clock() + 2 * 3_600_000
        f.before = { r ->
            if (r.method == "POST" && r.url.endsWith("/posts")) HttpResult(403, """{"error":"operator text","prop":"ecency_chat_banned_until","bannedUntil":$until,"reason":"spray"}""") else null
        }
        m.setText("spam")
        m.send()
        f.run(1)
        assertEquals(
            "You're paused from posting because the same message went to several channels at once. You can still read chat. Posting unlocks in about 2 hours.",
            m.current.banText,
        )
        assertEquals("spam", m.current.text, "the text stays")
        f.run(2 * 3_600_000 + ChatBan.TICK_MILLIS)
        assertNull(m.current.ban, "the notice lifts itself")
        f.before = { r -> if (r.method == "POST" && r.url.endsWith("/posts")) HttpResult(403, """{"error":"x","prop":"ecency_chat_banned_until"}""") else null }
        m.send()
        f.run(1)
        assertEquals(ChatBan.GENERIC, f.env.ui.current.toast?.text)
        assertNull(m.current.ban)
    }

    @Test
    fun liveEventsTypingAndTheLockFollowTheScreen() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        try {
            m.setActive(true)
            f.run(1)
            assertEquals("wss://ecency.com/api/mattermost/websocket?token=demo-chat-token", f.app.relay.opened.last())
            assertTrue(m.current.connected)
            f.chat.incoming("ch-ecency", "live hello")
            f.chat.incoming("ch-photo", "elsewhere")
            f.run(1)
            assertEquals("live hello", (m.current.items.first() as ChatMessageItem).spans.joinToString("") { it.text })
            assertTrue(m.current.items.none { (it as? ChatMessageItem)?.post?.message == "elsewhere" })
            f.chat.typing("ch-ecency", "spike-bob")
            f.run(1)
            assertEquals("spike-bob is typing...", m.current.typing)
            f.run(ChatThreadModel.TYPING_MILLIS + 1)
            assertNull(m.current.typing)
            val first = m.current.items.first() as ChatMessageItem
            f.chat.react("ch-ecency", first.id, "heart", "spike-carol")
            f.run(1)
            assertEquals(1, (m.current.items.first() as ChatMessageItem).reactions.single().count)
            // our own send echoes once
            m.setText("echo me")
            m.send()
            f.run(1)
            assertEquals(1, m.current.items.count { (it as? ChatMessageItem)?.post?.message == "echo me" })
            // composing sends a typing event, at most every 2 s ("echo me" above sent one)
            f.run(ChatThreadModel.TYPING_SEND_MILLIS)
            val typingBefore = f.chat.typingSent.size
            m.setText("a")
            m.setText("ab")
            f.run(1)
            assertEquals(listOf("ch-ecency"), f.chat.typingSent.drop(typingBefore), "one typing event per 2 s")
            f.run(ChatThreadModel.TYPING_SEND_MILLIS)
            m.setText("abc")
            f.run(1)
            assertEquals(2, f.chat.typingSent.size - typingBefore, "the next one after 2 s")
            m.setText("")
            f.run(ChatThreadModel.TYPING_SEND_MILLIS)
            m.setText("")
            f.run(1)
            assertEquals(2, f.chat.typingSent.size - typingBefore, "an empty composer sends none")
            m.setActive(false)
            f.run(1)
            assertFalse(m.current.connected)
        } finally {
            // a failed check must not leave the websocket's heartbeat running into runTest's idle wait
            m.close()
        }
    }

    @Test
    fun theLockAndAnAccountChangeStopTheConversation() = runTest {
        val f = ChatFixture(this, users = listOf(ChatFixture.USER, "spike-yan"), pin = "1234")
        f.start()
        "1234".forEach { f.env.appLock.enterDigit(it.digitToInt()) }
        f.idle()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        try {
            m.setActive(true)
            f.run(1)
            assertTrue(m.current.connected)
            f.env.appLock.onBackground()
            f.app.monotonic += f.env.security.lockTimeoutMillis + 1
            f.env.appLock.onForeground()
            f.run(1)
            assertTrue(f.env.appLock.current.hidesContent)
            assertFalse(m.current.connected, "no websocket behind the lock")
            "1234".forEach { f.env.appLock.enterDigit(it.digitToInt()) }
            f.run(1)
            f.run(1)
            assertTrue(m.current.connected)
            f.env.session.switchTo("spike-yan")
            f.run(1)
            f.run(1)
            assertNull(m.current.account)
            assertTrue(m.current.items.isEmpty())
            assertFalse(m.current.connected)
        } finally {
            // a failed check must not leave the websocket's heartbeat running into runTest's idle wait
            m.close()
        }
    }

    @Test
    fun aConversationThatBecomesActiveBehindTheLockOpensNoWebsocket() = runTest {
        val f = ChatFixture(this, pin = "1234")
        f.start()
        "1234".forEach { f.env.appLock.enterDigit(it.digitToInt()) }
        f.idle()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        try {
            f.env.appLock.onBackground()
            f.app.monotonic += f.env.security.lockTimeoutMillis + 1
            f.env.appLock.onForeground()
            f.run(1)
            assertTrue(f.env.appLock.current.hidesContent)
            val opened = f.app.relay.opened.size
            m.setActive(true)
            f.run(1)
            f.run(1)
            assertEquals(opened, f.app.relay.opened.size, "no websocket while the lock hides content")
            assertFalse(m.current.connected)
            "1234".forEach { f.env.appLock.enterDigit(it.digitToInt()) }
            f.run(1)
            f.run(1)
            assertTrue(m.current.connected, "it connects once unlocked")
        } finally {
            m.close()
        }
    }

    @Test
    fun afterAnAccountSwitchNothingIsAskedWithTheOldAccountsSession() = runTest {
        val f = ChatFixture(this, users = listOf(ChatFixture.USER, "spike-yan"))
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        try {
            val first = m.current.items.filterIsInstance<ChatMessageItem>().first()
            m.setText("after the switch")
            val before = f.chatRequests().size
            f.env.session.switchTo("spike-yan")
            // the session already names the other account; the model has not heard of it yet
            m.send()
            m.loadMore()
            m.react(first, "heart")
            m.setActive(true)
            f.run(1)
            f.run(1)
            val after = f.chatRequests().drop(before).map { "${it.method} ${it.url.substringAfter("/api/mattermost/")}" }
            assertTrue(after.none { it.startsWith("POST channels/ch-ecency") || it.startsWith("GET channels/ch-ecency") || it.contains("reactions") }, "asked after the switch: $after")
            assertTrue(f.app.relay.opened.isEmpty(), "no websocket for the old account")
            assertNull(m.current.account)
        } finally {
            m.close()
        }
    }

    @Test
    fun directMessagesWarnUntilTheViewerWritesAndGroupsTrackRenames() = runTest {
        val f = ChatFixture(this)
        f.start()
        val dm = thread(f, "ch-dm-alice", "D", "spike-alice")
        assertTrue(dm.current.showDmWarning)
        assertEquals("spike-alice", dm.current.title)
        assertEquals(listOf(ChatMessageAction.REPLY, ChatMessageAction.COPY, ChatMessageAction.SHARE, ChatMessageAction.TRANSLATE, ChatMessageAction.PIN), dm.actions(dm.current.items.first() as ChatMessageItem))
        dm.dismissDmWarning()
        assertFalse(dm.current.showDmWarning)
        assertFalse(thread(f, "ch-dm-alice", "D").current.showDmWarning, "closed for the run")

        val group = thread(f, "ch-group", "G")
        assertEquals("Book club", group.current.title)
        assertTrue(group.current.groupOwner)
        val notice = group.current.items.filterIsInstance<ChatNoticeItem>().single()
        assertEquals("spike-bob renamed the group to \"Book club\"", notice.text)
        val rename = f.env.chat.renameModel("ch-group", "Book club")
        rename.setValue("")
        rename.save()
        f.idle()
        assertEquals("spike-alice, spike-bob, spike-carol", group.current.title)
        val rows = group.members()
        assertEquals(4, rows.size)
        assertEquals("spike-alice", rows.first().username, "online first")
        assertEquals("4 members • 1 online", group.membersSubtitle(rows))
    }

    @Test
    fun mentionsLinkCardsAndImagesInTheComposer() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        m.setText("hi @spike-a")
        assertEquals(listOf("spike-alice"), m.current.mentions.map { it.name })
        m.selectMention(m.current.mentions.single())
        assertEquals("hi @spike-alice ", m.current.text)
        m.setText("see https://ecency.com/@spike-alice/fake-post-1")
        f.idle()
        assertEquals("Fixture post", m.current.preview?.title)
        assertEquals("Made-up summary", m.current.preview?.summary)
        m.send()
        f.idle()
        val props = f.body(f.chatRequests().last { it.method == "POST" && it.url.endsWith("/posts") })["props"]!!.jsonObject
        assertEquals("https://ecency.com/@spike-alice/fake-post-1", props["link_url"]!!.jsonPrimitive.content)
        assertNull(m.current.preview)
        // another site is never asked while typing
        m.setText("see https://example.org/page")
        f.idle()
        assertNull(m.current.preview)

        f.env.imageTransport = ImageUploadTransport { _, _, _, _ -> HttpResult(200, """{"url":"https://images.ecency.com/DQmFake/pic.png"}""") }
        m.setText("look")
        m.attach(PickedImage(byteArrayOf(1, 2, 3), "pic.png", "image/png"))
        f.idle()
        assertEquals("look https://images.ecency.com/DQmFake/pic.png", m.current.text)
        assertFalse(m.current.uploading)
        f.env.imageTransport = ImageUploadTransport { _, _, _, _ -> HttpResult(413, "") }
        m.attach(PickedImage(byteArrayOf(1), "big.png", "image/png"))
        f.idle()
        assertEquals("File size too big, please resize or upload smaller image", m.current.alert)
    }

    @Test
    fun whoReactedListsTheViewerFirstAndLooksUpStrangers() = runTest {
        val f = ChatFixture(this)
        f.start()
        val m = thread(f, "ch-ecency", "O", "Ecency")
        val reply = m.current.items.filterIsInstance<ChatMessageItem>().first { it.reactions.isNotEmpty() }
        m.react(reply, "+1")
        f.idle()
        val item = m.current.items.first { it.id == reply.id } as ChatMessageItem
        val r = m.reactorsModel(item, "+1").also { it.start(); f.idle() }
        assertEquals(listOf("+1", "heart"), r.current.tabs.map { it.emojiName })
        assertEquals(listOf("You", "@spike-alice", "@spike-bob"), r.current.rows.map { it.label })
        r.select("heart")
        assertEquals(listOf("@spike-dave"), r.current.rows.map { it.label })
        assertEquals("spike-dave", r.current.rows.single().account)
    }
}
