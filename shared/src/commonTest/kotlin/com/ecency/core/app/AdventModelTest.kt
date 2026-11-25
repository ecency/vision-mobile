package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Sent
import com.ecency.core.api.games.AdventDoor
import com.ecency.core.api.games.AdventDoorKind
import com.ecency.core.api.games.AdventDoorStatus
import com.ecency.core.api.games.AdventMeDoor
import com.ecency.core.api.games.AdventParticipation
import com.ecency.core.api.games.AdventPayoutStatus
import com.ecency.core.api.games.AdventPrize
import com.ecency.core.api.games.AdventReward
import com.ecency.core.api.games.AdventRewardKind
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.HiveSignerMessages
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.hive.PrivateKey
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #3739: the Advent Calendar's dates, door looks, banner, sign-in, answers and Waves, against [DemoAdvent]. */
class AdventSeasonTest {
    @Test
    fun theSeasonShowsFrom25NovemberAndHidesFrom1January() {
        assertEquals(AdventPhase.HIDDEN, AdventSeason.phase(AdventSeason.SHOW_FROM - 1))
        assertEquals(AdventPhase.TEASER, AdventSeason.phase(AdventSeason.SHOW_FROM))
        assertEquals(AdventPhase.TEASER, AdventSeason.phase(AdventSeason.FIRST_DOOR - 1))
        assertEquals(AdventPhase.SEASON, AdventSeason.phase(AdventSeason.FIRST_DOOR))
        assertEquals(AdventPhase.SEASON, AdventSeason.phase(AdventSeason.HIDE_AT - 1))
        assertEquals(AdventPhase.HIDDEN, AdventSeason.phase(AdventSeason.HIDE_AT))
        assertEquals("2026-11-25", AdventSeason.utcDay(AdventSeason.SHOW_FROM))
        assertEquals("2027-01-01", AdventSeason.utcDay(AdventSeason.HIDE_AT))
        assertEquals("2026-12-31", AdventSeason.utcDay(AdventSeason.HIDE_AT - 1))
    }

    @Test
    fun todaysDoorIsOneTo24FromTheFirstOfDecember() {
        assertEquals(0, AdventSeason.todayDoor(AdventSeason.FIRST_DOOR - 1))
        assertEquals(1, AdventSeason.todayDoor(AdventSeason.FIRST_DOOR))
        assertEquals(1, AdventSeason.todayDoor(AdventSeason.FIRST_DOOR + AdventSeason.DAY_MILLIS - 1))
        assertEquals(9, AdventSeason.todayDoor(AdventSeason.FIRST_DOOR + 8 * AdventSeason.DAY_MILLIS))
        assertEquals(24, AdventSeason.todayDoor(AdventSeason.FIRST_DOOR + 24 * AdventSeason.DAY_MILLIS - 1))
        assertEquals(0, AdventSeason.todayDoor(AdventSeason.FIRST_DOOR + 24 * AdventSeason.DAY_MILLIS))
    }

    private fun door(status: AdventDoorStatus) = AdventDoor(5, 0, 0, AdventDoorKind.OPEN, AdventReward(AdventRewardKind.POINTS, listOf(500.0)), status, null, emptyList())
    private fun me(answered: Boolean, late: Boolean = false, prize: AdventPrize? = null) = AdventMeDoor(true, answered, late, prize, null)

