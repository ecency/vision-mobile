package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.RankedSort
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.Sent
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import com.ecency.core.hive.GoldenVectors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared fixtures of the profile area tests: a chain with accounts, follows and RC; the Ecency API scripted. */
object ProfileFixtures {
    /** 2026-10-06T12:00:00Z in seconds (TestApp.NOW). */
    const val NOW_S = 1_791_288_000L

    fun account(
        name: String,
        posting: String = """{"profile":{"name":"Alice A","about":"Hive dev","location":"Berlin","website":"alice.example","cover_image":"https://img.example/c.png","pinned":"my-post"}}""",
        json: String = "",
        mana: Long = 873_000_000,
    ) = """{"name":"$name","created":"2018-05-01T10:00:00","post_count":10,"reputation":0,"balance":"1.000 HIVE",""" +
        """"hbd_balance":"0.000 HBD","vesting_shares":"1000.000000 VESTS","delegated_vesting_shares":"0.000000 VESTS",""" +
        """"received_vesting_shares":"0.000000 VESTS","vesting_withdraw_rate":"0.000000 VESTS","to_withdraw":0,"withdrawn":0,""" +
        """"voting_manabar":{"current_mana":"$mana","last_update_time":$NOW_S},""" +
        """"posting":{"weight_threshold":1,"account_auths":[],"key_auths":[]},"memo_key":"STM1",""" +
        """"json_metadata":${Json.encodeToString(json)},"posting_json_metadata":${Json.encodeToString(posting)}}"""

    fun profile(name: String, rep: Double = 64.7, followers: Int = 3, following: Int = 4) =
        """{"name":"$name","created":"2018-05-01T10:00:00","active":"2026-10-01T00:00:00","post_count":10,"reputation":$rep,""" +
            """"stats":{"followers":$followers,"following":$following},"metadata":{"profile":{"name":"Bridge $name"}}}"""

    /** A chain answering reads for [accounts] (name to posting metadata); [rpc] answers first when it returns non-null. */
    fun chain(
        accounts: Map<String, String> = mapOf("alice" to account("alice"), TestApp.USER to account(TestApp.USER, posting = "")),
        relationship: String = """{"follows":true,"ignores":false,"blacklists":false,"follows_muted":false,"follows_blacklists":false}""",
        rpc: suspend (Sent) -> HttpResult? = { null },
    ): suspend FakeTransport.(Sent) -> HttpResult = { req ->
        rpc(req) ?: when (req.method) {
            "condenser_api.get_accounts" -> {
                val names = req.params.jsonArray[0].jsonArray.map { it.jsonPrimitive.content }
                FakeTransport.result(req, names.mapNotNull { accounts[it] }.joinToString(",", "[", "]"))
            }
            "bridge.get_profile" -> FakeTransport.result(req, profile(req.params.jsonObject["account"]!!.jsonPrimitive.content))
            "bridge.get_profiles" -> {
                val names = req.params.jsonObject["accounts"]!!.jsonArray.map { it.jsonPrimitive.content }
                FakeTransport.result(req, names.filter { !it.startsWith("gone") }.joinToString(",", "[", "]") { profile(it, rep = 50.0) })
            }
            "rc_api.find_rc_accounts" -> FakeTransport.result(
                req, """{"rc_accounts":[{"account":"alice","rc_manabar":{"current_mana":"500","last_update_time":$NOW_S},"max_rc":"1000"}]}""",
            )
            "condenser_api.get_follow_count" -> FakeTransport.result(req, """{"account":"alice","follower_count":1234,"following_count":56}""")
            "bridge.get_relationship_between_accounts" -> FakeTransport.result(req, relationship)
            "bridge.get_account_posts" -> FakeTransport.result(req, "[]")
            "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "null")
            else -> TestApp.chain(req) ?: HttpResult(500, "no fixture for ${req.method}")
        }
    }

