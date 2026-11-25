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
        /** What `database_api.find_comments` holds: a Wave posted earlier, under [onChainPermlink] only. */
        var onChain: String? = null
        var onChainPermlink: String? = null

        /** The Waves container cannot be read (the Wave fails before anything is kept or sent). */
        var noContainer = false

        /** Holds the Waves container read back (a Wave still on its way) until completed. */
        var containerHeld: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    }

    /** [phoneAhead]: how far the phone's clock runs ahead of the games API's. */
    private fun TestScope.fixture(now: Long = dec9, account: VaultAccount? = VaultAccount(TestApp.USER, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF), phoneAhead: Long = 0): Fixture {
        lateinit var f: Fixture
        val app = TestApp(this) { req ->
            if (req.method.startsWith("bridge.get_account_posts")) f.containerHeld?.await()
            chain(f, req)
        }
        app.now = now
        val advent = DemoAdvent { app.clock() - phoneAhead }
        f = Fixture(app, advent)
        app.authBackend.rest = { r -> advent.http(r) }
        if (account != null) KeyVault(app.store).run { save(account); setCurrentUsername(account.username) }
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        return f
    }

    private fun chain(f: Fixture, req: Sent): HttpResult = when (req.method) {
        "bridge.get_account_posts", "bridge.get_account_posts_v2" ->
            if (f.noContainer) HttpResult(503, "")
            else if ("ecency.waves" in req.params.toString()) FakeTransport.result(req, """[{"author":"ecency.waves","permlink":"waves-20261209","json_metadata":{"tags":["waves-20261209","hive-125125"]}}]""")
            else FakeTransport.result(req, "[]")
        "database_api.find_comments" -> TestApp.commentAsked(req).let { (a, l) ->
            TestApp.comments(req, f.onChain?.takeIf { l == f.onChainPermlink }?.let { body -> """{"author":"$a","permlink":"$l","body":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(body))}}""" })
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
        // the chain has it now, under the permlink it was posted with
        val permlink = f.app.broadcasts().single().params.jsonArray[0].jsonObject.getValue("operations").jsonArray.single().jsonArray[1].jsonObject.getValue("permlink").jsonPrimitive.content
        f.onChain = first.single()
        f.onChainPermlink = permlink
        f.advent.waveStatus = 0
        m.retryWave()
        testScheduler.advanceUntilIdle()
        assertEquals(1, waveBodies(f).size, "the retry linked the Wave on chain, it did not post a second one")
        assertEquals(AdventWaveState.POSTED, m.current.sheet?.wave)
        assertEquals(permlink, f.advent.wave(user, 9), "the door links the Wave that was posted")
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
    fun closingTheSheetWhileTheAnswerIsSentStillPostsItsWave() = runTest {
        val f = fixture()
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.app.authBackend.gate = { r -> if (r.url.endsWith("/doors/9/answer")) held.await() }
        m.submit("Sent while the sheet closes.", 0)
        testScheduler.runCurrent()
        m.closeSheet()
        testScheduler.runCurrent()
        f.app.authBackend.gate = null
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertNull(m.current.sheet)
        assertEquals(1, waveBodies(f).size, "the answer's Wave went out after the sheet closed")
        assertTrue(f.advent.calls.any { it == "POST /v1/advent/2026/doors/9/wave" }, "and was linked to the door")
        assertNull(f.app.env.advent.pendingWave(user, 9))
    }

    @Test
    fun anAnsweredDoorWithNoWaveOffersToPostOne() = runTest {
        val f = fixture()
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        // the answer counts, the Wave fails before anything is kept or sent
        f.noContainer = true
        m.submit("No Wave yet.", 0)
        testScheduler.advanceUntilIdle()
        assertTrue(waveBodies(f).isEmpty())
        assertNull(f.app.env.advent.pendingWave(user, 9))
        m.closeSheet()
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.DONE, m.current.sheet?.mode)
        assertEquals(AdventWaveState.FAILED, m.current.sheet?.wave, "answered and no Wave on the server: \"Post Wave\" shows")
        f.noContainer = false
        m.retryWave()
        testScheduler.advanceUntilIdle()
        assertEquals(AdventWaveState.POSTED, m.current.sheet?.wave)
        assertEquals(1, waveBodies(f).size)
    }

    @Test
    fun closingADoorDuringASignInDoesNotStrandARefreshWaitingOnIt() = runTest {
        val f = fixture(account = VaultAccount(user, AuthType.HIVE_SIGNER, accessToken = "hs-access-$user"))
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        m.closeSheet()
        // the session is refused from now on: the next call signs in again, slowly
        f.advent.refused += "demo-game-$user-1"
        var waiting = 0
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.app.authBackend.gate = { r -> if ("/api/raidstead/session" in r.url) { waiting++; held.await() } }
        m.openDoor(9)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(1, waiting, "the door waits on the new sign-in")
        // a pull to refresh waits on the same sign-in; then the door is closed
        m.refresh()
        testScheduler.runCurrent()
        m.closeSheet()
        testScheduler.runCurrent()
        f.app.authBackend.gate = null
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse(m.current.loading, "the refresh is not stuck loading")
        assertEquals(AdventSignIn.READY, m.current.signIn)
    }

    @Test
    fun aRefusedSignInStillShowsTheCalendar() = runTest {
        val f = fixture()
        f.advent.refuseProofs = 99
        f.advent.refuseVouches = 99
        val m = started(f)
        assertEquals(AdventSignIn.FAILED, m.current.signIn)
        assertTrue(m.current.loaded)
        assertEquals(AdventDoorLook.TODAY, m.current.tiles.first { it.day == 9 }.look, "the doors as signed out readers see them")
        assertEquals(AdventDoorLook.RESULTS, m.current.tiles.first { it.day == 5 }.look, "a drawn door shows its results, not \"Missed\": the account's answers are unknown")
    }

    @Test
    fun aRefusedKeyProofFallsBackToTheEcencyVouch() = runTest {
        val f = fixture()
        com.ecency.core.auth.VaultAccountStore(KeyVault(f.app.store), f.app.store)
            .saveSession(com.ecency.core.auth.TokenSession(user, "token-$user", "refresh-$user", f.app.now + 86_400_000))
        // a phone clock far off: the signed proof is refused, the vouch does not depend on the clock
        f.advent.refuseProofs = 1
        val m = started(f)
        assertEquals(AdventSignIn.READY, m.current.signIn)
        assertEquals("$user|token-$user", f.advent.signIns.last())
    }

    @Test
    fun anOpenCalendarMovesToTheNextDoorAtMidnightUtc() = runTest {
        val f = fixture()
        f.app.clock = { dec9 + testScheduler.currentTime }
        val m = f.app.env.adventModel()
        m.start()
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(9, m.current.todayDoor)
        // 14 hours on: 10 Dec, 00:00 UTC
        testScheduler.advanceTimeBy(14 * 3_600_000L)
        testScheduler.runCurrent()
        assertEquals(10, m.current.todayDoor)
        assertEquals(AdventDoorLook.TODAY, m.current.tiles.first { it.day == 10 }.look)
        m.close()
    }

    @Test
    fun aDoorTappedAfterTheSeasonEndedDoesNotOpen() = runTest {
        val f = fixture()
        val m = started(f)
        f.app.now = AdventSeason.HIDE_AT
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        assertNull(m.current.sheet)
        assertEquals(AdventPhase.HIDDEN, m.current.phase)
    }

    @Test
    fun theTeaserCountsDownToTheFirstDoor() = runTest {
        // 25 Nov 2026, 10:00 UTC
        val start = AdventSeason.SHOW_FROM + 10 * 3_600_000L
        val f = fixture(now = start)
        f.app.clock = { start + testScheduler.currentTime }
        val m = f.app.env.adventModel()
        m.start()
        testScheduler.runCurrent()
        assertEquals(AdventPhase.TEASER, m.current.phase)
        assertEquals("5 days 14 h 0 min", m.current.countdown, "the headline \"The first door opens in\" goes on with the time left")
        testScheduler.advanceTimeBy(60_001)
        testScheduler.runCurrent()
        assertEquals("5 days 13 h 59 min", m.current.countdown, "and ticks every minute")
        m.close()
    }

    @Test
    fun aWavePostingForOneDoorDoesNotBlockAnswersToAnother() = runTest {
        val f = fixture()
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        // door 9's Wave is slow to link (a HiveAuth approval, a slow node)
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.app.authBackend.gate = { r -> if (r.url.endsWith("/doors/9/wave")) held.await() }
        m.submit("Door nine.", 0)
        testScheduler.runCurrent()
        m.closeSheet()
        // door 3, drawn: "Answer anyway"
        m.openDoor(3)
        testScheduler.advanceUntilIdle()
        m.answerFromResult()
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.ANSWER, m.current.sheet?.mode)
        m.submit(null, 0)
        testScheduler.runCurrent()
        assertTrue(f.advent.calls.any { it == "POST /v1/advent/2026/doors/3/answer" }, "door 3's answer went out while door 9's Wave posts")
        f.app.authBackend.gate = null
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.DONE, m.current.sheet?.mode)
    }

    @Test
    fun aCalendarComingBackOnANewUtcDayLoadsIt() = runTest {
        val f = fixture()
        var asleep = 0L
        f.app.clock = { dec9 + asleep + testScheduler.currentTime }
        val m = f.app.env.adventModel()
        m.start()
        testScheduler.runCurrent()
        assertEquals(9, m.current.todayDoor)
        val loads = f.advent.calls.count { it == "GET /v1/advent/2026" }
        m.resumed()
        testScheduler.runCurrent()
        assertEquals(loads, f.advent.calls.count { it == "GET /v1/advent/2026" }, "the same UTC day: nothing loads again")
        // the phone slept through midnight: the timer did not run, the clock moved on
        asleep = 15 * 3_600_000L
        m.resumed()
        testScheduler.runCurrent()
        assertEquals(10, m.current.todayDoor)
        m.close()
    }

    @Test
    fun aMidnightTimerThatWakesEarlyWaitsForTheRealMidnight() = runTest {
        val f = fixture()
        var shift = 0L
        f.app.clock = { dec9 + shift + testScheduler.currentTime }
        val m = f.app.env.adventModel()
        m.start()
        testScheduler.runCurrent()
        // an hour before midnight the clock is set back an hour: the timer wakes at 23:00
        testScheduler.advanceTimeBy(13 * 3_600_000L)
        shift = -3_600_000L
        testScheduler.advanceTimeBy(3_600_000L + 1)
        testScheduler.runCurrent()
        assertEquals(9, m.current.todayDoor)
        testScheduler.advanceTimeBy(3_600_000L + 1)
        testScheduler.runCurrent()
        assertEquals(10, m.current.todayDoor, "the timer was set again for the clock's midnight")
        m.close()
    }

    @Test
    fun aPhoneClockAheadOfTheServerDoesNotSignInOnEveryCall() = runTest {
        val f = fixture(phoneAhead = 2 * AdventSeason.DAY_MILLIS)
        val m = started(f)
        assertEquals(AdventSignIn.READY, m.current.signIn)
        m.refresh()
        testScheduler.advanceUntilIdle()
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        assertEquals(1, f.advent.signIns.size, "one session, kept until the games API refuses it")
    }

    @Test
    fun aSignInThatHandsBackTheRefusedTokenStopsAtTheSecondRefusal() = runTest {
        val f = fixture()
        val m = started(f)
        // the session is refused, and signing in again hands back the same token
        f.advent.reuseTokens = true
        f.advent.refused += "demo-game-$user-1"
        var signIns = 0
        f.app.authBackend.gate = { r -> if ("/raidstead/session" in r.url && ++signIns > 5) error("signing in without end") }
        m.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(signIns <= 2, "signed in $signIns times")
        assertEquals(AdventSignIn.FAILED, m.current.signIn, "two refusals: the screen asks")
    }

    @Test
    fun aCalendarThatFailedToLoadLoadsWhenTheAppComesBack() = runTest {
        val f = fixture()
        f.app.authBackend.rest = { HttpResult(503, "") }
        val m = started(f)
        assertFalse(m.current.loaded)
        assertNotNull(m.current.error)
        f.app.authBackend.rest = { r -> f.advent.http(r) }
        m.resumed()
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.loaded, "back from the background, the calendar loads again")
    }

    @Test
    fun twoCallsRefusedTogetherCountAsOneRefusal() = runTest {
        val f = fixture()
        val m = started(f)
        // the session expired: both calls in flight are refused with the same token
        f.advent.refused += "demo-game-$user-1"
        var inFlight = 0
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.app.authBackend.gate = { r -> if ("/v1/advent/" in r.url && !held.isCompleted) { inFlight++; held.await() } }
        m.refresh()
        m.openDoor(9)
        testScheduler.runCurrent()
        assertEquals(2, inFlight)
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSignIn.READY, m.current.signIn, "one expired session is one refusal: both sign in again")
        assertEquals(AdventSheetMode.ANSWER, m.current.sheet?.mode)
    }

    /** Answers [path] (a suffix) with [result] (null: the demo games API's answer) instead of the demo games API. */
    private fun answering(f: Fixture, path: String, result: (com.ecency.core.auth.AuthHttpRequest) -> HttpResult?) {
        f.app.authBackend.rest = { r -> (if (r.url.substringBefore('?').endsWith(path)) result(r) else null) ?: f.advent.http(r) }
    }

    @Test
    fun aDrawnDoorWhoseResultFailedRetriesTheResult() = runTest {
        val f = fixture()
        val m = started(f)
        var down = true
        answering(f, "/doors/3/result") { if (down) HttpResult(503, "") else null }
        m.openDoor(3)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.FAILED, m.current.sheet?.mode)
        down = false
        m.retrySheet()
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.RESULT, m.current.sheet?.mode, "the result again, not the answer form")
        assertNotNull(m.current.sheet?.result)
    }

    @Test
    fun aMidnightReloadThatFailedOfflineLoadsWhenTheAppComesBack() = runTest {
        val f = fixture()
        var asleep = 0L
        f.app.clock = { dec9 + asleep + testScheduler.currentTime }
        val m = f.app.env.adventModel()
        m.start()
        testScheduler.runCurrent()
        // offline over midnight: the reload fails
        f.app.authBackend.rest = { HttpResult(503, "") }
        asleep = 15 * 3_600_000L
        m.refresh()
        testScheduler.runCurrent()
        assertNotNull(m.current.error, "the failed reload shows")
        f.app.authBackend.rest = { r -> f.advent.http(r) }
        m.resumed()
        testScheduler.runCurrent()
        assertEquals(AdventDoorLook.TODAY, m.current.tiles.first { it.day == 10 }.look, "today's door opens")
        assertNull(m.current.error)
        m.close()
    }

    @Test
    fun aCalendarAfterTheLastDoorLoadsAgainOnTheNextUtcDay() = runTest {
        // 26 Dec: no door today, as on 27 Dec
        val dec26 = AdventSeason.FIRST_DOOR + 25 * AdventSeason.DAY_MILLIS + 3_600_000L
        val f = fixture(now = dec26)
        var asleep = 0L
        f.app.clock = { dec26 + asleep + testScheduler.currentTime }
        val m = f.app.env.adventModel()
        m.start()
        testScheduler.runCurrent()
        val loads = f.advent.calls.count { it == "GET /v1/advent/2026" }
        asleep = AdventSeason.DAY_MILLIS
        m.resumed()
        testScheduler.runCurrent()
        assertEquals(loads + 1, f.advent.calls.count { it == "GET /v1/advent/2026" }, "a new UTC day loads the calendar again")
        m.close()
    }

    @Test
    fun aLateRefusalOfAReplacedSessionDoesNotCountAgainstTheNewOne() = runTest {
        val f = fixture()
        val m = started(f)
        // session 1 expired: the door's call is refused late, after the refresh already signed in again
        f.advent.refused += "demo-game-$user-1"
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.app.authBackend.gate = { r -> if (r.url.endsWith("/doors/9/open")) held.await() }
        m.openDoor(9)
        testScheduler.runCurrent()
        f.app.authBackend.gate = null
        m.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSignIn.READY, m.current.signIn)
        // and session 2 expires too: one more sign-in is still owed
        f.advent.refused += "demo-game-$user-2"
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.ANSWER, m.current.sheet?.mode)
        assertEquals(AdventSignIn.READY, m.current.signIn)
    }

    @Test
    fun aDoorReadFromBeforeTheAnswerDoesNotUndoIt() = runTest {
        val f = fixture()
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        val heldAnswer = kotlinx.coroutines.CompletableDeferred<Unit>()
        val heldOpen = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.app.authBackend.gate = { r -> if (r.url.endsWith("/doors/9/answer")) heldAnswer.await() }
        m.submit("Sent before the door was read again.", 0)
        testScheduler.runCurrent()
        m.closeSheet()
        // the door read again before the answer lands, its answer delivered after it
        val stale = f.advent.http(com.ecency.core.auth.AuthHttpRequest("POST", "${com.ecency.core.api.games.AdventApi.BASE}/v1/advent/2026/doors/9/open", mapOf("authorization" to "Bearer demo-game-$user-1"), null))!!
        answering(f, "/doors/9/open") { stale }
        f.app.authBackend.gate = { r -> if (r.url.endsWith("/doors/9/open")) heldOpen.await() }
        m.openDoor(9)
        testScheduler.runCurrent()
        heldAnswer.complete(Unit)
        testScheduler.runCurrent()
        heldOpen.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.DONE, m.current.sheet?.mode, "the answer stands")
    }

    @Test
    fun leavingTheCalendarRightAfterSubmitStillSendsTheAnswerAndItsWave() = runTest {
        val f = fixture()
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.app.authBackend.gate = { r -> if (r.url.endsWith("/doors/9/answer")) held.await() }
        m.submit("Sent as the screen closes.", 0)
        testScheduler.runCurrent()
        m.close()
        f.app.authBackend.gate = null
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(f.advent.calls.any { it == "POST /v1/advent/2026/doors/9/answer" })
        assertEquals(1, waveBodies(f).size, "the Wave went out after the screen closed")
        assertTrue(f.advent.calls.any { it == "POST /v1/advent/2026/doors/9/wave" }, "and was linked to the door")
    }

    /** Submits [text] to door 9 with the Waves container read held back: the answer lands, its Wave waits (no time passes). */
    private fun TestScope.answeredWithTheWaveOnItsWay(f: Fixture, m: AdventModel, text: String) {
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        f.containerHeld = kotlinx.coroutines.CompletableDeferred()
        m.submit(text, 0)
        testScheduler.runCurrent()
        assertTrue(f.advent.calls.contains("POST /v1/advent/2026/doors/9/answer"))
        assertEquals(AdventWaveState.POSTING, m.current.sheet?.wave)
    }

    @Test
    fun theCalendarOpenedAgainWhileAWaveIsPostingShowsItPosting() = runTest {
        val f = fixture()
        val m = started(f)
        answeredWithTheWaveOnItsWay(f, m, "Still on its way.")
        m.close()
        val again = f.app.env.adventModel()
        again.start()
        testScheduler.runCurrent()
        again.openDoor(9)
        testScheduler.runCurrent()
        assertEquals(AdventWaveState.POSTING, again.current.sheet?.wave, "no second \"Post Wave\" while the first is on its way")
        f.containerHeld!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, waveBodies(f).size)
        assertEquals(AdventWaveState.POSTED, again.current.sheet?.wave, "the reopened sheet follows the Wave to its end")
        again.close()
    }

    @Test
    fun anAnswerThatFailsAfterItsDoorWasOpenedAgainKeepsItsError() = runTest {
        val f = fixture()
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.app.authBackend.gate = { r -> if (r.url.endsWith("/doors/9/answer")) held.await() }
        m.submit("Lost on the way.", 0)
        testScheduler.runCurrent()
        m.closeSheet()
        m.openDoor(9)
        testScheduler.runCurrent()
        f.advent.answerStatus = 503
        f.app.authBackend.gate = null
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.ANSWER, m.current.sheet?.mode)
        assertNotNull(m.current.sheet?.error, "the failure stays on the sheet")
    }

    @Test
    fun theCalendarOpenedAgainWhileTheAnswerIsSentShowsItSending() = runTest {
        val f = fixture()
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.app.authBackend.gate = { r -> if (r.url.endsWith("/doors/9/answer")) held.await() }
        m.submit("Sent while the calendar was closed and opened again.", 0)
        testScheduler.runCurrent()
        m.close()
        val again = f.app.env.adventModel()
        again.start()
        testScheduler.runCurrent()
        again.openDoor(9)
        testScheduler.runCurrent()
        assertEquals(true, again.current.sheet?.submitting, "the answer on its way shows as sending")
        f.app.authBackend.gate = null
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.DONE, again.current.sheet?.mode, "and as taken once it lands")
        assertEquals(AdventWaveState.POSTED, again.current.sheet?.wave)
        again.close()
    }

    @Test
    fun aWaveOnItsWayWhenItsAccountLogsOutIsNeitherKeptNorPosted() = runTest {
        val f = fixture()
        val m = started(f)
        answeredWithTheWaveOnItsWay(f, m, "Answered just before logging out.")
        m.close()
        f.app.env.session.logout(user)
        testScheduler.runCurrent()
        f.containerHeld!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(waveBodies(f).isEmpty(), "no Wave for an account that left")
        assertNull(f.app.env.advent.pendingWave(user, 9), "nor its answer kept on the device")
    }

    @Test
    fun aWaveOnItsWayWhenTheAccountIsSwitchedWaitsForItsAccount() = runTest {
        val f = fixture()
        KeyVault(f.app.store).save(VaultAccount("other-account", AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF))
        val m = started(f)
        answeredWithTheWaveOnItsWay(f, m, "Answered just before switching.")
        m.close()
        f.app.env.session.switchTo("other-account")
        testScheduler.runCurrent()
        f.containerHeld!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(waveBodies(f).isEmpty(), "nothing signed for an account that is not the current one")
        // back on the account: its door offers "Post Wave"
        f.app.env.session.switchTo(user)
        testScheduler.advanceUntilIdle()
        val again = started(f)
        again.openDoor(9)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventWaveState.FAILED, again.current.sheet?.wave)
        again.close()
    }

    @Test
    fun aSignInThatEndsAfterLogoutIsEndedNotUsed() = runTest {
        val f = fixture()
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        f.app.authBackend.gate = { r -> if (r.url.endsWith("/raidstead/session") && r.method == "POST") held.await() }
        val m = f.app.env.adventModel()
        m.start()
        testScheduler.runCurrent()
        f.app.env.session.logout(user)
        testScheduler.runCurrent()
        f.app.authBackend.gate = null
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue("demo-game-$user-1" in f.advent.ended, "the session that came back after the logout was ended")
        assertNull(m.current.account)
        m.close()
    }

    @Test
    fun aLoggedOutSessionNoLongerReadsTheAccountsDoors() = runTest {
        val f = fixture()
        started(f)
        f.app.env.session.logout(user)
        testScheduler.advanceUntilIdle()
        val season = f.advent.http(com.ecency.core.auth.AuthHttpRequest("GET", "${com.ecency.core.api.games.AdventApi.BASE}/v1/advent/2026", mapOf("authorization" to "Bearer demo-game-$user-1"), null))!!
        assertEquals(401, season.status, "the ended session is refused")
    }

    @Test
    fun anUnreadableAnswerResponseIsNotTakenAsAccepted() = runTest {
        val f = fixture()
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        // a captive Wi-Fi page answers 200 with HTML
        answering(f, "/doors/9/answer") { HttpResult(200, "<html>Sign in to the Wi-Fi</html>") }
        m.submit("Lost to a captive portal.", 0)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.ANSWER, m.current.sheet?.mode)
        assertNotNull(m.current.sheet?.error)
        assertTrue(waveBodies(f).isEmpty(), "no Wave for an answer the game never confirmed")
    }

    @Test
    fun aQuizWithAMissingChoiceIsNotAnsweredAtTheWrongIndex() = runTest {
        // 11 Dec: door 11 is a quiz
        val f = fixture(now = dec9 + 2 * AdventSeason.DAY_MILLIS)
        val m = started(f)
        answering(f, "/doors/11/open") { r ->
            val o = Json.parseToJsonElement(f.advent.http(r)!!.body).jsonObject
            HttpResult(200, kotlinx.serialization.json.JsonObject(o + ("choices" to Json.parseToJsonElement("""["A",null,"C"]"""))).toString())
        }
        m.openDoor(11)
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), m.current.sheet?.door?.choices, "a door whose choices are not all there offers none, not C at B's index")
    }

    @Test
    fun theGrandDrawShowsItsWinnersOnceDrawn() = runTest {
        // 26 Dec: the grand draw ran on 25 Dec at 12:00 UTC
        val f = fixture(now = AdventSeason.FIRST_DOOR + 25 * AdventSeason.DAY_MILLIS + 3_600_000L)
        val m = started(f)
        val grand = m.current.grand!!
        assertTrue(grand.drawn)
        assertEquals(listOf("demo-one", "demo-two", "demo-three"), grand.winners.map { it.account })
        assertEquals("5,000 Points", grand.winners.first().prize)
        assertNotNull(grand.seed)
    }

    @Test
    fun aWithdrawnPrizeIsShownAsWithdrawn() {
        val rows = AdventCopy.winners(
            listOf(com.ecency.core.api.games.AdventWinner(1, "first", false), com.ecency.core.api.games.AdventWinner(2, user, true)),
            AdventRewardKind.HIVE, listOf(100.0, 60.0), user,
        )
        assertEquals("100 HIVE", rows[0].prize)
        assertFalse(rows[0].you)
        assertTrue(rows[1].cancelled)
        assertEquals("Withdrawn", rows[1].prize, "no amount for a prize taken back")
        assertTrue(rows[1].you)
        assertEquals("@$user (you)", rows[1].name)
        assertEquals("@first", rows[0].name)
    }

    @Test
    fun doorsTappedWhileTheNewDayLoadsDoNotStartItOver() = runTest {
        val f = fixture()
        var later = 0L
        f.app.clock = { dec9 + later + testScheduler.currentTime }
        val m = f.app.env.adventModel()
        m.start()
        testScheduler.runCurrent()
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        var started = 0
        f.app.authBackend.gate = { r -> if (r.url.endsWith("/v1/advent/2026")) { started++; held.await() } }
        later = 15 * 3_600_000L
        repeat(3) { m.openDoor(9); testScheduler.runCurrent() }
        f.app.authBackend.gate = null
        held.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, started, "one load for the new day, not one per tap")
        assertEquals(10, m.current.todayDoor)
        m.close()
    }

    @Test
    fun aDoorThatFailedOnARefusedSignInSignsInAgainOnTryAgain() = runTest {
        val f = fixture()
        // every session refused: two refusals, the screen asks over the public calendar
        f.advent.refused += listOf("demo-game-$user-1", "demo-game-$user-2")
        val m = started(f)
        assertEquals(AdventDoorLook.RESULTS, m.current.tiles.first { it.day == 5 }.look, "the public calendar")
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.FAILED, m.current.sheet?.mode)
        assertEquals(AdventSignIn.FAILED, m.current.signIn)
        // the games API is back: the sheet's own "Try again" signs in again
        f.advent.refused.clear()
        m.retrySheet()
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.ANSWER, m.current.sheet?.mode)
        assertEquals(AdventSignIn.READY, m.current.signIn)
        assertEquals(AdventDoorLook.MISSED, m.current.tiles.first { it.day == 5 }.look, "the account's own calendar is back, not the public one")
    }

    @Test
    fun aSignInThatWorksBeforeAnOfflineAnswerIsNotLeftSigningIn() = runTest {
        val f = fixture()
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        f.advent.refused += listOf("demo-game-$user-1", "demo-game-$user-2")
        m.submit("Refused twice.", 0)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSignIn.FAILED, m.current.signIn)
        // signing in works again, the answer itself fails (offline)
        f.advent.refused.clear()
        f.advent.answerStatus = 503
        m.submit("Refused twice.", 0)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSignIn.READY, m.current.signIn, "signed in, whatever the answer did")
    }

    @Test
    fun anAnswerRefusedForItsSignInSaysSoAndSignsInAgainOnTheNextTap() = runTest {
        val f = fixture()
        val m = started(f)
        m.openDoor(9)
        testScheduler.advanceUntilIdle()
        f.advent.refused += listOf("demo-game-$user-1", "demo-game-$user-2")
        m.submit("Refused twice.", 0)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSignIn.FAILED, m.current.signIn, "the sign-in retry shows")
        assertEquals(AdventCopy.SIGN_RETRY, m.current.sheet?.error?.message)
        f.advent.refused.clear()
        m.submit("Refused twice.", 0)
        testScheduler.advanceUntilIdle()
        assertEquals(AdventSheetMode.DONE, m.current.sheet?.mode, "the next Submit signs in again")
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
