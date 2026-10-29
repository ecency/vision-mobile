package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.broadcast.NotificationOps
import com.ecency.core.api.ecency.ActivityNotification
import com.ecency.core.api.ecency.NotificationTime
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One call the scripted Ecency private API received. */
class ApiCall(val path: String, val body: JsonObject) {
    val code: String get() = body["code"]?.jsonPrimitive?.content.orEmpty()
    fun text(key: String): String? = body[key]?.jsonPrimitive?.content
}

/** The notification endpoints of the Ecency private API, scripted. Access tokens are `token-<user>`. */
class FakeNotificationsBackend {
    val calls = mutableListOf<ApiCall>()

    /** Rows by account, newest first; a page is [pageSize] rows after `since`. */
    val rows = mutableMapOf<String, List<String>>()
    var pageSize = 3
    val unread = mutableMapOf<String, Int>()
    var listStatus = 200
    var markStatus = 200
    var markUnread: Int? = null

    /** HTTP status of `register-device` by username (200 when absent). */
    val registerStatus = mutableMapOf<String, Int>()
    var leaderboard = """[{"_id":"spike-carol","count":3,"points":"10.000"},{"_id":"spike-dave","count":9,"points":"7.500","quests_done":true}]"""

    fun paths(): List<String> = calls.map { it.path }
    fun of(path: String): List<ApiCall> = calls.filter { it.path == path }

    fun answer(request: AuthHttpRequest): HttpResult {
        val path = request.url.substringAfter("/private-api/")
        val body = request.body?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        val call = ApiCall(path, body)
        calls += call
        val user = call.code.substringAfter('-')
        return when {
            path == "notifications" -> {
                if (listStatus != 200) return HttpResult(listStatus, "{}")
                val all = rows[user].orEmpty()
                val since = call.text("since").orEmpty()
                val from = if (since.isEmpty()) 0 else all.indexOfFirst { "\"id\":\"$since\"" in it } + 1
                HttpResult(200, all.drop(from).take(pageSize).joinToString(",", "[", "]"))
            }
            path == "notifications/unread" -> HttpResult(200, """{"count":${unread[user] ?: 0}}""")
            path == "notifications/mark" -> if (markStatus == 200) HttpResult(200, markUnread?.let { """{"unread":$it}""" } ?: "{}") else HttpResult(markStatus, "{}")
            path == "register-device" -> HttpResult(registerStatus[call.text("username")] ?: 200, "{}")
            path.startsWith("leaderboard/") -> HttpResult(200, leaderboard)
            else -> HttpResult(404, "{}")
        }
    }

    companion object {
        fun vote(id: String, read: Int = 0, ts: Long = NotificationGoldens.NOW / 1000 - 120) =
            """{"id":"$id","type":"vote","source":"spike-bob","read":$read,"timestamp":$ts,"author":"spike-alice","permlink":"post-$id","title":"Title $id","weight":10000}"""
    }
}

class NotificationFixture(
    private val scope: TestScope,
    users: List<String> = listOf(ALICE),
    pin: String? = null,
) {
    val app = TestApp(scope) { request ->
        TestApp.chain(request) ?: when (request.method) {
            "condenser_api.broadcast_transaction" -> FakeTransport.result(request, "{}")
            "condenser_api.get_accounts" -> FakeTransport.result(request, "[]")
            else -> HttpResult(500, "")
        }
    }
    val backend = FakeNotificationsBackend()
    val env get() = app.env
    val model get() = app.env.notifications

    init {
        val vault = KeyVault(app.store)
        users.forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$it")) }
        users.firstOrNull()?.let { vault.setCurrentUsername(it) }
        if (pin != null) PinLock(vault, 16).setPin(pin)
        app.authBackend.privateApi = { backend.answer(it) }
        // the token endpoint is not part of these tests: stored tokens stay as they are
        app.authBackend.down = false
        app.authBackend.rest = { request -> if ("/private-api/" in request.url) null else HttpResult(503, "{}") }
        app.now = NotificationGoldens.NOW
    }

    fun start() {
        env.session.start()
        env.appLock.start()
        env.notifications.start()
        idle()
    }

    fun idle() = scope.testScheduler.advanceUntilIdle()

    /** Runs what is ready without moving the clock (a held-back answer must not run into the request timeout). */
    fun step() = scope.testScheduler.runCurrent()

    fun unlock(pin: String = "1234") {
        pin.forEach { env.appLock.enterDigit(it.digitToInt()) }
        idle()
    }

    companion object {
        const val ALICE = "spike-alice"
        const val BOB = "spike-bob"
    }
}

class NotificationsTest {
    private val alice = NotificationFixture.ALICE
    private val bob = NotificationFixture.BOB

    private fun parse(json: String) = ActivityNotification.fromJson(Json.parseToJsonElement(json).jsonObject)!!

    private fun text(json: String) = NotificationFormat.text(parse(json), NotificationGoldens.HIVE_PER_MVESTS)

    // ---- formatting (RN notificationLineView) ----

