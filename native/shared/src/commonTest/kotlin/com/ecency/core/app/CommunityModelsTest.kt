package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Sent
import com.ecency.core.api.community.CommunityRole
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

/** A scripted hivemind for the community screens; every field can change between calls. */
class CommunityNode {
    var community: String = community()
    var subscriptions = "[]"
    var communities = "[]"
    var subscribers = """[["spike-alice","guest",null,""],["spike-bob","member","Writer",""],["hive-100001","guest",null,""],["spike-admin","guest",null,""]]"""
    var activities = "[]"
    var lookup = "[]"
    var broadcastFails = false

    /** Roles `get_community_context` answers, by account ("guest" otherwise). */
    var roles = mapOf<String, String>()

    /** Holds the next `get_community` answers until completed. */
    var communityGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun handler(): suspend FakeTransport.(Sent) -> HttpResult = { req ->
        when (req.method) {
            "bridge.get_community" -> {
                communityGate?.await()
                FakeTransport.result(req, community)
            }
            "bridge.get_community_context" -> {
                val account = req.params.jsonObject["account"]!!.jsonPrimitive.content
                FakeTransport.result(req, """{"role":"${roles[account] ?: "guest"}","subscribed":false}""")
            }
            "bridge.list_all_subscriptions" -> FakeTransport.result(req, subscriptions)
            "bridge.list_communities" -> FakeTransport.result(req, communities)
            "bridge.list_subscribers" -> FakeTransport.result(req, subscribers)
            "bridge.account_notifications" -> FakeTransport.result(req, activities)
            "condenser_api.lookup_accounts" -> FakeTransport.result(req, lookup)
            "condenser_api.broadcast_transaction" ->
                if (broadcastFails) FakeTransport.error(req, -32003, "Assert Exception:community.is_valid(): community does not exist") else FakeTransport.result(req, "null")
            else -> TestApp.chain(req) ?: HttpResult(500, "no fixture for ${req.method}")
        }
    }

    companion object {
        /** A community whose team has the test user ([TestApp.USER]) as [role] (null: not on the team). */
        fun community(role: String? = "admin", subscribed: Boolean? = null, name: String = "hive-100001", title: String = "Test Square"): String {
            val me = role?.let { """,["${TestApp.USER}","$it",""]""" }.orEmpty()
            val context = subscribed?.let { """{"role":"guest","subscribed":$it}""" } ?: "{}"
            return """{"id":1,"lang":"en","name":"$name","team":[["$name","owner",""],["spike-owner","owner",""],["spike-admin","admin",""],["spike-mod","mod","Helper"]$me],""" +
                """"about":"About it","title":"$title","context":$context,"is_nsfw":false,"type_id":1,"flag_text":"Be kind","created_at":"2019-11-12 09:52:15",""" +
                """"description":"A test community.","num_authors":40,"num_pending":280,"subscribers":1200}"""
        }

        fun listed(name: String, title: String, subscribed: Boolean? = null) =
            """{"id":1,"name":"$name","title":"$title","about":"About $title","context":${subscribed?.let { """{"subscribed":$it}""" } ?: "{}"},""" +
                """"is_nsfw":false,"type_id":1,"num_authors":3,"num_pending":7,"subscribers":12}"""
    }
}

class CommunityModelsTest {
    private fun TestScope.signedIn(app: TestApp) {
        app.signIn()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
    }

    /** The community operation of the [index]th broadcast: `[action, {fields}]`. */
    private fun TestApp.communityOp(index: Int = -1): JsonArray {
        val all = broadcasts()
        val tx = all[if (index < 0) all.size + index else index].params.jsonArray.single().jsonObject
        val op = tx["operations"]!!.jsonArray.single().jsonArray
        assertEquals("custom_json", op[0].jsonPrimitive.content)
        val body = op[1].jsonObject
        assertEquals("community", body["id"]!!.jsonPrimitive.content)
        assertEquals(listOf(TestApp.USER), body["required_posting_auths"]!!.jsonArray.map { it.jsonPrimitive.content })
        return Json.parseToJsonElement(body["json"]!!.jsonPrimitive.content).jsonArray
    }

    // ---- subscriptions ----