    @Test
    fun doorLooksFollowTheWebsitesRules() {
        val look = AdventDoors::look
        assertEquals(AdventDoorLook.LOCKED, look(door(AdventDoorStatus.LOCKED), null, true, false))
        assertEquals(AdventDoorLook.LOCKED, look(door(AdventDoorStatus.OPEN), null, true, true), "the teaser wraps every door")
        assertEquals(AdventDoorLook.ANSWERED, look(door(AdventDoorStatus.OPEN), me(true, late = true), true, false))
        assertEquals(AdventDoorLook.TODAY, look(door(AdventDoorStatus.OPEN), null, true, false))
        assertEquals(AdventDoorLook.WAITING, look(door(AdventDoorStatus.OPEN), me(true), true, false))
        assertEquals(AdventDoorLook.WAITING, look(door(AdventDoorStatus.CLOSED), me(true), true, false))
        assertEquals(AdventDoorLook.MISSED, look(door(AdventDoorStatus.CLOSED), null, true, false))
        assertEquals(AdventDoorLook.CLOSED, look(door(AdventDoorStatus.CLOSED), null, false, false))
        assertEquals(AdventDoorLook.WON, look(door(AdventDoorStatus.DRAWN), me(true, prize = AdventPrize(1, AdventRewardKind.HIVE, 100.0, AdventPayoutStatus.PENDING)), true, false))
        assertEquals(AdventDoorLook.ANSWERED, look(door(AdventDoorStatus.DRAWN), me(true, prize = AdventPrize(1, AdventRewardKind.HIVE, 100.0, AdventPayoutStatus.CANCELLED)), true, false), "a cancelled prize is no win")
        assertEquals(AdventDoorLook.ANSWERED, look(door(AdventDoorStatus.DRAWN), me(true), true, false))
        assertEquals(AdventDoorLook.MISSED, look(door(AdventDoorStatus.DRAWN), null, true, false))
        assertEquals(AdventDoorLook.RESULTS, look(door(AdventDoorStatus.DRAWN), null, false, false))
        val answered = AdventDoors.tile(door(AdventDoorStatus.DRAWN), AdventMeDoor(true, true, false, null, AdventParticipation(50.0, true)), true, false)
        assertEquals("+50 Points", answered.tag)
    }

    @Test
    fun theWaveOfAQuizOrPollNeverSaysTheChoice() {
        assertEquals("#ecencyadvent I opened door 3 of the Ecency Advent Calendar 🎄\n\nhttps://ecency.com/advent", AdventWave.text(3, AdventDoorKind.QUIZ, "[Choice B]"))
        assertEquals("#ecencyadvent I opened door 2 of the Ecency Advent Calendar 🎄\n\nMy answer\n\nhttps://ecency.com/advent", AdventWave.text(2, AdventDoorKind.OPEN, "  My answer \n"))
    }

    /** The web's proof (`@ecency/raidstead` encodeProof): standard base64 of the exact JSON, whole seconds, the posting key's signature. */
    @Test
    fun theGameLoginProofIsTheWebsitesByteForByte() {
        val key = PrivateKey.fromWif(GoldenVectors.WIF)
        val proof = HiveSignerMessages.gameLoginProof("spike-voter", key, 1_796_789_123_456L)
        val json = Base64.Default.decode(proof).decodeToString()
        val unsigned = """{"signed_message":{"type":"login","app":"ecency.app","audience":"raidstead"},"authors":["spike-voter"],"timestamp":1796789123}"""
        assertTrue(json.startsWith(unsigned.dropLast(1) + ",\"signatures\":[\""), json)
        val signature = Json.parseToJsonElement(json).jsonObject.getValue("signatures").jsonArray.single().jsonPrimitive.content
        assertEquals(GoldenVectors.PUBLIC_KEY, HiveSignerMessages.recoverSigner(unsigned, signature).toString())
        assertFalse('-' in proof || '_' in proof, "standard base64 (btoa), not base64url")
    }
}

class AdventModelTest {
    private val user = TestApp.USER

    /** 9 Dec 2026, 10:00 UTC: door 9 open, doors 1 to 7 drawn, 8 closed and not drawn yet... (8 is drawn by 02:00). */
    private val dec9 = AdventSeason.FIRST_DOOR + 8 * AdventSeason.DAY_MILLIS + 10 * 3_600_000L

    private class Fixture(val app: TestApp, val advent: DemoAdvent) {
        /** What `database_api.find_comments` holds: a Wave posted earlier. */
        var onChain: String? = null
    }

    private fun TestScope.fixture(now: Long = dec9, account: VaultAccount? = VaultAccount(TestApp.USER, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF)): Fixture {
        lateinit var f: Fixture
        val app = TestApp(this) { req -> chain(f, req) }
        app.now = now
        val advent = DemoAdvent { app.now }
        f = Fixture(app, advent)
        app.authBackend.rest = { r -> advent.http(r) }
        if (account != null) KeyVault(app.store).run { save(account); setCurrentUsername(account.username) }
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        return f
    }