    @Test
    fun delegationAndWeightTextsMatchRn() {
        for ((amount, expected) in NotificationGoldens.delegations) {
            assertEquals("delegated $expected", text("""{"id":"1","type":"delegations","source":"a","amount":"$amount"}"""), amount)
        }
        for ((weight, expected) in NotificationGoldens.weights) {
            assertEquals("voted $expected", text("""{"id":"1","type":"vote","source":"a","weight":$weight}"""), weight.toString())
        }
        // numbers arrive as JSON numbers too
        assertEquals("delegated 5B RC", text("""{"id":"1","type":"delegations","source":"a","amount":5000000000}"""))
        assertEquals("delegated", text("""{"id":"1","type":"delegations","source":"a"}"""), "RN crashes without an amount; here no extra")
    }

    @Test
    fun timestampsInEveryFormTheApiSendsMatchRn() {
        for (case in NotificationGoldens.times) {
            for (form in case.forms) {
                val millis = NotificationTime.parse(form)
                assertEquals(NotificationGoldens.NOW - case.secondsAgo * 1000, millis, form)
                assertEquals(case.text, NotificationTime.relative(millis, NotificationGoldens.NOW), form)
            }
        }
        assertNull(NotificationTime.relative(NotificationGoldens.NOW - 7 * 86_400_000L, NotificationGoldens.NOW), "from 7 days on the platform shows a date")
        assertNull(NotificationTime.relative(0, NotificationGoldens.NOW))
        assertEquals(0, NotificationTime.parse("not a date"))
        assertEquals(0, NotificationTime.parse(""))
        assertEquals(NotificationTime.parse("2026-10-06T12:00:00Z"), NotificationTime.parse("2026-10-06T14:00:00+02:00"))
        // `ts` is the fallback for `timestamp`
        assertEquals(NotificationGoldens.NOW, parse("""{"id":"1","type":"vote","ts":${NotificationGoldens.NOW / 1000}}""").timestampMillis)
    }

    @Test
    fun thumbnailsFollowRnsTypeTable() {
        for (case in NotificationGoldens.images) {
            val n = parse("""{"id":"1","type":"${case.type}","source":"a","img_url":"${case.imgUrl}","parent_img_url":"${case.parentImgUrl}"}""")
            assertEquals(case.url, NotificationFormat.row(n, 0.0, TestApp.PROXY).imageUrl, case.type)
        }
        val hostile = parse("""{"id":"1","type":"mention","source":"a","img_url":"javascript:alert(1)"}""")
        assertEquals("", NotificationFormat.row(hostile, 0.0, TestApp.PROXY).imageUrl)
    }