    /** Ecency API: favourites-check false, Pro has "alice", everything else answers `ok`. */
    fun ecency(
        favorite: Boolean = false,
        failReport: Boolean = false,
        extra: (AuthHttpRequest) -> HttpResult? = { null },
    ): (AuthHttpRequest) -> HttpResult = { r ->
        extra(r) ?: when {
            r.url.endsWith("/private-api/pro-members") -> HttpResult(200, """{"members":["Alice","bob"],"count":2}""")
            r.url.endsWith("/private-api/favorites-check") -> HttpResult(200, favorite.toString())
            r.url.endsWith("/private-api/report") -> if (failReport) HttpResult(500, "{}") else HttpResult(200, "{}")
            else -> HttpResult(200, "{}")
        }
    }

    /** Signs [user] in with a posting key and an Ecency token, then starts the session. */
    fun TestScope.signIn(app: TestApp, user: String = TestApp.USER) {
        val vault = KeyVault(app.store)
        vault.save(VaultAccount(user, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "access-$user"))
        vault.setCurrentUsername(user)
        app.env.session.start()
        testScheduler.advanceUntilIdle()
    }

    fun Sent.ops(): String = params.jsonArray.single().jsonObject["operations"].toString()

    fun body(r: AuthHttpRequest) = Json.parseToJsonElement(r.body ?: "{}").jsonObject
}

class ProfileModelTest {
    private fun TestScope.app(
        relationship: String = """{"follows":true,"ignores":false,"blacklists":false,"follows_muted":false,"follows_blacklists":false}""",
        rpc: suspend (Sent) -> HttpResult? = { null },
        ecency: (AuthHttpRequest) -> HttpResult = ProfileFixtures.ecency(),
    ): TestApp {
        val app = TestApp(this, handler = ProfileFixtures.chain(relationship = relationship, rpc = rpc))
        app.authBackend.privateApi = ecency
        // the app starts the session at launch; models wait for it before they know the observer
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        return app
    }

    @Test
    fun headerShowsWhatRnShows() = runTest {
        val app = app()
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        val s = model.current
        val h = assertNotNull(s.header)
        assertEquals("Alice A", h.displayName)
        assertEquals("Hive dev", h.about)
        assertEquals("Berlin", h.location)
        assertEquals("https://alice.example", h.websiteUrl)
        assertEquals(CalendarDate(2018, 5, 1), h.joined)
        assertEquals(64, h.reputation)
        assertEquals("1.2K", h.followersText)
        assertEquals("56", h.followingText)
        assertEquals("87.3", h.votingPower)
        assertEquals(11, h.votingPowerHours)
        assertEquals("Voting power: 87.3% • Full in 11 hours", h.votingPowerText)
        assertEquals("50.0", h.resourceCredits)
        assertEquals(42, h.resourceCreditsHours)
        assertEquals("my-post", h.pinned)
        assertTrue(h.coverUrl.startsWith(TestApp.PROXY + "/"))
        assertEquals("${TestApp.PROXY}/u/alice/avatar/512", h.avatarUrl)
        assertTrue(s.isFollowing)
        assertFalse(s.isMuted)
        assertTrue(s.isPro, "Pro is case-insensitive")
        assertFalse(s.isOwn)
        assertTrue(s.showsActions)
        assertEquals(listOf(ProfileTab.BLOG, ProfileTab.POSTS, ProfileTab.WAVES, ProfileTab.COMMENTS), s.tabs)
        assertEquals("ADD TO FAVOURITES", s.menuLabel(ProfileMenuItem.FAVORITE))
        // the favourite check carries the observer's code and the target
        val check = app.authBackend.sent.single { it.url.endsWith("favorites-check") }
        assertEquals("alice", ProfileFixtures.body(check)["account"]!!.jsonPrimitive.content)
        assertEquals("access-${TestApp.USER}", ProfileFixtures.body(check)["code"]!!.jsonPrimitive.content)
        model.close()
    }

