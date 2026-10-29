package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Sent
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.waves.WavePublish
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.editor.PollDraft
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Waves tab's getting-started card, the daily check-in, and a poll (and the video seam) in the wave composer. */
class WavesGapsTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"

    private var created = mapOf(alice to "2026-10-01T00:00:00", bob to "2019-01-01T00:00:00")
    private var posts = mapOf(alice to 2, bob to 500)
    private var quests = mutableMapOf(alice to """{"daily":[],"streak":{"current":0}}""", bob to """{"daily":[],"streak":{"current":0}}""")
    private var questsDown = false
    private var activityStatus = 200

    private fun chain(): suspend FakeTransport.(Sent) -> HttpResult = { req ->
        when (req.method) {
            "condenser_api.get_accounts" -> {
                val names = listOf(alice, bob).filter { "\"$it\"" in req.body }
                FakeTransport.result(req, names.joinToString(",", "[", "]") { """{"name":"$it","created":"${created[it]}","post_count":${posts[it]},"json_metadata":"","posting_json_metadata":""}""" })
            }
            "bridge.get_account_posts" -> if ("ecency.waves" in req.body) {
                FakeTransport.result(req, """[{"author":"ecency.waves","permlink":"waves-2026-10-06","json_metadata":{"tags":["ecency","waves"]}}]""")
            } else FakeTransport.result(req, "[]")
            "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "null")
            "condenser_api.get_following" -> FakeTransport.result(req, "[]")
            else -> TestApp.chain(req) ?: HttpResult(500, "")
        }
    }

    private fun TestScope.app(signedIn: Boolean = true): TestApp {
        val app = TestApp(this, handler = chain())
        if (signedIn) {
            val vault = KeyVault(app.store)
            listOf(bob, alice).forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "access-$it")) }
            vault.setCurrentUsername(alice)
        }
        app.authBackend.rest = { r ->
            when {
                r.url.endsWith("/private-api/quests") -> {
                    val user = Json.parseToJsonElement(r.body!!).jsonObject.getValue("username").jsonPrimitive.content
                    if (questsDown) HttpResult(500, "") else HttpResult(200, quests.getValue(user))
                }
                r.url.endsWith("/private-api/usr-activity") -> HttpResult(activityStatus, "{}")
                r.url.endsWith("/private-api/public/bots") -> HttpResult(200, "[]")
                else -> null
            }
        }
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        return app
    }

    private fun TestScope.card(app: TestApp): WavesOnboardingModel = app.env.wavesOnboarding.also {
        it.start()
        testScheduler.runCurrent()
    }

    private fun TestApp.calls(suffix: String): List<AuthHttpRequest> = authBackend.sent.filter { it.url.endsWith(suffix) }

    // ---- RN golden vectors ----

    @Test
    fun newAccountRuleMatchesRn() = WavesGapsGoldens.NEW_ACCOUNT.forEach { (created, posts, expected) ->
        assertEquals(expected, WavesOnboarding.isNewAccount(created, posts, WavesGapsGoldens.NOW), "isNewAccount($created, $posts)")
    }

    @Test
    fun checklistDerivationMatchesRn() = WavesGapsGoldens.DERIVE.forEach { c ->
        val quests = QuestProgress.parse(c.quests)!!
        val derived = WavesOnboarding.derive(quests, "2026-10-01T00:00:00", 2, c.persisted.mapNotNull(WavesOnboardingItemId::of).toSet(), WavesGapsGoldens.NOW)
        val label = "${c.quests} with ${c.persisted}"
        assertEquals(c.eligible, derived.eligible, label)
        assertEquals(c.completed, derived.items.map { it.completed }, label)
        assertEquals(c.completedCount, derived.completedCount, label)
        assertEquals(c.allComplete, derived.allComplete, label)
    }

    @Test
    fun aWaveWithAPollMatchesRnsPermlinkAndMetadata() {
        val poll = PollDraft(title = "Tea or coffee?", choices = listOf("Tea", "Coffee", "Neither"), endTime = "2026-10-13T12:00:00.000Z")
        val weighted = poll.copy(
            title = " Weighted? ", interpretation = PollDraft.TOKENS, token = PollDraft.HP_TOKEN, voteChange = true, hideVotes = true, hideResults = false,
            maxChoicesVoted = 2, communityMembership = listOf("hive-125125"), accountAge = 30,
        )
        WavesGapsGoldens.WAVE_POLLS.forEach { c ->
            val p = if (c.name == "tokens") weighted else poll
            assertEquals(c.pollKey, WavePublish.pollKey(p), "JSON.stringify(pollDraft) for ${c.name}")
            val draft = WavePublish.draft(alice, "ecency.waves", "waves-2026-10-06", listOf("ecency", "waves"), c.body, poll = p, videoThumbs = listOfNotNull(c.videoThumb))
            assertEquals(c.permlink, draft.permlink, c.name)
            val comment = draft.operations.first() as com.ecency.core.hive.Comment
            assertEquals(c.jsonMetadata, comment.jsonMetadata, c.name)
        }
        // without a poll nothing changes: the key is empty and the metadata has no poll field
        assertEquals("", WavePublish.pollKey(null))
        assertFalse("content_type" in WavePublish.metadata("plain", listOf("ecency")))
    }

    // ---- the getting-started card ----

    @Test
    fun aNewAccountSeesTheCardWithRnsCopyAndNothingDone() = runTest {
        val app = app()
        val model = card(app)
        testScheduler.advanceUntilIdle()
        val s = model.current
        assertTrue(s.visible)
        assertEquals(alice, s.account)
        assertEquals(listOf("Post your first Wave", "Vote on a Wave you like", "Reply to someone", "Check in to start your streak"), s.items.map { it.label })
        assertEquals("0/4", s.progressText)
        assertEquals(0, s.percent)
        assertEquals(listOf(true, false, false, true), s.items.map { it.actionable }, "the wave and the check-in rows are the tappable ones")
        assertEquals("""{"username":"spike-alice"}""", app.calls("/private-api/quests").single().body, "the editor area's quests request")
    }

    @Test
    fun anEstablishedAccountNeverSeesIt() = runTest {
        val app = app()
        app.env.session.switchTo(bob)
        val model = card(app)
        testScheduler.advanceUntilIdle()
        assertEquals(bob, model.current.account)
        assertFalse(model.current.visible)
    }

    @Test
    fun questProgressTicksItemsAndTheyStayTickedAfterTheDailyReset() = runTest {
        val app = app()
        quests[alice] = """{"daily":[{"id":"vote","progress":1},{"id":"comment","progress":2}],"streak":{"current":0}}"""
        val model = card(app)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false, true, true, false), model.current.items.map { it.completed })
        assertEquals("2/4", model.current.progressText)
        assertEquals(50, model.current.percent)
        // 00:00 UTC: the daily numbers are back at zero
        quests[alice] = """{"daily":[{"id":"vote","progress":0},{"id":"comment","progress":0}],"streak":{"current":0}}"""
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(false, true, true, false), model.current.items.map { it.completed }, "latched: an item never un-checks")
        assertEquals("""{"dismissed":false,"celebrated":false,"done":["vote","reply"]}""", app.env.prefs.getString(WavesOnboardingModel.key(alice)))
    }

    @Test
    fun publishingAWaveHereTicksTheFirstItemOnlyForItsAuthor() = runTest {
        val app = app()
        val model = card(app)
        testScheduler.advanceUntilIdle()
        app.env.emit(AppEvent.WavePosted(PostKey(bob, "wave-x")))
        testScheduler.runCurrent()
        assertFalse(model.current.items[0].completed)
        app.env.emit(AppEvent.WavePosted(PostKey(alice, "wave-y")))
        testScheduler.runCurrent()
        assertTrue(model.current.items[0].completed)
        assertNull(app.env.prefs.getString(WavesOnboardingModel.key(bob)))
    }

    @Test
    fun whenEverythingIsDoneItCelebratesOnceThenStaysAway() = runTest {
        val app = app()
        quests[alice] = """{"daily":[{"id":"vote","progress":1},{"id":"comment","progress":1},{"id":"checkin","progress":1}],"streak":{"current":1}}"""
        val model = card(app)
        testScheduler.advanceUntilIdle()
        assertEquals("3/4", model.current.progressText)
        app.env.emit(AppEvent.WavePosted(PostKey(alice, "wave-y")))
        testScheduler.runCurrent()
        assertTrue(model.current.visible)
        assertTrue(model.current.celebrating)
        assertEquals("All done 🎉", WavesOnboardingCopy.ALL_DONE_TITLE)
        // the next time the card is built for this account (a new start of the app): gone
        val again = WavesOnboardingModel(app.env)
        again.start()
        testScheduler.advanceUntilIdle()
        assertFalse(again.current.visible)
        assertFalse(again.current.celebrating)
    }

    @Test
    fun dismissHidesItForGoodForThatAccountOnly() = runTest {
        val app = app()
        posts = posts + (bob to 1) // bob is "new" too
        val model = card(app)
        testScheduler.advanceUntilIdle()
        model.dismiss()
        assertFalse(model.current.visible)
        val again = WavesOnboardingModel(app.env)
        again.start()
        testScheduler.advanceUntilIdle()
        assertFalse(again.current.visible)
        app.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        assertEquals(bob, model.current.account)
        assertTrue(model.current.visible, "bob has not dismissed his")
    }

    @Test
    fun anAccountChangeDropsTheCardBeforeTheNextAccountsIsRead() = runTest {
        val app = app()
        quests[alice] = """{"daily":[{"id":"vote","progress":1}],"streak":{"current":0}}"""
        val model = card(app)
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible)
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("/private-api/quests")) held.await() }
        app.env.session.switchTo(bob)
        testScheduler.runCurrent()
        // nothing of alice's card is left while bob's loads
        assertEquals(bob, model.current.account)
        assertFalse(model.current.visible)
        assertTrue(model.current.items.isEmpty())
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.visible, "bob is an established account")
        assertNull(app.env.prefs.getString(WavesOnboardingModel.key(bob)), "alice's progress was never written under bob")
    }

    @Test
    fun withoutTheQuestsAnswerNothingShowsAndSignedOutNothingIsAsked() = runTest {
        questsDown = true
        val app = app()
        val model = card(app)
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.visible)

        val guest = app(signedIn = false)
        val none = card(guest)
        testScheduler.advanceUntilIdle()
        assertNull(none.current.account)
        assertTrue(guest.calls("/private-api/quests").isEmpty())
    }

    // ---- daily check-in ----

    @Test
    fun browsingWavesRecordsOneCheckInPerQuarterHourWithTheAccountsCode() = runTest {
        val app = app()
        assertTrue(app.env.checkIn.record())
        assertFalse(app.env.checkIn.record(), "the window is claimed before the request, so two visits never both send")
        testScheduler.runCurrent()
        val sent = app.calls("/private-api/usr-activity").single()
        assertEquals(Json.parseToJsonElement("""{"ty":10,"code":"access-spike-alice"}"""), Json.parseToJsonElement(sent.body!!))
        app.now += CheckInRecorder.THROTTLE_MILLIS - 1
        assertFalse(app.env.checkIn.record())
        app.now += 1
        assertTrue(app.env.checkIn.record())
        testScheduler.runCurrent()
        assertEquals(2, app.calls("/private-api/usr-activity").size)
    }

    @Test
    fun aFailedCheckInIsTriedThreeTimesParkedAndGivesTheWindowBack() = runTest {
        val app = app()
        activityStatus = 500
        assertTrue(app.env.checkIn.record())
        testScheduler.advanceUntilIdle()
        assertEquals(3, app.calls("/private-api/usr-activity").size, "RN useUserActivityMutation: retry 2")
        assertEquals(CheckInRecorder.CHECKIN_ACTIVITY, app.env.pointActivities.pending(alice).single().type, "parked for a replay")
        activityStatus = 200
        assertTrue(app.env.checkIn.record(), "the next visit tries again")
    }

    @Test
    fun checkInsAreKeptPerAccountAndNeverSentSignedOutOrLocked() = runTest {
        val app = app()
        assertTrue(app.env.checkIn.record())
        app.env.session.switchTo(bob)
        testScheduler.runCurrent()
        assertTrue(app.env.checkIn.record(), "bob's own window")
        testScheduler.runCurrent()
        assertEquals(listOf("access-spike-alice", "access-spike-bob"), app.calls("/private-api/usr-activity").map { Json.parseToJsonElement(it.body!!).jsonObject.getValue("code").jsonPrimitive.content })

        val guest = app(signedIn = false)
        assertFalse(guest.env.checkIn.record())

        val locked = TestApp(this, handler = chain())
        KeyVault(locked.store).apply { save(VaultAccount(alice, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "t")); setCurrentUsername(alice) }
        PinLock(KeyVault(locked.store), 16).setPin("1234")
        locked.env.session.start()
        locked.env.appLock.start()
        testScheduler.advanceUntilIdle()
        assertTrue(locked.env.appLock.current.isLocked)
        assertFalse(locked.env.checkIn.record())
        assertTrue(locked.authBackend.sent.none { it.url.endsWith("/usr-activity") })
    }

    @Test
    fun aRecordedCheckInRefreshesTheCardAMinuteLater() = runTest {
        val app = app()
        val model = card(app)
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.items[3].completed)
        app.env.checkIn.record()
        testScheduler.runCurrent()
        quests[alice] = """{"daily":[{"id":"checkin","progress":1}],"streak":{"current":1}}"""
        testScheduler.advanceTimeBy(WavesOnboardingModel.QUESTS_REFRESH_DELAY_MILLIS - 1)
        assertFalse(model.current.items[3].completed, "not before the backend had time to credit it")
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.items[3].completed)
    }

    // ---- the wave composer: poll and the video seam ----

    private fun broadcastComment(app: TestApp) =
        app.broadcasts().last().params.jsonArray[0].jsonObject.getValue("operations").jsonArray.first().jsonArray[1].jsonObject

    @Test
    fun aPollAttachedInTheComposerIsPublishedWithTheWave() = runTest {
        val app = app()
        val composer = app.env.waves.composer()
        val poll = PollDraft(title = "Tea or coffee?", choices = listOf("Tea", "Coffee", "Neither"), endTime = "2026-10-13T12:00:00.000Z")
        composer.setText("What do you drink in the morning? #poll")
        composer.setPoll(PollDraft(title = "", choices = listOf("a", "")))
        assertNull(composer.current.poll, "the wizard's rule: a question and no empty choice")
        composer.setPoll(poll)
        assertEquals(poll, composer.current.poll)
        composer.send()
        testScheduler.advanceUntilIdle()
        val golden = WavesGapsGoldens.WAVE_POLLS.first { it.name == "plain" }
        val fields = broadcastComment(app)
        assertEquals(golden.permlink, fields.getValue("permlink").jsonPrimitive.content)
        assertEquals(golden.jsonMetadata, fields.getValue("json_metadata").jsonPrimitive.content)
        assertNull(composer.current.poll, "RN removePollDraft after a publish")
        assertEquals(PostKey(alice, golden.permlink), composer.current.sent)
    }

    @Test
    fun aWaveWithAPollAndAVideoCarriesBoth() = runTest {
        // RN submitWave(body, pollDraft, videoThumbUrl): nothing forbids the two together
        val app = app()
        val composer = app.env.waves.composer()
        val golden = WavesGapsGoldens.WAVE_POLLS.first { it.name == "video_no_thumb" }
        composer.setText("Vote on my short")
        composer.setPoll(PollDraft(title = "Tea or coffee?", choices = listOf("Tea", "Coffee", "Neither"), endTime = "2026-10-13T12:00:00.000Z"))
        composer.setVideoEmbed("https://play.3speak.tv/embed?v=spike-alice/vid123")
        assertTrue(composer.current.poll != null && composer.current.videoEmbedUrl != null)
        composer.send()
        testScheduler.advanceUntilIdle()
        val fields = broadcastComment(app)
        assertEquals(golden.body, fields.getValue("body").jsonPrimitive.content)
        assertEquals(golden.permlink, fields.getValue("permlink").jsonPrimitive.content)
        assertEquals(golden.jsonMetadata, fields.getValue("json_metadata").jsonPrimitive.content)
        assertNull(composer.current.poll)
        assertNull(composer.current.videoEmbedUrl)
    }

    @Test
    fun aVideoIsOnlyAttachedForTheComposersOwnerWhileSignedIn() = runTest {
        val app = app()
        val composer = app.env.waves.composer()
        val embed = "https://play.3speak.tv/embed?v=spike-alice/vid123"
        composer.setText("Mine")
        app.env.session.signOut()
        testScheduler.advanceUntilIdle()
        composer.setVideoEmbed(embed)
        assertNull(composer.current.videoEmbedUrl, "the owner is not signed in: nothing is attached to their composer")
        assertTrue(composer.current.error != null, "and it is said")
    }

    @Test
    fun removingThePollPublishesAPlainWave() = runTest {
        val app = app()
        val composer = app.env.waves.composer()
        composer.setText("No poll after all")
        composer.setPoll(PollDraft(title = "Q", choices = listOf("a", "b"), endTime = "2026-10-13T12:00:00.000Z"))
        composer.setPoll(null)
        composer.send()
        testScheduler.advanceUntilIdle()
        val fields = broadcastComment(app)
        assertEquals(WavePublish.permlink(alice, "ecency.waves", "waves-2026-10-06", "No poll after all"), fields.getValue("permlink").jsonPrimitive.content)
        assertFalse("question" in fields.getValue("json_metadata").jsonPrimitive.content)
    }

    @Test
    fun theVideoSeamPutsTheEmbedOnItsOwnLineAndRefusesAnythingButHttps() = runTest {
        val app = app()
        val composer = app.env.waves.composer()
        composer.setText("Watch this")
        composer.setVideoEmbed("javascript:alert(1)")
        composer.setVideoEmbed("http://play.3speak.tv/embed?v=a/b")
        assertNull(composer.current.videoEmbedUrl)
        composer.setVideoEmbed("https://play.3speak.tv/embed?v=spike-alice/abc")
        assertEquals(10, composer.current.length, "the embed does not count towards the 250 characters")
        composer.send()
        testScheduler.advanceUntilIdle()
        val ops = app.broadcasts().last().params.jsonArray[0].jsonObject.getValue("operations").jsonArray
        assertEquals("Watch this\n\nhttps://play.3speak.tv/embed?v=spike-alice/abc", ops.first().jsonArray[1].jsonObject.getValue("body").jsonPrimitive.content)
        assertEquals("comment_options", ops[1].jsonArray[0].jsonPrimitive.content, "the 3Speak beneficiary rule applies")
        assertNull(composer.current.videoEmbedUrl)
    }
}
