package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.RankedSort
import com.ecency.core.api.Sent
import com.ecency.core.api.broadcast.CommunityOps
import com.ecency.core.api.broadcast.PostActionOps
import com.ecency.core.api.broadcast.ProfileMetadataGuard
import com.ecency.core.api.model.Account
import com.ecency.core.api.model.Manabar
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class FeedxTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    /** Same operation as the SDK's: fields equal as JSON, and custom_json `json` text byte for byte. */
    private fun assertSameOp(expected: String, actual: JsonArray) {
        val e = json(expected).jsonArray
        assertEquals(e[0], actual.single().jsonArray[0])
        val eb = e[1].jsonObject
        val ab = actual.single().jsonArray[1].jsonObject
        assertEquals(eb, ab)
        eb["json"]?.let { assertEquals(it.jsonPrimitive.content, ab["json"]!!.jsonPrimitive.content) }
    }

    @Test
    fun menuOperationsMatchTheSdkBuilders() {
        val g = FeedxGoldens.ops
        assertSameOp(g.getValue("reblog"), PostActionOps.reblog("spike-alice", "spike-bob", "hello-world", undo = false))
        assertSameOp(g.getValue("reblog_undo"), PostActionOps.reblog("spike-alice", "spike-bob", "hello-world", undo = true))
        assertSameOp(g.getValue("ignore"), PostActionOps.ignore("spike-alice", "spike-bob"))
        assertSameOp(g.getValue("pin_community"), CommunityOps.ops(CommunityOps.pinPost("spike-mod", "hive-100001", "spike-bob", "hello-world", true)))
        assertSameOp(g.getValue("unpin_community"), CommunityOps.ops(CommunityOps.pinPost("spike-mod", "hive-100001", "spike-bob", "hello-world", false)))
        assertSameOp(g.getValue("mute_post"), CommunityOps.ops(CommunityOps.mutePost("spike-mod", "hive-100001", "spike-bob", "hello-world", "off topic", true)))
        assertSameOp(g.getValue("unmute_post"), CommunityOps.ops(CommunityOps.mutePost("spike-mod", "hive-100001", "spike-bob", "hello-world", "restored 🐝", false)))
        assertSameOp(g.getValue("delete"), PostActionOps.delete("spike-alice", "re-hello"))
    }

    @Test
    fun pinToBlogKeepsTheProfileLikeTheSdk() {
        for (case in FeedxGoldens.pins) {
            val expected = json(case.op).jsonArray[1].jsonObject
            // the one builder of profile writes (the profile edit's too), over an unchecked base here
            val base = ProfileMetadataGuard.Base(case.existing.orEmpty(), ProfileMetadataGuard.effectiveProfile(case.existing, null))
            val actual = ProfileMetadataGuard.operation("spike-alice", base, ProfileMetadataGuard.pinnedProfile(base, case.pinned)).single().jsonArray[1].jsonObject
            // byte for byte: the metadata string is what goes on chain
            assertEquals(expected["posting_json_metadata"]!!.jsonPrimitive.content, actual["posting_json_metadata"]!!.jsonPrimitive.content, case.existing)
            assertEquals(expected, actual)
        }
    }

    @Test
    fun pinReplyRepostsTheParentWithPinnedReply() {
        val parent = Post.fromJson(json(entry("spike-alice", "hello-world", metadata = FeedxGoldens.PARENT_META)))!!
            .copy(category = "ecency", title = "Hello", body = "Body text", parentAuthor = "", parentPermlink = "ecency")
        val reply = Post(author = "spike-bob", permlink = "re-hello")
        val pin = PostActionOps.pinReply(parent, reply).single().jsonArray
        val expected = json(FeedxGoldens.PIN_REPLY).jsonArray
        assertEquals(expected, pin)
        val unpin = PostActionOps.pinReply(parent, null).single().jsonArray
        assertEquals(json(FeedxGoldens.UNPIN_REPLY).jsonArray, unpin)
    }

    @Test
    fun voteValueMatchesTheSdk() {
        for (c in FeedxGoldens.votes) {
            val account = Account(
                name = c.name, vestingShares = c.vesting, delegatedVestingShares = c.delegated, receivedVestingShares = c.received,
                votingManabar = Manabar(0.0, 1000),
            )
            val props = RewardProps(FeedxGoldens.RECENT_CLAIMS, FeedxGoldens.REWARD_BALANCE, FeedxGoldens.BASE, FeedxGoldens.QUOTE, FeedxGoldens.RESERVE_RATE, c.hardfork)
            val value = VoteValue.value(account, props, c.weight, TestApp.NOW)
            val tolerance = maxOf(1e-12, abs(c.value) * 1e-9)
            assertTrue(abs(value - c.value) <= tolerance, "${c.name} hf${c.hardfork} ${c.weight}: $value vs ${c.value}")
        }
        assertEquals("0.000", VoteValue.format(0.0))
        assertEquals("0.001", VoteValue.format(0.0004))
        assertEquals("0.123", VoteValue.format(0.1234))
        assertEquals("12.35", VoteValue.format(12.345))
    }

    @Test
    fun crossPostOperationsFollowTheRnMutation() {
        val original = Post(author = "spike-bob", permlink = "hello-world", title = "Hello")
        val ops = PostActionOps.crossPost("spike-alice", original, "hive-100001", "Have a look", "4.0.0")
        val comment = ops[0].jsonArray[1].jsonObject
        assertEquals("", comment["parent_author"]!!.jsonPrimitive.content)
        assertEquals("hive-100001", comment["parent_permlink"]!!.jsonPrimitive.content)
        assertEquals("hello-world-hive-100001", comment["permlink"]!!.jsonPrimitive.content)
        assertEquals("This is a cross post of [@spike-bob/hello-world](/@spike-bob/hello-world) by @spike-alice.<br><br>Have a look", comment["body"]!!.jsonPrimitive.content)
        assertEquals(
            """{"original_author":"spike-bob","original_permlink":"hello-world","tags":["cross-post"],"app":"ecency/4.0.0-mobile","format":"markdown+html"}""",
            comment["json_metadata"]!!.jsonPrimitive.content,
        )
        val options = ops[1].jsonArray[1].jsonObject
        assertEquals("0.000 HBD", options["max_accepted_payout"]!!.jsonPrimitive.content)
        assertEquals("false", options["allow_curation_rewards"]!!.jsonPrimitive.content)
        assertEquals("true", options["allow_votes"]!!.jsonPrimitive.content)
    }

    @Test
    fun pollVoteOperation() {
        val op = PostActionOps.pollVote("spike-alice", "abc123", listOf(2, 3)).single().jsonArray[1].jsonObject
        assertEquals("polls", op["id"]!!.jsonPrimitive.content)
        assertEquals("""{"poll":"abc123","action":"vote","choices":[2,3]}""", op["json"]!!.jsonPrimitive.content)
        assertEquals(listOf("spike-alice"), op["required_posting_auths"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    // ---------------------------------------------------------------- menu

    private fun TestScope.signedIn(app: TestApp, user: String = TestApp.USER) {
        val vault = KeyVault(app.store)
        vault.save(VaultAccount(user, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$user"))
        vault.setCurrentUsername(user)
        app.env.session.start()
        testScheduler.advanceUntilIdle()
    }

    private fun post(text: String) = Post.fromJson(json(text))!!

    private fun reads(reblogged: List<String> = emptyList(), team: String = "[]"): suspend FakeTransport.(Sent) -> HttpResult = { req ->
        when (req.method) {
            "condenser_api.get_reblogged_by" -> FakeTransport.result(req, Json.encodeToString(reblogged))
            "bridge.get_community" -> FakeTransport.result(req, """{"name":"hive-100001","title":"Test","team":$team}""")
            "condenser_api.get_accounts" -> FakeTransport.result(req, """[{"name":"${TestApp.USER}","posting_json_metadata":${Json.encodeToString(nodeMetadata)},"json_metadata":${Json.encodeToString(nodeLegacyMetadata)}}]""")
            "bridge.get_post" -> FakeTransport.result(req, entry("spike-voter", "root", metadata = """{"pinned_reply":"bob/re-1"}"""))
            "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "null")
            "bridge.get_ranked_posts" -> FakeTransport.result(req, "[${entry(TestApp.USER, "one")},${entry("bob", "two")}]")
            "bridge.get_profile" -> if (hivemindDown) HttpResult(500, "down") else FakeTransport.result(req, """{"name":"${TestApp.USER}","metadata":{"profile":$hivemindProfile}}""")
            "condenser_api.get_following" -> FakeTransport.result(req, Json.encodeToString(ignored.map { mapOf("follower" to TestApp.USER, "following" to it) }))
            else -> TestApp.chain(req) ?: HttpResult(500, "")
        }
    }

    /** What hivemind shows as the signed-in account's profile, and the raw posting_json_metadata nodes return. */
    private var hivemindProfile = "{}"
    private var nodeMetadata = """{"profile":{"pinned":"mine"}}"""
    private var nodeLegacyMetadata = ""
    private var hivemindDown = false
    private var ignored = listOf<String>()

    @Test
    fun guestsSeeTheMenuWithoutAccountItems() = runTest {
        val app = TestApp(this, handler = reads())
        val items = app.env.postActions.menu(post(entry("alice", "one")), PostMenuContext())
        assertEquals(
            listOf(
                PostMenuItem.CROSS_POST, PostMenuItem.PROMOTE, PostMenuItem.COPY, PostMenuItem.REPLY, PostMenuItem.REBLOG,
                PostMenuItem.EDIT_HISTORY, PostMenuItem.SHARE, PostMenuItem.BOOKMARKS, PostMenuItem.MUTE, PostMenuItem.REPORT,
            ),
            items,
        )
        // signed out, the account actions open the login screen
        assertEquals(PostMenuEffect.Login, app.env.postActions.select(PostMenuItem.BOOKMARKS, post(entry("alice", "one"))))
        assertEquals(PostMenuEffect.Login, app.env.postActions.select(PostMenuItem.REBLOG, post(entry("alice", "one"))))
    }

    @Test
    fun ownPostOffersPinAndDeleteOnlyWhenTheChainAllows() = runTest {
        val app = TestApp(this, handler = reads(reblogged = listOf(TestApp.USER)))
        signedIn(app)
        val mine = post(entry(TestApp.USER, "fresh", netRshares = 0))
        val items = app.env.postActions.menu(mine, PostMenuContext())
        assertTrue(PostMenuItem.DELETE in items)
        assertTrue(PostMenuItem.PIN_BLOG in items)
        assertTrue(PostMenuItem.UNDO_REBLOG in items && PostMenuItem.REBLOG !in items)
        // a positive vote or a reply makes hived refuse the delete: RN hides it
        assertFalse(PostMenuItem.DELETE in app.env.postActions.menu(post(entry(TestApp.USER, "voted", netRshares = 5)), PostMenuContext()))
        assertFalse(PostMenuItem.DELETE in app.env.postActions.menu(post(entry(TestApp.USER, "replied", children = 1)), PostMenuContext()))
        // the profile's pinned permlink decides when the entry does not say
        val pinned = post(entry(TestApp.USER, "mine"))
        assertTrue(PostMenuItem.UNPIN_BLOG in app.env.postActions.menu(pinned, PostMenuContext()))
    }

    @Test
    fun moderatorsGetCommunityPinAndMute() = runTest {
        val app = TestApp(this, handler = reads(team = """[["${TestApp.USER}","mod",""]]"""))
        signedIn(app)
        val items = app.env.postActions.menu(post(entry("alice", "one", pinned = true)), PostMenuContext())
        assertTrue(PostMenuItem.UNPIN_COMMUNITY in items && PostMenuItem.PIN_COMMUNITY !in items)
        assertTrue(PostMenuItem.MUTE_POST in items)
        val member = TestApp(this, handler = reads(team = """[["${TestApp.USER}","member",""]]"""))
        signedIn(member)
        val plain = member.env.postActions.menu(post(entry("alice", "one")), PostMenuContext())
        assertTrue(plain.none { it == PostMenuItem.PIN_COMMUNITY || it == PostMenuItem.MUTE_POST })
    }

    @Test
    fun postAuthorCanPinADirectReply() = runTest {
        val app = TestApp(this, handler = reads())
        signedIn(app)
        val reply = post(entry("bob", "re-1", parent = TestApp.USER to "root"))
        val items = app.env.postActions.menu(reply, PostMenuContext(translate = true, openThread = true))
        assertTrue(PostMenuItem.UNPIN_REPLY in items, items.toString())
        assertTrue(PostMenuItem.COPY_TEXT in items && PostMenuItem.OPEN_THREAD in items && PostMenuItem.TRANSLATE in items)
    }

    @Test
    fun deleteAsksFirstThenBroadcastsAndDropsTheCard() = runTest {
        val app = TestApp(this, handler = reads())
        signedIn(app)
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        feed.start()
        testScheduler.advanceUntilIdle()
        val mine = post(entry(TestApp.USER, "one"))
        // someone else's post: confirming a delete does nothing (the menu never offers it, the model checks again)
        app.env.postActions.confirm(PostMenuItem.DELETE, post(entry("bob", "two")), TestApp.USER)
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty())
        val effect = app.env.postActions.select(PostMenuItem.DELETE, mine)
        assertIs<PostMenuEffect.Confirm>(effect)
        assertEquals("Are you sure you want to remove?", effect.title)
        assertTrue(app.broadcasts().isEmpty())
        app.env.postActions.confirm(PostMenuItem.DELETE, mine, TestApp.USER)
        testScheduler.advanceUntilIdle()
        val op = app.broadcasts().single().params.jsonArray.single().jsonObject["operations"].toString()
        assertEquals("""[["delete_comment",{"author":"${TestApp.USER}","permlink":"one"}]]""", op)
        assertTrue(PostKey(TestApp.USER, "one") in app.env.postActions.current.deleted)
        assertEquals(listOf("two"), feed.current.items.map { it.key.permlink })
        assertEquals("Removed", app.env.ui.current.toast?.text)
        feed.close()
    }

    /** The `json` of the community custom_json in broadcast number [index]. */
    private fun communityJson(app: TestApp, index: Int): String {
        val op = app.broadcasts()[index].params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertEquals("custom_json", op[0].jsonPrimitive.content)
        assertEquals("community", op[1].jsonObject["id"]!!.jsonPrimitive.content)
        return op[1].jsonObject["json"]!!.jsonPrimitive.content
    }

    @Test
    fun menuCommunityActionsGoThroughCommunityOpsWithItsNotesRules() = runTest {
        val app = TestApp(this, handler = reads(team = """[["${TestApp.USER}","mod",""]]"""))
        signedIn(app)
        val muted = post(entry("alice", "one")).let { it.copy(stats = it.stats.copy(gray = true)) }
        // unmute asks for a reason too (hivemind wants notes on both), with RN's copy
        val ask = app.env.postActions.select(PostMenuItem.UNMUTE_POST, muted)
        assertIs<PostMenuEffect.AskNotes>(ask)
        assertEquals("Unmute this post", ask.title)
        assertEquals("Why is this being restored?", ask.placeholder)
        assertEquals("Unmute post", ask.confirmLabel)
        // blank or missing: nothing goes out
        for (blank in listOf(null, "", " \n\t ")) {
            app.env.postActions.confirm(PostMenuItem.UNMUTE_POST, muted, TestApp.USER, blank)
            testScheduler.advanceUntilIdle()
        }
        assertTrue(app.broadcasts().isEmpty())
        // padded: sent trimmed (hivemind refuses padding); byte for byte what CommunityOps builds
        app.env.postActions.confirm(PostMenuItem.UNMUTE_POST, muted, TestApp.USER, "  restored 🐝 \n")
        testScheduler.advanceUntilIdle()
        assertEquals(CommunityOps.mutePost(TestApp.USER, "hive-100001", "alice", "one", "restored 🐝", false).json, communityJson(app, 0))
        // over 120 code points: cut to 120 (never inside a surrogate pair), so hivemind accepts it
        app.env.postActions.confirm(PostMenuItem.MUTE_POST, muted, TestApp.USER, "🐝".repeat(130))
        testScheduler.advanceUntilIdle()
        assertEquals(CommunityOps.mutePost(TestApp.USER, "hive-100001", "alice", "one", "🐝".repeat(120), true).json, communityJson(app, 1))
        // the builder itself refuses what hivemind would drop
        for (bad in listOf("", "  ", " padded", "padded ", "x".repeat(121))) {
            assertFailsWith<com.ecency.core.api.InvalidInputException>(bad) { CommunityOps.mutePost(TestApp.USER, "hive-100001", "alice", "one", bad, false) }
        }
        // community pin and unpin: the same builder
        app.env.postActions.select(PostMenuItem.PIN_COMMUNITY, muted)
        testScheduler.advanceUntilIdle()
        assertEquals(CommunityOps.pinPost(TestApp.USER, "hive-100001", "alice", "one", true).json, communityJson(app, 2))
        app.env.postActions.select(PostMenuItem.UNPIN_COMMUNITY, muted)
        testScheduler.advanceUntilIdle()
        assertEquals(CommunityOps.pinPost(TestApp.USER, "hive-100001", "alice", "one", false).json, communityJson(app, 3))
        assertNull(app.env.postActions.current.alert)
    }

    /** A second account on the device, then a switch to it (as a link login or the switcher does). */
    private fun TestScope.switchToOther(app: TestApp, other: String = "spike-other") {
        val switching = launch { app.env.session.switchTo(other) }
        // runCurrent, not advanceUntilIdle: virtual time must not run into a node timeout while a gate holds
        repeat(20) { testScheduler.runCurrent() }
        assertTrue(switching.isCompleted, "switched")
        assertEquals(other, app.env.session.current.username)
    }

    private fun addOther(app: TestApp, other: String = "spike-other") {
        KeyVault(app.store).save(VaultAccount(other, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$other"))
    }

    @Test
    fun aConfirmationOpenedByOneAccountDoesNothingAsAnother() = runTest {
        val app = TestApp(this, handler = reads(team = """[["${TestApp.USER}","admin",""],["spike-other","admin",""]]"""))
        app.authBackend.rest = { r -> if (r.url.endsWith("/private-api/report")) HttpResult(200, "{}") else null }
        addOther(app)
        signedIn(app)
        val p = post(entry("alice", "one"))
        // the first account opens the report alert, the mute notes sheet and the delete alert of its own post
        val report = app.env.postActions.select(PostMenuItem.REPORT, p)
        assertIs<PostMenuEffect.Confirm>(report)
        assertEquals(TestApp.USER, report.user)
        val notes = app.env.postActions.select(PostMenuItem.MUTE_POST, p)
        assertIs<PostMenuEffect.AskNotes>(notes)
        assertEquals(TestApp.USER, notes.user)
        val mine = post(entry(TestApp.USER, "fresh", netRshares = 0))
        val delete = app.env.postActions.select(PostMenuItem.DELETE, mine)
        assertIs<PostMenuEffect.Confirm>(delete)

        // a link login switches the app to another account (a moderator too), then the user confirms
        switchToOther(app)
        app.env.ui.clear()
        app.env.postActions.confirm(report.item, report.post, report.user)
        app.env.postActions.confirm(notes.item, notes.post, notes.user, "off topic")
        app.env.postActions.confirm(delete.item, delete.post, delete.user)
        testScheduler.advanceUntilIdle()
        assertTrue(app.authBackend.sent.none { it.url.endsWith("/private-api/report") }, "no report as the other account")
        assertTrue(app.broadcasts().isEmpty(), "nothing signed by the other account")
        assertNull(app.env.ui.current.toast, "and no success said")
        assertTrue(app.env.mutedAuthors.isEmpty())
        assertNull(app.env.postActions.current.alert)

        // the community service refuses the same way when told whom the action was asked of
        val refused = assertFailsWith<AppException> { app.env.communities.mutePost("hive-100001", "alice", "one", "off topic", true, asUser = TestApp.USER) }
        assertEquals(ErrorKind.CANCELLED, refused.error.kind)
        assertFailsWith<AppException> { app.env.communities.pinPost("hive-100001", "alice", "one", true, asUser = TestApp.USER) }
        assertTrue(app.broadcasts().isEmpty())
        // asked of the current account, it goes out, signed by that account
        app.env.postActions.confirm(notes.item, notes.post, "spike-other", "off topic")
        testScheduler.advanceUntilIdle()
        assertTrue(communityJson(app, 0).contains("mutePost"))
        assertTrue(app.broadcasts().single().body.contains("spike-other"), "signed for the account that confirmed")
    }

    @Test
    fun aReplyDraftedByOneAccountIsNeverSentAsAnother() = runTest {
        val app = TestApp(this, handler = reads())
        addOther(app)
        signedIn(app)
        val parent = post(entry("alice", "one"))
        // the composer a sheet holds on to
        val reply = app.env.replyModel(parent.key, parent)
        reply.setText("written as the first account")
        // a same-account event keeps the draft
        app.env.session.onForeground()
        testScheduler.advanceUntilIdle()
        assertSame(reply, app.env.replyModel(parent.key, parent))
        assertEquals("written as the first account", reply.current.text)

        switchToOther(app)
        reply.send()
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty(), "not sent as the other account")
        assertFalse(reply.current.isSending)
        // the new account's composer for the same parent is its own, empty one, and it sends as that account
        val fresh = app.env.replyModel(parent.key, parent)
        assertNotSame(reply, fresh)
        assertEquals("", fresh.current.text)
        fresh.setText("written as the second account")
        fresh.send()
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().single().body.contains("spike-other"))
    }

    @Test
    fun aMuteThatLandsAfterAnAccountSwitchLeavesTheNewAccountsListAlone() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val base = reads()
        val app = TestApp(this, handler = { req ->
            if (req.method == "condenser_api.broadcast_transaction") gate.await()
            base(req)
        })
        addOther(app)
        signedIn(app)
        app.env.postActions.select(PostMenuItem.MUTE, post(entry("alice", "one"))) // ignore, held at the node
        testScheduler.runCurrent()
        switchToOther(app)
        app.env.ui.clear()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size, "the first account's ignore did go out")
        assertTrue(app.env.mutedAuthors.isEmpty(), "but it is not the second account's ignore list")
        assertNull(app.env.ui.current.toast)
    }

    @Test
    fun aQuickProfileFollowThatLandsAfterAnAccountSwitchChangesNothing() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val base = reads()
        val app = TestApp(this, handler = { req ->
            if (req.method == "condenser_api.broadcast_transaction") gate.await()
            base(req)
        })
        addOther(app)
        signedIn(app)
        val quick = app.env.quickProfileModel("alice")
        quick.start()
        testScheduler.advanceUntilIdle()
        quick.follow() // held at the node
        testScheduler.runCurrent()
        switchToOther(app)
        // the second account ignores alice (its own list)
        app.env.setAuthorMuted("alice", true)
        app.env.ui.clear()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue("alice" in app.env.mutedAuthors, "the first account's follow must not unmute for the second")
        assertFalse(quick.current.isFollowing)
        assertFalse(quick.current.actionRunning)
        assertNull(app.env.ui.current.toast)
        quick.close()
    }

    @Test
    fun muteRequiresNotesAndReportMutesTheAuthor() = runTest {
        val app = TestApp(this, handler = reads(team = """[["${TestApp.USER}","admin",""]]"""))
        app.authBackend.rest = { r -> if (r.url.endsWith("/private-api/report")) HttpResult(200, "{}") else null }
        signedIn(app)
        val p = post(entry("alice", "one"))
        assertIs<PostMenuEffect.AskNotes>(app.env.postActions.select(PostMenuItem.MUTE_POST, p))
        app.env.postActions.confirm(PostMenuItem.MUTE_POST, p, TestApp.USER, "   ")
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty(), "blank notes broadcast nothing (RN)")
        app.env.postActions.confirm(PostMenuItem.MUTE_POST, p, TestApp.USER, "spam")
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().single().body.contains("mutePost"))

        app.env.postActions.confirm(PostMenuItem.REPORT, p, TestApp.USER)
        testScheduler.advanceUntilIdle()
        val report = app.authBackend.sent.single { it.url.endsWith("/private-api/report") }
        assertEquals(
            // with the reporter's session code, as RN's API client adds it to every private API call but its public ones
            json("""{"type":"post","author":"alice","reporter":"${TestApp.USER}","permlink":"one","code":"token-${TestApp.USER}"}"""),
            json(report.body!!),
        )
        assertTrue(app.broadcasts().last().body.contains("ignore"))
        assertTrue("alice" in app.env.mutedAuthors)
    }

    @Test
    fun bookmarkCarriesTheSessionCode() = runTest {
        val app = TestApp(this, handler = reads())
        app.authBackend.rest = { r -> if (r.url.endsWith("/private-api/bookmarks-add")) HttpResult(200, "{}") else null }
        signedIn(app)
        app.env.postActions.select(PostMenuItem.BOOKMARKS, post(entry("alice", "one")))
        testScheduler.advanceUntilIdle()
        val sent = app.authBackend.sent.single { it.url.endsWith("bookmarks-add") }
        assertEquals(json("""{"author":"alice","permlink":"one","code":"token-${TestApp.USER}"}"""), json(sent.body!!))
        assertEquals(PostActionsModel.SUCCESS, app.env.ui.current.toast?.text)
    }

    @Test
    fun copyAndShareUseTheCanonicalUrl() = runTest {
        val app = TestApp(this, handler = reads())
        val p = post(entry("alice", "one"))
        assertEquals(PostMenuEffect.Copy("https://ecency.com/@alice/one", "Copied!"), app.env.postActions.select(PostMenuItem.COPY, p))
        assertEquals(PostMenuEffect.Share("Title of one https://ecency.com/@alice/one"), app.env.postActions.select(PostMenuItem.SHARE, p))
        assertEquals(PostMenuEffect.Open(AppScreen.EditHistory("alice", "one")), app.env.postActions.select(PostMenuItem.EDIT_HISTORY, p))
    }

    // ---------------------------------------------------------------- review fixes

    /** What hivemind answers for an account whose profile is [profileJson] (`bridge.get_profile`). */
    private fun hivemind(profileJson: String) =
        com.ecency.core.api.model.Profile.fromJson(json("""{"name":"spike-alice","metadata":{"profile":$profileJson}}"""))!!

    private fun metadataOf(base: ProfileMetadataGuard.Base, pinned: String?) =
        ProfileMetadataGuard.postingJsonMetadata(base, ProfileMetadataGuard.pinnedProfile(base, pinned))

    @Test
    fun profileGuardRefusesStrippedReads() {
        val known = hivemind("""{"name":"Spike","about":"hi","profile_image":"https://x/y.png"}""")
        val g = ProfileMetadataGuard
        // stripped node: empty text, an empty object, a profile with fewer values than hivemind shows
        assertNull(g.safeBase("", "", known))
        assertNull(g.safeBase("{}", "", known))
        assertNull(g.safeBase("""{"profile":{"name":"Spike"}}""", "", known))
        assertNull(g.safeBase("""{"profile":"oops"}""", "", known))
        assertNull(g.safeBase("""{"profile":[1]}""", "", known))
        // not JSON
        assertNull(g.safeBase("not json {", "", known))
        assertNull(g.safeBase("[1,2]", "", known))
        // nothing to compare with
        assertNull(g.safeBase("""{"profile":{"name":"Spike","about":"hi","profile_image":"https://x/y.png"}}""", "", null))
        // a genuinely empty profile may be written
        val empty = hivemind("""{"name":"","about":""}""")
        assertEquals("", g.safeBase("", "", empty)!!.postingJsonMetadata)
        assertEquals(emptyList(), g.safeBase("", "", empty)!!.profile.keys)
        assertEquals("""{"profile":{"pinned":"p","version":2}}""", metadataOf(g.safeBase("  ", null, empty)!!, "p"))
        // but unreadable metadata is never treated as empty, even then
        assertNull(g.safeBase("not json {", "", empty))
        // nor is a profile that is not an object, whatever hivemind shows
        assertNull(g.safeBase("""{"profile":"oops"}""", "", empty))
        assertNull(g.safeBase("""{"profile":[1]}""", "", empty))
        assertNull(g.safeBase("""{"profile":7}""", """{"profile":{"name":"Legacy"}}""", hivemind("""{"name":"Legacy"}""")))
        assertNull(g.safeBase("[".repeat(400) + "]".repeat(400), "", empty), "nesting past the parser's limit is unreadable too")
        assertEquals("""{"other":1,"profile":{"pinned":"p","version":2}}""", metadataOf(g.safeBase("""{"other":1}""", "", empty)!!, "p"))
        // hivemind holding any value at all (not only the fields the app shows) makes an empty read a stripped one
        assertNull(g.safeBase("", "", hivemind("""{"name":"","blacklist_description":"my list"}""")))
        assertNull(g.safeBase("""{"other":1}""", "", hivemind("""{"blacklist_description":"my list"}""")))
        // the normal case keeps every key, tokens and unknown keys included
        val full = """{"profile":{"name":"Spike","about":"hi","profile_image":"https://x/y.png","tokens":[{"symbol":"LEO"}],"custom":{"a":1}},"apps":["x"]}"""
        assertEquals(
            """{"profile":{"name":"Spike","about":"hi","profile_image":"https://x/y.png","tokens":[{"symbol":"LEO"}],"custom":{"a":1},"pinned":"new-post","version":2},"apps":["x"]}""",
            metadataOf(g.safeBase(full, "", known)!!, "new-post"),
        )
        // more values than hivemind shows is fine (hivemind lags and trims)
        assertNotNull(g.safeBase(full, "", hivemind("""{"name":"Spike"}""")))
    }

    @Test
    fun profileGuardFallsBackToTheLegacyProfile() {
        val g = ProfileMetadataGuard
        val legacy = """{"profile":{"name":"Legacy","about":"Old about","website":"https://old.example"}}"""
        val known = hivemind("""{"name":"Legacy","about":"Old about","website":"https://old.example"}""")
        val nothing = hivemind("""{"name":"","about":"","website":""}""")
        // hivemind reads the legacy profile when the posting metadata is blank or `{}`: it is the base, checked field by field
        for (posting in listOf("", "{}")) {
            val base = g.safeBase(posting, legacy, known)
            assertNotNull(base, posting)
            assertEquals(listOf("name", "about", "website"), base.profile.keys, posting)
            assertNull(g.safeBase(posting, legacy, nothing), "hivemind would show the legacy profile: $posting")
        }
        // posting metadata that is there but has no profile fields: hivemind reads IT and shows nothing (no fall
        // back), and the legacy profile, which the app still shows for such an account, seeds the write
        for (posting in listOf("""{"profile":{}}""", """{"profile":null}""", """{"apps":["x"]}""")) {
            val base = g.safeBase(posting, legacy, nothing)
            assertNotNull(base, posting)
            assertEquals(listOf("name", "about", "website"), base.profile.keys, posting)
            // hivemind showing exactly the legacy profile instead: it has no posting row of its own for the account
            // (the chain row is hived's hardfork copy), so the legacy profile is the current one
            assertEquals(listOf("name", "about", "website"), assertNotNull(g.safeBase(posting, legacy, known), posting).profile.keys)
            assertNull(g.safeBase(posting, legacy, hivemind("""{"name":"Someone else","about":"Old about"}""")), "neither this row nor the legacy one: $posting")
        }
        assertEquals(
            """{"apps":["x"],"profile":{"name":"Legacy","about":"Old about","website":"https://old.example","pinned":"p","version":2}}""",
            metadataOf(g.safeBase("""{"apps":["x"]}""", legacy, nothing)!!, "p"),
        )
        // a cut legacy profile is a stripped read too
        assertNull(g.safeBase("", """{"profile":{"name":"Legacy"}}""", known))
        assertNull(g.safeBase("", "not json", known))
        // a posting profile with any key wins over the legacy one, as the app and hivemind read it
        assertEquals(listOf("pinned"), g.safeBase("""{"profile":{"pinned":"x"}}""", legacy, hivemind("{}"))!!.profile.keys)
        // unreadable posting metadata is refused even when the legacy profile would do
        assertNull(g.safeBase("not json {", legacy, known))
    }

    // an account that moved from an old json_metadata profile to a current posting one (tokens, unknown keys)
    private val oldLegacy = """{"profile":{"name":"Old Name","about":"From 2018","location":"Nowhere","website":"https://old.example","profile_image":"https://img.example/old-a.png","cover_image":"https://img.example/old-c.png"}}"""
    private val currentPosting = """{"profile":{"name":"Current","about":"Now","location":"Vilnius","website":"https://now.example","cover_image":"https://img.example/now.png","tokens":[{"symbol":"LEO"}],"custom":{"a":1},"version":2},"apps":["x"]}"""
    private val currentShown = """{"name":"Current","about":"Now","location":"Vilnius","website":"https://now.example","cover_image":"https://img.example/now.png","profile_image":""}"""

    @Test
    fun aBlankedPostingRowNeverFallsBackToAStaleLegacyProfile() {
        val g = ProfileMetadataGuard
        // the node blanked only posting_json_metadata: the legacy profile has more values than hivemind shows,
        // but they are not the ones hivemind shows
        assertNull(g.safeBase("", oldLegacy, hivemind(currentShown)))
        assertNull(g.safeBase("{}", oldLegacy, hivemind(currentShown)))
        assertNull(g.safeBase("""{"apps":["x"]}""", oldLegacy, hivemind(currentShown)))
        // one differing field is enough, and so is a value hivemind shows that the legacy profile lacks
        val legacy = """{"profile":{"name":"Legacy","about":"Old about","website":"https://old.example"}}"""
        assertNotNull(g.safeBase("", legacy, hivemind("""{"name":"Legacy","about":"Old about","website":"https://old.example"}""")))
        assertNull(g.safeBase("", legacy, hivemind("""{"name":"Legacy","about":"New about","website":"https://old.example"}""")))
        assertNull(g.safeBase("", legacy, hivemind("""{"name":"Legacy","about":"Old about"}""")), "hivemind shows less: not the profile it reads")
        assertNull(g.safeBase("", """{"profile":{"name":"Legacy","about":"Old about","location":"X"}}""", hivemind("""{"name":"Legacy","about":"Old about","website":"https://old.example"}""")), "a field hivemind shows and the legacy profile lacks")
        assertNull(g.safeBase("", """{"profile":{"name":7,"about":"a"}}""", hivemind("""{"about":"a","website":"https://w.example"}""")), "even when the counts agree")
        // a blank row with a legacy profile while hivemind shows nothing: the current profile is not the legacy one
        assertNull(g.safeBase("", legacy, hivemind("""{"name":"","about":""}""")))
        // an explicitly empty posting profile that did arrive is what hivemind shows as nothing: the legacy one may seed it
        assertNotNull(g.safeBase("""{"profile":{}}""", legacy, hivemind("""{"name":"","about":""}""")))
        // the present posting profile itself is still accepted (count rule), also while hivemind lags behind an edit
        assertNotNull(g.safeBase(currentPosting, oldLegacy, hivemind(currentShown)))
        assertNotNull(g.safeBase(currentPosting, oldLegacy, hivemind("""{"name":"Before","about":"The edit","location":"Vilnius","website":"https://now.example","cover_image":"https://img.example/now.png"}""")))
    }

    private fun obj(text: String) = com.ecency.core.api.model.JsValue.parse(text) as com.ecency.core.api.model.JsValue.Obj

    @Test
    fun hivemindViewFollowsTheSqlThatServesGetProfile() {
        val g = ProfileMetadataGuard
        // hivemind_postgrest_utilities.extract_profile_metadata, rule by rule
        val view = g.hivemindView(obj("""{"name":"  A very long display name here ","about":"  ${"a".repeat(200)}","location":"${"🐝".repeat(40)}","website":"","profile_image":"ipfs://x","cover_image":"https://img.example/c.png"}"""))
        assertEquals("  A very long dis...", view["name"], "cut on the raw value, not trimmed first")
        assertEquals("  " + "a".repeat(155) + "...", view["about"])
        assertEquals("🐝".repeat(27) + "...", view["location"], "characters, not UTF-16 units")
        assertEquals("http://", view["website"], "an empty website gets the prefix too")
        assertEquals("", view["profile_image"])
        assertEquals("https://img.example/c.png", view["cover_image"])
        assertEquals("", g.hivemindView(obj("""{"name":"@handle"}"""))["name"])
        assertEquals("", g.hivemindView(obj("""{"name":"@${"x".repeat(30)}"}"""))["name"], "the @ check comes before the cut")
        assertEquals("", g.hivemindView(obj("""{"about":"a\\x00b"}"""))["about"], "the SQL looks for the text \\x00")
        assertEquals("exactly twenty chars", g.hivemindView(obj("""{"name":"exactly twenty chars"}"""))["name"])
        assertEquals("http://example.com", g.hivemindView(obj("""{"website":"example.com"}"""))["website"])
        assertEquals("https://example.com", g.hivemindView(obj("""{"website":"https://example.com"}"""))["website"])
        assertEquals("", g.hivemindView(obj("""{"website":"https://${"w".repeat(100)}.example"}"""))["website"])
        assertEquals("", g.hivemindView(obj("""{"cover_image":"https://img.example/${"c".repeat(1024)}"}"""))["cover_image"])
        assertEquals(mapOf<String, String?>("name" to "", "about" to "", "location" to "", "website" to "", "profile_image" to "", "cover_image" to ""), g.hivemindView(obj("""{"name":null}""")))
        assertEquals("", g.hivemindView(null)["website"], "no key, no prefix")
        assertNull(g.hivemindView(obj("""{"name":7}"""))["name"], "shown as text by hivemind; not reproduced, not compared")
    }

    @Test
    fun hivemindSourceFollowsTheSqlFallbackRule() {
        val g = ProfileMetadataGuard
        val legacy = """{"profile":{"name":"Legacy"}}"""
        fun name(posting: String?, json: String?) = g.hivemindSource(posting, json).let { (it.fields?.get("name") as? com.ecency.core.api.model.JsValue.Str)?.value to it.legacy }
        // legacy only when the posting metadata is empty, not JSON, or under 3 characters as jsonb
        for (posting in listOf(null, "", "{}", " {} ", "[]", "\"\"", "7", "not json", "  ")) assertEquals("Legacy" to true, name(posting, legacy), "$posting")
        // a NUL anywhere makes the whole cast fail: the legacy profile is read for every field
        assertEquals("Legacy" to true, name("""{"profile":{"name":"Posting","about":"a\u0000b"}}""", legacy))
        // anything else is read, with or without a profile, and never falls back
        assertEquals("Posting" to false, name("""{"profile":{"name":"Posting"}}""", legacy))
        assertEquals(null to false, name("""{"profile":{}}""", legacy))
        assertEquals(null to false, name("""{"profile":null}""", legacy))
        assertEquals(null to false, name("""{"apps":["x"]}""", legacy))
        assertEquals("Root" to false, name("""{"name":"Root"}""", legacy), "no profile key: the root is read")
        assertEquals(null to false, name("123", legacy))
        // the legacy metadata: its profile, or its root when it has no wrapper
        assertEquals("Root legacy" to true, name("", """{"name":"Root legacy","about":"x"}"""))
        assertEquals(null to true, name("", "not json"))
        assertEquals(null to true, name("", ""))
    }

    // shapes of real rows (synthetic names): Ecency web writes an empty website, and wallets add tokens
    private val webShaped = """{"profile":{"name":"Web User","about":"Writes on the web","website":"","location":"Kaunas","profile_image":"https://img.example/a.png","cover_image":"","pinned":"old-pin","tokens":[{"symbol":"LEO","type":"ENGINE","meta":{"show":true}}],"custom":{"a":1},"version":2},"apps":["x"]}"""
    private val webShapedShown = """{"name":"Web User","about":"Writes on the web","website":"http://","location":"Kaunas","profile_image":"https://img.example/a.png","cover_image":"","blacklist_description":"","muted_list_description":""}"""

    @Test
    fun realWorldRowsAreNeverLockedOut() {
        val g = ProfileMetadataGuard
        // a present posting row with "website":"": hivemind shows "http://", five values, and so does the row's view
        val base = assertNotNull(g.safeBase(webShaped, "", hivemind(webShapedShown)))
        assertEquals(
            """{"profile":{"name":"Web User","about":"Writes on the web","website":"","location":"Kaunas","profile_image":"https://img.example/a.png","cover_image":"","pinned":"new-pin","tokens":[{"symbol":"LEO","type":"ENGINE","meta":{"show":true}}],"custom":{"a":1},"version":2},"apps":["x"]}""",
            metadataOf(base, "new-pin"),
        )
        // legacy only, with the same empty website
        val legacy = """{"profile":{"name":"Old Timer","about":"Since 2017","website":"","profile_image":"https://img.example/o.png"}}"""
        assertNotNull(g.safeBase("", legacy, hivemind("""{"name":"Old Timer","about":"Since 2017","website":"http://","profile_image":"https://img.example/o.png","cover_image":""}""")))
        // a long legacy value with leading white space: cut raw by hivemind, the app sees the answer trimmed
        val long = "  " + "word ".repeat(60)
        assertNotNull(g.safeBase("", """{"profile":{"name":"N","about":"$long"}}""", hivemind("""{"name":"N","about":"${long.take(157)}..."}""")))
        // a legacy profile whose fields sit at the root, without the profile wrapper
        val rooted = g.safeBase("", """{"name":"Rooted","about":"No wrapper","location":"X","other":{"keep":1}}""", hivemind("""{"name":"Rooted","about":"No wrapper","location":"X"}"""))
        assertEquals(listOf("name", "about", "location"), assertNotNull(rooted).profile.keys)
        assertEquals("""{"profile":{"name":"Rooted","about":"No wrapper","location":"X","pinned":"p","version":2}}""", metadataOf(rooted, "p"))
        // names starting with @, images that are not http(s), a website without a scheme: shown differently, never a lockout
        assertNotNull(g.safeBase("""{"profile":{"name":"@handle","about":"hi","website":"example.com","profile_image":"ipfs://x"}}""", "", hivemind("""{"name":"","about":"hi","website":"http://example.com","profile_image":""}""")))
        assertNotNull(g.safeBase("", """{"profile":{"name":"@handle","about":"hi","website":"example.com"}}""", hivemind("""{"name":"","about":"hi","website":"http://example.com"}""")))
        // a number where a string belongs is shown as its text by hivemind: counted, not compared
        assertNotNull(g.safeBase("", """{"profile":{"name":7,"about":"hi"}}""", hivemind("""{"name":"7","about":"hi"}""")))
        assertNotNull(g.safeBase("""{"profile":{"name":7,"about":"hi"}}""", "", hivemind("""{"name":"7","about":"hi"}""")))
        // hivemind lagging one edit behind a present row is fine
        assertNotNull(g.safeBase(webShaped, "", hivemind(webShapedShown.replace("Writes on the web", "Before the edit"))))
    }

    // a Steem-era account: hived copied json_metadata into posting at the hardfork (2018 values), the owner kept
    // editing with account_update, so hivemind has no posting row of its own and shows the legacy, current profile
    private val snapshot2018 = """{"profile":{"name":"Old Name","about":"From 2018","location":"Nowhere","website":"https://old.example","tokens":[{"symbol":"LEO"}],"custom":{"a":1}},"apps":["x"]}"""
    private val legacyCurrent = """{"profile":{"name":"Current","about":"Edited since, and longer","website":"https://now.example","profile_image":"https://img.example/now-a.png","cover_image":"https://img.example/now-c.png","dtube_pub":"k"}}"""
    private val legacyCurrentShown = """{"name":"Current","about":"Edited since, and longer","website":"https://now.example","location":"","profile_image":"https://img.example/now-a.png","cover_image":"https://img.example/now-c.png"}"""

    @Test
    fun aStaleHardforkSnapshotInPostingGivesWayToTheLegacyProfileHivemindShows() {
        val g = ProfileMetadataGuard
        // five values in the legacy profile against four in the snapshot: the count rule alone refused this forever
        val base = assertNotNull(g.safeBase(snapshot2018, legacyCurrent, hivemind(legacyCurrentShown)))
        // legacy fields win entirely (the location deleted since 2018 stays deleted), its other keys too; what
        // only the snapshot holds beyond the profile fields is kept
        assertEquals(listOf("name", "about", "website", "profile_image", "cover_image", "dtube_pub", "tokens", "custom"), base.profile.keys)
        assertEquals(
            """{"profile":{"name":"Current","about":"Edited since, and longer","website":"https://now.example","profile_image":"https://img.example/now-a.png","cover_image":"https://img.example/now-c.png","dtube_pub":"k","tokens":[{"symbol":"LEO"}],"custom":{"a":1},"pinned":"p","version":2},"apps":["x"]}""",
            metadataOf(base, "p"),
        )
        // the same with fewer legacy values than the snapshot (the count rule passed, and wrote the 2018 profile)
        val fewer = """{"profile":{"name":"Current","about":"Short"}}"""
        assertEquals(
            """{"profile":{"name":"Current","about":"Short","tokens":[{"symbol":"LEO"}],"custom":{"a":1},"pinned":"p","version":2},"apps":["x"]}""",
            metadataOf(assertNotNull(g.safeBase(snapshot2018, fewer, hivemind("""{"name":"Current","about":"Short"}"""))), "p"),
        )
        // after the app's own first write hivemind showing the legacy values is lag: the posting row stays the base
        val own = assertNotNull(g.safeBase(snapshot2018, fewer, hivemind("""{"name":"Current","about":"Short"}"""), ownWrite = true))
        assertEquals("Old Name", (own.profile["name"] as com.ecency.core.api.model.JsValue.Str).value)
        assertEquals(snapshot2018, own.postingJsonMetadata)
        // a posting row that is what hivemind shows is untouched, whatever the legacy metadata holds
        val matching = assertNotNull(g.safeBase(snapshot2018, legacyCurrent, hivemind("""{"name":"Old Name","about":"From 2018","location":"Nowhere","website":"https://old.example"}""")))
        assertEquals(snapshot2018, matching.postingJsonMetadata)
        assertEquals(listOf("name", "about", "location", "website", "tokens", "custom"), matching.profile.keys)
        // hivemind one edit behind a real posting row (legacy does not match either): the posting row, by count
        val lagging = assertNotNull(g.safeBase(snapshot2018, legacyCurrent, hivemind("""{"name":"Old Name","about":"Before the edit","location":"Nowhere","website":"https://old.example"}""")))
        assertEquals(snapshot2018, lagging.postingJsonMetadata)
        // a profile copied from legacy into posting and extended there: both match hivemind, the posting row is the base
        val migrated = """{"profile":{"about":"Short","name":"Current","blacklist_description":"mine","pinned":"x"},"apps":["x"]}"""
        assertEquals(migrated, assertNotNull(g.safeBase(migrated, fewer, hivemind("""{"name":"Current","about":"Short","blacklist_description":"mine"}"""))).postingJsonMetadata)
        // a legacy profile without any profile field "matches" a hivemind that shows nothing: that is no evidence
        assertEquals(snapshot2018, assertNotNull(g.safeBase(snapshot2018, """{"profile":{"dtube_pub":"k"}}""", hivemind("""{"name":""}"""))).postingJsonMetadata)
        // hivemind showing nothing never picks the legacy profile over a present row
        assertEquals(snapshot2018, assertNotNull(g.safeBase(snapshot2018, "", hivemind("""{"name":""}"""))).postingJsonMetadata)
    }

    @Test
    fun pinOnAStaleSnapshotAccountWritesTheCurrentProfile() = runTest {
        nodeMetadata = snapshot2018
        nodeLegacyMetadata = legacyCurrent
        hivemindProfile = legacyCurrentShown
        val app = TestApp(this, handler = reads())
        signedIn(app)
        val mine = post(entry(TestApp.USER, "fresh"))
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        val written = pinnedMetadata(app)
        assertEquals(
            """{"profile":{"name":"Current","about":"Edited since, and longer","website":"https://now.example","profile_image":"https://img.example/now-a.png","cover_image":"https://img.example/now-c.png","dtube_pub":"k","tokens":[{"symbol":"LEO"}],"custom":{"a":1},"pinned":"fresh","version":2},"apps":["x"]}""",
            written,
        )
        // what hivemind will show from the new posting row is what it showed before: nothing but the pin changed
        val after = ProfileMetadataGuard.hivemindView(ProfileMetadataGuard.hivemindSource(written, legacyCurrent).fields)
        assertEquals(ProfileMetadataGuard.hivemindView(ProfileMetadataGuard.hivemindSource("", legacyCurrent).fields), after)

        // hivemind still shows the legacy values right after (lag): the app's own posting row is the base now,
        // and an unpin keeps it
        nodeMetadata = written
        app.env.postActions.select(PostMenuItem.UNPIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        val op = app.broadcasts().last().params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertEquals(written.replace("\"pinned\":\"fresh\"", "\"pinned\":null"), op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content)
    }

    @Test
    fun pinFromARealWorldRowKeepsEverything() = runTest {
        nodeMetadata = webShaped
        hivemindProfile = webShapedShown
        val app = TestApp(this, handler = reads())
        signedIn(app)
        app.env.postActions.select(PostMenuItem.PIN_BLOG, post(entry(TestApp.USER, "new-pin")))
        testScheduler.advanceUntilIdle()
        assertNull(app.env.ui.current.toast?.text?.takeIf { it == ProfileMetadataGuard.REFUSED }, "an empty website must not lock the account out")
        assertEquals(
            """{"profile":{"name":"Web User","about":"Writes on the web","website":"","location":"Kaunas","profile_image":"https://img.example/a.png","cover_image":"","pinned":"new-pin","tokens":[{"symbol":"LEO","type":"ENGINE","meta":{"show":true}}],"custom":{"a":1},"version":2},"apps":["x"]}""",
            pinnedMetadata(app),
        )
    }

    @Test
    fun aBlankRowAfterTheAppKnewAPostingRowIsAStrippingNode() = runTest {
        // a profile migrated by copying the legacy one into posting, which also holds what hivemind does not show
        // (tokens, a pin, unknown keys)
        val legacy = """{"profile":{"name":"Legacy","about":"Old about"}}"""
        nodeLegacyMetadata = legacy
        hivemindProfile = """{"name":"Legacy","about":"Old about"}"""
        nodeMetadata = """{"profile":{"name":"Legacy","about":"Old about","pinned":"mine","tokens":[{"symbol":"LEO"}],"version":2},"apps":["x"]}"""
        val app = TestApp(this, handler = reads())
        signedIn(app)
        app.env.api.accounts.getAccount(TestApp.USER, refresh = true) // the app has seen the real row
        val mine = post(entry(TestApp.USER, "fresh"))
        nodeMetadata = "" // now a node blanks it: the legacy profile matches hivemind field by field
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty(), "tokens and unknown keys of the real row must not be dropped")
        assertEquals(ProfileMetadataGuard.REFUSED, app.env.ui.current.toast?.text)
        // and again: the refused read did not become what the app knows
        app.env.ui.clear()
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty(), "still refused on the second try")
        assertEquals(ProfileMetadataGuard.REFUSED, app.env.ui.current.toast?.text)
        // the node answers properly again
        nodeMetadata = """{"profile":{"name":"Legacy","about":"Old about","pinned":"mine","tokens":[{"symbol":"LEO"}],"version":2},"apps":["x"]}"""
        app.env.ui.clear()
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertEquals("""{"profile":{"name":"Legacy","about":"Old about","pinned":"fresh","tokens":[{"symbol":"LEO"}],"version":2},"apps":["x"]}""", pinnedMetadata(app))
    }

    @Test
    fun aWriteThatNeverLandedDoesNotLockALegacyAccountOut() = runTest {
        nodeLegacyMetadata = """{"profile":{"name":"Legacy","about":"Old about"}}"""
        hivemindProfile = """{"name":"Legacy","about":"Old about"}"""
        nodeMetadata = ""
        val app = TestApp(this, handler = reads())
        signedIn(app)
        val mine = post(entry(TestApp.USER, "fresh"))
        // the first pin is accepted by the node, then expires or is dropped by a fork: the chain row stays blank
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size)
        assertTrue(app.env.api.accounts.wrotePosting(TestApp.USER))
        // right after, two blank reads cannot tell "did not land" from "landed, stripped, hivemind lagging": try again
        app.env.ui.clear()
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size)
        assertEquals(ProfileMetadataGuard.REFUSED, app.env.ui.current.toast?.text)
        app.now += ProfileWrites.NOT_LANDED_AFTER_MILLIS - 1
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size, "still inside the window")
        // past any transaction's life and hivemind's lag, a blank row means the write never landed: go on
        app.now += 1
        val reads = accountReads(app)
        app.env.ui.clear()
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertEquals(2, accountReads(app) - reads, "one confirming reread")
        assertEquals(2, app.broadcasts().size, "not refused until a restart")
        val op = app.broadcasts().last().params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertEquals("""{"profile":{"name":"Legacy","about":"Old about","pinned":"fresh","version":2}}""", op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content)
    }

    /** A legacy-only account changes its About in the app (the first posting row, written by this process). */
    private fun TestScope.editAbout(app: TestApp, about: String) {
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        edit.setValue(ProfileField.ABOUT, about)
        edit.save()
        testScheduler.advanceUntilIdle()
        assertTrue(edit.current.saved, "${edit.current.alert}")
        edit.close()
        AvatarCacheStamps.clear()
    }

    @Test
    fun anOwnWriteHivemindShowsIsAStripWhenTheRowReadsBlank() = runTest {
        nodeLegacyMetadata = """{"profile":{"name":"Legacy","about":"Old about"}}"""
        hivemindProfile = """{"name":"Legacy","about":"Old about"}"""
        nodeMetadata = ""
        val app = TestApp(this, handler = reads())
        signedIn(app)
        editAbout(app, "New about")
        assertEquals(1, app.broadcasts().size)
        // the write landed: hivemind shows it. The pool's node blanks the field on every read
        hivemindProfile = """{"name":"Legacy","about":"New about"}"""
        val mine = post(entry(TestApp.USER, "fresh"))
        app.env.ui.clear()
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size, "a pin from the legacy profile would undo the edit")
        assertEquals(ProfileMetadataGuard.REFUSED, app.env.ui.current.toast?.text)
        assertTrue(app.env.api.accounts.sawPosting(TestApp.USER), "confirmed by hivemind: as good as a node's row")
        // and no amount of waiting turns the strip into a legacy account
        app.now += 10 * ProfileWrites.NOT_LANDED_AFTER_MILLIS
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size)
    }

    @Test
    fun anOwnWriteHivemindDoesNotShowYetIsNotUndoneInsideTheLagWindow() = runTest {
        nodeLegacyMetadata = """{"profile":{"name":"Legacy","about":"Old about"}}"""
        hivemindProfile = """{"name":"Legacy","about":"Old about"}"""
        nodeMetadata = ""
        val app = TestApp(this, handler = reads())
        signedIn(app)
        editAbout(app, "New about")
        // landed, but hivemind lags (still the legacy values, which match the legacy row) and the node strips
        val mine = post(entry(TestApp.USER, "fresh"))
        app.env.ui.clear()
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size, "the pin must not overwrite the edit just made")
        assertEquals(ProfileMetadataGuard.REFUSED, app.env.ui.current.toast?.text)
        // hivemind catches up inside the window: now it is known to have landed
        app.now += 30_000
        hivemindProfile = """{"name":"Legacy","about":"New about"}"""
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size)
        assertTrue(app.env.api.accounts.sawPosting(TestApp.USER))
        // a proper read: the pin builds on the edit
        nodeMetadata = app.env.api.accounts.written(TestApp.USER)!!.postingJsonMetadata
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        val op = app.broadcasts().last().params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertTrue(op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content.contains("New about"))
        assertTrue(op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content.contains("\"pinned\":\"fresh\""))
    }

    @Test
    fun aPostingRowANodeShowedKeepsRefusingABlankOne() = runTest {
        nodeLegacyMetadata = """{"profile":{"name":"Legacy","about":"Old about"}}"""
        hivemindProfile = """{"name":"Legacy","about":"Old about"}"""
        nodeMetadata = ""
        val app = TestApp(this, handler = reads())
        signedIn(app)
        val mine = post(entry(TestApp.USER, "fresh"))
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        // this time the write landed and a node showed it: that is knowledge of the chain, not only of our broadcast
        nodeMetadata = """{"profile":{"name":"Legacy","about":"Old about","pinned":"fresh","version":2}}"""
        app.env.api.accounts.getAccount(TestApp.USER, refresh = true)
        assertFalse(app.env.api.accounts.wrotePosting(TestApp.USER))
        assertTrue(app.env.api.accounts.sawPosting(TestApp.USER))
        nodeMetadata = "" // a stripping node
        repeat(2) {
            app.env.ui.clear()
            app.env.postActions.select(PostMenuItem.UNPIN_BLOG, mine)
            testScheduler.advanceUntilIdle()
            assertEquals(1, app.broadcasts().size, "refused, every time")
            assertEquals(ProfileMetadataGuard.REFUSED, app.env.ui.current.toast?.text)
        }
        // the edit form does not open on the stripped read either (it would show the legacy values, then refuse to save)
        val edit = app.env.profileEditModel()
        edit.start()
        testScheduler.advanceUntilIdle()
        assertFalse(edit.current.loaded)
        assertEquals(ProfileMetadataGuard.REFUSED, edit.current.loadError?.message)
        edit.close()
    }

    @Test
    fun aRefusedRowDoesNotStayInTheAccountCache() = runTest {
        hivemindProfile = currentShown
        nodeLegacyMetadata = oldLegacy
        nodeMetadata = "" // blanked from the start: the app never had a good row to put back
        val app = TestApp(this, handler = reads())
        signedIn(app)
        app.env.postActions.select(PostMenuItem.PIN_BLOG, post(entry(TestApp.USER, "fresh")))
        testScheduler.advanceUntilIdle()
        assertEquals(ProfileMetadataGuard.REFUSED, app.env.ui.current.toast?.text)
        assertNull(app.env.api.accounts.cached(TestApp.USER), "the stripped row is not what the profile screen shows next")
    }

    @Test
    fun aBlankRowTheAppNeverSawOtherwiseIsALegacyAccount() = runTest {
        nodeLegacyMetadata = """{"profile":{"name":"Legacy","about":"Old about"}}"""
        hivemindProfile = """{"name":"Legacy","about":"Old about"}"""
        nodeMetadata = ""
        val app = TestApp(this, handler = reads())
        signedIn(app)
        app.env.api.accounts.getAccount(TestApp.USER, refresh = true) // seen, and blank then too
        app.env.postActions.select(PostMenuItem.PIN_BLOG, post(entry(TestApp.USER, "fresh")))
        testScheduler.advanceUntilIdle()
        assertEquals("""{"profile":{"name":"Legacy","about":"Old about","pinned":"fresh","version":2}}""", pinnedMetadata(app))
    }

    @Test
    fun pinToBlogRefusesWhenOnlyThePostingRowIsBlanked() = runTest {
        hivemindProfile = currentShown
        nodeLegacyMetadata = oldLegacy
        nodeMetadata = "" // the node blanked posting_json_metadata and nothing else
        val app = TestApp(this, handler = reads())
        signedIn(app)
        val mine = post(entry(TestApp.USER, "fresh"))
        val readsBefore = accountReads(app)
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty(), "the 2018 profile must never replace the current one")
        assertEquals(ProfileMetadataGuard.REFUSED, app.env.ui.current.toast?.text)
        // read once more before refusing
        assertEquals(2, accountReads(app) - readsBefore)

        // the node answers properly again: the pin keeps the current profile, its tokens and unknown keys
        nodeMetadata = currentPosting
        app.env.ui.clear()
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertEquals(
            """{"profile":{"name":"Current","about":"Now","location":"Vilnius","website":"https://now.example","cover_image":"https://img.example/now.png","tokens":[{"symbol":"LEO"}],"custom":{"a":1},"version":2,"pinned":"fresh"},"apps":["x"]}""",
            pinnedMetadata(app),
        )
    }

    private fun accountReads(app: TestApp) = app.transport.sent.count { it.method == "condenser_api.get_accounts" }

    @Test
    fun pinToBlogNeverBroadcastsFromAStrippedNode() = runTest {
        hivemindProfile = """{"name":"Spike","about":"hi"}"""
        nodeMetadata = "" // the node stripped it
        val app = TestApp(this, handler = reads())
        signedIn(app)
        val mine = post(entry(TestApp.USER, "fresh"))
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty(), "a stripped read must not be broadcast")
        assertNull(app.env.signing.current.prompt, "a stripped read never reaches signing")
        assertEquals(ProfileMetadataGuard.REFUSED, app.env.ui.current.toast?.text)

        // hivemind unreadable: nothing to compare with, refused as well
        nodeMetadata = """{"profile":{"name":"Spike","about":"hi","cover_image":"https://x/c.png"}}"""
        hivemindDown = true
        app.env.ui.clear()
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty(), "no hivemind answer, no write")
        assertEquals(ProfileMetadataGuard.REFUSED, app.env.ui.current.toast?.text)

        hivemindDown = false
        app.env.ui.clear()
        app.env.postActions.select(PostMenuItem.PIN_BLOG, mine)
        testScheduler.advanceUntilIdle()
        // past the guard: a metadata-only update is a posting operation (SDK useAccountUpdate), signed with the key held
        assertNull(app.env.signing.current.prompt)
        assertEquals(
            """{"profile":{"name":"Spike","about":"hi","cover_image":"https://x/c.png","pinned":"fresh","version":2}}""",
            pinnedMetadata(app),
        )
    }

    private fun pinnedMetadata(app: TestApp): String {
        val op = app.broadcasts().single().params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertEquals("account_update2", op[0].jsonPrimitive.content)
        assertEquals("", op[1].jsonObject["json_metadata"]!!.jsonPrimitive.content)
        return op[1].jsonObject["posting_json_metadata"]!!.jsonPrimitive.content
    }

    @Test
    fun pinToBlogKeepsAProfileThatLivesOnlyInLegacyJsonMetadata() = runTest {
        // an old account: nothing in posting_json_metadata, the profile hivemind and the app show is json_metadata's
        nodeMetadata = ""
        nodeLegacyMetadata = """{"profile":{"name":"Legacy","about":"Old about","cover_image":"https://img.example/old.png","location":"Vilnius"},"beneficiary":"x"}"""
        hivemindProfile = """{"name":"Legacy","about":"Old about","cover_image":"https://img.example/old.png","location":"Vilnius"}"""
        val app = TestApp(this, handler = reads())
        signedIn(app)
        app.env.postActions.select(PostMenuItem.PIN_BLOG, post(entry(TestApp.USER, "fresh")))
        testScheduler.advanceUntilIdle()
        assertEquals(
            """{"profile":{"name":"Legacy","about":"Old about","cover_image":"https://img.example/old.png","location":"Vilnius","pinned":"fresh","version":2}}""",
            pinnedMetadata(app),
        )
    }

    @Test
    fun unpinFromBlogWritesNullAndKeepsEveryOtherKey() = runTest {
        nodeMetadata = """{"profile":{"name":"Spike","pinned":"mine","tokens":[{"symbol":"LEO","meta":{"address":"a","privateKey":"k"}}],"custom":{"a":1}},"apps":["x"]}"""
        hivemindProfile = """{"name":"Spike"}"""
        val app = TestApp(this, handler = reads())
        signedIn(app)
        app.env.postActions.select(PostMenuItem.UNPIN_BLOG, post(entry(TestApp.USER, "mine")))
        testScheduler.advanceUntilIdle()
        // unknown keys and the wallet token list stay (tokens cleaned of private fields, as the SDK writes them)
        assertEquals(
            """{"profile":{"name":"Spike","pinned":null,"tokens":[{"symbol":"LEO","meta":{"address":"a"}}],"custom":{"a":1},"version":2},"apps":["x"]}""",
            pinnedMetadata(app),
        )
    }

    @Test
    fun deleteIsNotOfferedForPaidOutOrCrossPosts() = runTest {
        val app = TestApp(this, handler = reads())
        signedIn(app)
        val mine = post(entry(TestApp.USER, "fresh", netRshares = 0))
        assertTrue(PostMenuItem.DELETE in app.env.postActions.menu(mine, PostMenuContext()))
        assertFalse(PostMenuItem.DELETE in app.env.postActions.menu(mine.copy(isPaidout = true), PostMenuContext()))
        // a cross post wrapper acts on the original: deleting from the wrapper's card would remove the original
        val wrapper = post(entry(TestApp.USER, "fresh-hive-100001")).copy(crossPostOriginal = mine)
        assertFalse(PostMenuItem.DELETE in app.env.postActions.menu(wrapper, PostMenuContext()))
    }

    @Test
    fun reblogAndMuteOutOfResourceCreditsRaiseTheAppWideOffer() = runTest {
        val base = reads()
        val app = TestApp(this, handler = { req ->
            if (req.method == "condenser_api.broadcast_transaction") FakeTransport.error(req, -32000, "Account: spike-voter has 12 RC, needs 3400 RC. Please wait to transact, or power up HIVE.") else base(req)
        })
        signedIn(app)
        val p = post(entry("alice", "one"))
        // RN postOptionsModal reblog: the offer, not "Fail!"
        assertEquals(PostMenuEffect.Started, app.env.postActions.select(PostMenuItem.REBLOG, p))
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.rcOffer.current.open)
        assertNull(app.env.ui.current.toast)
        app.env.rcOffer.choose(RcOfferChoice.CANCEL)
        // mute (RN _profileActionDone): the offer, not the alert
        assertEquals(PostMenuEffect.Started, app.env.postActions.select(PostMenuItem.MUTE, p))
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.rcOffer.current.open)
        assertNull(app.env.postActions.current.alert)
    }

    @Test
    fun aSecondActionOnTheSamePostSaysBusy() = runTest {
        val app = TestApp(this, handler = reads())
        signedIn(app)
        val p = post(entry("alice", "one"))
        assertEquals(PostMenuEffect.Started, app.env.postActions.select(PostMenuItem.REBLOG, p))
        assertEquals(PostMenuEffect.None, app.env.postActions.select(PostMenuItem.MUTE, p))
        assertEquals(PostActionsModel.BUSY, app.env.ui.current.toast?.text)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size)
    }

    @Test
    fun hostileEditHistoryIsCappedAndRebuiltOffTheMainDispatcher() = runTest {
        val patch = "@@ -1,3 +1,4 @@\n abc\n+d\n"
        val big = "@@ -1,3 +1,4 @@\n abc\n+" + "x".repeat(60_000) + "\n"
        var list = (1..3).joinToString(",") { """{"title":"T","body":${Json.encodeToString(if (it == 1) "abc" else patch)},"tags":[],"timestamp":"","v":$it}""" }
        val app = TestApp(this, handler = reads())
        app.authBackend.rest = { r -> if (r.url.endsWith("/private-api/comment-history")) HttpResult(200, """{"list":[$list]}""") else null }
        val before = app.render.dispatches
        val ok = app.env.editHistoryModel("alice", "one")
        ok.start()
        testScheduler.advanceUntilIdle()
        assertEquals(3, ok.current.versions.size)
        assertTrue(app.render.dispatches > before, "patches are applied on the render dispatcher")
        // many large patches: refused, with RN's failure text
        list = (1..8).joinToString(",") { """{"title":"T","body":${Json.encodeToString(if (it == 1) "abc" else big)},"tags":[],"timestamp":"","v":$it}""" }
        val hostile = app.env.editHistoryModel("alice", "two")
        hostile.start()
        testScheduler.advanceUntilIdle()
        assertTrue(hostile.current.versions.isEmpty())
        assertEquals("No History found!", hostile.current.message)
        ok.close()
        hostile.close()
    }

    private val spanish = "Hola a todos, este es un texto largo en español para que la detección de idioma funcione y el lector vea la opción."
    private val english = "This is a long enough English text for the reader, and it is the same language that the reader of this post has."

    @Test
    fun languageDetectionStaysOnTheDeviceForTheReadersLanguage() = runTest {
        val app = TestApp(this, handler = reads())
        val calls = mutableListOf<com.ecency.core.auth.AuthHttpRequest>()
        app.authBackend.rest = { r ->
            when {
                r.url == "https://translate.ecency.com/detect" -> { calls += r; HttpResult(200, """[{"confidence":90,"language":"es"}]""") }
                r.url == "https://translate.ecency.com/translate" -> { calls += r; HttpResult(200, """{"translatedText":"Hello","detectedLanguage":{"language":"es"}}""") }
                else -> null
            }
        }
        // the reader's own language: no request at all
        val same = app.env.translateModel("alice", "en", "en-US")
        same.start(Post(author = "alice", permlink = "en", body = english))
        testScheduler.advanceUntilIdle()
        assertTrue(calls.isEmpty(), "an English post for an English reader is not sent anywhere")
        assertFalse(same.current.show)

        // logged out (RN #3460): the device guess decides, /detect is never asked, and the guess is not remembered
        val p = Post(author = "alice", permlink = "es", body = spanish)
        val loggedOut = app.env.translateModel("alice", "es", "en-US")
        loggedOut.start(p)
        testScheduler.advanceUntilIdle()
        assertTrue(calls.isEmpty(), "no /detect for a logged-out reader")
        assertTrue(loggedOut.current.show, "the banner still comes from the device guess")
        assertFalse(app.env.detectedLanguages.has(PostKey("alice", "es")))
        loggedOut.close()

        app.signIn()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        // another language: one /detect with the 600 character summary sample, and no translation yet
        val other = app.env.translateModel("alice", "es", "en-US")
        other.start(p)
        testScheduler.advanceUntilIdle()
        assertEquals(1, calls.size)
        assertEquals(json("""{"q":${Json.encodeToString(spanish)},"api_key":""}"""), json(calls.single().body!!))
        assertTrue(other.current.show)
        assertEquals("This post is in Spanish. Translate to English?", other.current.bannerText)
        assertTrue(calls.none { it.url.endsWith("/translate") }, "translation only starts when the reader asks")

        // the same post again this session: answered from the cache
        val again = app.env.translateModel("alice", "es", "en-US")
        again.start(p)
        testScheduler.advanceUntilIdle()
        assertEquals(1, calls.size)
        assertTrue(again.current.show)

        // muted and NSFW posts are never sent
        val muted = app.env.translateModel("alice", "m", "en-US")
        muted.start(Post(author = "alice", permlink = "m", body = spanish, moderationReason = com.ecency.core.api.model.ModerationReason.MOD_MUTED))
        val nsfw = app.env.translateModel("alice", "n", "en-US")
        nsfw.start(Post(author = "alice", permlink = "n", body = spanish, metadata = com.ecency.core.api.model.PostMetadata(tags = listOf("nsfw"))))
        testScheduler.advanceUntilIdle()
        assertEquals(1, calls.size)

        other.translate(p)
        testScheduler.advanceUntilIdle()
        val t = calls.last()
        assertTrue(t.url.endsWith("/translate"))
        assertEquals(json("""{"q":${Json.encodeToString(spanish)},"source":"auto","target":"en","format":"text","api_key":""}"""), json(t.body!!))
        assertEquals("Hello", other.current.translated)
        listOf(same, other, again, muted, nsfw).forEach { it.close() }
    }

    @Test
    fun languageGuess() {
        assertEquals("en", Languages.guess(english))
        assertEquals("es", Languages.guess(spanish))
        assertEquals("ko", Languages.guess("안녕하세요 반갑습니다 오늘은 날씨가 좋습니다"))
        assertEquals("ja", Languages.guess("今日は天気がいいですね、散歩に行きましょう"))
        assertEquals("zh", Languages.guess("今天天气很好我们去公园散步"))
        // several languages share Cyrillic: not decided on the device
        assertNull(Languages.guess("Привет, как дела сегодня"))
        assertNull(Languages.guess("12345 !!!"))
        val cache = DetectedLanguages(max = 2)
        cache.put(PostKey("a", "1"), "en"); cache.put(PostKey("a", "2"), null); cache.put(PostKey("a", "3"), "es")
        assertFalse(cache.has(PostKey("a", "1")))
        assertTrue(cache.has(PostKey("a", "2")) && cache.get(PostKey("a", "3")) == "es")
    }

    @Test
    fun theIgnoreListLoadsAtSignInAndFiltersFeedsAndPromoted() = runTest {
        ignored = listOf("bob", "promo")
        val app = TestApp(this, handler = reads())
        app.authBackend.rest = { r ->
            if (r.url.endsWith("/private-api/promoted-entries")) HttpResult(200, "[${entry("promo", "ad")},${entry("fine", "ad2")},${entry("fine", "ad2")}]") else null
        }
        signedIn(app)
        assertEquals(setOf("bob", "promo"), app.env.mutedAuthors.toSet())
        val call = app.transport.sent.single { it.method == "condenser_api.get_following" }
        assertEquals(json("""["${TestApp.USER}","","ignore",1000]"""), call.params)
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING), withPromoted = true)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("one"), feed.current.items.map { it.key.permlink })
        feed.close()
    }

    @Test
    fun promotedEntriesAreDedupedAmongThemselves() {
        val feed = (1..8).map { "f$it" }
        assertEquals(listOf("f1", "f2", "f3", "p1", "f4", "f5", "f6", "p2", "f7", "f8"), Promoted.inject(feed, listOf("p1", "p1", "p2"), { it }))
    }

    @Test
    fun tagFollowStateUsesTheCheckEndpoint() = runTest {
        val app = TestApp(this, handler = reads())
        app.authBackend.rest = { r -> if (r.url.endsWith("/private-api/favorite-tags-check")) HttpResult(200, "true") else null }
        signedIn(app)
        val model = app.env.tagFollowModel("#Photography")
        model.start()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible && model.current.following)
        val sent = app.authBackend.sent.single { it.url.contains("favorite-tags") }
        assertEquals(json("""{"tag":"photography","code":"token-${TestApp.USER}"}"""), json(sent.body!!))
        model.close()
    }

    @Test
    fun endedPollsAndEmptySelectionsDoNotVote() = runTest {
        val results = """[{"poll_trx_id":"trx1","poll_choices":[{"choice_num":1,"choice_text":"A","votes":{"total_votes":3,"hive_hp":10}}],"poll_voters":[]}]"""
        val app = TestApp(this, handler = reads())
        app.authBackend.rest = { r -> if (r.url.startsWith("https://poll.ecency.com/rpc/poll")) HttpResult(200, results) else null }
        signedIn(app)
        fun meta(end: Long) = """{"content_type":"poll","question":"Q","choices":["A"],"end_time":$end}"""
        val open = app.env.pollModel(post(entry("alice", "open", metadata = meta(TestApp.NOW / 1000 + 3600))))!!
        open.start()
        testScheduler.advanceUntilIdle()
        assertTrue(open.current.canVote)
        open.vote() // nothing selected
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty())
        val ended = app.env.pollModel(post(entry("alice", "ended", metadata = meta(TestApp.NOW / 1000 - 60))))!!
        ended.start()
        testScheduler.advanceUntilIdle()
        assertFalse(ended.current.canVote)
        assertEquals("Ended", ended.current.endText)
        assertTrue(ended.current.showResults)
        ended.toggle(1)
        ended.vote()
        testScheduler.advanceUntilIdle()
        assertTrue(ended.current.selection.isEmpty() && app.broadcasts().isEmpty())
        open.close()
        ended.close()
    }

    // ---------------------------------------------------------------- feed

    @Test
    fun feedTabsNeedThreeAndFallBack() {
        val prefs = InMemoryPrefs()
        assertEquals(FeedTabs.DEFAULT, FeedTabs.tabs(prefs, loggedIn = true))
        assertEquals(FeedTabs.GUEST, FeedTabs.tabs(prefs, loggedIn = false))
        assertEquals(FeedTabs.TOO_FEW, FeedTabs.save(prefs, listOf(FeedFilter.HOT, FeedFilter.TRENDING)))
        assertNull(FeedTabs.save(prefs, listOf(FeedFilter.CREATED, FeedFilter.HOT, FeedFilter.BLOG)))
        assertEquals(listOf(FeedFilter.CREATED, FeedFilter.HOT, FeedFilter.BLOG), FeedTabs.tabs(prefs, loggedIn = true))
        assertEquals(FeedFilter.CREATED, FeedTabs.sheetOrder(listOf(FeedFilter.CREATED)).first())
        assertEquals(FeedSource.Account("me", com.ecency.core.api.AccountPostsSort.FEED), FeedFilter.FRIENDS.source("me"))
        assertNull(FeedFilter.FRIENDS.source(null))
    }

    @Test
    fun promotedEntriesGoEveryFourthFromTheFourth() {
        val feed = (1..10).map { "f$it" }
        assertEquals(
            listOf("f1", "f2", "f3", "p1", "f4", "f5", "f6", "p2", "f7", "f8", "f9", "p3", "f10"),
            Promoted.inject(feed, listOf("p1", "p2", "p3", "p4"), { it }),
        )
        // already in the feed or muted: dropped
        assertEquals(listOf("f1", "f2", "f3", "p2", "f4"), Promoted.inject(feed.take(4), listOf("f2", "x", "p2"), { it }) { it == "x" })
        // the list is too short: none
        assertEquals(listOf("f1", "f2", "f3"), Promoted.inject(feed.take(3), listOf("p1"), { it }))
    }

    @Test
    fun nsfwHideDropsPostsAndWarnMarksThem() = runTest {
        val nsfw = entry("carol", "nsfw-post", metadata = """{"tags":["nsfw","art"]}""")
        val app = TestApp(this, handler = { req ->
            when (req.method) {
                "bridge.get_ranked_posts" -> FakeTransport.result(req, "[${entry("alice", "one")},$nsfw]")
                else -> HttpResult(500, "")
            }
        })
        val warn = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        warn.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false, true), warn.current.items.map { it.isNsfw })
        app.env.prefs.putString(NsfwMode.PREFS_KEY, NsfwMode.HIDE.value)
        warn.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("one"), warn.current.items.map { it.key.permlink })
        warn.close()
    }

    @Test
    fun promotedCardsAreMarkedAndInjected() = runTest {
        val feedJson = (1..5).joinToString(",") { entry("a$it", "p$it") }
        val app = TestApp(this, handler = { req ->
            when (req.method) {
                "bridge.get_ranked_posts" -> FakeTransport.result(req, "[$feedJson]")
                else -> HttpResult(500, "")
            }
        })
        app.authBackend.rest = { r ->
            if (r.url.endsWith("/private-api/promoted-entries")) HttpResult(200, "[${entry("promo", "ad")},${entry("a1", "p1")}]") else null
        }
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING), withPromoted = true)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("p1", "p2", "p3", "ad", "p4", "p5"), feed.current.items.map { it.key.permlink })
        assertEquals("PROMOTED", feed.current.items[3].featuredText)
        feed.close()
    }

    // ---------------------------------------------------------------- lists

    @Test
    fun votersShareThePayoutByRshares() {
        val p = Post(author = "a", permlink = "p", created = "2026-10-01T10:00:00", pendingPayoutValue = "10.000 HBD")
        val votes = listOf(
            com.ecency.core.api.ActiveVoteDetail("small", 100, 3333, "2026-10-01T11:00:00"),
            com.ecency.core.api.ActiveVoteDetail("big", 900, 10000, "1970-01-01T00:00:00"),
            com.ecency.core.api.ActiveVoteDetail("down", -0, -10000, "2026-10-01T12:00:00"),
        )
        val rows = VotersModel.rows(votes, p, TestApp.PROXY, TestApp.NOW)
        assertEquals(listOf("big", "small", "down"), rows.map { it.voter })
        assertEquals(listOf("9.000", "1.000", "0.000"), rows.map { it.reward })
        assertEquals(listOf("100", "33.33", "-100"), rows.map { it.percent })
        // a vote time before the post (old chain votes) shows no time
        assertNull(rows[0].timeAgo)
        assertEquals("5 days ago", rows[1].timeAgo)
        assertEquals(listOf("down", "small", "big"), VotersModel.sort(rows, VoterSort.TIME, ascending = false).map { it.voter })
    }

    @Test
    fun votersSortAndSearchLikeRn() = runTest {
        val app = TestApp(this, handler = { req ->
            when (req.method) {
                "condenser_api.get_active_votes" -> FakeTransport.result(
                    req,
                    """[{"voter":"alpha","rshares":"100","percent":5000,"time":"2026-10-02T00:00:00"},
                        {"voter":"beta","rshares":300,"percent":10000,"time":"2026-10-03T00:00:00"},
                        {"voter":"gamma","rshares":200,"percent":100,"time":"2026-10-04T00:00:00"}]""",
                )
                "bridge.get_post" -> FakeTransport.result(req, entry("a", "p", pending = "6.000 HBD"))
                else -> HttpResult(500, "")
            }
        })
        val model = app.env.votersModel("a", "p")
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals("Voters Info (3)", model.current.title)
        assertEquals(listOf("beta", "gamma", "alpha"), model.current.rows.map { it.voter })
        assertEquals(listOf("3.000", "2.000", "1.000"), model.current.rows.map { it.reward })
        model.selectSort(VoterSort.TIME)
        assertEquals(listOf("gamma", "beta", "alpha"), model.current.rows.map { it.voter })
        model.selectSort(VoterSort.TIME)
        assertEquals(listOf("alpha", "beta", "gamma"), model.current.rows.map { it.voter })
        model.search("MM")
        assertEquals(listOf("gamma"), model.current.rows.map { it.voter })
        model.close()
    }

    @Test
    fun reblogsListAndButton() = runTest {
        val app = TestApp(this, handler = reads(reblogged = listOf("x", TestApp.USER, "y")))
        signedIn(app)
        val model = app.env.reblogsModel("alice", "one")
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals("Reblog Info (3)", model.current.title)
        assertEquals("Undo Reblog", model.current.buttonLabel)
        model.search("Y")
        assertEquals(listOf("y"), model.current.accounts)
        model.close()
    }

    @Test
    fun editHistoryRebuildsPatchedBodiesAndDiffs() = runTest {
        val patch = "@@ -1,14 +1,20 @@\n Hello \n+brave \n world, t\n@@ -24,13 +24,17 @@\n  is the \n+new \n body.\n"
        val app = TestApp(this, handler = reads())
        app.authBackend.rest = { r ->
            if (r.url.endsWith("/private-api/comment-history")) HttpResult(
                200,
                """{"meta":{"count":2},"list":[
                  {"title":"T1","body":"Hello world, this is the body.","tags":["a","b"],"timestamp":"2026-10-01T10:00:00","v":1},
                  {"title":"T2","body":${Json.encodeToString(patch)},"tags":["a"],"timestamp":"2026-10-02T10:00:00","v":2}]}""",
            ) else null
        }
        val model = app.env.editHistoryModel("alice", "one")
        model.start()
        testScheduler.advanceUntilIdle()
        val v = model.current.versions
        assertEquals("Hello brave world, this is the new body.", v[1].body)
        assertEquals("Version 2", EditHistoryModel.versionLabel(v[1]))
        assertEquals(json("""{"author":"alice","permlink":"one","onlyMeta":""}"""), json(app.authBackend.sent.single { it.url.endsWith("comment-history") }.body!!))
        model.select(1)
        model.toggleDiff()
        testScheduler.advanceUntilIdle()
        val body = model.current.diff!!.body
        assertEquals("Hello brave world, this is the new body.", body.filter { it.kind >= 0 }.joinToString("") { it.text })
        assertEquals("Hello world, this is the body.", body.filter { it.kind <= 0 }.joinToString("") { it.text })
        assertEquals(listOf(com.ecency.core.api.DiffSpan(0, "a"), com.ecency.core.api.DiffSpan(-1, ", b")), model.current.diff!!.tags)
        // empty list and failures: RN's alerts
        app.authBackend.rest = { r -> if (r.url.endsWith("/private-api/comment-history")) HttpResult(500, "") else null }
        val failed = app.env.editHistoryModel("alice", "two")
        failed.start()
        testScheduler.advanceUntilIdle()
        assertEquals("No History found!", failed.current.message)
        model.close()
        failed.close()
    }

    // ---------------------------------------------------------------- polls

    @Test
    fun pollFromMetadataAndVote() = runTest {
        val meta = """{"content_type":"poll","question":" Best? ","choices":["A","B","C"],"end_time":1893456000,
            "max_choices_voted":2,"filters":{"account_age":30},"preferred_interpretation":"number_of_votes","version":1.1}"""
        val app = TestApp(this, handler = reads())
        app.authBackend.rest = { r ->
            if (r.url.startsWith("https://poll.ecency.com/rpc/poll?author=eq.alice&permlink=eq.one")) HttpResult(
                200,
                """[{"poll_trx_id":"trx1","max_choices_voted":2,"filter_account_age_days":0,
                   "poll_choices":[{"choice_num":1,"choice_text":"A","votes":{"total_votes":3,"hive_hp":10}},
                                   {"choice_num":2,"choice_text":"B","votes":{"total_votes":1,"hive_hp":5}},
                                   {"choice_num":3,"choice_text":"C","votes":{"total_votes":0,"hive_hp":0}}],
                   "poll_voters":[{"name":"z","choice_num":1}]}]""",
            ) else null
        }
        signedIn(app)
        val p = post(entry("alice", "one", metadata = meta))
        val model = app.env.pollModel(p)!!
        assertEquals("Best?", model.current.poll!!.question)
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("• Only accounts older than 30 days allowed", "• You may select upto 2 choices"), model.current.notes)
        assertTrue(model.current.canVote)
        assertFalse(model.current.showResults)
        model.toggle(1)
        model.toggle(3)
        model.toggle(2) // over the maximum: ignored (RN)
        assertEquals(listOf(1, 3), model.current.selection)
        model.vote()
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().single().body.contains("""{\"poll\":\"trx1\",\"action\":\"vote\",\"choices\":[1,3]}"""))
        assertTrue(model.current.showResults)
        assertEquals(listOf("4 voted", "1 voted", "1 voted"), model.current.rows.map { it.label })
        assertEquals(listOf(true, false, true), model.current.rows.map { it.votedByMe })
        assertEquals(listOf("z"), model.votersOf(1).filter { it != TestApp.USER })
        assertNull(app.env.pollModel(post(entry("alice", "two"))))
        model.close()
    }

    // ---------------------------------------------------------------- reading

    @Test
    fun languagesAndChunks() {
        assertEquals("zt", Languages.normalize("zh_TW"))
        assertEquals("nb", Languages.normalize("nn-NO"))
        assertEquals("pt", Languages.normalize("pt-BR"))
        assertEquals("de", Languages.readerLanguage("xx", "de-AT"))
        assertEquals("en", Languages.readerLanguage(null, "xx"))
        assertTrue(Languages.isRtl("he"))
        val text = (1..500).joinToString(" ") { "word$it" }
        val chunks = Languages.chunk(text, 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(text, chunks.joinToString(" "))
        assertEquals(listOf("a".repeat(10), "a".repeat(5)), Languages.chunk("a".repeat(15), 10))
    }

    @Test
    fun plainTextFollowsRnRegexChain() {
        val body = """
            # Title here
            Some **bold** and *italic* text with [a link](https://x.y) and ![img](https://i.png).
            <div>html</div> https://example.com/raw
            > quoted line
            ---
            `code` &amp; done! Next?
        """.trimIndent()
        assertEquals(
            "Title here Some bold and italic text with a link and . ... html quoted line code & done! ... Next?",
            Speech.plainText(body),
        )
        assertEquals(2 to 3, Speech.wordCount("你好 one two ... three"))
        assertEquals(1, Speech.readingMinutes(0, 10))
        assertEquals(3, Speech.readingMinutes(500, 250))
    }

    @Test
    fun formats() {
        assertEquals("1.000", Num.fixed(0.9995, 3))
        // JS toFixed rounds the exact binary value
        assertEquals("12.35", Num.fixed(12.345, 2))
        assertEquals("1.00", Num.fixed(1.005, 2))
        assertEquals("3", Num.fixed(2.5, 0))
        assertEquals("-2.50", Num.fixed(-2.5, 2))
        assertEquals("0.5", Num.plain(0.5))
        assertEquals("100", Num.plain(100.0))
        assertEquals("a few seconds ago", FromNow.format(0, 10_000))
        assertEquals("in 3 days", FromNow.format(3 * 86_400_000L, 0))
        assertEquals("2 hours ago", FromNow.format(0, 2 * 3_600_000L))
        assertEquals("tag", TagFollowModel.normalize(" #Tag "))
        assertNull(TagFollowModel.normalize("hive-123"))
        assertNull(TagFollowModel.normalize("bad tag"))
        assertTrue(com.ecency.core.api.BadActors.flagged(" Phisher , ok", setOf("phisher")))
        assertEquals(setOf("a", "b"), com.ecency.core.api.BadActors.parse("a\n\nb\n"))
    }
}
