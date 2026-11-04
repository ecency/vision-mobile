package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ecency Pro by in-app purchase (issue #3555): the Perks card and sheet over the purchase service, against fakes. */
class ProTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"

    private class Rig(val app: TestApp, val store: FakeStore) {
        val env get() = app.env
        val orders: List<AuthHttpRequest> get() = app.authBackend.sent.filter { it.url.endsWith("/private-api/purchase-order") }
        val rosterReads: Int get() = app.authBackend.sent.count { it.url.endsWith("/private-api/pro-members") }
    }

    /** The Pro roster Ecency answers; an accepted Pro order adds its account when [deliverOnOrder]. */
    private val roster = mutableSetOf<String>()
    private var deliverOnOrder = true
    private var orderAnswer = HttpResult(201, "{}")

    private fun TestScope.rig(signedIn: String? = alice, prefs: Prefs = InMemoryPrefs(), held: List<StorePurchase> = emptyList(), purchasesFirst: Boolean = false): Rig {
        val app = appWithAccounts(this)
        val vault = KeyVault(app.store)
        listOf(bob, alice).forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(it, com.ecency.core.hive.KeyRole.POSTING), accessToken = "access-$it")) }
        vault.setCurrentUsername(signedIn)
        app.authBackend.rest = { r ->
            when {
                r.url.endsWith("/private-api/purchase-order") -> orderAnswer.also {
                    val o = Json.parseToJsonElement(r.body!!).jsonObject
                    if (it.status in 200..299 && deliverOnOrder && o["product"]?.jsonPrimitive?.content == PurchaseSkus.PRO) roster += o["user"]!!.jsonPrimitive.content
                }
                r.url.endsWith("/private-api/pro-members") -> HttpResult(200, """{"members":[${roster.joinToString(",") { "\"$it\"" }}],"count":${roster.size}}""")
                else -> null
            }
        }
        val store = FakeStore(PurchaseService.PLAY)
        store.held += held
        app.env.prefs = prefs
        app.env.store = store
        app.env.crashReporter = RecordingReporter()
        // iOS starts the purchases before the session ([purchasesFirst]): its recovery runs first and must wait
        if (purchasesFirst) {
            app.env.purchases.start()
            testScheduler.advanceUntilIdle()
        }
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        if (!purchasesFirst) app.env.purchases.start()
        testScheduler.advanceUntilIdle()
        return Rig(app, store)
    }

    private fun TestScope.pro(r: Rig): ProModel = r.env.proModel().also {
        it.start()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun aNonMemberSeesTheBenefitsAndTheStoresPrice() = runTest {
        val r = rig()
        val m = pro(r)
        val s = m.current
        assertEquals(alice, s.account)
        assertFalse(s.member)
        assertEquals("$19.99", s.price)
        assertEquals("Go Pro for $19.99 a year. Verified badge, a free blog, and support Ecency.", s.cardText)
        assertEquals("Get Pro for $19.99 a year", s.buyLabel)
        assertTrue(s.canBuy)
        assertEquals(
            listOf(
                "A verified Pro badge next to your name.", "A free blog at yourname.blogs.ecency.com.",
                "Directly support Ecency development.", "Early access to new features.",
            ),
            ProCopy.BENEFITS, "web pro.benefit-* wording",
        )
        m.close()
    }

    @Test
    fun aMemberSeesTheMemberStateAndCanExtend() = runTest {
        roster += alice
        val r = rig()
        val m = pro(r)
        assertTrue(m.current.member)
        assertEquals("You are a Pro member.", m.current.cardText)
        assertEquals("Extend by 12 months for $19.99", m.current.buyLabel)
        m.close()
    }

    @Test
    fun theStoreWithoutTheProductShowsNoBuyButton() = runTest {
        val r = rig()
        r.store.catalog = r.store.catalog.filter { it.productId != PurchaseSkus.PRO }
        val m = pro(r)
        assertNull(m.current.price)
        assertTrue(m.current.unavailable)
        assertFalse(m.current.showBuy)
        assertEquals(ProCopy.CARD_PLAIN, m.current.cardText)
        m.buy()
        testScheduler.advanceUntilIdle()
        assertTrue(r.store.sheets.isEmpty())
        m.close()
    }

    @Test
    fun aPurchaseIsOrderedForTheSignedInAccountThenActivatesOnceTheRosterHasIt() = runTest {
        deliverOnOrder = false
        val r = rig()
        val m = pro(r)
        m.open()
        m.buy()
        // not advanceUntilIdle: it would run every roster poll at once
        testScheduler.runCurrent()
        assertEquals(listOf(PurchaseSkus.PRO), r.store.sheets)
        val order = Json.parseToJsonElement(r.orders.single().body!!).jsonObject
        assertEquals("play_store", order["platform"]!!.jsonPrimitive.content)
        assertEquals("1999pro", order["product"]!!.jsonPrimitive.content)
        assertEquals(alice, order["user"]!!.jsonPrimitive.content, "the logged-in account, no gifting")
        assertEquals(1, r.store.finished.size, "consumed after Ecency accepted the order")
        // the roster has not caught up yet: activating, and it is read again past the 5 minute cache
        assertTrue(m.current.activating)
        assertFalse(m.current.showBuy)
        val reads = r.rosterReads
        roster += alice
        testScheduler.advanceTimeBy(ProMembersModel.ACTIVATION_POLL_MILLIS + 1)
        testScheduler.runCurrent()
        assertTrue(r.rosterReads > reads)
        assertTrue(m.current.member)
        assertTrue(m.current.succeeded)
        assertEquals(ProCopy.SUCCESS_TITLE, m.current.successTitle)
        assertTrue(r.env.proMembers.isPro(alice), "the badge's roster too")
        // no more reads once the account is on it
        val after = r.rosterReads
        testScheduler.advanceTimeBy(ProMembersModel.ACTIVATION_POLL_MILLIS * 3)
        testScheduler.advanceUntilIdle()
        assertEquals(after, r.rosterReads)
        m.close()
    }

    @Test
    fun theRosterIsReadABoundedNumberOfTimesWhenTheAccountNeverShows() = runTest {
        deliverOnOrder = false
        val r = rig()
        val m = pro(r)
        val before = r.rosterReads
        m.buy()
        testScheduler.advanceUntilIdle()
        assertEquals(ProMembersModel.ACTIVATION_POLLS, r.rosterReads - before)
        assertTrue(m.current.activating, "still activating: the order was accepted, the roster is behind")
        m.close()
    }

    @Test
    fun aMembersSecondPurchaseExtends() = runTest {
        roster += alice
        val r = rig()
        val m = pro(r)
        m.buy()
        testScheduler.advanceUntilIdle()
        assertEquals(1, r.orders.size, "buying again while active is a new order (Ecency extends from the expiry)")
        assertTrue(m.current.succeeded)
        assertEquals(ProCopy.EXTENDING, m.current.successBody)
        m.open()
        m.dismiss()
        assertFalse(m.current.open)
        assertFalse(m.current.purchased, "a finished purchase starts over next time")
        m.close()
    }

    @Test
    fun aCancelCreatesNoOrderAndChangesNothing() = runTest {
        val r = rig()
        r.store.onSheet = { id -> r.store.storeListener?.onError(StoreError(StoreError.USER_CANCELLED, "cancelled", id, StoreError.PLAY_USER_CANCELED)) }
        val m = pro(r)
        m.buy()
        testScheduler.advanceUntilIdle()
        assertTrue(r.orders.isEmpty())
        assertFalse(m.current.purchased)
        assertFalse(m.current.buying)
        assertNull(m.current.alert, "a cancel is told nothing")
        assertTrue(m.current.canBuy)
        m.close()
    }

    @Test
    fun aRefusedOrderAlertsAndKeepsThePurchaseUnfinished() = runTest {
        orderAnswer = HttpResult(400, """{"message":"bad receipt"}""")
        val r = rig()
        val m = pro(r)
        m.buy()
        testScheduler.advanceUntilIdle()
        assertEquals(PurchaseCopy.WARNING, m.current.alert?.title)
        assertFalse(m.current.purchased)
        assertTrue(r.store.finished.isEmpty(), "never consumed without an accepted order")
        assertTrue(PurchaseSkus.PRO in r.env.purchases.current.unfinished)
        m.close()
    }

    @Test
    fun aPendingPaymentIsNotPostedAndSaysSo() = runTest {
        val r = rig()
        r.store.onSheet = { id -> r.store.deliver(r.store.paid(id, StorePurchaseState.PENDING)) }
        val m = pro(r)
        m.buy()
        testScheduler.advanceUntilIdle()
        assertTrue(r.orders.isEmpty())
        assertEquals(PurchaseCopy.PENDING, m.current.notice)
        assertFalse(m.current.purchased)
        m.close()
    }

    @Test
    fun anInterruptedPurchaseIsDeliveredAtTheNextStartToTheAccountItWasBoughtFor() = runTest {
        val prefs = InMemoryPrefs()
        // bob bought, the app died before the order; alice is signed in at the next start
        PurchaseService.Intents.save(prefs, PurchaseSkus.PRO, PurchaseTarget(user = bob), 0)
        val held = FakeStore().paid(PurchaseSkus.PRO)
        val r = rig(signedIn = alice, prefs = prefs, held = listOf(held))
        val order = Json.parseToJsonElement(r.orders.single().body!!).jsonObject
        assertEquals("1999pro", order["product"]!!.jsonPrimitive.content)
        assertEquals(bob, order["user"]!!.jsonPrimitive.content)
        assertEquals(listOf(held.token), r.store.finished)
        assertTrue(r.env.proMembers.isPro(bob), "the roster is read again after a recovered Pro purchase")
    }

    @Test
    fun anOutcomeAfterAnAccountChangeIsNotTheNewAccounts() = runTest {
        val r = rig()
        var deliver: (() -> Unit)? = null
        r.store.onSheet = { id -> deliver = { r.store.deliver(r.store.paid(id)) } }
        val m = pro(r)
        m.buy()
        testScheduler.advanceUntilIdle()
        r.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        assertEquals(bob, m.current.account)
        deliver!!()
        testScheduler.advanceUntilIdle()
        assertEquals(alice, Json.parseToJsonElement(r.orders.single().body!!).jsonObject["user"]!!.jsonPrimitive.content)
        assertFalse(m.current.purchased, "bob's sheet shows nothing of alice's purchase")
        m.close()
    }

    @Test
    fun theStoreGetsTheBuyersAccountToken() = runTest {
        val r = rig()
        val m = pro(r)
        m.buy()
        testScheduler.runCurrent()
        assertEquals(listOf(PurchaseService.accountToken(alice)), r.store.tokens)
        val t = PurchaseService.accountToken(alice)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(t), t)
        assertEquals(t, PurchaseService.accountToken("Spike-Alice"), "names are lowercased")
        assertFalse(t == PurchaseService.accountToken(bob))
        m.close()
    }

    @Test
    fun bobCannotBuyWhileAlicesPurchaseIsUnderWayAndNeverSeesItsOutcome() = runTest {
        val r = rig()
        var deliver: (() -> Unit)? = null
        r.store.onSheet = { id -> val t = r.store.tokens.last(); deliver = { r.store.deliver(r.store.paid(id).copy(accountToken = t)) } }
        val m = pro(r)
        m.buy()
        testScheduler.runCurrent()
        r.env.session.switchTo(bob)
        testScheduler.runCurrent()
        assertEquals(bob, m.current.account)
        assertTrue(m.current.buying, "alice's buy is still under way")
        assertFalse(m.current.canBuy)
        m.buy()
        testScheduler.runCurrent()
        assertEquals(1, r.store.sheets.size, "no second sheet")
        deliver!!()
        testScheduler.runCurrent()
        assertEquals(alice, Json.parseToJsonElement(r.orders.single().body!!).jsonObject["user"]!!.jsonPrimitive.content)
        assertFalse(m.current.purchased, "bob's sheet shows nothing of alice's purchase")
        assertFalse(m.current.buying)
        r.env.session.switchTo(alice)
        testScheduler.runCurrent()
        assertTrue(m.current.purchased, "alice sees her purchase when she is back")
        m.close()
    }

    @Test
    fun aPendingPaymentThatSettlesLaterShowsAsPurchased() = runTest {
        val r = rig()
        var pending: StorePurchase? = null
        r.store.onSheet = { id -> pending = r.store.paid(id, StorePurchaseState.PENDING).copy(accountToken = r.store.tokens.last()); r.store.deliver(pending!!) }
        val m = pro(r)
        m.buy()
        testScheduler.runCurrent()
        assertEquals(PurchaseCopy.PENDING, m.current.notice)
        // the payment settles: the store hands the same purchase over as paid, by itself
        r.store.deliver(pending!!.copy(state = StorePurchaseState.PURCHASED))
        testScheduler.runCurrent()
        assertEquals(alice, Json.parseToJsonElement(r.orders.single().body!!).jsonObject["user"]!!.jsonPrimitive.content)
        assertTrue(m.current.purchased)
        assertNull(m.current.notice)
        m.close()
    }

    @Test
    fun anOutcomeThatLandsWhilePerksIsClosedShowsWhenItOpensAgain() = runTest {
        val r = rig()
        var deliver: (() -> Unit)? = null
        r.store.onSheet = { id -> val t = r.store.tokens.last(); deliver = { r.store.deliver(r.store.paid(id).copy(accountToken = t)) } }
        val first = pro(r)
        first.buy()
        testScheduler.runCurrent()
        first.close()
        deliver!!()
        testScheduler.runCurrent()
        val again = pro(r)
        assertTrue(again.current.purchased)
        assertTrue(again.current.succeeded, "the order put alice on the roster meanwhile")
        again.dismiss()
        assertFalse(again.current.purchased, "seen: the next time starts over")
        again.close()
    }

    @Test
    fun anInterruptedPurchaseGoesToTheAccountItsTokenNamesNotTheSignedInOne() = runTest {
        // no saved target (another install, cleared data): bob's token, alice signed in
        val held = FakeStore().paid(PurchaseSkus.PRO).copy(accountToken = PurchaseService.accountToken(bob))
        val r = rig(signedIn = alice, held = listOf(held))
        assertEquals(bob, Json.parseToJsonElement(r.orders.single().body!!).jsonObject["user"]!!.jsonPrimitive.content)
        assertEquals(bob.lowercase(), r.env.proPurchases.current.keys.single())
    }

    @Test
    fun aPointsPurchaseIsCreditedByItsTokenToo() = runTest {
        val held = FakeStore().paid("999points").copy(accountToken = PurchaseService.accountToken(bob))
        val prefs = InMemoryPrefs()
        // a newer buy saved alice as the target of the same product: it is not this purchase's
        PurchaseService.Intents.save(prefs, "999points", PurchaseTarget(user = alice), 0)
        val r = rig(signedIn = alice, prefs = prefs, held = listOf(held))
        assertEquals(bob, Json.parseToJsonElement(r.orders.single().body!!).jsonObject["user"]!!.jsonPrimitive.content)
        assertEquals(alice, PurchaseService.Intents.find(prefs, "999points")?.target?.user, "alice's target stays for her own purchase")
    }

    @Test
    fun aTokenNamingNoAccountHereFallsBackAndLeavesABreadcrumb() = runTest {
        val held = FakeStore().paid(PurchaseSkus.PRO).copy(accountToken = PurchaseService.accountToken("someone-else"))
        val r = rig(signedIn = alice, held = listOf(held))
        assertEquals(alice, Json.parseToJsonElement(r.orders.single().body!!).jsonObject["user"]!!.jsonPrimitive.content)
        assertTrue((r.env.crashReporter as RecordingReporter).crumbs.any { it.first.contains("names no account") })
    }

    @Test
    fun twoAccountsActivateTogetherWithoutDroppingEachOther() = runTest {
        val r = rig()
        r.env.proMembers.activate(alice)
        r.env.proMembers.activate(bob)
        testScheduler.runCurrent()
        roster += alice
        testScheduler.advanceTimeBy(ProMembersModel.ACTIVATION_POLL_MILLIS + 1)
        testScheduler.runCurrent()
        assertTrue(r.env.proMembers.isPro(alice))
        roster += bob
        testScheduler.advanceTimeBy(ProMembersModel.ACTIVATION_POLL_MILLIS + 1)
        testScheduler.runCurrent()
        assertTrue(r.env.proMembers.isPro(bob), "alice's activation did not end bob's")
        val reads = r.rosterReads
        testScheduler.advanceTimeBy(ProMembersModel.ACTIVATION_POLL_MILLIS * 5)
        testScheduler.runCurrent()
        assertEquals(reads, r.rosterReads, "no reads once both are on it")
    }

    private fun user(r: Rig, i: Int = 0) = Json.parseToJsonElement(r.orders[i].body!!).jsonObject["user"]!!.jsonPrimitive.content

    @Test
    fun aTokenNamingNoAccountHereIgnoresAnotherAccountsSavedTarget() = runTest {
        val prefs = InMemoryPrefs()
        PurchaseService.Intents.save(prefs, PurchaseSkus.PRO, PurchaseTarget(user = bob), 0)
        val held = FakeStore().paid(PurchaseSkus.PRO).copy(accountToken = PurchaseService.accountToken("someone-else"))
        val r = rig(signedIn = alice, prefs = prefs, held = listOf(held))
        assertEquals(alice, user(r), "the signed-in account, never bob's unrelated target")
    }

    @Test
    fun aBuyThatFindsAnotherAccountsUnfinishedPointsTellsTheBuyerAndShowsNoSuccess() = runTest {
        // bob's purchase from before a reinstall (no saved target): Ecency is down at the start, it stays unfinished
        orderAnswer = HttpResult(500, "{}")
        val held = FakeStore().paid("999points").copy(accountToken = PurchaseService.accountToken(bob))
        val prefs = InMemoryPrefs()
        val r = rig(signedIn = alice, prefs = prefs, held = listOf(held))
        orderAnswer = HttpResult(201, "{}")
        val screen = r.env.storeScreenModel(StoreScreenKind.POINTS, "", "").also { it.start() }
        testScheduler.advanceUntilIdle()
        screen.buy("999points")
        testScheduler.advanceUntilIdle()
        assertTrue(r.store.sheets.isEmpty(), "no new purchase while bob's is completed")
        assertEquals(PurchaseCopy.earlierPurchase(bob), screen.current.alert?.body)
        assertEquals(bob, user(r, r.orders.lastIndex))
        assertFalse(r.env.ui.current.toast?.text == PurchaseCopy.POINTS_SUCCESS, "bob's delivery is not alice's success")
        assertNull(PurchaseService.Intents.find(prefs, "999points"), "no stale target of alice's")
        screen.close()
    }

    @Test
    fun aProTapThatFindsBobsUnfinishedProTellsAliceAndCreditsBob() = runTest {
        orderAnswer = HttpResult(500, "{}")
        val held = FakeStore().paid(PurchaseSkus.PRO).copy(accountToken = PurchaseService.accountToken(bob))
        val r = rig(signedIn = alice, held = listOf(held))
        orderAnswer = HttpResult(201, "{}")
        val m = pro(r)
        m.buy()
        testScheduler.runCurrent()
        assertEquals(PurchaseCopy.earlierPurchase(bob), m.current.alert?.body)
        testScheduler.advanceUntilIdle()
        assertEquals(bob, user(r, r.orders.lastIndex))
        // nothing of bob's delivery on alice's sheet: no success, no activating, free to buy for herself
        assertFalse(m.current.purchased)
        assertFalse(m.current.activating || m.current.succeeded)
        assertNull(m.current.notice)
        assertFalse(m.current.buying)
        assertTrue(m.current.canBuy)
        assertNull(r.env.proPurchases.of(alice)?.progress)
        assertEquals(ProProgress.DELIVERED, r.env.proPurchases.of(bob)?.progress)
        m.close()
    }

    @Test
    fun aNewBuyKeepsAPendingPaymentAndARecoveredMembersPurchaseIsAnExtension() = runTest {
        val r = rig()
        val p = r.env.proPurchases
        p.update(alice, ProProgress.PENDING)
        p.started(alice, extending = false)
        assertEquals(ProProgress.PENDING, p.of(alice)?.progress)
        roster += bob
        r.env.proMembers.activate(bob)
        testScheduler.runCurrent()
        p.update(bob, ProProgress.DELIVERED)
        assertEquals(true, p.of(bob)?.extending, "bob was a member: an extension")
        assertEquals(false, p.of(alice)?.extending)
    }

    @Test
    fun aPaidAccountBoughtSignedOutCarriesNoToken() = runTest {
        val r = rig(signedIn = null)
        r.store.onSheet = {}
        r.env.purchases.buy(PurchaseSkus.ACCOUNT, PurchaseTarget(meta = com.ecency.core.api.ecency.AccountOrderMeta("spike-new", "new@example.com", "")))
        testScheduler.runCurrent()
        assertEquals(listOf(""), r.store.tokens)
    }

    @Test
    fun recoveryAtStartWaitsForTheAccountsWhenPurchasesStartFirst() = runTest {
        val held = FakeStore().paid(PurchaseSkus.PRO).copy(accountToken = PurchaseService.accountToken(bob))
        val r = rig(signedIn = alice, held = listOf(held), purchasesFirst = true)
        assertEquals(bob, user(r), "bob's token found among the loaded accounts")
        assertEquals(1, r.orders.size)
        assertFalse((r.env.crashReporter as RecordingReporter).crumbs.any { it.first.contains("names no account") }, "never looked up before the accounts loaded")
    }

    @Test
    fun aStalePendingGoesWhenTheStoreNoLongerHoldsIt() = runTest {
        val r = rig()
        r.store.onSheet = { id -> r.store.storeListener?.onError(StoreError(StoreError.USER_CANCELLED, "cancelled", id, StoreError.PLAY_USER_CANCELED)) }
        val m = pro(r)
        r.env.proPurchases.update(alice, ProProgress.PENDING)
        testScheduler.runCurrent()
        assertEquals(PurchaseCopy.PENDING, m.current.notice)
        // a fresh buy opens a sheet (the store holds nothing) and is cancelled: no pending is left
        m.buy()
        testScheduler.advanceUntilIdle()
        assertNull(m.current.notice)
        // and a recovery that finds no pending Pro purchase drops one shown for any account
        r.env.proPurchases.update(bob, ProProgress.PENDING)
        r.env.purchases.recover()
        testScheduler.advanceUntilIdle()
        assertNull(r.env.proPurchases.of(bob)?.progress)
        m.close()
    }
}