    private fun chain(f: Fixture, req: Sent): HttpResult = when (req.method) {
        "bridge.get_account_posts", "bridge.get_account_posts_v2" ->
            if ("ecency.waves" in req.params.toString()) FakeTransport.result(req, """[{"author":"ecency.waves","permlink":"waves-20261209","json_metadata":{"tags":["waves-20261209","hive-125125"]}}]""")
            else FakeTransport.result(req, "[]")
        "database_api.find_comments" -> TestApp.commentAsked(req).let { (a, l) ->
            TestApp.comments(req, f.onChain?.let { body -> """{"author":"$a","permlink":"$l","body":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(body))}}""" })
        }
        "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "null")
        else -> TestApp.chain(req) ?: FakeTransport.result(req, "[]")
    }

    private fun TestScope.started(f: Fixture): AdventModel = f.app.env.adventModel().also { it.start(); testScheduler.advanceUntilIdle() }

    private fun waveBodies(f: Fixture) = f.app.broadcasts().map { tx ->
        tx.params.jsonArray[0].jsonObject.getValue("operations").jsonArray.single().jsonArray[1].jsonObject.getValue("body").jsonPrimitive.content
    }

    @Test
    fun aSignedInAccountSeesItsDoorsAndSignsInWithItsPostingKey() = runTest {
        val f = fixture()
        f.advent.seedHistory(user)
        val m = started(f)
        assertEquals(AdventSignIn.READY, m.current.signIn)
        assertEquals(user, m.current.account)
        assertEquals(9, m.current.todayDoor)
        val tiles = m.current.tiles.associateBy { it.day }
        assertEquals(AdventDoorLook.TODAY, tiles.getValue(9).look)
        assertEquals(AdventDoorLook.WON, tiles.getValue(3).look)
        assertEquals(AdventDoorLook.MISSED, tiles.getValue(5).look)
        assertEquals(AdventDoorLook.LOCKED, tiles.getValue(10).look)
        assertTrue(tiles.getValue(6).late)
        // the proof the games API got is the web's login for raidstead, by this account
        val proof = Json.parseToJsonElement(f.advent.signIns.single()).jsonObject
        assertEquals("raidstead", proof.getValue("signed_message").jsonObject.getValue("audience").jsonPrimitive.content)
        assertEquals(user, proof.getValue("authors").jsonArray.single().jsonPrimitive.content)
        assertNotNull(m.current.grand, "the grand draw card")
    }

