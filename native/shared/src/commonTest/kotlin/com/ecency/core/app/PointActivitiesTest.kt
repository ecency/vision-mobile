package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.SignedBroadcast
import com.ecency.core.auth.SigningMethod
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Points earning reports (RN useUserActivityMutation, the adapter's recordActivity): the SDK's request, three tries,
 * parked and replayed for the account that acted, and the broadcasts that report.
 */
class PointActivitiesTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"
    private var activityStatus = 200
    private var broadcastDown = false

    /** What `get_content` answers per "author/permlink" (a reply that landed). */
    private val onChain = mutableMapOf<String, String>()

    private fun TestScope.app(): TestApp {
        val app = TestApp(this) { req ->
            when (req.method) {
                "condenser_api.get_accounts" -> FakeTransport.result(req, listOf(alice, bob).filter { "\"$it\"" in req.body }.joinToString(",", "[", "]") { AppTestKeys.accountJson(it) })
                "condenser_api.broadcast_transaction" -> if (broadcastDown) HttpResult(503, "down") else FakeTransport.result(req, "null")
                "condenser_api.get_content" -> {
                    val p = req.params.toString().trim('[', ']').split(',').map { it.trim('"') }
                    onChain["${p[0]}/${p[1]}"]?.let { FakeTransport.result(req, it) } ?: TestApp.chain(req)!!
                }
                "bridge.get_post" -> FakeTransport.result(req, entry("someone", "root"))
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }
        val vault = KeyVault(app.store)
        listOf(bob, alice).forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(it, com.ecency.core.hive.KeyRole.POSTING), accessToken = "access-$it")) }
        vault.setCurrentUsername(alice)
        app.authBackend.rest = { r -> if (r.url.endsWith("/private-api/usr-activity")) HttpResult(activityStatus, "{}") else null }
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        return app
    }

    private fun TestApp.types() = reports().map { it.getValue("ty").toString().toInt() }

    private fun TestApp.txs() = reports().map { it["tx"]?.toString()?.trim('"') }

    private fun TestApp.reports() = authBackend.sent.filter { it.url.endsWith("/private-api/usr-activity") }.map { Json.parseToJsonElement(it.body!!).jsonObject }

    @Test
    fun aReportIsTheSdksRequestAndRefreshesTheQuestsAMinuteLater() = runTest {
        val app = app()
        val due = mutableListOf<String>()
        backgroundScope.launch(app.main) { app.env.questRefreshes.due.collect { due += it } }
        app.env.pointActivities.recordBroadcast(alice, PointActivity.VOTE, SignedBroadcast(SigningMethod.KEY, "abc123", 77L))
        testScheduler.runCurrent()
        assertEquals(listOf(Json.parseToJsonElement("""{"ty":120,"bl":77,"tx":"abc123","code":"access-spike-alice"}""")), app.reports())
        testScheduler.advanceTimeBy(QuestRefreshes.DELAY_MILLIS + 1)
        assertEquals(listOf(alice), due)
    }

    @Test
    fun anEditOrABroadcastWithoutATransactionIdReportsNothing() = runTest {
        val app = app()
        app.env.pointActivities.recordBroadcast(alice, PointActivity.content("parent", isUpdate = true), SignedBroadcast(SigningMethod.KEY, "t1"))
        app.env.pointActivities.recordBroadcast(alice, PointActivity.VOTE, SignedBroadcast(SigningMethod.HIVEAUTH, null))
        testScheduler.advanceUntilIdle()
        assertTrue(app.reports().isEmpty())
        assertEquals(PointActivity.POST, PointActivity.content("", isUpdate = false))
        assertEquals(PointActivity.COMMENT, PointActivity.content("parent", isUpdate = false))
    }

    @Test
    fun aFailingReportIsTriedThreeTimesThenParkedAndReplayedForItsAccount() = runTest {
        val app = app()
        activityStatus = 500
        app.env.pointActivities.record(alice, PointActivity.REBLOG, "tx-1", 5L)
        testScheduler.advanceUntilIdle()
        assertEquals(3, app.reports().size, "one try and React Query's two retries")
        assertEquals(listOf(PointActivities.Parked(app.env.pointActivities.pending(alice).single().id, 130, "tx-1", 5L)), app.env.pointActivities.pending(alice))

        // replayed for the signed-in account only
        activityStatus = 200
        app.env.session.switchTo(bob)
        testScheduler.runCurrent()
        app.env.pointActivities.replayPending()
        testScheduler.advanceUntilIdle()
        assertEquals(3, app.reports().size)
        app.env.session.switchTo(alice)
        testScheduler.runCurrent()
        // the start and the foreground at once, with a slow answer: each parked report is sent once
        val slow = kotlinx.coroutines.CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("/usr-activity")) slow.await() }
        app.env.pointActivities.replayPending()
        testScheduler.advanceTimeBy(1_000)
        app.env.pointActivities.replayPending()
        testScheduler.advanceTimeBy(PointActivities.REPLAY_DELAY_MILLIS - 1_001)
        assertEquals(3, app.reports().size, "3 s after the start")
        testScheduler.advanceTimeBy(5_000)
        slow.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(4, app.reports().size)
        assertEquals(Json.parseToJsonElement("""{"ty":130,"bl":5,"tx":"tx-1","code":"access-spike-alice"}"""), app.reports().last())
        assertTrue(app.env.pointActivities.pending(alice).isEmpty())
    }

    @Test
    fun parkedReportsGoWithAWipeAndStayBounded() = runTest {
        val app = app()
        activityStatus = 500
        repeat(PointActivities.MAX_PARKED + 3) { app.env.pointActivities.record(alice, PointActivity.VOTE, "tx-$it") }
        testScheduler.advanceUntilIdle()
        val parked = app.env.pointActivities.pending(alice)
        assertEquals(PointActivities.MAX_PARKED, parked.size)
        assertEquals("tx-3", parked.first().txId, "the oldest give way")
        assertTrue(PointActivities.key(alice) in app.env.prefs.keys())
        AccountPrefs.clear(app.env.prefs)
        assertTrue(app.env.pointActivities.pending(alice).isEmpty(), "the key names the account: a wipe removes it")
    }

    @Test
    fun anAccountWithoutAnEcencySessionParksNothing() = runTest {
        val app = app()
        KeyVault(app.store).save(VaultAccount("spike-nocode", AuthType.POSTING_KEY, postingKey = AppTestKeys.wif("spike-nocode", com.ecency.core.hive.KeyRole.POSTING)))
        var result: Boolean? = null
        app.env.pointActivities.record("spike-nocode", PointActivity.VOTE, "t") { result = it }
        testScheduler.advanceUntilIdle()
        assertEquals(false, result)
        assertTrue(app.env.pointActivities.pending("spike-nocode").isEmpty())
    }

    @Test
    fun aVoteReportsWithItsTransaction() = runTest {
        val app = app()
        app.env.votes.vote("someone", "root", 10000)
        testScheduler.advanceUntilIdle()
        val report = app.reports().single()
        assertEquals("120", report.getValue("ty").toString())
        val tx = report.getValue("tx").toString().trim('"')
        assertEquals(40, tx.length, "the signed transaction's id")
    }

    @Test
    fun aKeyLoginReportsTheLogin() = runTest {
        val app = app()
        app.env.session.logout(alice)
        testScheduler.advanceUntilIdle()
        app.env.session.loginWithKey(alice, AppTestKeys.wif(alice, com.ecency.core.hive.KeyRole.POSTING))
        testScheduler.advanceUntilIdle()
        val ty = app.reports().map { it.getValue("ty").toString() }
        assertTrue("20" in ty, "$ty")
    }

    // ---- one replay at a time, the lock ----

    @Test
    fun overlappingReplaysSendEachParkedReportOnce() = runTest {
        val app = app()
        activityStatus = 500
        app.env.pointActivities.record(alice, PointActivity.VOTE, "tx-1", 1L)
        app.env.pointActivities.record(alice, PointActivity.VOTE, "tx-2", 2L)
        testScheduler.advanceUntilIdle()
        val before = app.reports().size
        activityStatus = 200
        // the first replay's first entry is slow; a second replay (a start and a foreground at once) overlaps it
        val slow = kotlinx.coroutines.CompletableDeferred<Unit>()
        var held = false
        app.authBackend.gate = { r -> if (r.url.endsWith("/usr-activity") && !held) { held = true; slow.await() } }
        app.env.pointActivities.replayPending()
        app.env.pointActivities.replayPending()
        testScheduler.advanceTimeBy(PointActivities.REPLAY_DELAY_MILLIS + 1)
        testScheduler.runCurrent()
        slow.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("tx-1", "tx-2"), app.txs().drop(before), "each parked report once")
        assertTrue(app.env.pointActivities.pending(alice).isEmpty())
    }

    @Test
    fun aFinishedReplayLetsTheNextOneRun() = runTest {
        val app = app()
        activityStatus = 500
        app.env.pointActivities.record(alice, PointActivity.VOTE, "tx-1")
        testScheduler.advanceUntilIdle()
        activityStatus = 200
        app.env.pointActivities.replayPending()
        testScheduler.advanceUntilIdle()
        activityStatus = 500
        app.env.pointActivities.record(alice, PointActivity.VOTE, "tx-2")
        testScheduler.advanceUntilIdle()
        activityStatus = 200
        app.env.pointActivities.replayPending()
        testScheduler.advanceUntilIdle()
        assertEquals("tx-2", app.txs().last(), "the second replay ran")
        assertTrue(app.env.pointActivities.pending(alice).isEmpty())
    }

    @Test
    fun aReportWhileTheLockHidesTheAppIsParkedAndSentAfterTheUnlock() = runTest {
        val app = app()
        app.env.pointActivities.start()
        PinLock(KeyVault(app.store), 16).setPin("1234")
        app.env.appLock.refresh()
        testScheduler.advanceUntilIdle()
        app.env.appLock.lockIfEnabled()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.appLock.current.hidesContent)
        app.env.pointActivities.record(alice, PointActivity.VOTE, "tx-locked")
        testScheduler.advanceUntilIdle()
        assertTrue(app.reports().isEmpty(), "nothing is sent while the lock hides the app")
        assertEquals(listOf("tx-locked"), app.env.pointActivities.pending(alice).map { it.txId })
        "1234".forEach { app.env.appLock.enterDigit(it - '0') }
        testScheduler.advanceUntilIdle()
        assertTrue(!app.env.appLock.current.hidesContent)
        assertEquals(listOf("tx-locked"), app.txs(), "the unlock replays it")
        assertTrue(app.env.pointActivities.pending(alice).isEmpty())
    }

    // ---- what each broadcast reports ----

    private fun TestScope.ensure(app: TestApp) {
        testScheduler.advanceUntilIdle()
        assertTrue(app.txs().all { it != null && it.length == 40 }, "${app.txs()}")
    }

    @Test
    fun aNewReplyReportsACommentAndItsEditNothing() = runTest {
        val app = app()
        val root = com.ecency.core.api.model.PostKey("someone", "root")
        val reply = app.env.replyModel(root, com.ecency.core.api.model.Post("someone", "root"))
        reply.setText("first")
        reply.send()
        ensure(app)
        assertEquals(listOf(110), app.types())

        // a second reply whose first send failed and landed anyway: the chosen update is an edit and reports nothing
        val other = app.env.replyModel(root, com.ecency.core.api.model.Post("someone", "root"))
        other.setText("second")
        broadcastDown = true
        other.send()
        testScheduler.advanceUntilIdle()
        broadcastDown = false
        val link = other.current.draftPermlink!!
        onChain["$alice/$link"] = """{"author":"$alice","permlink":"$link","parent_author":"someone","parent_permlink":"root","title":"","body":"second","json_metadata":"{}","created":"2026-10-06T11:59:00"}"""
        other.setText("second, edited")
        other.send()
        testScheduler.advanceUntilIdle()
        assertTrue(other.current.conflict, "asks: update or new")
        other.resolveConflict(true)
        ensure(app)
        assertEquals(listOf(110), app.types(), "the edit reported nothing")
        reply.close()
        other.close()
    }

    @Test
    fun aReblogAndItsUndoReportAReblogEach() = runTest {
        val app = app()
        val post = com.ecency.core.api.model.Post("someone", "root")
        app.env.postActions.select(PostMenuItem.REBLOG, post)
        ensure(app)
        app.env.postActions.select(PostMenuItem.UNDO_REBLOG, post)
        ensure(app)
        assertEquals(listOf(130, 130), app.types())
        assertEquals(2, app.txs().toSet().size)
    }

    @Test
    fun aCrossPostReportsAReblog() = runTest {
        val app = app()
        val cross = app.env.crossPostModel(com.ecency.core.api.model.Post("someone", "root", title = "Root"), "4.0.0")
        cross.select(com.ecency.core.api.SubscribedCommunity("hive-100001", "Test"))
        cross.publish()
        ensure(app)
        assertEquals("hive-100001", cross.current.published)
        assertEquals(listOf(130), app.types())
        cross.close()
    }
}
