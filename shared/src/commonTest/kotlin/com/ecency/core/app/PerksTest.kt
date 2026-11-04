package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Perks: the quests card (SDK getQuestsQueryOptions, RN questsCard), its streak freeze buy, and the spend options. */
class PerksTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"

    private val quests = mutableMapOf(
        alice to """{"daily":[{"id":"checkin","progress":1,"reward_each":5},{"id":"post","progress":0,"cap":5},{"id":"comment","progress":2},""" +
            """{"id":"vote","progress":14},{"id":"reblog","progress":0}],"weekly":[{"id":"post","progress":2},{"id":"vote","progress":50}],""" +
            """"monthly":[{"id":"post","progress":7}],"streak":{"current":4,"best":9,"at_risk":false,"freezes_owned":1}}""",
        bob to """{"daily":[],"weekly":[],"monthly":[],"streak":{"current":0,"best":0,"at_risk":false}}""",
    )
    private var questsStatus = 200
    private var freezeAnswer: () -> HttpResult = { HttpResult(200, """{"owned":2,"points":120.5}""") }

    private fun TestScope.app(): TestApp {
        val app = appWithAccounts(this)
        val vault = KeyVault(app.store)
        listOf(bob, alice).forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(it, com.ecency.core.hive.KeyRole.POSTING), accessToken = "access-$it")) }
        vault.setCurrentUsername(alice)
        app.authBackend.rest = { r ->
            when {
                r.url.endsWith("/private-api/quests") -> {
                    val user = Json.parseToJsonElement(r.body!!).jsonObject.getValue("username").jsonPrimitive.content
                    if (questsStatus != 200) HttpResult(questsStatus, "") else HttpResult(200, quests.getValue(user))
                }
                r.url.endsWith("/private-api/streak-freeze/buy") -> freezeAnswer()
                else -> null
            }
        }
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        return app
    }

    private fun TestScope.started(app: TestApp): PerksModel = app.env.perksModel().also {
        it.start()
        testScheduler.runCurrent()
    }

    private fun TestApp.calls(path: String) = authBackend.sent.filter { it.url.endsWith(path) }

    private fun AuthHttpRequest.json(): JsonObject = Json.parseToJsonElement(body!!).jsonObject

    @Test
    fun loadsTheQuestsWithTheSdksPublicRequestAndRnsRows() = runTest {
        val app = app()
        val model = started(app)
        val s = model.current
        assertEquals(alice, s.account)
        val sent = app.calls("/private-api/quests").single()
        assertEquals("POST", sent.method)
        assertEquals("https://ecency.com/private-api/quests", sent.url)
        assertEquals(Json.parseToJsonElement("""{"username":"spike-alice"}"""), Json.parseToJsonElement(sent.body!!), "public: no code")
        // the daily tier without the spin, in the catalog's order
        assertEquals(listOf("checkin", "post", "comment", "vote", "reblog"), s.rows.map { it.id })
        assertEquals(listOf("Check in", "Publish a post", "Leave comments", "Curate posts", "Reblog a post"), s.rows.map { it.title })
        assertEquals(listOf("1/1", "0/1", "2/3", "14/10", "0/1"), s.rows.map { it.count })
        assertEquals(listOf(100, 0, 67, 100, 0), s.rows.map { it.percent })
        assertEquals(listOf(true, false, false, true, false), s.rows.map { it.completed })
        assertEquals(listOf(AppIcon.CHECK_CIRCLE, AppIcon.EDIT, AppIcon.COMMENT, AppIcon.CHECK_CIRCLE, AppIcon.REBLOG), s.rows.map { it.icon })
        assertEquals("4-day streak", PerksCopy.streak(s.streak))
        assertTrue(s.showStreak && s.showFreezeOwned && s.showFreezeBuy)
        assertEquals("1 in reserve", PerksCopy.freezeOwned(s.freezesOwned))
        assertEquals("Protect streak (300 Points)", s.freezeLabel)

        model.selectTier(QuestTier.WEEKLY)
        assertEquals(listOf("post" to "2/5", "comment" to "0/15", "vote" to "50/50", "reblog" to "0/5"), model.current.rows.map { it.id to it.count })
        model.selectTier(QuestTier.MONTHLY)
        assertEquals(listOf("post" to "7/20"), model.current.rows.map { it.id to it.count })
        assertEquals(35, model.current.rows.single().percent)
        model.close()
    }

    @Test
    fun theStreakAndItsFreezesShowOnlyWithAStreakAndBelowTheMaximum() = runTest {
        val noStreak = QuestsData.parse(quests.getValue(bob))!!
        val s = PerksState(account = bob, quests = noStreak)
        assertFalse(s.showStreak || s.showFreezeOwned || s.showFreezeBuy)
        val full = PerksState(quests = noStreak.copy(streak = 3, freezesOwned = 2))
        assertTrue(full.showStreak && full.showFreezeOwned)
        assertFalse(full.showFreezeBuy, "two owned: the buy button goes (SDK STREAK_FREEZE_MAX_OWNED)")
        val none = PerksState(quests = noStreak.copy(streak = 3, freezesOwned = 0))
        assertFalse(none.showFreezeOwned)
        assertTrue(none.showFreezeBuy)
        assertEquals("Protecting", none.copy(buyingFreeze = true).freezeLabel)
    }

    @Test
    fun anUntrustedAnswerReadsSafely() {
        assertNull(QuestsData.parse("<html>"))
        assertNull(QuestsData.parse("[1,2]"))
        val odd = QuestsData.parse("""{"daily":[{"id":"vote","progress":-4},{"id":"vote","progress":3},{"progress":9}],"streak":{"current":-2,"freezes_owned":"x"}}""")!!
        assertEquals(3, odd.progress.getValue(QuestTier.DAILY)["vote"], "the last row of an id wins (RN Object.fromEntries)")
        assertEquals(0, odd.streak)
        assertEquals(0, odd.freezesOwned)
        assertEquals(0, QuestRow("vote", QuestTier.DAILY, "x", 0, 0, "repeat").percent, "a goal of 0 is 0 %")
        assertEquals(AppIcon.STAR, AppIcon.quest("gift"), "RN's fallback icon")
    }

    @Test
    fun aFailureWithoutDataSaysSoAndRetryLoadsAgain() = runTest {
        questsStatus = 500
        val app = app()
        val model = started(app)
        assertTrue(model.current.failed)
        assertEquals("We could not load this. Please try again.", model.current.errorText)
        assertTrue(model.current.rows.isEmpty(), "never every quest at 0")
        questsStatus = 200
        model.retry()
        assertTrue(model.current.retrying)
        testScheduler.runCurrent()
        assertFalse(model.current.failed || model.current.retrying)
        assertEquals(5, model.current.rows.size)
        // a later failed refetch keeps the numbers (RN shows the error only while there is no data)
        questsStatus = 503
        model.onForeground()
        testScheduler.runCurrent()
        assertFalse(model.current.failed)
        assertEquals(5, model.current.rows.size)
        model.close()
    }

    @Test
    fun aTimeoutHasItsOwnText() = runTest {
        val app = app()
        app.authBackend.gate = { r -> if (r.url.endsWith("/quests")) CompletableDeferred<Unit>().await() }
        val model = started(app)
        testScheduler.advanceTimeBy(com.ecency.core.auth.EcencyPrivateApi.TIMEOUT_MILLIS + 1)
        assertTrue(model.current.failed && model.current.timedOut)
        assertEquals("The connection is taking too long. Check your network and try again.", model.current.errorText)
        model.close()
    }

    @Test
    fun theCardFollowsTheAccountTheForegroundAndARecordedActivity() = runTest {
        val app = app()
        val model = started(app)
        assertEquals(1, app.calls("/quests").size)
        model.onForeground()
        testScheduler.runCurrent()
        assertEquals(2, app.calls("/quests").size, "RN refetches the quests on focus")

        // an activity of alice: the quests are asked again 70 s later, not before
        app.env.questRefreshes.schedule(alice)
        testScheduler.advanceTimeBy(QuestRefreshes.DELAY_MILLIS - 1)
        assertEquals(2, app.calls("/quests").size)
        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        assertEquals(3, app.calls("/quests").size)
        // another account's activity does not touch this card
        app.env.questRefreshes.schedule(bob)
        testScheduler.advanceTimeBy(QuestRefreshes.DELAY_MILLIS + 1)
        testScheduler.runCurrent()
        assertEquals(3, app.calls("/quests").size)

        app.env.session.switchTo(bob)
        testScheduler.runCurrent()
        assertEquals(bob, model.current.account)
        assertFalse(model.current.showStreak)
        assertEquals(bob, app.calls("/quests").last().json().getValue("username").jsonPrimitive.content)
        model.close()
    }

    @Test
    fun aRefreshIsKeptPerAccountSoASecondAccountNeverCancelsTheFirst() = runTest {
        val app = app()
        val due = mutableListOf<String>()
        backgroundScope.launch(app.main) { app.env.questRefreshes.due.collect { due += it } }
        app.env.questRefreshes.schedule("@$alice")
        testScheduler.advanceTimeBy(30_000)
        app.env.questRefreshes.schedule(bob)
        testScheduler.advanceTimeBy(30_000)
        app.env.questRefreshes.schedule(bob) // a burst of bob's collapses into one
        testScheduler.advanceUntilIdle()
        testScheduler.runCurrent() // the collector runs in the background scope
        assertEquals(listOf(alice, bob), due)
    }

    // ---- the streak freeze ----

    @Test
    fun theFreezeAsksFirstThenBuysOnceWithItsOwnKey() = runTest {
        val app = app()
        var keys = 0
        app.env.idempotencyKey = { "key-${++keys}" }
        val model = started(app)
        model.askFreeze()
        assertTrue(model.current.confirmingFreeze)
        testScheduler.runCurrent()
        assertTrue(app.calls("/streak-freeze/buy").isEmpty(), "nothing is spent before the confirmation")
        model.confirmFreeze()
        model.confirmFreeze() // a second tap on the closed dialog
        assertTrue(model.current.buyingFreeze)
        testScheduler.runCurrent()
        val sent = app.calls("/streak-freeze/buy").single()
        assertEquals("https://ecency.com/private-api/streak-freeze/buy", sent.url)
        assertEquals(Json.parseToJsonElement("""{"idempotency_key":"key-1","code":"access-spike-alice"}"""), Json.parseToJsonElement(sent.body!!))
        assertFalse(model.current.buyingFreeze)
        assertNull(model.current.alert)
        assertNull(app.env.streakFreezeBuys.key(alice), "delivered: the key is done")
        assertEquals(2, app.calls("/quests").size, "the owned count again after the buy")
        // the next buy is a new confirmation and a new key
        model.askFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        assertEquals("key-2", app.calls("/streak-freeze/buy").last().json().getValue("idempotency_key").jsonPrimitive.content)
        model.close()
    }

    @Test
    fun cancellingTheConfirmationSendsNothing() = runTest {
        val app = app()
        val model = started(app)
        model.askFreeze()
        model.cancelFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        assertFalse(model.current.confirmingFreeze)
        assertTrue(app.calls("/streak-freeze/buy").isEmpty())
        model.close()
    }

    @Test
    fun aLostAnswerKeepsTheKeySoTheNextConfirmationReplaysIt() = runTest {
        val app = app()
        var keys = 0
        app.env.idempotencyKey = { "key-${++keys}" }
        val model = started(app)
        freezeAnswer = { HttpResult(502, "<html>") }
        model.askFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        assertEquals(PerksCopy.FREEZE_ERROR, model.current.alert)
        assertEquals("key-1", app.env.streakFreezeBuys.key(alice), "the outcome is unknown: the key stays")
        model.dismissAlert()
        // a thrown transport error and an unreadable 200 are lost answers too
        app.authBackend.gate = { r -> if (r.url.endsWith("/buy")) throw IllegalStateException("reset") }
        model.askFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        app.authBackend.gate = null
        freezeAnswer = { HttpResult(200, "not json") }
        model.askFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        freezeAnswer = { HttpResult(200, """{"owned":1}""") }
        model.askFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        assertEquals(listOf("key-1", "key-1", "key-1"), app.calls("/streak-freeze/buy").map { it.json().getValue("idempotency_key").jsonPrimitive.content })
        assertNull(app.env.streakFreezeBuys.key(alice))
        model.close()
    }

    @Test
    fun refusalsEndTheKeyAndRouteLikeRn() = runTest {
        val app = app()
        var keys = 0
        app.env.idempotencyKey = { "key-${++keys}" }
        val model = started(app)
        // 402: not enough Points -> Get Points for this account, no alert
        freezeAnswer = { HttpResult(402, """{"message":"insufficient"}""") }
        model.askFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        assertEquals(alice, model.current.getPointsFor)
        assertNull(model.current.alert)
        assertNull(app.env.streakFreezeBuys.key(alice))
        model.getPointsOpened()
        assertNull(model.current.getPointsFor)
        // 409: already at the maximum -> silent, the count is asked again
        val before = app.calls("/quests").size
        freezeAnswer = { HttpResult(409, "{}") }
        model.askFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        assertNull(model.current.alert)
        assertEquals(before + 1, app.calls("/quests").size)
        // another refusal: RN's alert, and a refusal charges nothing so the key goes
        freezeAnswer = { HttpResult(400, "{}") }
        model.askFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        assertEquals(PerksCopy.FREEZE_ERROR, model.current.alert)
        assertNull(app.env.streakFreezeBuys.key(alice))
        assertEquals(listOf("key-1", "key-2", "key-3"), app.calls("/streak-freeze/buy").map { it.json().getValue("idempotency_key").jsonPrimitive.content })
        model.close()
    }

    @Test
    fun aBuyOutlivesItsScreenAndAReopenedScreenJoinsIt() = runTest {
        val app = app()
        val gate = CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("/buy")) gate.await() }
        val first = started(app)
        first.askFreeze()
        first.confirmFreeze()
        testScheduler.runCurrent()
        first.close()
        val second = started(app)
        assertTrue(second.current.buyingFreeze, "the buy of this account is still running")
        second.askFreeze()
        second.confirmFreeze()
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, app.calls("/streak-freeze/buy").size, "joined, never a second buy")
        assertFalse(second.current.buyingFreeze)
        second.close()
    }

    @Test
    fun twoOpenScreensConfirmingAtOnceBuyOnce() = runTest {
        val app = app()
        val gate = CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("/buy")) gate.await() }
        val first = started(app)
        val second = started(app)
        first.askFreeze()
        second.askFreeze()
        first.confirmFreeze()
        second.confirmFreeze()
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, app.calls("/streak-freeze/buy").size, "the second joins the running buy")
        assertFalse(first.current.buyingFreeze || second.current.buyingFreeze)
        first.close()
        second.close()
    }

    @Test
    fun anotherAccountNeverBuysWithTheFirstOnesConfirmation() = runTest {
        val app = app()
        val model = started(app)
        model.askFreeze()
        app.env.session.switchTo(bob)
        // confirmed before the card followed the switch: the account check at the confirmation stops it
        model.confirmFreeze()
        testScheduler.runCurrent()
        assertTrue(app.calls("/streak-freeze/buy").isEmpty())
        assertFalse(model.current.confirmingFreeze)
        model.close()
    }

    @Test
    fun aKeptKeyOutlivesALogoutSoTheReturningAccountReplaysIt() = runTest {
        val app = app()
        var keys = 0
        app.env.idempotencyKey = { "key-${++keys}" }
        val model = started(app)
        val gate = CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("/buy")) gate.await() }
        freezeAnswer = { HttpResult(504, "") }
        model.askFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        // logged out while the buy is on its way: its outcome is not known yet, the key stays
        app.env.session.logout(alice)
        testScheduler.runCurrent()
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals("key-1", app.env.streakFreezeBuys.key(alice), "a lost answer: still unknown")
        KeyVault(app.store).save(VaultAccount(alice, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(alice, com.ecency.core.hive.KeyRole.POSTING), accessToken = "access-$alice"))
        app.env.session.switchTo(alice)
        testScheduler.runCurrent()
        freezeAnswer = { HttpResult(200, """{"owned":2}""") }
        model.askFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        assertEquals(listOf("key-1", "key-1"), app.calls("/streak-freeze/buy").map { it.json().getValue("idempotency_key").jsonPrimitive.content })
        assertNull(app.env.streakFreezeBuys.key(alice), "a definite outcome ends it")
        model.close()
    }

    @Test
    fun theFreezeIsOfferedOnlyBelowTheMaximumAndNotWhileABuyRuns() = runTest {
        quests[alice] = quests.getValue(alice).replace("\"freezes_owned\":1", "\"freezes_owned\":2")
        val app = app()
        val model = started(app)
        assertFalse(model.current.showFreezeBuy)
        model.askFreeze()
        assertFalse(model.current.confirmingFreeze, "two owned: nothing to ask")
        model.close()

        quests[alice] = quests.getValue(alice).replace("\"freezes_owned\":2", "\"freezes_owned\":0")
        val gate = CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("/buy")) gate.await() }
        val busy = started(app)
        busy.askFreeze()
        busy.confirmFreeze()
        testScheduler.runCurrent()
        busy.askFreeze()
        assertFalse(busy.current.confirmingFreeze, "a buy is running")
        gate.complete(Unit)
        testScheduler.runCurrent()
        busy.close()
    }

    @Test
    fun anOutcomeThatArrivesAfterAnAccountSwitchIsNotShownToTheNextAccount() = runTest {
        val app = app()
        val model = started(app)
        val gate = CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("/buy")) gate.await() }
        freezeAnswer = { HttpResult(402, "{}") }
        model.askFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        app.env.session.switchTo(bob)
        testScheduler.runCurrent()
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(bob, model.current.account)
        assertNull(model.current.getPointsFor, "alice's 402 never opens Get Points for bob")
        assertFalse(model.current.buyingFreeze)
        model.close()
    }

    @Test
    fun theOwnedCountIsAskedAgainAfterTheBuyEvenWhileARefreshWasOnItsWay() = runTest {
        val app = app()
        val model = started(app)
        val before = app.calls("/quests").size
        // a refresh is on its way (slow) when the buy's outcome arrives: its answer may predate the buy
        val slow = CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("/quests")) slow.await() }
        model.onForeground()
        testScheduler.runCurrent()
        model.askFreeze()
        model.confirmFreeze()
        testScheduler.runCurrent()
        app.authBackend.gate = null
        slow.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(before + 2, app.calls("/quests").size, "the slow one, then one after the buy")
        model.close()
    }

    @Test
    fun theCardAsksForTheQuestsOnceWhenItOpens() = runTest {
        val app = app()
        val model = started(app)
        // a session change that keeps the account (a login prefill) asks nothing again
        app.env.session.requestLogin("someone")
        testScheduler.advanceUntilIdle()
        app.env.session.consumeLoginPrefill()
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.calls("/quests").size)
        model.close()
    }

    // ---- the ways to spend ----

    @Test
    fun theSpendOptionsAreRnsInRnsOrder() {
        assertEquals(listOf("spin", "boost_plus", "rc_topup", "promote", "account_boost"), SpendOptions.ALL.map { it.id })
        assertEquals(
            listOf(AppScreen.SpinGame, AppScreen.Redeem("boost_plus"), AppScreen.Redeem("rc_topup"), AppScreen.Redeem("promote"), AppScreen.AccountBoost()),
            SpendOptions.ALL.map { it.screen },
        )
        assertEquals("Keep posting when low on Resource Credits", SpendOptions.ALL[2].description)
        assertTrue(RouteName.PERKS.built && RouteName.PERKS.requiresLogin)
        assertTrue(RouteName.SPIN_GAME.built && RouteName.SPIN_GAME.requiresLogin)
        assertTrue(RouteName.ACCOUNT_BOOST.built && RouteName.BOOST.built, "in-app purchases are built")
        assertEquals(AppScreen.Perks, AppScreen.of(RouteName.PERKS))
        assertEquals(AppScreen.SpinGame, AppScreen.of(RouteName.SPIN_GAME))
        val saved = NavState().push(AppScreen.Perks).push(AppScreen.SpinGame).encode()
        assertEquals(listOf<AppScreen>(AppScreen.Perks, AppScreen.SpinGame), NavState.decode(saved).stack.map { it.screen })
    }
}