    @Test
    fun aWrittenAnswerPostsOneWaveTaggedEcencyadventAndLinksIt() = runTest {
        val f = fixture()
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.ANSWER, m.current.sheet?.mode)
        m.submit("Snow on the roofs and tea.", 0)
        testScheduler.advanceUntilIdle()
        val sheet = m.current.sheet!!
        assertEquals(AdventSheetMode.DONE, sheet.mode)
        assertEquals(AdventWaveState.POSTED, sheet.wave)
        val bodies = waveBodies(f)
        assertEquals(1, bodies.size, "one Wave")
        assertTrue(bodies.single().startsWith("#ecencyadvent I opened door 9"), bodies.single())
        assertTrue("Snow on the roofs and tea." in bodies.single())
        assertTrue(f.advent.calls.any { it == "POST /v1/advent/2026/doors/9/wave" }, "the Wave linked to the door")
        assertNull(f.app.env.advent.pendingWave(user, 9), "nothing left to post")
        assertEquals(AdventDoorLook.WAITING, m.current.tiles.first { it.day == 9 }.look)
    }

    @Test
    fun aQuizAnswersWaveHasNoChoiceInIt() = runTest {
        // 11 Dec: door 11 is a quiz
        val f = fixture(now = dec9 + 2 * AdventSeason.DAY_MILLIS)
        val m = started(f)
        m.openDoor(11)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventDoorKind.QUIZ, m.current.sheet?.door?.kind)
        m.submit(null, 1)
        testScheduler.advanceUntilIdle()
        val body = waveBodies(f).single()
        assertFalse("Choice" in body, body)
        assertEquals("#ecencyadvent I opened door 11 of the Ecency Advent Calendar 🎄\n\nhttps://ecency.com/advent", body)
    }

    @Test
    fun aRetriedWaveIsNeverPostedTwice() = runTest {
        val f = fixture()
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        // the Wave goes out, then its link to the door fails: it is kept as still to link
        f.advent.waveStatus = 503
        m.submit("A retried answer.", 0)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventWaveState.FAILED, m.current.sheet?.wave)
        val first = waveBodies(f)
        assertEquals(1, first.size)
        assertNotNull(f.app.env.advent.pendingWave(user, 9), "kept to link")
        // the chain has it now
        f.onChain = first.single()
        f.advent.waveStatus = 0
        m.retryWave()
        testScheduler.advanceUntilIdle()
        assertEquals(1, waveBodies(f).size, "the retry linked the Wave on chain, it did not post a second one")
        assertEquals(AdventWaveState.POSTED, m.current.sheet?.wave)
    }

    @Test
    fun aRefusedSessionSignsInAgainOnceThenAsks() = runTest {
        val f = fixture()
        val m = started(f)
        assertEquals(1, f.advent.signIns.size)
        // the session is refused: one new sign-in, and the calendar loads
        f.advent.refused += "demo-game-$user-1"
        m.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(2, f.advent.signIns.size, "signed in again once")
        assertEquals(AdventSignIn.READY, m.current.signIn)
        // every session refused: after one more sign-in the screen asks
        f.advent.refused += "demo-game-$user-2"
        f.advent.refused += "demo-game-$user-3"
        m.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSignIn.FAILED, m.current.signIn)
        val asked = f.advent.signIns.size
        m.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(asked, f.advent.signIns.size, "no sign-in loop while it asks")
    }

    @Test
    fun anAccountWithoutAPostingKeyIsVouchedForWithItsEcencyToken() = runTest {
        val f = fixture(account = VaultAccount(user, AuthType.HIVE_SIGNER, accessToken = "hs-access-$user"))
        val m = started(f)
        assertEquals(AdventSignIn.READY, m.current.signIn)
        assertEquals("$user|hs-access-$user", f.advent.signIns.single())
    }

    @Test
    fun aRefusedEcencyTokenIsRenewedOnceForTheVouch() = runTest {
        val f = fixture(account = VaultAccount(user, AuthType.HIVE_SIGNER, accessToken = "hs-access-$user"))
        com.ecency.core.auth.VaultAccountStore(KeyVault(f.app.store), f.app.store)
            .saveSession(com.ecency.core.auth.TokenSession(user, "token-$user", "refresh-$user", f.app.now + 86_400_000))
        // the first vouch refuses the stored token; the renewed one is accepted
        f.advent.refuseVouches = 1
        val m = started(f)
        assertEquals(AdventSignIn.READY, m.current.signIn)
        assertEquals(listOf("$user|token-$user", "$user|access-$user"), f.advent.signIns)
    }

    @Test
    fun anAnswerThatArrivesAfterAnAccountSwitchIsNotShownForTheNewAccount() = runTest {
        val f = fixture()
        KeyVault(f.app.store).save(VaultAccount("spike-yan", AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF))
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.app.authBackend.gate = { r -> if (r.url.endsWith("/doors/9/answer")) held.await() }
        m.submit("Mine, not hers.", 0)
        testScheduler.runCurrent()
        f.app.env.session.switchTo("spike-yan")
        testScheduler.runCurrent()
        f.app.authBackend.gate = null
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("spike-yan", m.current.account)
        assertNull(m.current.sheet, "the previous account's door is gone")
        assertTrue(waveBodies(f).isEmpty(), "no Wave for the previous account after it left the screen")
    }

    @Test
    fun theBannerShowsByDateAndHidesForTheDayOnceClosedOrAnswered() = runTest {
        val f = fixture(now = AdventSeason.SHOW_FROM - 1)
        val advent = f.app.env.advent
        assertNull(advent.banner(f.app.now), "not before 25 Nov")
        f.app.now = AdventSeason.SHOW_FROM + 1000
        assertEquals(AdventBannerKind.TEASER, advent.banner(f.app.now)?.kind)
        f.app.now = dec9
        assertEquals(9, advent.banner(f.app.now)?.day)
        advent.closeBanner()
        assertNull(advent.banner(f.app.now), "closed for today")
        f.app.now = dec9 + AdventSeason.DAY_MILLIS
        assertEquals(10, advent.banner(f.app.now)?.day, "back the next UTC day")
        val m = started(f)
        m.openDoor(10)
        testScheduler.advanceUntilIdle()
        m.submit("An answer for door ten.", 0)
        testScheduler.advanceUntilIdle()
        assertNull(advent.banner(f.app.now), "answered today")
        f.app.now = AdventSeason.FIRST_DOOR + 26 * AdventSeason.DAY_MILLIS
        assertEquals(AdventBannerKind.RESULTS, advent.banner(f.app.now)?.kind)
        f.app.now = AdventSeason.HIDE_AT
        assertNull(advent.banner(f.app.now), "gone from 1 Jan")
    }

    @Test
    fun aSignedOutReaderSeesResultsAndIsAskedToLogInForAnOpenDoor() = runTest {
        val f = fixture(account = null)
        val m = started(f)
        assertNull(m.current.account)
        assertEquals(AdventDoorLook.TODAY, m.current.tiles.first { it.day == 9 }.look)
        assertEquals(AdventDoorLook.RESULTS, m.current.tiles.first { it.day == 2 }.look)
        m.openDoor(9)
        assertTrue(m.current.askLogin)
        m.consumeLogin()
        m.openDoor(3)
        testScheduler.advanceUntilIdle()
        val sheet = m.current.sheet!!
        assertEquals(AdventSheetMode.RESULT, sheet.mode)
        assertEquals(listOf(136, 51, 25), sheet.result?.tallies)
        assertEquals(0, sheet.result?.answerIndex)
    }

    @Test
    fun accountsThatLeaveTakeTheirGameSessionAndPendingWaveWithThem() = runTest {
        val f = fixture()
        val m = started(f)
        f.app.env.advent.savePendingWave(user, 4, AdventPendingWave("ecency.waves", "w", emptyList(), "b"))
        f.app.env.session.logout(user)
        testScheduler.advanceUntilIdle()
        assertNull(f.app.env.advent.pendingWave(user, 4))
        assertTrue(f.advent.calls.contains("DELETE /v1/raidstead/session"), "the session ended on the server")
        assertNull(m.current.account)
    }
}