    @Test
    fun joinAndLeaveBroadcastAndShowEverywhere() = runTest {
        val node = CommunityNode()
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val subs = app.env.communities
        subs.start()
        testScheduler.advanceUntilIdle()
        assertTrue(subs.current.loaded)

        assertTrue(subs.toggleSubscription("hive-100001", "Test Square"))
        assertTrue("hive-100001" in subs.current.pending)
        assertFalse(subs.toggleSubscription("hive-100001")) // one change at a time
        testScheduler.advanceUntilIdle()
        assertEquals("""["subscribe",{"community":"hive-100001"}]""", app.communityOp().toString())
        assertTrue(subs.current.isSubscribed("hive-100001"))
        assertEquals(CommunityService.SUCCESS_SUBSCRIBE, app.env.ui.current.toast?.text)
        assertEquals(listOf("Test Square"), subs.current.items.map { it.title })

        subs.toggleSubscription("hive-100001")
        testScheduler.advanceUntilIdle()
        assertEquals("""["unsubscribe",{"community":"hive-100001"}]""", app.communityOp().toString())
        assertFalse(subs.current.isSubscribed("hive-100001"))
        // RN's membership tab keeps a left community, now offering Join
        assertEquals(listOf(false), subs.current.joined.map { it.second })
        assertEquals(2, app.broadcasts().size)
    }

    @Test
    fun aFailedJoinChangesNothingAndSaysSo() = runTest {
        val node = CommunityNode().apply { broadcastFails = true }
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        app.env.communities.start()
        testScheduler.advanceUntilIdle()
        app.env.communities.toggleSubscription("hive-100001")
        testScheduler.advanceUntilIdle()
        assertFalse(app.env.communities.current.isSubscribed("hive-100001"))
        assertTrue(app.env.communities.current.pending.isEmpty())
        assertEquals(CommunityService.FAIL_SUBSCRIBE, app.env.ui.current.toast?.text)
    }

    @Test
    fun signedOutCannotJoin() = runTest {
        val app = TestApp(this, handler = CommunityNode().handler())
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        assertFalse(app.env.communities.toggleSubscription("hive-100001"))
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun aJoinWinsOverAStaleListUntilHivemindAgrees() = runTest {
        val node = CommunityNode()
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val subs = app.env.communities
        subs.start()
        testScheduler.advanceUntilIdle()
        subs.toggleSubscription("hive-100001", "Test Square")
        testScheduler.advanceUntilIdle()

        subs.refresh() // hivemind has not indexed the join yet
        testScheduler.advanceUntilIdle()
        assertTrue(subs.current.isSubscribed("hive-100001"))
        assertEquals(mapOf("hive-100001" to true), subs.current.overrides)
        assertEquals(listOf("hive-100001"), subs.current.items.map { it.community })

        node.subscriptions = """[["hive-100001","Test Square","guest",""]]"""
        subs.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(subs.current.overrides.isEmpty())
        assertTrue(subs.current.isSubscribed("hive-100001"))
    }

    @Test
    fun anAccountChangeDropsThePreviousSubscriptions() = runTest {
        val node = CommunityNode().apply { subscriptions = """[["hive-2","B","guest",""],["hive-1","a","guest",""]]""" }
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        app.env.communities.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a", "B"), app.env.communities.current.items.map { it.title }) // sorted by title, as RN
        app.env.emit(AppEvent.SessionChanged(null))
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.communities.current.items.isEmpty())
        assertNull(app.env.communities.current.username)
    }

    // ---- communities screen ----