    @Test
    fun ownProfileHasOwnTabsAndNoRelationshipCall() = runTest {
        val app = app()
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel(TestApp.USER, "")
        model.start()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.isOwn)
        assertFalse(model.current.showsActions)
        assertEquals(emptyList(), model.current.menu)
        assertEquals(listOf(ProfileTab.BLOG, ProfileTab.POSTS, ProfileTab.WAVES, ProfileTab.REPLIES), model.current.tabs)
        assertFalse("bridge.get_relationship_between_accounts" in app.transport.methods())
        assertTrue(app.authBackend.sent.none { it.url.endsWith("favorites-check") })
        // no profile in posting metadata: the username stands in
        assertEquals(TestApp.USER, model.current.header!!.displayName)
        model.close()
    }

    @Test
    fun profileOpenedBeforeTheSessionWasReadWaitsForTheObserver() = runTest {
        val app = TestApp(this, handler = ProfileFixtures.chain())
        app.authBackend.privateApi = ProfileFixtures.ecency()
        val vault = KeyVault(app.store)
        vault.save(VaultAccount(TestApp.USER, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "access-${TestApp.USER}"))
        vault.setCurrentUsername(TestApp.USER)
        // restored after process death: the screen comes back before the vault was read
        val own = app.env.profileModel(TestApp.USER, "")
        val other = app.env.profileModel("alice", "")
        own.start()
        other.start()
        testScheduler.advanceUntilIdle()
        assertNull(own.current.header, "nothing loads before the session")
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        assertTrue(own.current.isOwn)
        assertEquals(ProfileTab.REPLIES, own.current.tabs.last())
        assertTrue(other.current.showsActions)
        assertTrue(other.current.isFollowing)
        own.close()
        other.close()
    }

    @Test
    fun ownProfileNeverBroadcastsFollowOrMute() = runTest {
        val app = app()
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel(TestApp.USER, "")
        model.start()
        testScheduler.advanceUntilIdle()
        model.toggleFollow()
        model.toggleMute()
        model.toggleFavorite()
        model.report()
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty(), "nobody follows or mutes themselves")
        assertTrue(app.authBackend.sent.none { it.url.contains("favorites-add") || it.url.endsWith("/report") })
        assertFalse(model.current.isFollowing)
        assertFalse(model.current.isMuted)
        model.close()
    }

    @Test
    fun aFollowResultAfterAnAccountSwitchIsDropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        val other = "spike-other"
        val app = TestApp(this, handler = ProfileFixtures.chain(
            accounts = mapOf(
                "alice" to ProfileFixtures.account("alice"),
                TestApp.USER to ProfileFixtures.account(TestApp.USER),
                other to ProfileFixtures.account(other),
            ),
            rpc = { req ->
                when (req.method) {
                    "condenser_api.broadcast_transaction" -> { gate.await(); null }
                    // the first account follows alice; for the second the node gives no answer, so only the
                    // reset on the account change keeps the first account's "following" from showing
                    "bridge.get_relationship_between_accounts" ->
                        if (req.params.jsonArray[0].jsonPrimitive.content == TestApp.USER) FakeTransport.result(req, """{"follows":true,"ignores":false}""")
                        else HttpResult(500, "down")
                    else -> null
                }
            },
        ))
        app.authBackend.privateApi = ProfileFixtures.ecency()
        val vault = KeyVault(app.store)
        vault.save(VaultAccount(other, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "access-$other"))
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.isFollowing, "first account follows")

        model.toggleFollow() // unfollow as the first account, held at the node
        // runCurrent, not advanceUntilIdle: virtual time must not run into the node timeout while the gate holds
        testScheduler.runCurrent()
        assertTrue(model.current.busy, "held at the node")
        val switching = launch { app.env.session.switchTo(other) }
        repeat(20) { testScheduler.runCurrent() }
        assertTrue(switching.isCompleted, "switched")
        assertEquals(other, app.env.session.current.username)
        assertFalse(model.current.isFollowing, "the first account's relation is not shown to the second")
        assertFalse(model.current.busy, "the first account's action does not block the second")
        app.env.ui.clear()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        // the first account's result arrives now: it changes nothing here and says nothing
        assertFalse(model.current.isFollowing)
        assertFalse(model.current.busy)
        assertNull(app.env.ui.current.toast)
        assertNull(model.current.alert)
        // and the second account can act
        model.toggleFollow()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.isFollowing, "second account followed: ${model.current.alert} ${app.env.session.current.username}")
        assertEquals(2, app.broadcasts().size)
        model.close()
    }

    @Test
    fun deepLinkSectionSelectsOrReplacesTheLastTab() {
        assertEquals(ProfileTab.defaults(false) to 3, ProfileTab.forSection(false, "comments"))
        assertEquals(listOf(ProfileTab.BLOG, ProfileTab.POSTS, ProfileTab.WAVES, ProfileTab.WALLET) to 3, ProfileTab.forSection(false, "wallet"))
        assertEquals(listOf(ProfileTab.BLOG, ProfileTab.POSTS, ProfileTab.WAVES, ProfileTab.WALLET) to 3, ProfileTab.forSection(true, "points"))
        assertEquals(listOf(ProfileTab.BLOG, ProfileTab.POSTS, ProfileTab.WAVES, ProfileTab.COMMENTS) to 3, ProfileTab.forSection(true, "comments"))
        assertEquals(ProfileTab.defaults(true) to 1, ProfileTab.forSection(true, "posts"))
        assertEquals(ProfileTab.defaults(true) to 0, ProfileTab.forSection(true, "nonsense"))
    }

    @Test
    fun signedOutSeesNoActionsAndNoPrivateCalls() = runTest {
        val app = app()
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        assertNotNull(model.current.header)
        assertFalse(model.current.showsActions)
        model.toggleFollow()
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty())
        assertTrue(app.authBackend.sent.none { it.url.contains("favorites") })
        model.close()
    }

    @Test
    fun unfollowAndFollowBroadcastTheSdkOperations() = runTest {
        val app = app()
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.isFollowing)
        assertEquals("Unfollow", model.current.followLabel)

        model.toggleFollow()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.busy)
        assertFalse(model.current.isFollowing)
        with(ProfileFixtures) {
            assertEquals(
                """[["custom_json",{"required_auths":[],"required_posting_auths":["spike-voter"],"id":"follow","json":"[\"follow\",{\"follower\":\"spike-voter\",\"following\":\"alice\",\"what\":[]}]"}]]""",
                app.broadcasts().last().ops(),
            )
        }
        assertEquals("Unfollow success!", app.env.ui.current.toast?.text)
        app.env.ui.clear()

        model.toggleFollow()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.isFollowing)
        with(ProfileFixtures) {
            assertTrue(app.broadcasts().last().ops().contains("""\"what\":[\"blog\"]"""))
        }
        model.close()
    }

    @Test
    fun muteReplacesFollowAndUnmuteClearsIt() = runTest {
        val app = app()
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        model.toggleMute()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.isMuted)
        assertFalse(model.current.isFollowing, "an ignore replaces the blog follow on chain")
        assertEquals("UNMUTE / UNBLOCK", model.current.menuLabel(ProfileMenuItem.MUTE))
        with(ProfileFixtures) { assertTrue(app.broadcasts().last().ops().contains("""\"what\":[\"ignore\"]""")) }

        app.env.ui.clear()
        model.toggleMute()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.isMuted)
        with(ProfileFixtures) { assertTrue(app.broadcasts().last().ops().contains("""\"what\":[]""")) }
        assertEquals("Unmute success!", app.env.ui.current.toast?.text ?: "")

        // following a muted account clears the mute
        model.toggleMute()
        testScheduler.advanceUntilIdle()
        model.toggleFollow()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.isFollowing)
        assertFalse(model.current.isMuted)
        model.close()
    }

    @Test
    fun muteAndUnmuteFromTheProfileUpdateTheSharedIgnoreListAndOpenFeeds() = runTest {
        val app = app(rpc = { req ->
            if (req.method == "bridge.get_ranked_posts") {
                FakeTransport.result(req, "[${com.ecency.core.api.Fixtures.entry("alice", "one")},${com.ecency.core.api.Fixtures.entry("bob", "two")}]")
            } else null
        })
        with(ProfileFixtures) { signIn(app) }
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        feed.start()
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("alice", "bob"), feed.current.items.map { it.author })

        model.toggleMute()
        testScheduler.advanceUntilIdle()
        // the set the feeds, promoted entries and the post menu share, and the open feed drops the author at once
        assertTrue("alice" in app.env.mutedAuthors)
        assertEquals(listOf("bob"), feed.current.items.map { it.author })
        // a reload keeps filtering
        feed.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("bob"), feed.current.items.map { it.author })

        model.toggleMute()
        testScheduler.advanceUntilIdle()
        assertFalse("alice" in app.env.mutedAuthors)
        feed.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("alice", "bob"), feed.current.items.map { it.author }, "unmuted: back after a reload, as RN")

        // following a muted account takes it off the list too (one \"what\" per pair on chain)
        model.toggleMute()
        testScheduler.advanceUntilIdle()
        assertTrue("alice" in app.env.mutedAuthors)
        model.toggleFollow()
        testScheduler.advanceUntilIdle()
        assertFalse("alice" in app.env.mutedAuthors)
        feed.close()
        model.close()
    }

    @Test
    fun aRejectedMuteLeavesTheIgnoreListAlone() = runTest {
        val app = app(rpc = { req ->
            if (req.method == "condenser_api.broadcast_transaction") FakeTransport.error(req, -32000, "missing required posting authority") else null
        })
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        model.toggleMute()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.isMuted)
        assertTrue(app.env.mutedAuthors.isEmpty())
        model.close()
    }

    @Test
    fun followOutOfResourceCreditsRaisesTheAppWideOfferInsteadOfTheAlert() = runTest {
        val app = app(rpc = { req ->
            if (req.method == "condenser_api.broadcast_transaction") FakeTransport.error(req, -32000, "Account: spike-voter has 12 RC, needs 3400 RC. Please wait to transact, or power up HIVE.") else null
        })
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        model.toggleMute()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.rcOffer.current.open, "RN profileContainer: setRcOffer(true)")
        assertNull(model.current.alert)
        assertFalse(model.current.busy)
        assertFalse(model.current.isMuted, "state unchanged")
        model.close()
    }

    @Test
    fun rejectedFollowShowsTheFailAlertAndKeepsState() = runTest {
        val app = app(rpc = { req ->
            if (req.method == "condenser_api.broadcast_transaction") FakeTransport.error(req, -32000, "rejected: out of bandwidth") else null
        })
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        model.toggleFollow()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.isFollowing, "state unchanged")
        assertFalse(model.current.busy)
        assertNotNull(model.current.alert)
        assertFalse(app.env.rcOffer.current.open, "out of bandwidth is not an RC shortfall (RN RC_SHORTFALL)")
        model.dismissAlert()
        assertNull(model.current.alert)
        model.close()
    }

    @Test
    fun actionsDoNotOverlap() = runTest {
        val gate = CompletableDeferred<Unit>()
        val app = app(rpc = { req -> if (req.method == "condenser_api.broadcast_transaction") { gate.await(); null } else null })
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        model.toggleFollow()
        testScheduler.runCurrent()
        model.toggleMute()
        model.toggleFavorite()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size)
        assertTrue(app.authBackend.sent.none { it.url.contains("favorites-add") })
        model.close()
    }

    @Test
    fun favouriteSendsTheAccountName() = runTest {
        val app = app()
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        model.toggleFavorite()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.isFavorite)
        val add = app.authBackend.sent.single { it.url.endsWith("/private-api/favorites-add") }
        // RN passes {account} where the SDK expects the name, so its request body nests an object; not here
        assertEquals("alice", ProfileFixtures.body(add)["account"]!!.jsonPrimitive.content)
        assertEquals("Success!", app.env.ui.current.toast?.text)
        model.toggleFavorite()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.isFavorite)
        assertTrue(app.authBackend.sent.any { it.url.endsWith("/private-api/favorites-delete") })
        model.close()
    }

    @Test
    fun reportThenMutes() = runTest {
        val app = app()
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        model.report()
        testScheduler.advanceUntilIdle()
        val report = ProfileFixtures.body(app.authBackend.sent.single { it.url.endsWith("/private-api/report") })
        assertEquals("account", report["type"]!!.jsonPrimitive.content)
        assertEquals("alice", report["author"]!!.jsonPrimitive.content)
        assertEquals(TestApp.USER, report["reporter"]!!.jsonPrimitive.content)
        assertTrue(model.current.isMuted)
        model.close()
    }

    @Test
    fun failedReportShowsTheErrorAndDoesNotMute() = runTest {
        val app = app(ecency = ProfileFixtures.ecency(failReport = true))
        with(ProfileFixtures) { signIn(app) }
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        model.report()
        testScheduler.advanceUntilIdle()
        // RN #3599: no "Reported" toast for a report that failed
        assertNull(app.env.ui.current.toast)
        assertNotNull(model.current.alert)
        assertFalse(model.current.busy)
        assertFalse(model.current.isMuted)
        assertTrue(app.broadcasts().isEmpty())
        model.close()
    }

    @Test
    fun unknownAccountIsNotFound() = runTest {
        val app = app()
        val model = app.env.profileModel("nobody", "")
        model.start()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.notFound)
        assertEquals(ErrorKind.NOT_FOUND, model.current.error?.kind)
        model.close()
    }

    @Test
    fun failedSideCallsStillShowTheHeader() = runTest {
        val app = app(rpc = { req ->
            when (req.method) {
                "rc_api.find_rc_accounts", "condenser_api.get_follow_count" -> HttpResult(500, "down")
                else -> null
            }
        })
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        val h = assertNotNull(model.current.header)
        assertEquals("0.0", h.resourceCredits)
        // the bridge profile's counts stand in for get_follow_count
        assertEquals(3, h.followers)
        assertEquals(4, h.following)
        model.close()
    }

    @Test
    fun accountReadFailureIsRetryable() = runTest {
        var fail = true
        val app = app(rpc = { req -> if (req.method == "condenser_api.get_accounts" && fail) HttpResult(500, "down") else null })
        val model = app.env.profileModel("alice", "")
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(ErrorKind.NETWORK, model.current.error?.kind)
        assertNull(model.current.header)
        fail = false
        model.retry()
        testScheduler.advanceUntilIdle()
        assertNull(model.current.error)
        assertNotNull(model.current.header)
        model.close()
    }

    @Test
    fun tabFeedsAreTheAccountSortsAndCloseWithTheModel() = runTest {
        val app = app()
        val model = app.env.profileModel("alice", "")
        assertNull(model.feed(ProfileTab.WAVES))
        assertNull(model.feed(ProfileTab.WALLET))
        val comments = assertNotNull(model.feed(ProfileTab.COMMENTS))
        assertEquals(com.ecency.core.api.repository.FeedSource.Account("alice", com.ecency.core.api.AccountPostsSort.COMMENTS), comments.source)
        assertTrue(model.feed(ProfileTab.COMMENTS) === comments)
        model.selectTab(3)
        assertEquals(ProfileTab.COMMENTS, model.current.tab)
        model.selectTab(9)
        assertEquals(3, model.current.selectedTab)
        model.close()
    }

    @Test
    fun formatHelpersMatchRn() {
        assertEquals("0", ProfileFormat.count(0))
        assertEquals("999", ProfileFormat.count(999))
        assertEquals("1K", ProfileFormat.count(1000))
        assertEquals("1.5K", ProfileFormat.count(1450))
        assertEquals("1.4K", ProfileFormat.count(1449))
        assertEquals("12.3M", ProfileFormat.count(12_345_678))
        assertNull(ProfileFormat.hoursToFull("100.0"))
        assertEquals(1, ProfileFormat.hoursToFull("99.9"))
        assertEquals(84, ProfileFormat.hoursToFull("0.0"))
        assertNull(ProfileFormat.joined("1970-01-01T00:00:00"))
        assertNull(ProfileFormat.joined(""))
        assertEquals("https://ecency.com", ProfileFormat.websiteUrl("ecency.com"))
        assertEquals("http://x.example/a", ProfileFormat.websiteUrl("http://x.example/a"))
        assertNull(ProfileFormat.websiteUrl("javascript:alert(1)"))
        assertNull(ProfileFormat.websiteUrl("  "))
    }
}