/** #3739: the Advent route, its link, its menu item by date. */
class AdventNavigationTest {
    @Test
    fun theAdventScreenHasItsRouteAndSurvivesSaveAndRestore() {
        assertEquals(AppScreen.Advent, AppScreen.of(RouteName.ADVENT, ""))
        assertFalse(RouteName.ADVENT.requiresLogin, "the calendar shows signed out; a door asks for the login")
        val state = NavState().push(AppScreen.Advent)
        assertEquals(listOf<AppScreen>(AppScreen.Advent), NavState.decode(state.encode()).screens)
    }

    @Test
    fun ecencyComAdventOpensTheCalendar() {
        assertEquals(AppScreen.Advent, ContentLinks.entry("https://ecency.com/advent")?.screens?.single())
        assertEquals(AppScreen.Advent, ContentLinks.entry("https://ecency.com/advent?ref=wave")?.screens?.single())
        assertNull(ContentLinks.entry("https://peakd.com/advent")?.screens?.singleOrNull()?.takeIf { it == AppScreen.Advent }, "only Ecency's own page")
    }

    @Test
    fun theMenuItemLeadsTheMenuOnlyWhileTheSeasonShows() {
        assertFalse(SideMenuItem.ADVENT in SideMenu.items(true, MenuGroup.MAIN, AdventSeason.SHOW_FROM - 1))
        assertEquals(SideMenuItem.ADVENT, SideMenu.items(true, MenuGroup.MAIN, AdventSeason.SHOW_FROM).first())
        assertEquals(SideMenuItem.ADVENT, SideMenu.items(false, MenuGroup.MAIN, AdventSeason.FIRST_DOOR).first(), "signed out too")
        assertFalse(SideMenuItem.ADVENT in SideMenu.items(true, MenuGroup.FOOTER, AdventSeason.FIRST_DOOR))
        assertFalse(SideMenuItem.ADVENT in SideMenu.items(true, MenuGroup.MAIN, AdventSeason.HIDE_AT))
        assertEquals(MenuAction.Open(AppScreen.Advent), SideMenu.action(SideMenuItem.ADVENT, null, false))
        assertEquals("Door 9", SideMenu.adventBadge(AdventSeason.FIRST_DOOR + 8 * AdventSeason.DAY_MILLIS))
        assertNull(SideMenu.adventBadge(AdventSeason.SHOW_FROM))
    }
}