    @Test
    fun communitiesScreenListsMembershipAndDiscover() = runTest {
        val node = CommunityNode().apply {
            subscriptions = """[["hive-1","One","guest",""]]"""
            communities = "[${CommunityNode.listed("hive-1", "One")},${CommunityNode.listed("hive-2", "Two", subscribed = false)}]"
        }
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val model = CommunitiesModel(app.env)
        model.start()
        testScheduler.advanceUntilIdle()
        val s = model.current
        assertEquals(listOf(JoinedRow("hive-1", "One", "guest", subscribed = true, pending = false)), s.joined)
        assertEquals(listOf("hive-1" to true, "hive-2" to false), s.discover.map { it.name to it.subscribed })
        assertEquals("12 Members • 3 Posters • 7 Posts", s.discover[0].stats)
        val list = app.transport.sent.first { it.method == "bridge.list_communities" }.params.jsonObject
        assertEquals(50, list["limit"]!!.jsonPrimitive.content.toInt())
        assertEquals(TestApp.USER, list["observer"]!!.jsonPrimitive.content)

        // a join from Discover shows on both tabs
        model.toggle(s.discover[1])
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.discover[1].subscribed)
        assertTrue(model.current.joined.any { it.name == "hive-2" && it.subscribed })
        model.close()
    }

    // ---- community page ----

    @Test
    fun communityPageKnowsModeratorsAndJoinState() = runTest {
        val node = CommunityNode().apply { community = CommunityNode.community(role = "mod", subscribed = true) }
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val model = CommunityModel(app.env, "hive-100001")
        model.start()
        testScheduler.advanceUntilIdle()
        val s = model.current
        assertEquals("Test Square community", s.headerTitle)
        assertEquals("1,200 Members • 40 Posters • 280 Posts", s.stats) // plural counts are grouped as ICU
        assertTrue(s.isModerator)
        assertTrue(s.subscribed) // from the community's own context while the list has not got it
        assertEquals(TestApp.USER, app.transport.sent.first { it.method == "bridge.get_community" }.params.jsonObject["observer"]!!.jsonPrimitive.content)
        assertEquals(AppScreen.CommunityMembers("hive-100001", "Test Square"), model.screenFor(CommunityManageAction.MEMBERS))
        assertEquals(AppScreen.CommunitySettings("hive-100001"), model.screenFor(CommunityManageAction.SETTINGS))
        assertEquals(listOf(CommunityTab.TRENDING, CommunityTab.HOT, CommunityTab.NEW), CommunityTab.entries)
        assertEquals(CommunityTab.HOT, CommunityTab.of("hot"))
        assertEquals(CommunityTab.TRENDING, CommunityTab.of("payout"))

        model.toggleSubscription()
        testScheduler.advanceUntilIdle()
        assertEquals("unsubscribe", app.communityOp()[0].jsonPrimitive.content)
        assertFalse(model.current.subscribed)
        model.close()
    }

    @Test
    fun aGuestIsNoModerator() = runTest {
        val node = CommunityNode().apply { community = CommunityNode.community(role = null) }
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val model = CommunityModel(app.env, "hive-100001")
        model.start()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.isModerator)
        assertFalse(model.current.subscribed)
        model.close()
    }

    @Test
    fun aMissingCommunityIsNotFound() = runTest {
        val node = CommunityNode().apply { community = "null" }
        val app = TestApp(this, handler = node.handler())
        val model = CommunityModel(app.env, "hive-404")
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(ErrorKind.NOT_FOUND, model.current.error?.kind)
        model.close()
    }

    // ---- members and roles ----

    @Test
    fun membersAreTheTeamThenSubscribersSortedByRole() = runTest {
        val node = CommunityNode()
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val model = CommunityMembersModel(app.env, "hive-100001", "")
        model.start()
        testScheduler.advanceUntilIdle()
        val s = model.current
        // the community account is dropped; spike-admin keeps its team role over the subscriber row
        assertEquals(
            listOf("spike-owner" to CommunityRole.OWNER, "spike-admin" to CommunityRole.ADMIN, TestApp.USER to CommunityRole.ADMIN,
                "spike-mod" to CommunityRole.MOD, "spike-bob" to CommunityRole.MEMBER, "spike-alice" to CommunityRole.GUEST),
            s.members.map { it.account to it.role },
        )
        assertEquals("Test Square members", s.headerTitle)
        // an admin assigns mod and below, and only to accounts below it, never to itself
        assertEquals(listOf(CommunityRole.MOD, CommunityRole.MEMBER, CommunityRole.GUEST, CommunityRole.MUTED), s.assignableRoles)
        assertEquals(listOf("spike-mod", "spike-bob", "spike-alice"), s.members.filter { it.editable }.map { it.account })
        assertTrue(s.endReached)

        model.setRole("spike-alice", CommunityRole.MUTED)
        testScheduler.advanceUntilIdle()
        assertEquals("""["setRole",{"community":"hive-100001","account":"spike-alice","role":"muted"}]""", app.communityOp().toString())
        assertEquals(CommunityRole.MUTED, model.current.members.first { it.account == "spike-alice" }.role)
        assertEquals(CommunityCopy.SUCCESSFUL, app.env.ui.current.toast?.text)
        model.close()
    }

    @Test
    fun aFailedRoleChangeAlertsAndKeepsTheRole() = runTest {
        val node = CommunityNode().apply { broadcastFails = true }
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val model = CommunityMembersModel(app.env, "hive-100001", "Test Square")
        model.start()
        testScheduler.advanceUntilIdle()
        model.setRole("spike-bob", CommunityRole.MOD)
        testScheduler.advanceUntilIdle()
        assertNotNull(model.current.alert)
        assertEquals(CommunityRole.MEMBER, model.current.members.first { it.account == "spike-bob" }.role)
        model.dismissAlert()
        assertNull(model.current.alert)
        model.close()
    }

    @Test
    fun nonModeratorsSeeAReadOnlyRoster() = runTest {
        val node = CommunityNode().apply { community = CommunityNode.community(role = "member") }
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val model = CommunityMembersModel(app.env, "hive-100001", "Test Square")
        model.start()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.assignableRoles.isEmpty())
        assertTrue(model.current.members.none { it.editable })
        model.close()
    }

    @Test
    fun aFullSubscriberPageLoadsMoreAndAFailureSaysTheRosterIsIncomplete() = runTest {
        val node = CommunityNode().apply { subscribers = (1..100).joinToString(",", "[", "]") { """["user${it.toString().padStart(3, '0')}","guest",null,""]""" } }
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val model = CommunityMembersModel(app.env, "hive-100001", "Test Square")
        model.start()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.endReached)
        node.subscribers = """[["user101","guest",null,""]]"""
        model.loadMore()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.endReached)
        assertTrue(model.current.members.any { it.account == "user101" })
        assertEquals("user100", app.transport.sent.last { it.method == "bridge.list_subscribers" }.params.jsonObject["last"]!!.jsonPrimitive.content)

        node.subscribers = "not json"
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertNotNull(model.current.error)
        assertEquals(CommunityCopy.MEMBERS_INCOMPLETE, model.current.footerNote)
        model.close()
    }

    @Test
    fun memberMergeRules() {
        val merged = CommunityMembersModel.merge(
            listOf(Triple("b", "member", ""), Triple("a", "", "t"), Triple("hive-123456", "owner", ""), Triple("b", "admin", ""), Triple("c", "weird", "")),
        )
        assertEquals(listOf("b" to CommunityRole.MEMBER, "a" to CommunityRole.GUEST, "c" to CommunityRole.GUEST), merged.map { it.first to it.second })
    }

    @Test
    fun aMemberIsNoModerator() = runTest {
        val node = CommunityNode().apply { community = CommunityNode.community(role = "member", subscribed = true) }
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val model = CommunityModel(app.env, "hive-100001")
        model.start()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.isModerator) // on the team, but member carries no moderation authority
        assertTrue(model.current.subscribed)
        model.close()
    }

    @Test
    fun anAccountSwitchForgetsThePreviousObserversJoinState() = runTest {
        val node = CommunityNode().apply {
            community = CommunityNode.community(role = "admin", subscribed = true)
            communities = "[${CommunityNode.listed("hive-2", "Two", subscribed = true)}]"
        }
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val page = CommunityModel(app.env, "hive-100001")
        val list = CommunitiesModel(app.env)
        val search = SearchModel(app.env)
        page.start()
        list.start()
        search.selectTab(SearchTab.COMMUNITIES)
        testScheduler.advanceUntilIdle()
        assertTrue(page.current.subscribed)
        assertTrue(list.current.discover.single().subscribed)
        assertTrue(search.current.communities.rows.single().subscribed)

        search.selectTab(SearchTab.POSTS) // the communities tab keeps its rows while another tab shows
        // another account: until the reloads answer, nothing the previous observer was told may show
        node.communityGate = kotlinx.coroutines.CompletableDeferred()
        node.communities = "not json" // the list reloads fail: the old rows stay, without the old context
        app.env.emit(AppEvent.SessionChanged("someone-else"))
        testScheduler.advanceUntilIdle()
        assertFalse(page.current.subscribed)
        assertFalse(list.current.discover.single().subscribed)
        assertTrue(search.current.communities.rows.none { it.subscribed })
        node.communityGate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        page.close(); list.close(); search.close()
    }

    @Test
    fun roleChangesHivemindWouldDropAreRefusedWithItsReason() = runTest {
        val node = CommunityNode().apply { roles = mapOf("spike-elder" to "admin", "spike-new" to "guest") }
        val app = TestApp(this, handler = node.handler())
        signedIn(app) // the test user is an admin
        val model = CommunityMembersModel(app.env, "hive-100001", "Test Square")
        model.start()
        testScheduler.advanceUntilIdle()

        fun refused(account: String, role: CommunityRole): String? {
            model.setRole(account, role)
            testScheduler.advanceUntilIdle()
            return model.current.alert.also { model.dismissAlert() }
        }
        assertEquals("cant modify owner role", refused("spike-owner", CommunityRole.MEMBER))
        assertEquals("cant modify a user with a higher role", refused("spike-admin", CommunityRole.MOD)) // an equal is not below
        assertEquals("role would not change", refused("spike-bob", CommunityRole.MEMBER))
        assertEquals("cannot promote to or above own rank", refused("spike-bob", CommunityRole.ADMIN))
        // an account typed in the assign sheet is not on the roster: its role is asked for first
        assertEquals("cant modify a user with a higher role", refused("spike-elder", CommunityRole.MUTED))
        assertEquals("role would not change", refused("spike-new", CommunityRole.GUEST))
        assertTrue(app.broadcasts().isEmpty())
        assertNull(app.env.ui.current.toast)
        assertTrue(model.current.saving.isEmpty())

        assertNull(refused("spike-new", CommunityRole.MEMBER))
        assertEquals("""["setRole",{"community":"hive-100001","account":"spike-new","role":"member"}]""", app.communityOp().toString())
        model.close()
    }

    @Test
    fun setRoleRules() {
        // hivemind's community_set_role, rule by rule and in its order (hive/db/sql_scripts/community.sql)
        fun r(actor: CommunityRole?, current: CommunityRole, new: CommunityRole, self: Boolean, others: Int = 0) =
            CommunityRole.setRoleRefusal(actor, current, new, self, others)
        assertEquals("only mods and up can alter roles", r(CommunityRole.MEMBER, CommunityRole.GUEST, CommunityRole.MUTED, false))
        assertEquals("only mods and up can alter roles", r(null, CommunityRole.GUEST, CommunityRole.MUTED, false))
        assertEquals("only mods and up can alter roles", r(CommunityRole.MEMBER, CommunityRole.MEMBER, CommunityRole.GUEST, true), "not even one's own")
        assertNull(r(CommunityRole.MOD, CommunityRole.GUEST, CommunityRole.MUTED, false))
        assertNull(r(CommunityRole.MOD, CommunityRole.MUTED, CommunityRole.GUEST, false))
        assertEquals("cannot promote to or above own rank", r(CommunityRole.MOD, CommunityRole.GUEST, CommunityRole.MOD, false))
        assertEquals("cannot promote to or above own rank", r(CommunityRole.ADMIN, CommunityRole.GUEST, CommunityRole.OWNER, false))
        assertEquals("cannot promote to or above own rank", r(CommunityRole.ADMIN, CommunityRole.ADMIN, CommunityRole.ADMIN, true))
        assertNull(r(CommunityRole.OWNER, CommunityRole.ADMIN, CommunityRole.MOD, false))
        assertNull(r(CommunityRole.ADMIN, CommunityRole.ADMIN, CommunityRole.MEMBER, true)) // stepping down oneself
        assertNull(r(CommunityRole.MOD, CommunityRole.MOD, CommunityRole.MUTED, true))
        // an owner's role never changes: not by an admin (its own message, before the rank check), not by the owner
        assertEquals("cant modify owner role", r(CommunityRole.ADMIN, CommunityRole.OWNER, CommunityRole.MEMBER, false))
        assertEquals("cant modify owner role", r(CommunityRole.OWNER, CommunityRole.OWNER, CommunityRole.ADMIN, true))
        assertEquals("cannot promote to or above own rank", r(CommunityRole.OWNER, CommunityRole.OWNER, CommunityRole.OWNER, true))
        // someone else at or above the actor
        assertEquals("cant modify a user with a higher role", r(CommunityRole.MOD, CommunityRole.MOD, CommunityRole.GUEST, false))
        assertEquals("cant modify a user with a higher role", r(CommunityRole.MOD, CommunityRole.ADMIN, CommunityRole.GUEST, false))
        assertEquals("role would not change", r(CommunityRole.ADMIN, CommunityRole.MEMBER, CommunityRole.MEMBER, false))
        assertNull(r(CommunityRole.ADMIN, CommunityRole.ADMIN, CommunityRole.MOD, true), "the same-role check is for others only")
        // the moderator limit: promotions to mod or above only, the target not counted
        assertEquals("moderator limit exceeded", r(CommunityRole.OWNER, CommunityRole.MEMBER, CommunityRole.MOD, false, 100))
        assertEquals("moderator limit exceeded", r(CommunityRole.OWNER, CommunityRole.MOD, CommunityRole.ADMIN, false, 100))
        assertNull(r(CommunityRole.OWNER, CommunityRole.MEMBER, CommunityRole.MOD, false, 99))
        assertNull(r(CommunityRole.OWNER, CommunityRole.MOD, CommunityRole.MEMBER, false, 100), "a demotion is never limited")
        assertEquals("role would not change", r(CommunityRole.OWNER, CommunityRole.MOD, CommunityRole.MOD, false, 100), "earlier rules win")
        assertEquals(100, CommunityRole.MAX_MODERATORS)
        assertEquals(listOf(5, 4, 3, 2, 1, 0), CommunityRole.entries.map { it.rank })
    }

    // ---- role sheet: account lookup ----

    @Test
    fun accountLookupKnowsOnlyExactMatches() = runTest {
        val node = CommunityNode().apply { lookup = """["spike-alice","spike-alicea","spike-alicf"]""" }
        val app = TestApp(this, handler = node.handler())
        val lookup = AccountLookupModel(app.env)
        lookup.setInput("  @Spike-Ali ")
        assertEquals("spike-ali", lookup.current.input)
        assertTrue(lookup.current.searching)
        testScheduler.advanceUntilIdle()
        assertEquals(3, lookup.current.suggestions.size)
        assertFalse(lookup.current.known) // lookup_accounts answers neighbours for anything
        assertEquals("""["spike-ali",20]""", app.transport.sent.single().params.toString())

        lookup.setInput("spike-alice")
        lookup.setInput("spike-alice") // typing fast: one request after the pause
        testScheduler.advanceUntilIdle()
        assertTrue(lookup.current.known)
        assertEquals(2, app.transport.sent.size)

        lookup.pick("spike-alicea")
        assertTrue(lookup.current.known)
        assertTrue(lookup.current.suggestions.isEmpty())
        lookup.setInput("")
        assertFalse(lookup.current.searching)
        lookup.close()
    }

    // ---- settings ----

    @Test
    fun settingsAreEditableByOwnerAndAdminOnly() = runTest {
        val node = CommunityNode().apply { community = CommunityNode.community(role = "mod") }
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val model = CommunitySettingsModel(app.env, "hive-100001")
        model.start()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.canEdit)
        assertEquals(CommunitySettingsState.OWNER_ADMIN_ONLY, model.current.notice)
        assertEquals("Be kind", model.current.form.rules)
        model.update(model.current.form.copy(title = "Hijack"))
        model.save()
        testScheduler.advanceUntilIdle()
        assertEquals("Test Square", model.current.form.title)
        assertTrue(app.broadcasts().isEmpty())
        model.close()
    }

    @Test
    fun settingsValidateAndSaveTrimmedProps() = runTest {
        val node = CommunityNode()
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val model = CommunitySettingsModel(app.env, "hive-100001")
        model.start()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.canEdit)
        assertNull(model.current.notice)

        model.update(model.current.form.copy(title = "ab"))
        assertEquals("Between 3 and 20 characters", model.current.form.titleError)
        model.save()
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty())
        model.update(model.current.form.copy(title = " ", about = "", lang = " "))
        assertEquals(CommunitySettingsForm.REQUIRED, model.current.form.titleError)
        assertEquals(CommunitySettingsForm.REQUIRED, model.current.form.aboutError)
        assertEquals(CommunitySettingsForm.REQUIRED, model.current.form.langError)

        model.update(CommunitySettingsForm(" New Title ", " About ", " en ", " Desc ", " Rules\n", isNsfw = true))
        model.save()
        testScheduler.advanceUntilIdle()
        val op = app.communityOp()
        assertEquals("updateProps", op[0].jsonPrimitive.content)
        assertEquals(
            """{"title":"New Title","about":"About","lang":"en","description":"Desc","flag_text":"Rules","is_nsfw":true}""",
            (op[1] as JsonObject)["props"].toString(),
        )
        assertTrue(model.current.saved)
        assertEquals(CommunityCopy.SUCCESSFUL, app.env.ui.current.toast?.text)
        model.close()
    }

    @Test
    fun settingsFollowHivemindsPropRules() {
        val ok = CommunitySettingsForm("Title", "About", "en", "", "", false)
        assertTrue(ok.isValid)
        val bee = "\uD83D\uDC1D"
        // lengths are code points: three emoji are a valid title, twenty too, twenty one not
        assertNull(ok.copy(title = bee.repeat(3)).titleError)
        assertNull(ok.copy(title = bee.repeat(20)).titleError)
        assertEquals("Between 3 and 20 characters", ok.copy(title = bee.repeat(21)).titleError)
        assertEquals("Between 3 and 20 characters", ok.copy(title = bee + "a").titleError)
        assertEquals("Cannot start with @ or #", ok.copy(title = "@name").titleError)
        assertEquals("Cannot start with @ or #", ok.copy(title = "  #tag ").titleError)
        assertNull(ok.copy(about = "a".repeat(120)).aboutError)
        assertEquals("At most 120 characters", ok.copy(about = "a".repeat(121)).aboutError)
        assertNull(ok.copy(description = bee.repeat(1000)).descriptionError)
        assertEquals("At most 1,000 characters", ok.copy(description = "d".repeat(1001)).descriptionError)
        assertEquals("At most 1,000 characters", ok.copy(rules = "r".repeat(1001)).rulesError)
        for (bad in listOf("eng", "e", "EN", "zz", "zu", "e n")) assertEquals("Use a two letter language code, like en", ok.copy(lang = bad).langError, bad)
        for (good in listOf("en", " ru ", "zh", "za")) assertNull(ok.copy(lang = good).langError, good)
        assertFalse(ok.copy(rules = "r".repeat(1001)).isValid)
        assertFalse(ok.copy(description = "d".repeat(1001)).isValid)
        // what is sent is trimmed, so it equals its stripped form (hivemind refuses padding)
        val props = CommunitySettingsForm(" T i ", " a ", " en ", " d\n", "\tr ", true).props()
        for (v in listOf(props.title, props.about, props.lang, props.description, props.flagText)) assertEquals(v.trim(), v)
    }

    @Test
    fun aSettingsRetryNeverRacesTheLoadItReplaces() = runTest {
        val node = CommunityNode().apply {
            community = CommunityNode.community(role = "admin", title = "Old Title")
            communityGate = kotlinx.coroutines.CompletableDeferred()
        }
        val app = TestApp(this, handler = node.handler())
        signedIn(app)
        val model = CommunitySettingsModel(app.env, "hive-100001")
        model.start() // held at the gate with the old answer
        testScheduler.runCurrent()
        val first = node.communityGate!!
        node.communityGate = null
        node.community = CommunityNode.community(role = "admin", title = "New Title")
        model.retry()
        testScheduler.runCurrent()
        assertEquals("New Title", model.current.form.title)
        node.community = CommunityNode.community(role = "mod", title = "Stale Title")
        first.complete(Unit) // the replaced load's answer arrives late: ignored
        testScheduler.advanceUntilIdle()
        assertEquals("New Title", model.current.form.title)
        assertNull(model.current.error) // nor does the replaced load's failure or timeout reach the screen
        assertFalse(model.current.loading)
        model.close()
    }

    // ---- activities ----

    @Test
    fun activityMessagesLinkAccountsAndPosts() {
        val parts = CommunityActivitiesModel.parts("@alice muted @bob/my-post - spam, see @carol.")
        assertEquals(
            listOf(
                ActivityPart("@alice", "alice"), ActivityPart(" muted "), ActivityPart("@bob/my-post", "bob", "my-post"),
                ActivityPart(" - spam, see "), ActivityPart("@carol", "carol"), ActivityPart("."),
            ),
            parts,
        )
        assertEquals(listOf(ActivityPart("no mentions")), CommunityActivitiesModel.parts("no mentions"))
        // only what is a real account or post becomes a link; the text is never dropped
        for (plain in listOf("hi @Al", "@UPPER did it", "@ab x", "@a..b", "@alice/Bad_Permlink!", "@-x-")) {
            val p = CommunityActivitiesModel.parts(plain)
            assertTrue(p.none { it.isLink }, plain)
            assertEquals(plain, p.joinToString("") { it.text }, plain)
        }
        assertEquals("@alice/ok-1 and @x/y.", CommunityActivitiesModel.parts("@alice/ok-1 and @x/y.").joinToString("") { it.text })
    }

    @Test
    fun activitiesPageAndFailClearly() = runTest {
        val node = CommunityNode().apply {
            activities = (1..50).joinToString(",", "[", "]") { """{"id":"${1000 - it}","msg":"@user$it subscribed to Test Square","url":"","date":"2026-10-06T11:00:00","type":"subscribe","score":1}""" }
        }
        val app = TestApp(this, handler = node.handler())
        val model = CommunityActivitiesModel(app.env, "hive-100001", "Test Square")
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(50, model.current.items.size)
        assertEquals("user1", model.current.items.first().actor)
        assertEquals("Test Square activity", model.current.headerTitle)
        assertFalse(model.current.endReached)
        node.activities = """[{"id":"1","msg":"@last pinned @x/y","url":"","date":"2026-10-06T10:00:00","type":"pin_post","score":1}]"""
        model.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(51, model.current.items.size)
        assertTrue(model.current.endReached)
        assertEquals("950", app.transport.sent.last().params.jsonObject["last_id"]!!.jsonPrimitive.content)
        model.close()

        node.activities = "{}"
        val failing = TestApp(this) { req -> if (req.method == "bridge.account_notifications") HttpResult(500, "") else HttpResult(500, "") }
        val m2 = CommunityActivitiesModel(failing.env, "hive-100001", "")
        m2.start()
        testScheduler.advanceUntilIdle()
        assertEquals(CommunityCopy.ACTIVITIES_LOAD_FAILED, m2.current.emptyText)
        m2.close()
    }

    @Test
    fun modNotesAreTrimmedAndCapped() {
        assertNull(ModNotes.notes("   "))
        assertEquals("spam", ModNotes.notes("  spam "))
        assertEquals(ModNotes.MAX_LENGTH, ModNotes.notes("x".repeat(500))!!.length)
        // hivemind counts code points: 120 emoji fit, and a cut never leaves half of one
        val bee = "\uD83D\uDC1D"
        assertEquals(bee.repeat(120), ModNotes.notes(bee.repeat(121)))
        assertEquals("x" + bee.repeat(119), ModNotes.limit("x" + bee.repeat(200)))
        assertEquals(120, ModNotes.count(bee.repeat(120)))
        assertEquals("a", ModNotes.notes("a" + " ".repeat(130) + "b")) // the cut can expose padding: trimmed again
        // whatever the sheet hands over is accepted by the operation builder
        com.ecency.core.api.broadcast.CommunityOps.mutePost("m", "hive-1", "a", "p", ModNotes.notes(" " + bee.repeat(300))!!, true)
        assertEquals("Mute this post", ModNotes.title(unmute = false))
        assertEquals("Unmute post", ModNotes.confirmLabel(unmute = true))
    }

    @Test
    fun moderationCallsForThePostMenu() = runTest {
        val app = TestApp(this, handler = CommunityNode().handler())
        signedIn(app)
        val pin = async { app.env.communities.pinPost("hive-100001", "alice", "post", pin = true) }
        testScheduler.advanceUntilIdle()
        pin.await()
        assertEquals("""["pinPost",{"community":"hive-100001","account":"alice","permlink":"post"}]""", app.communityOp().toString())
        val mute = async { app.env.communities.mutePost("hive-100001", "alice", "post", "spam", mute = true) }
        testScheduler.advanceUntilIdle()
        mute.await()
        assertEquals("""["mutePost",{"community":"hive-100001","account":"alice","permlink":"post","notes":"spam"}]""", app.communityOp().toString())
    }
}