    @Test
    fun everyTypeUsesRnCopy() {
        fun t(fields: String) = text("""{"id":"1","source":"spike-bob",$fields}""")
        assertEquals("voted 50%", t(""""type":"vote","weight":5000"""))
        assertEquals("downvoted -100%", t(""""type":"unvote","weight":-10000"""))
        assertEquals("replied to", t(""""type":"reply""""))
        assertEquals("mentioned in", t(""""type":"mention""""))
        assertEquals("followed you", t(""""type":"follow""""))
        assertEquals("unfollowed you", t(""""type":"unfollow""""))
        assertEquals("ignored you", t(""""type":"ignore""""))
        assertEquals("blacklisted you", t(""""type":"blacklist""""))
        assertEquals("reblogged", t(""""type":"reblog""""))
        assertEquals("transferred 1.000 HIVE", t(""""type":"transfer","amount":"1.000 HIVE""""))
        assertEquals("made new post", t(""""type":"favorites""""))
        assertEquals("replied to bookmarked content", t(""""type":"bookmarks""""))
        assertEquals("Time to earn more Points", t(""""type":"spin""""))
        assertEquals("Be active! Write a post, continue earning", t(""""type":"inactive""""))
        assertEquals("joined with your referral, welcome them", t(""""type":"referral""""))
        assertEquals("published a scheduled post", t(""""type":"scheduled_published""""))
        assertEquals("You checked in 1 day this week. Keep it up!", t(""""type":"checkin","count":1"""))
        assertEquals("You checked in 3 days this week. Keep it up!", t(""""type":"checkin","count":"3""""))
        // a plural message now; the server's English `suffix` is ignored
        assertEquals("You created 2 posts this month. Keep it up!", t(""""type":"monthly_posts","count":2,"suffix":"z""""))
        assertEquals("You created 1 post this month. Keep it up!", t(""""type":"monthly_posts","count":1"""))
        assertEquals("1 new post in #hive", t(""""type":"tags","tag":"hive","count":1"""))
        assertEquals("posted in #photography", t(""""type":"tags","tags":["photography","art"],"permlink":"p""""))
        assertEquals("12 new posts in #hive", t(""""type":"tags","tag":"hive","count":12"""))
        assertEquals("Payout received: \$1.25", t(""""type":"payouts","amount_usd":"1.25","amount":"9""""))
        assertEquals("Payout received: \$9", t(""""type":"payouts","amount":"9""""))
        assertEquals("You earned \$4.20 this week - \$3.00 from posts, \$1.20 from curation.", t(""""type":"weekly_earnings","total_usd":"4.20","author_usd":"3.00","curation_usd":"1.20""""))
        assertEquals("You earned \$0 this week from your posts and curation.", t(""""type":"weekly_earnings""""))
        // a type without copy shows its name
        assertEquals("some new type", t(""""type":"some_new_type""""))
    }

    @Test
    fun accountUpdatePicksTheMostSevereMessage() {
        fun t(fields: String) = text("""{"id":"1","type":"account_update","source":"spike-alice"$fields}""")
        assertEquals("Your account or profile were updated", t(""))
        assertEquals("Your owner key was changed. If this was not you, secure your account immediately.", t(""","keys_changed":["posting","owner"]"""))
        assertEquals("Your active key was changed. If this was not you, secure your account.", t(""","keys_changed":["active","posting"]"""))
        assertEquals("Your posting key was changed. If this was not you, secure your account.", t(""","keys_changed":["posting"]"""))
        assertEquals(
            "@spike-x, @spike-y was granted owner authority on your account. If this was not you, secure your account immediately.",
            t(""","accounts_granted":[{"account":"spike-x","authority":"posting"},{"account":"spike-y","authority":"owner"}]"""),
        )
        assertEquals(
            "@spike-x was granted active authority on your account. If you did not authorize this, revoke it.",
            t(""","accounts_granted":[{"account":"spike-x","authority":"active"}]"""),
        )
        assertEquals(
            "@spike-x can now post and vote on your behalf. If you did not authorize this, revoke it.",
            t(""","accounts_granted":[{"account":"spike-x","authority":"posting"}]"""),
        )
    }

    @Test
    fun secondLineFollowsRnsRules() {
        fun more(fields: String) = NotificationFormat.row(parse("""{"id":"1","source":"spike-bob",$fields}"""), 0.0, TestApp.PROXY).moreInfo
        assertEquals("My post", more(""""type":"vote","title":"My post","permlink":"my-post""""))
        assertEquals("my-post", more(""""type":"reblog","permlink":"my-post""""))
        assertEquals("Parent", more(""""type":"reply","parent_title":"Parent","parent_permlink":"pp","permlink":"p""""))
        assertEquals("pp", more(""""type":"bookmarks","parent_permlink":"pp","permlink":"p""""))
        assertEquals("p", more(""""type":"reply","permlink":"p""""))
        assertEquals("In a post", more(""""type":"mention","post":true,"title":"In a post","parent_title":"x""""))
        assertEquals("In a comment", more(""""type":"mention","post":false,"title":"x","parent_title":"In a comment""""))
        assertEquals("", more(""""type":"tags","tag":"hive","count":3,"title":"x""""), "a tag bundle has no post line")
        assertEquals("", more(""""type":"follow","title":"x""""))
    }

    @Test
    fun rowTapsRouteLikeRnAndRefuseHostileTargets() {
        fun target(fields: String) = NotificationFormat.target(parse("""{"id":"1","source":"spike-bob",$fields}"""))
        assertEquals(NavEntry.screen(AppScreen.Post("spike-carol", "a-post")), target(""""type":"vote","author":"spike-carol","permlink":"a-post""""))
        assertEquals(NavEntry.screen(AppScreen.Post("spike-bob", "sched")), target(""""type":"scheduled_published","permlink":"sched""""))
        for (type in listOf("follow", "unfollow", "ignore", "blacklist")) {
            assertEquals(NavEntry.screen(AppScreen.Profile("spike-dave")), target(""""type":"$type","follower":"spike-dave""""), type)
        }
        assertEquals(NavEntry.screen(AppScreen.Post("spike-bob", "tagged")), target(""""type":"tags","permlink":"tagged""""))
        assertEquals(NavEntry.screen(AppScreen.TagResult("photography")), target(""""type":"tags","tag":"photography","count":4"""))
        assertEquals(NavEntry.tab(AppTab.WALLET), target(""""type":"transfer","amount":"1.000 HIVE""""))
        assertEquals(NavEntry.tab(AppTab.WALLET), target(""""type":"weekly_earnings""""))
        assertEquals(RouteName.BOOST, target(""""type":"spin"""")!!.screens.single().route)
        assertEquals(RouteName.EDITOR, target(""""type":"inactive"""")!!.screens.single().route)
        assertNull(target(""""type":"account_update""""))
        assertNull(target(""""type":"delegations","amount":"1 VESTS""""))
        // hostile values never become a destination
        assertNull(target(""""type":"vote","author":"Spike/../x","permlink":"a-post""""))
        assertNull(target(""""type":"vote","author":"spike-carol","permlink":"a post?x=1""""))
        assertNull(target(""""type":"follow","follower":"https://evil.example""""))
        assertNull(target(""""type":"tags","tag":"../../settings","count":2"""))
        assertNull(target(""""type":"tags","tag":"UPPER","count":2"""))
    }

    @Test
    fun setLastReadOpsMatchTheSdk() {
        val ops = NotificationOps.setLastRead(alice, NotificationGoldens.NOW)
        assertEquals(Json.parseToJsonElement(NotificationGoldens.SET_LAST_READ), ops)
        // the JSON string inside is what gets signed: byte for byte
        val expected = Json.parseToJsonElement(NotificationGoldens.SET_LAST_READ).jsonArray.map { it.jsonArray[1].jsonObject["json"]!!.jsonPrimitive.content }
        assertEquals(expected, ops.map { it.jsonArray[1].jsonObject["json"]!!.jsonPrimitive.content })
    }

    // ---- requests (the SDK's paths and bodies) ----

    @Test
    fun requestsCarryTheSdksPathsAndFields() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"))
        val api = f.env.notificationsApi
        api.notifications(alice, null, "")
        api.notifications(alice, "rvotes", "n1")
        api.unreadCount(alice)
        api.markRead(alice, "n1")
        api.markRead(alice, null)
        api.registerDevice("token-$alice", alice, "device-token", "fcm-android", true, listOf(1, 2, 23))
        api.leaderboard("week")

        val sent = f.app.authBackend.sent
        assertTrue(sent.all { it.url.startsWith("https://ecency.com/private-api/") })
        assertEquals(listOf("POST", "POST", "POST", "POST", "POST", "POST", "GET"), sent.map { it.method })
        assertEquals("https://ecency.com/private-api/leaderboard/week", sent.last().url)
        val c = f.backend.calls
        assertEquals(setOf("code", "since"), c[0].body.keys)
        assertEquals("", c[0].text("since"))
        assertEquals("token-$alice", c[0].code)
        assertEquals(setOf("code", "filter", "since"), c[1].body.keys)
        assertEquals("rvotes", c[1].text("filter"))
        assertEquals("n1", c[1].text("since"))
        assertEquals("notifications/unread", c[2].path)
        assertEquals(setOf("code"), c[2].body.keys)
        assertEquals("notifications/mark", c[3].path)
        assertEquals(setOf("code", "id"), c[3].body.keys)
        assertEquals(setOf("code"), c[4].body.keys, "mark all sends no id")
        assertEquals("register-device", c[5].path)
        assertEquals(setOf("code", "username", "token", "system", "allows_notify", "notify_types"), c[5].body.keys)
        assertEquals("""{"username":"spike-alice","token":"device-token","system":"fcm-android","allows_notify":1,"notify_types":[1,2,23],"code":"token-spike-alice"}""", c[5].body.toString())
    }

    // ---- list ----

    @Test
    fun loadsPagesBySinceAndStopsWhenAPageAddsNothing() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = (1..7).map { FakeNotificationsBackend.vote("n$it") }
        f.start()
        assertFalse(f.model.current.loaded, "nothing is listed before the tab is shown")
        assertEquals(listOf("notifications/unread"), f.backend.paths())

        f.model.show()
        assertTrue(f.model.current.isLoading)
        f.idle()
        var s = f.model.current
        assertEquals(listOf("n1", "n2", "n3"), s.items.map { it.id })
        assertEquals(alice, s.account)
        assertEquals("spike-bob", s.items[0].source)
        assertEquals("voted 100%", s.items[0].text)
        assertEquals("Title n1", s.items[0].moreInfo)
        assertEquals("https://i.ecency.com/u/spike-bob/avatar/128", s.items[0].avatarUrl)
        assertFalse(s.endReached)

        f.model.loadMore(); f.idle()
        f.model.loadMore(); f.idle()
        s = f.model.current
        assertEquals(7, s.items.size)
        assertFalse(s.endReached, "a short page is not the end for RN either: the next one decides")
        f.model.loadMore(); f.idle()
        assertTrue(f.model.current.endReached)
        val before = f.backend.of("notifications").size
        f.model.loadMore(); f.idle()
        assertEquals(before, f.backend.of("notifications").size, "nothing is asked past the end")
        assertEquals(listOf("", "n3", "n6", "n7"), f.backend.of("notifications").map { it.text("since") })
    }

    @Test
    fun aServerThatRepeatsItselfCannotPageForever() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"))
        f.backend.pageSize = 1
        // every page is the first page again (RN's own cursor does this at the end of the list)
        f.app.authBackend.privateApi = { request ->
            f.backend.answer(request).let { if (request.url.endsWith("/notifications")) HttpResult(200, "[${FakeNotificationsBackend.vote("n1")}]") else it }
        }
        f.start()
        f.model.show(); f.idle()
        f.model.loadMore(); f.idle()
        assertEquals(1, f.model.current.items.size, "no duplicate rows")
        assertTrue(f.model.current.endReached)
        val asked = f.backend.of("notifications").size
        f.model.loadMore(); f.idle()
        assertEquals(asked, f.backend.of("notifications").size)
    }

    @Test
    fun olderPagesKeepComingAndOnlyTheLastTenStayInMemory() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = (1..40).map { FakeNotificationsBackend.vote("n$it") }
        f.backend.pageSize = 2
        f.start()
        f.model.show(); f.idle()
        repeat(9) { f.model.loadMore(); f.idle() }
        assertEquals(20, f.model.current.items.size)
        assertFalse(f.model.current.endReached, "RN's maxPages is a window, not an end")

        repeat(5) { f.model.loadMore(); f.idle() }
        var s = f.model.current
        assertEquals(15, s.pages)
        assertEquals((11..30).map { "n$it" }, s.items.map { it.id }, "the pages loaded first left memory")
        assertFalse(s.endReached)

        repeat(6) { f.model.loadMore(); f.idle() }
        s = f.model.current
        assertEquals("n40", s.items.last().id, "the whole history is reachable")
        assertEquals(20, s.items.size)
        assertTrue(s.endReached, "the empty page after the oldest row is the end")

        // a refresh starts from the newest again
        f.model.refresh(); f.idle()
        assertEquals(listOf("n1", "n2"), f.model.current.items.map { it.id })
    }

    @Test
    fun aServerThatStartsOverAfterRowsLeftTheWindowStillEnds() = runTest {
        val f = NotificationFixture(this)
        // 12 pages of 2, then the server answers with its first page again for every later cursor
        f.backend.rows[alice] = (1..24).map { FakeNotificationsBackend.vote("n$it") }
        f.backend.pageSize = 2
        f.app.authBackend.privateApi = { request ->
            val answer = f.backend.answer(request)
            if (request.url.endsWith("/notifications") && answer.body == "[]") {
                HttpResult(200, "[${FakeNotificationsBackend.vote("n1")},${FakeNotificationsBackend.vote("n2")}]")
            } else {
                answer
            }
        }
        f.start()
        f.model.show(); f.idle()
        repeat(20) { f.model.loadMore(); f.idle() }
        assertTrue(f.model.current.endReached, "rows seen before, even ones no longer in memory, add nothing")
        assertEquals(13, f.backend.of("notifications").size)
    }

    @Test
    fun showImagesIsKeptOnTheDevice() = runTest {
        val f = NotificationFixture(this)
        f.start()
        assertTrue(f.model.current.showImages)
        f.model.setShowImages(false)
        assertFalse(f.model.current.showImages)
        assertEquals("0", f.env.prefs.getString("content.showImages"))
        // survives an account change and a filter change
        f.model.selectFilter(NotificationFilter.VOTES)
        f.env.session.signOut(); f.idle()
        assertFalse(f.model.current.showImages)
        f.model.setShowImages(true)
        assertNull(f.env.prefs.getString("content.showImages"))
    }

    // ---- mark from a tapped push ----

    @Test
    fun aTappedPushMarksItsIdHoweverFarBackTheNotificationIs() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"), FakeNotificationsBackend.vote("n2", read = 1))
        f.backend.unread[alice] = 3
        f.backend.markUnread = 2
        f.start()
        // tapped late: the notification is nowhere near the first page, and the list was never opened
        f.model.markFromPush("old-activity-9"); f.idle()
        assertEquals("old-activity-9", f.backend.of("notifications/mark").single().text("id"))
        assertTrue(f.backend.of("notifications").isEmpty(), "no list request just to check")
        assertEquals(2, f.env.badges.current.notifications, "the server's count")

        // a row the list already shows as read is not sent again
        f.model.show(); f.idle()
        f.model.markFromPush("n2"); f.idle()
        assertEquals(1, f.backend.of("notifications/mark").size)
        f.model.markFromPush("n1"); f.idle()
        assertEquals("n1", f.backend.of("notifications/mark").last().text("id"))
        assertTrue(f.model.current.items[0].read)
    }

    @Test
    fun aRequestThatNeverAnswersTimesOut() = runTest {
        val f = NotificationFixture(this)
        f.app.authBackend.gate = { CompletableDeferred<Unit>().await() }
        f.start()
        f.model.show()
        testScheduler.advanceTimeBy(com.ecency.core.api.ecency.NotificationsApi.TIMEOUT_MILLIS - 1_000)
        assertTrue(f.model.current.isLoading)
        testScheduler.advanceTimeBy(2_000)
        assertFalse(f.model.current.isLoading)
        assertNotNull(f.model.current.error)
        assertTrue(f.model.current.error!!.retryable)
    }

    @Test
    fun filterChangeReloadsWithTheApiFilter() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"))
        f.start()
        f.model.show(); f.idle()
        f.model.selectFilter(NotificationFilter.VOTES)
        assertTrue(f.model.current.items.isEmpty(), "the other filter's rows are not shown while loading")
        assertTrue(f.model.current.isLoading)
        f.idle()
        assertEquals("rvotes", f.backend.of("notifications").last().text("filter"))
        assertNull(f.backend.of("notifications").first().text("filter"), "All sends no filter")
        assertEquals(NotificationFilter.entries.map { it.key }, listOf(
            "activities", "replies", "mentions", "rvotes", "follows", "reblogs", "transfers", "delegations", "nfavorites",
            "nbookmarks", "tags", "payouts", "scheduled_published", "account_updates", "weekly_earnings",
        ))
        assertEquals("SCHEDULED POSTS", NotificationFilter.SCHEDULED_PUBLISHED.title)
    }

    @Test
    fun aFailedFirstLoadShowsAnErrorAndAFailedLoadMoreKeepsTheRows() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = (1..5).map { FakeNotificationsBackend.vote("n$it") }
        f.backend.listStatus = 503
        f.start()
        f.model.show(); f.idle()
        var s = f.model.current
        assertNotNull(s.error)
        assertTrue(s.error!!.retryable)
        assertFalse(s.isEmpty, "a request that never arrived is not an empty inbox")

        f.backend.listStatus = 200
        f.model.retry(); f.idle()
        assertEquals(3, f.model.current.items.size)
        assertNull(f.model.current.error)

        f.backend.listStatus = 500
        f.model.loadMore(); f.idle()
        s = f.model.current
        assertEquals(3, s.items.size)
        assertNull(s.error)
        assertFalse(s.isLoadingMore)
    }

    @Test
    fun emptyInboxIsNotAnError() = runTest {
        val f = NotificationFixture(this)
        f.start()
        f.model.show(); f.idle()
        assertTrue(f.model.current.isEmpty)
        assertNull(f.model.current.error)
    }

    @Test
    fun signedOutNothingIsRequested() = runTest {
        val f = NotificationFixture(this, users = emptyList())
        f.start()
        f.model.show()
        f.model.refresh()
        f.model.refreshUnread()
        f.model.onForeground()
        f.idle()
        assertTrue(f.backend.calls.isEmpty())
        assertNull(f.model.current.account)
        assertFalse(f.model.current.isLoading)
    }

    @Test
    fun nothingIsRequestedOrShownWhileLocked() = runTest {
        val f = NotificationFixture(this, pin = "1234")
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"))
        f.backend.unread[alice] = 4
        f.start()
        assertTrue(f.env.appLock.current.hidesContent)
        f.model.show()
        f.model.refresh()
        f.model.refreshUnread()
        f.model.onForeground()
        f.idle()
        assertTrue(f.backend.calls.isEmpty(), "no request leaves while the lock is on")
        assertTrue(f.model.current.items.isEmpty())
        assertEquals(0, f.env.badges.current.notifications)

        f.unlock()
        assertEquals(listOf("n1"), f.model.current.items.map { it.id }, "the tab that was asked for loads after the unlock")
        assertEquals(4, f.env.badges.current.notifications)
    }

    @Test
    fun relockStopsRequestsAndTheUnlockRefreshesTheCount() = runTest {
        val f = NotificationFixture(this, pin = "1234")
        f.backend.unread[alice] = 1
        f.start()
        f.unlock()
        assertEquals(1, f.model.current.unread)
        f.env.appLock.onBackground()
        f.app.monotonic += 61_000
        f.env.appLock.onForeground()
        f.idle()
        assertTrue(f.env.appLock.current.isLocked)
        f.backend.unread[alice] = 6
        val before = f.backend.calls.size
        f.model.onForeground(); f.idle()
        assertEquals(before, f.backend.calls.size)
        f.unlock()
        assertEquals(6, f.model.current.unread)
    }

    // ---- unread count ----

    @Test
    fun unreadCountFeedsTheBadgeAndAGrowingCountReloadsTheList() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"))
        f.backend.unread[alice] = 2
        f.start()
        assertEquals(2, f.model.current.unread)
        assertEquals("2", f.env.badges.current.text(BadgeKind.NOTIFICATION))
        f.model.show(); f.idle()
        assertEquals(1, f.backend.of("notifications").size)

        // the same count again: no reload
        f.model.onForeground(); f.idle()
        assertEquals(1, f.backend.of("notifications").size)

        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n0"), FakeNotificationsBackend.vote("n1"))
        f.backend.unread[alice] = 3
        f.model.refreshUnread(); f.idle()
        assertEquals(3, f.env.badges.current.notifications)
        assertEquals(listOf("n0", "n1"), f.model.current.items.map { it.id }, "new activity shows without a pull")

        f.backend.unread[alice] = 150
        f.model.refreshUnread(); f.idle()
        assertEquals("99+", f.env.badges.current.text(BadgeKind.NOTIFICATION))
        assertEquals("99+", Badges(notifications = 99).text(BadgeKind.NOTIFICATION), "RN shows 99+ from 99")
        assertEquals("98", Badges(notifications = 98).text(BadgeKind.NOTIFICATION))
    }

    @Test
    fun aFailedCountKeepsTheLastKnownOne() = runTest {
        val f = NotificationFixture(this)
        f.backend.unread[alice] = 5
        f.start()
        f.app.authBackend.privateApi = { HttpResult(502, "") }
        f.model.refreshUnread(); f.idle()
        assertEquals(5, f.env.badges.current.notifications)
        f.app.authBackend.privateApi = { HttpResult(200, """{"count":"oops"}""") }
        f.model.refreshUnread(); f.idle()
        assertEquals(5, f.env.badges.current.notifications, "an answer without a count changes nothing")
    }

    @Test
    fun pullToRefreshAlsoRefreshesTheBadge() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"))
        f.start()
        f.model.show(); f.idle()
        f.backend.unread[alice] = 1
        f.model.refresh()
        assertTrue(f.model.current.isRefreshing)
        f.idle()
        assertEquals(1, f.env.badges.current.notifications)
        assertFalse(f.model.current.isRefreshing)
    }

    // ---- account change ----

    @Test
    fun accountSwitchDropsRowsAndCountAtOnceAndIgnoresTheLateAnswer() = runTest {
        val f = NotificationFixture(this, users = listOf(alice, bob))
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("alice-1"))
        f.backend.rows[bob] = listOf(FakeNotificationsBackend.vote("bob-1"))
        f.backend.unread[alice] = 7
        f.backend.unread[bob] = 1
        f.start()
        f.model.show(); f.idle()
        assertEquals(listOf("alice-1"), f.model.current.items.map { it.id })
        assertEquals(7, f.env.badges.current.notifications)

        // alice's next list and count answers are held back (a slow network)
        val held = CompletableDeferred<Unit>()
        f.app.authBackend.gate = { request ->
            val body = request.body.orEmpty()
            // not cancellable, like a response already on the wire: the answer arrives whatever the model cancelled
            if ("token-$alice" in body && ("/notifications" in request.url)) withContext(NonCancellable) { held.await() }
        }
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("alice-2"), FakeNotificationsBackend.vote("alice-1"))
        f.backend.unread[alice] = 9
        f.model.refresh()
        f.step()
        assertTrue(f.model.current.isRefreshing)

        f.env.session.switchTo(bob)
        // before any answer for bob: nothing of alice is left
        val now = f.model.current
        f.step()
        assertEquals(bob, f.model.current.account)
        assertTrue(now.account == bob || now.items.isEmpty())
        assertEquals(listOf("bob-1"), f.model.current.items.map { it.id })
        assertEquals(1, f.env.badges.current.notifications)

        held.complete(Unit)
        f.idle()
        assertEquals(listOf("bob-1"), f.model.current.items.map { it.id }, "alice's late list never lands in bob's inbox")
        assertEquals(1, f.model.current.unread, "nor her count")
        assertEquals(1, f.env.badges.current.notifications)
        assertEquals(bob, f.model.current.account)
    }

    @Test
    fun theStateIsEmptyTheMomentTheAccountChanges() = runTest {
        val f = NotificationFixture(this, users = listOf(alice, bob))
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("alice-1"))
        f.backend.unread[alice] = 7
        f.start()
        f.model.show(); f.idle()
        // bob's answers never come
        f.app.authBackend.gate = { request -> if ("token-$bob" in request.body.orEmpty()) CompletableDeferred<Unit>().await() }
        f.env.session.switchTo(bob)
        f.idle()
        val s = f.model.current
        assertEquals(bob, s.account)
        assertTrue(s.items.isEmpty())
        assertEquals(0, s.unread)
        assertEquals(0, f.env.badges.current.notifications)
        assertNull(f.env.badges.current.text(BadgeKind.NOTIFICATION))
    }

    @Test
    fun signOutOfTheLastAccountClearsEverything() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"))
        f.backend.unread[alice] = 3
        f.start()
        f.model.show(); f.idle()
        f.env.session.signOut()
        f.idle()
        assertNull(f.model.current.account)
        assertTrue(f.model.current.items.isEmpty())
        assertEquals(0, f.env.badges.current.notifications)
        val before = f.backend.calls.size
        f.model.refresh(); f.idle()
        assertEquals(before, f.backend.calls.size)
    }

    // ---- mark as read ----

    @Test
    fun tappingAnUnreadRowMarksItAndReturnsItsTarget() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"), FakeNotificationsBackend.vote("n2", read = 1))
        f.backend.unread[alice] = 4
        f.backend.markUnread = 2
        f.start()
        f.model.show(); f.idle()

        val entry = f.model.open("n1")
        assertEquals(NavEntry.screen(AppScreen.Post(alice, "post-n1")), entry)
        // at once, before the server answered
        assertTrue(f.model.current.items[0].read)
        assertEquals(3, f.model.current.unread)
        assertEquals(3, f.env.badges.current.notifications)
        f.idle()
        assertEquals("n1", f.backend.of("notifications/mark").single().text("id"))
        assertEquals(2, f.model.current.unread, "the server's count replaces the guess")

        f.model.open("n2"); f.idle()
        assertEquals(1, f.backend.of("notifications/mark").size, "a read row is not marked again")
        assertNull(f.model.open("nope"))
    }

    @Test
    fun aSwipeMarksOneRowWithoutOpeningIt() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"), FakeNotificationsBackend.vote("n2", read = 1))
        f.backend.unread[alice] = 4
        f.start()
        f.model.show(); f.idle()

        assertTrue(f.model.markRead("n1"))
        assertTrue(f.model.current.items[0].read)
        assertEquals(3, f.model.current.unread)
        f.idle()
        assertEquals("n1", f.backend.of("notifications/mark").single().text("id"))
        assertFalse(f.model.markRead("n2"), "a read row has nothing to mark")
        assertFalse(f.model.markRead("nope"))
        f.idle()
        assertEquals(1, f.backend.of("notifications/mark").size)
    }

    @Test
    fun theQuickChipsSelectTheirFilterAndTheSelectedOneGoesBackToAll() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"))
        f.start()
        f.model.show(); f.idle()
        assertEquals(listOf(NotificationFilter.REPLIES, NotificationFilter.MENTIONS), NotificationFilter.quick)
        assertTrue(NotificationFilter.quick.all { it in NotificationFilter.entries }, "the chips stay in the menu")

        f.model.toggleQuickFilter(NotificationFilter.REPLIES); f.idle()
        assertEquals(NotificationFilter.REPLIES, f.model.current.filter)
        assertEquals("replies", f.backend.of("notifications").last().text("filter"))
        // another chip switches straight to its filter
        f.model.toggleQuickFilter(NotificationFilter.MENTIONS); f.idle()
        assertEquals(NotificationFilter.MENTIONS, f.model.current.filter)
        assertEquals("mentions", f.backend.of("notifications").last().text("filter"))
        // the selected chip again: back to All, which sends no filter
        f.model.toggleQuickFilter(NotificationFilter.MENTIONS); f.idle()
        assertEquals(NotificationFilter.ACTIVITIES, f.model.current.filter)
        assertNull(f.backend.of("notifications").last().text("filter"))
        // a chip equals the menu: chosen in the menu, the chip shows selected and a tap on it goes back to All
        f.model.selectFilter(NotificationFilter.REPLIES); f.idle()
        f.model.toggleQuickFilter(NotificationFilter.REPLIES); f.idle()
        assertEquals(NotificationFilter.ACTIVITIES, f.model.current.filter)
        assertNull(f.backend.of("notifications").last().text("filter"))
        assertEquals(6, f.backend.of("notifications").size, "every chip tap is one request")
    }

    @Test
    fun aFailedMarkPutsRowAndCountBackAndSaysFail() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"))
        f.backend.unread[alice] = 4
        f.start()
        f.model.show(); f.idle()
        // everything fails, the count re-read too: the rollback alone must restore the count
        f.app.authBackend.privateApi = { HttpResult(500, "") }
        f.model.open("n1")
        assertTrue(f.model.current.items[0].read)
        assertEquals(3, f.model.current.unread)
        f.idle()
        assertFalse(f.model.current.items[0].read)
        assertEquals(4, f.model.current.unread)
        assertEquals(4, f.env.badges.current.notifications)
        assertEquals("Fail!", f.env.ui.current.toast?.text)
    }

    @Test
    fun markAllMarksEveryRowAndWritesSetLastReadOnChain() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"), FakeNotificationsBackend.vote("n2"))
        f.backend.unread[alice] = 2
        f.start()
        f.model.show(); f.idle()

        f.model.markAllRead()
        assertTrue(f.model.current.items.all { it.read })
        assertEquals(0, f.env.badges.current.notifications)
        f.idle()
        assertEquals(setOf("code"), f.backend.of("notifications/mark").single().body.keys)
        val broadcast = f.app.broadcasts().single().body
        assertTrue("setLastRead" in broadcast)
        assertTrue("\"notify\"" in broadcast && "\"ecency_notify\"" in broadcast)
        assertTrue("2026-10-06T12:00:00" in broadcast)
    }

    @Test
    fun markAllDoesNothingOffline() = runTest {
        val f = NotificationFixture(this)
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"))
        f.backend.unread[alice] = 1
        f.start()
        f.model.show(); f.idle()
        f.env.connectivity.setOnline(false)
        f.model.markAllRead(); f.idle()
        assertFalse(f.model.current.items[0].read)
        assertTrue(f.backend.of("notifications/mark").isEmpty())
        assertTrue(f.app.broadcasts().isEmpty())
    }

    @Test
    fun aMarkAnsweredAfterAnAccountSwitchChangesNothingForTheNewAccount() = runTest {
        val f = NotificationFixture(this, users = listOf(alice, bob))
        f.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("n1"))
        f.backend.unread[alice] = 4
        f.backend.unread[bob] = 6
        f.backend.markUnread = 0
        f.start()
        f.model.show(); f.idle()
        val held = CompletableDeferred<Unit>()
        f.app.authBackend.gate = { request -> if (request.url.endsWith("/notifications/mark")) held.await() }
        f.model.open("n1")
        f.step()
        f.env.session.switchTo(bob)
        f.step()
        assertEquals(6, f.model.current.unread)
        held.complete(Unit)
        f.idle()
        assertEquals(6, f.model.current.unread, "alice's answer does not set bob's badge")
        assertEquals(6, f.env.badges.current.notifications)
    }

    // ---- leaderboard ----

    @Test
    fun leaderboardPutsFinishedQuestsFirstOnTheDailyBoardOnly() = runTest {
        val f = NotificationFixture(this, users = emptyList())
        f.start()
        val board = f.env.leaderboardModel()
        board.start(); f.idle()
        board.showEarnHint()
        assertEquals("Earn points by actively participating in Ecency.", f.env.ui.current.toast?.text)
        assertEquals(listOf("spike-dave", "spike-carol"), board.current.users.map { it.username })
        assertTrue(board.current.users[0].questsDone)
        assertEquals("7.500", board.current.users[0].points)
        assertEquals("9", board.current.users[0].count)
        assertTrue(board.current.showsQuestCheck)

        board.select(LeaderboardDuration.WEEK); f.idle()
        assertEquals(listOf("spike-carol", "spike-dave"), board.current.users.map { it.username }, "weekly keeps the ranking")
        assertFalse(board.current.showsQuestCheck)
        assertEquals(listOf("leaderboard/day", "leaderboard/week"), f.backend.paths(), "public: works signed out")
        assertEquals(listOf("DAILY", "WEEKLY", "MONTHLY"), LeaderboardDuration.entries.map { it.label })
    }

    @Test
    fun leaderboardFailureIsAToast() = runTest {
        val f = NotificationFixture(this, users = emptyList())
        f.start()
        f.app.authBackend.privateApi = { HttpResult(500, "") }
        val board = f.env.leaderboardModel()
        board.start(); f.idle()
        assertTrue(board.current.users.isEmpty())
        assertFalse(board.current.isLoading)
        assertTrue(f.env.ui.current.toast!!.text.startsWith("Something went wrong"))
    }
}
