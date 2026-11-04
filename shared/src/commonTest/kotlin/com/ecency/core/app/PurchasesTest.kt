package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A store double: products, held purchases, the sheet and finish, all recorded. */
class FakeStore(override val platform: String = PurchaseService.PLAY) : StorePlatform {
    var storeListener: StoreListener? = null
    var connectError: StoreError? = null
    var productsError: StoreError? = null
    var sheetError: StoreError? = null
    var finishError: StoreError? = null
    var catalog = listOf(
        StoreProduct("099points", "100 Points (Ecency)", "", "0.99", "USD", "$0.99"),
        StoreProduct("999points", "1000 Points (Ecency)", "", "9.99", "USD", "$9.99"),
        StoreProduct("4999points", "5500 Points (Ecency)", "", "49.99", "USD", "$49.99"),
        StoreProduct("9999points", "12000 Points (Ecency)", "", "99.99", "USD", "$99.99"),
        StoreProduct("199points", "200 Points (Ecency)", "", "1.99", "USD", "$1.99"),
        // Play's placeholder for an id it does not sell: no price
        StoreProduct("499points", "", "", "", "", ""),
        StoreProduct(PurchaseSkus.BOOST, "Account boost", "", "9.99", "USD", "$9.99"),
        StoreProduct(PurchaseSkus.SPINS, "5 spins", "", "4.99", "USD", "$4.99"),
        StoreProduct(PurchaseSkus.ACCOUNT, "Premium account", "", "9.99", "USD", "$9.99"),
        StoreProduct(PurchaseSkus.PRO, "Ecency Pro", "", "19.99", "USD", "$19.99"),
    )
    val held = mutableListOf<StorePurchase>()
    val sheets = mutableListOf<String>()
    val finished = mutableListOf<String>()
    var connects = 0

    /** What the sheet does when it opens: by default the purchase completes at once. */
    var onSheet: (String) -> Unit = { id -> deliver(paid(id).copy(accountToken = tokens.lastOrNull().orEmpty())) }
    private var n = 0

    /** The store's purchase date of the next purchases (0: unknown). */
    var purchasedAt = 0L

    fun paid(id: String, state: StorePurchaseState = StorePurchaseState.PURCHASED, acknowledged: Boolean = false) =
        StorePurchase(id, "token-$id-${++n}", state, acknowledged, "ref-$n", purchasedAt)

    fun deliver(p: StorePurchase) {
        held.removeAll { it.token == p.token }
        held += p
        storeListener?.onPurchases(listOf(p))
    }

    override fun connect(done: (StoreError?) -> Unit) {
        connects++
        done(connectError)
    }

    override fun products(ids: List<String>, done: (List<StoreProduct>?, StoreError?) -> Unit) =
        if (productsError != null) done(null, productsError) else done(catalog.filter { it.productId in ids }, null)

    override fun purchases(done: (List<StorePurchase>?, StoreError?) -> Unit) = done(held.toList(), null)

    /** The account token of each sheet, in order. */
    val tokens = mutableListOf<String>()

    override fun purchase(productId: String, accountToken: String, done: (StoreError?) -> Unit) {
        sheets += productId
        tokens += accountToken
        val e = sheetError
        if (e != null) return done(e)
        done(null)
        onSheet(productId)
    }

    override fun finish(purchase: StorePurchase, done: (StoreError?) -> Unit) {
        val e = finishError
        if (e == null) {
            finished += purchase.token
            held.removeAll { it.token == purchase.token }
        }
        done(e)
    }

    override fun setListener(listener: StoreListener?) {
        storeListener = listener
    }
}

class RecordingReporter : CrashReporter {
    val reports = mutableListOf<CrashReport>()
    val crumbs = mutableListOf<Pair<String, Map<String, String>>>()
    override fun capture(report: CrashReport) {
        reports += report
    }
    override fun breadcrumb(category: String, message: String, data: Map<String, String>) {
        crumbs += message to data
    }
}

/** In-app purchases (RN inAppPurchaseContainer, providers/iap, ecency.ts purchaseOrder) and the money rules. */
class PurchasesTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"

    private class Rig(val app: TestApp, val store: FakeStore, val reporter: RecordingReporter) {
        val orders: List<AuthHttpRequest> get() = app.authBackend.sent.filter { it.url.endsWith("/private-api/purchase-order") }
        val env get() = app.env
    }

    /** Ecency's answers to purchase-order, in order; the last repeats. */
    private var answers = mutableListOf(HttpResult(201, "{}"))

    private fun TestScope.rig(platform: String = PurchaseService.PLAY, signedIn: String? = alice, prefs: Prefs = InMemoryPrefs(), start: Boolean = true): Rig {
        val app = appWithAccounts(this)
        val vault = KeyVault(app.store)
        listOf(bob, alice).forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(it, com.ecency.core.hive.KeyRole.POSTING), accessToken = "access-$it")) }
        if (signedIn != null) vault.setCurrentUsername(signedIn) else vault.setCurrentUsername(null)
        app.authBackend.rest = { r ->
            if (r.url.endsWith("/private-api/purchase-order")) (if (answers.size > 1) answers.removeAt(0) else answers.first()) else null
        }
        val store = FakeStore(platform)
        val reporter = RecordingReporter()
        app.env.prefs = prefs
        app.env.store = store
        app.env.crashReporter = reporter
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        if (start) {
            app.env.purchases.start()
            testScheduler.advanceUntilIdle()
        }
        return Rig(app, store, reporter)
    }

    private fun AuthHttpRequest.json(): JsonObject = Json.parseToJsonElement(body!!).jsonObject

    private fun TestScope.screen(r: Rig, kind: StoreScreenKind, username: String = "", productId: String = ""): StoreScreenModel =
        r.env.storeScreenModel(kind, username, productId).also {
            it.start()
            testScheduler.advanceUntilIdle()
        }

    @Test
    fun aPointsPurchaseIsOrderedWithRnsRequestAndFinishedOnlyAfterEcencyAcceptedIt() = runTest {
        val r = rig()
        val model = screen(r, StoreScreenKind.POINTS)
        var finishedBeforeOrder = true
        r.app.authBackend.gate = { req -> if (req.url.endsWith("purchase-order")) finishedBeforeOrder = r.store.finished.isNotEmpty() }
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("999points"), r.store.sheets)
        val order = r.orders.single()
        assertEquals("POST", order.method)
        assertEquals("https://ecency.com/private-api/purchase-order", order.url)
        assertEquals(
            Json.parseToJsonElement("""{"platform":"play_store","product":"999points","receipt":"token-999points-1","user":"spike-alice","code":"access-spike-alice"}"""),
            Json.parseToJsonElement(order.body!!),
        )
        assertEquals(60_000L, order.timeoutMillis, "RN PURCHASE_ORDER_TIMEOUT_MS")
        assertFalse(finishedBeforeOrder, "finished only after the order")
        assertEquals(listOf("token-999points-1"), r.store.finished)
        assertEquals(PurchaseCopy.POINTS_SUCCESS, r.env.ui.current.toast?.text)
        assertFalse(model.current.processing)
        assertTrue(r.env.purchases.current.unfinished.isEmpty())
        model.close()
    }

    @Test
    fun theProductRowsFollowRn() = runTest {
        val r = rig()
        val points = screen(r, StoreScreenKind.POINTS)
        assertEquals(
            listOf("9999points" to "12000 POINTS", "4999points" to "5500 POINTS", "999points" to "1000 POINTS", "199points" to "200 POINTS", "099points" to "100 POINTS", "freePoints" to "FREE POINTS"),
            points.current.rows.map { it.productId to it.title },
            "highest price first, digits + POINTS, the unpriced placeholder left out, the free row last",
        )
        assertEquals(listOf("BEST DEAL!", "POPULAR!", null, null, null, null), points.current.rows.map { it.deal })
        assertEquals("$99.99", points.current.rows.first().price)
        assertEquals("FREE POINTS", PurchaseSkus.title("free points"))
        points.buy(PurchaseSkus.FREE_POINTS)
        assertTrue(points.current.openSpinGame, "the free row opens the spin game")
        assertTrue(r.store.sheets.isEmpty())

        val spins = screen(r, StoreScreenKind.SPINS)
        assertEquals(listOf(StoreRow(PurchaseSkus.SPINS, "5 SPINS", "$4.99", null)), spins.current.rows)
        val boost = screen(r, StoreScreenKind.ACCOUNT_BOOST)
        assertEquals(PurchaseSkus.BOOST, boost.current.rows.single().productId)
        assertTrue(boost.current.rows.single().title.startsWith("Boost+  |  "))
        assertEquals("Boost+  |  305.1 HP", PurchaseCopy.boostRow(PurchaseSkus.boostHp(551.4)), "RN vestsToHp(553311).toFixed(1)")
        listOf(points, spins, boost).forEach { it.close() }
    }

    @Test
    fun aPendingPaymentIsNeverPostedNorShownAsPaidAndIsDeliveredWhenItSettles() = runTest {
        val r = rig()
        r.store.onSheet = { id -> r.store.deliver(r.store.paid(id, StorePurchaseState.PENDING)) }
        val model = screen(r, StoreScreenKind.POINTS)
        model.buy("199points")
        testScheduler.advanceUntilIdle()
        assertTrue(r.orders.isEmpty(), "a pending purchase is not posted")
        assertTrue(r.store.finished.isEmpty())
        assertEquals(StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.PENDING), model.current.alert)
        assertTrue(r.env.ui.current.toast == null, "no success toast")
        assertEquals(setOf("199points"), r.env.purchases.current.pending)
        assertFalse(model.current.processing)

        // the payment settles while the app is in the background; the next foreground delivers it
        val token = r.store.held.single().token
        r.store.held[0] = r.store.held[0].copy(state = StorePurchaseState.PURCHASED)
        r.app.now += PurchaseService.FOREGROUND_RECOVERY_MILLIS
        r.env.purchases.onForeground()
        testScheduler.advanceUntilIdle()
        assertEquals(token, r.orders.single().json()["receipt"]!!.jsonPrimitive.content)
        assertEquals(listOf(token), r.store.finished)
        assertTrue(r.env.purchases.current.pending.isEmpty())
        model.close()
    }

    @Test
    fun aFailedOrderIsRetriedTwiceThenKeptUnfinishedForTheNextStart() = runTest {
        answers = mutableListOf(HttpResult(500, ""))
        val r = rig()
        val model = screen(r, StoreScreenKind.POINTS)
        val started = testScheduler.currentTime
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(3, r.orders.size, "RN PURCHASE_ORDER_MAX_ATTEMPTS")
        assertEquals(3_000L, testScheduler.currentTime - started, "1 s, then 2 s, no wait after the last")
        assertTrue(r.store.finished.isEmpty(), "never finished without an accepted order")
        assertEquals(PurchaseCopy.pointsFailed("Ecency API error 500"), r.env.ui.current.toast?.text)
        assertEquals(setOf("999points"), r.env.purchases.current.unfinished)
        assertEquals("order", r.reporter.reports.last().tags["iap.stage"])

        // next start: the same purchase is posted again and goes through
        answers = mutableListOf(HttpResult(201, "{}"))
        val prefs = r.env.prefs
        val held = r.store.held.toList()
        model.close()
        val again = rig(prefs = prefs, start = false)
        again.store.held += held
        again.env.purchases.start()
        testScheduler.advanceUntilIdle()
        assertEquals("spike-alice", again.orders.single().json()["user"]!!.jsonPrimitive.content)
        assertEquals(listOf(held.single().token), again.store.finished)
        assertNull(again.env.ui.current.toast, "a recovered purchase is silent")
    }

    @Test
    fun aTimedOutOrderIsNotSentAgainAtOnce() = runTest {
        val r = rig()
        r.app.authBackend.gate = { req -> if (req.url.endsWith("purchase-order")) awaitCancellation() }
        val model = screen(r, StoreScreenKind.POINTS)
        model.buy("999points")
        testScheduler.advanceTimeBy(60_001)
        testScheduler.runCurrent()
        assertEquals(PurchaseCopy.PROCESSING, r.env.ui.current.toast?.text)
        assertTrue(r.store.finished.isEmpty())
        assertFalse(model.current.processing)
        model.close()
    }

    @Test
    fun onPlayA409MeansEcencyHasTheOrderAndThePurchaseIsConsumed() = runTest {
        // ePoints answers 409 only when a row for this receipt exists (its other failures are 503)
        answers = mutableListOf(HttpResult(409, "{}"))
        val r = rig()
        val model = screen(r, StoreScreenKind.POINTS)
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(1, r.orders.size, "409 is not retried")
        assertEquals(listOf("token-999points-1"), r.store.finished, "consumed on 409, acknowledged or not")
        assertEquals(PurchaseCopy.POINTS_SUCCESS, r.env.ui.current.toast?.text)
        assertTrue(r.env.purchases.current.unfinished.isEmpty())
        val warning = r.reporter.reports.single { it.type == "IapOrderDuplicate" }
        assertEquals(listOf("iap", "order-409"), warning.fingerprint)
        assertTrue(warning.isMessage)
        // nothing is posted again later
        r.app.now += PurchaseService.FOREGROUND_RECOVERY_MILLIS
        r.env.purchases.onForeground()
        testScheduler.advanceUntilIdle()
        assertEquals(1, r.orders.size)
        model.close()
    }
    @Test
    fun aReceiptThisDeviceSawAcceptedIsFinishedWithoutPostingAgain() = runTest {
        val r = rig()
        r.store.finishError = StoreError(StoreError.SERVICE_ERROR, "finish failed")
        val model = screen(r, StoreScreenKind.POINTS)
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(PurchaseCopy.POINTS_SUCCESS, r.env.ui.current.toast?.text, "a failed finish is not a failed purchase")
        assertEquals("finish", r.reporter.reports.single().tags["iap.stage"])
        assertEquals(1, r.store.held.size)

        // the next recovery finishes it without posting it again: the device knows Ecency took it
        answers = mutableListOf(HttpResult(409, "{}"))
        r.store.finishError = null
        r.app.now += PurchaseService.FOREGROUND_RECOVERY_MILLIS
        r.env.purchases.onForeground()
        testScheduler.advanceUntilIdle()
        assertEquals(1, r.orders.size, "never posted again")
        assertEquals(1, r.store.finished.size)
        assertTrue(r.reporter.reports.none { it.type == "IapOrderDuplicate" })
        model.close()
    }

    @Test
    fun onTheAppStoreAPurchaseWhoseAcceptWasLostIsFinishedOnThe409AndNeverLoops() = runTest {
        val r = rig(platform = PurchaseService.APP_STORE)
        var posts = 0
        // Ecency stored the order but its 201 never arrived; every later post of the receipt is answered 409
        r.app.authBackend.rest = { req ->
            when {
                !req.url.endsWith("/private-api/purchase-order") -> null
                posts++ == 0 -> throw IllegalStateException("connection reset")
                else -> HttpResult(409, "{}")
            }
        }
        val outcomes = mutableListOf<PurchaseOutcome>()
        backgroundScope.launch(r.app.main) { r.env.purchases.outcomes.collect { outcomes += it } }
        val model = screen(r, StoreScreenKind.POINTS)
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(2, r.orders.size, "the lost answer is retried, the 409 ends it")
        assertEquals("app_store", r.orders.last().json()["platform"]!!.jsonPrimitive.content)
        assertEquals(listOf("token-999points-1"), r.store.finished, "the transaction is finished on 409")
        assertEquals(PurchaseCopy.POINTS_SUCCESS, r.env.ui.current.toast?.text)
        assertEquals(listOf<PurchaseOutcome>(PurchaseOutcome.Delivered("999points", true)), outcomes)
        assertTrue(r.store.held.isEmpty())
        assertTrue(r.env.purchases.current.unfinished.isEmpty())
        // later starts and returns hours apart post nothing and tell the user nothing more
        repeat(3) {
            r.app.now += 6 * 3_600_000L
            r.env.purchases.onForeground()
            testScheduler.advanceUntilIdle()
        }
        assertEquals(2, r.orders.size)
        assertEquals(1, outcomes.size)
        // buying the pack again opens a new sheet
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("999points", "999points"), r.store.sheets)
        model.close()
    }

    @Test
    fun anUnverifiedTransactionIsOrderedWithItsSignedPayloadAndFinishedOnlyAfterEcencyAccepted() = runTest {
        // the device's verdict decides nothing: Ecency's worker verifies the JWS itself
        val r = rig(platform = PurchaseService.APP_STORE)
        var finishedBeforeOrder = true
        r.app.authBackend.gate = { req -> if (req.url.endsWith("purchase-order")) finishedBeforeOrder = r.store.finished.isNotEmpty() }
        r.store.onSheet = { id -> r.store.deliver(r.store.paid(id, StorePurchaseState.UNVERIFIED)) }
        val model = screen(r, StoreScreenKind.POINTS)
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        val order = r.orders.single()
        assertEquals("token-999points-1", order.json()["receipt"]!!.jsonPrimitive.content, "the signed transaction, as for a verified one")
        assertEquals("app_store", order.json()["platform"]!!.jsonPrimitive.content)
        assertFalse(finishedBeforeOrder, "not finished before Ecency answered")
        assertEquals(listOf("token-999points-1"), r.store.finished)
        assertEquals(PurchaseCopy.POINTS_SUCCESS, r.env.ui.current.toast?.text)
        assertTrue(model.current.alert == null, "no \"could not verify\"")
        val report = r.reporter.reports.single { it.tags["iap.code"] == StoreError.VERIFICATION_FAILED }
        assertEquals("999points", report.tags["iap.product"])
        assertFalse("token-999points-1" in report.toString(), "the receipt is never reported")
        model.close()
    }

    @Test
    fun anUnverifiedTransactionStaysUnfinishedWhileEcencyCannotAnswer() = runTest {
        answers = mutableListOf(HttpResult(503, "{}"))
        val r = rig(platform = PurchaseService.APP_STORE, start = false)
        r.store.held += r.store.paid("999points", StorePurchaseState.UNVERIFIED)
        r.env.purchases.start()
        testScheduler.advanceUntilIdle()
        assertEquals(3, r.orders.size, "posted and retried as any purchase")
        assertTrue(r.store.finished.isEmpty(), "never finished without a definite answer")
        assertEquals(setOf("999points"), r.env.purchases.current.unfinished)
        // Ecency is back: the next foreground delivers it
        answers = mutableListOf(HttpResult(201, "{}"))
        r.app.now += PurchaseService.FOREGROUND_RECOVERY_MILLIS
        r.env.purchases.onForeground()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("token-999points-1"), r.store.finished)
    }

    @Test
    fun anUnverifiedTransactionIsReportedOncePerTransactionNotAtEveryLaunch() = runTest {
        answers = mutableListOf(HttpResult(503, "{}"))
        val prefs = InMemoryPrefs()
        var held: List<StorePurchase>? = null
        var reports = 0
        repeat(3) {
            val r = rig(platform = PurchaseService.APP_STORE, prefs = prefs, start = false)
            r.store.held += held ?: listOf(r.store.paid("999points", StorePurchaseState.UNVERIFIED)).also { held = it }
            r.env.purchases.start()
            testScheduler.advanceUntilIdle()
            assertTrue(r.store.finished.isEmpty())
            reports += r.reporter.reports.count { it.tags["iap.code"] == StoreError.VERIFICATION_FAILED }
        }
        assertEquals(1, reports, "one report for the transaction, not one per launch")
    }

    @Test
    fun anUnverifiedTransactionEcencyRefusesOrHoldsStaysUnfinishedWithNothingDelivered() = runTest {
        answers = mutableListOf(HttpResult(400, "{}"))
        val r = rig(platform = PurchaseService.APP_STORE)
        val outcomes = mutableListOf<PurchaseOutcome>()
        backgroundScope.launch(r.app.main) { r.env.purchases.outcomes.collect { outcomes += it } }
        r.store.onSheet = { id -> r.store.deliver(r.store.paid(id, StorePurchaseState.UNVERIFIED)) }
        val model = screen(r, StoreScreenKind.POINTS)
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertTrue(r.orders.isNotEmpty())
        assertTrue(r.store.finished.isEmpty(), "finished only on 201 or 409")
        assertTrue(outcomes.none { it is PurchaseOutcome.Delivered })
        assertEquals(setOf("999points"), r.env.purchases.current.unfinished)
        // held for review: still unfinished
        answers = mutableListOf(HttpResult(422, """{"reason":"receipt_held","order":7}"""))
        r.app.now += PurchaseService.FOREGROUND_RECOVERY_MILLIS
        r.env.purchases.onForeground()
        testScheduler.advanceUntilIdle()
        assertTrue(r.store.finished.isEmpty())
        assertTrue(outcomes.none { it is PurchaseOutcome.Delivered })
        model.close()
    }

    @Test
    fun aHeldReceiptStaysUnfinishedAndTheBuyerGetsTheOrderNumber() = runTest {
        answers = mutableListOf(HttpResult(422, """{"reason":"receipt_held","order":4711}"""))
        val r = rig()
        val model = screen(r, StoreScreenKind.POINTS)
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(1, r.orders.size, "a held receipt is not retried")
        assertTrue(r.store.finished.isEmpty(), "kept unfinished while support reviews it")
        assertEquals(StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.held("4711")), model.current.alert)
        assertTrue("#4711" in PurchaseCopy.held("4711"))
        assertEquals(setOf("999points"), r.env.purchases.current.unfinished)
        assertFalse(model.current.processing)
        // the next start resends it and is told nothing again (the buyer saw it)
        val prefs = r.env.prefs
        val held = r.store.held.toList()
        model.close()
        val again = rig(prefs = prefs, start = false)
        again.store.held += held
        again.env.purchases.start()
        testScheduler.advanceUntilIdle()
        assertEquals(1, again.orders.size)
        assertNull(again.env.ui.current.toast)
        assertTrue(again.store.finished.isEmpty())
        // support released it: the next resend gets 409 and finishes it
        answers = mutableListOf(HttpResult(409, "{}"))
        again.app.now += PurchaseService.FOREGROUND_RECOVERY_MILLIS
        again.env.purchases.onForeground()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(held.single().token), again.store.finished)
    }

    @Test
    fun aHeldReceiptFoundAtRecoveryIsToldOncePerOrder() = runTest {
        answers = mutableListOf(HttpResult(422, """{"reason":"receipt_held","order":12}"""))
        val r = rig(start = false)
        r.store.held += r.store.paid("999points")
        r.env.purchases.start()
        testScheduler.advanceUntilIdle()
        assertEquals(PurchaseCopy.held("12"), r.env.ui.current.toast?.text)
        r.env.ui.dismiss(r.env.ui.current.toast!!.id)
        repeat(2) {
            r.app.now += PurchaseService.FOREGROUND_RECOVERY_MILLIS
            r.env.purchases.onForeground()
            testScheduler.advanceUntilIdle()
        }
        assertEquals(3, r.orders.size, "resent at each recovery")
        assertNull(r.env.ui.current.toast, "told once, not at every launch")
        assertTrue(r.store.finished.isEmpty())
    }

    @Test
    fun a400OrA422WithoutTheHeldBodyKeepsThePurchaseUnfinished() = runTest {
        for (answer in listOf(HttpResult(400, """{"message":"unknown product"}"""), HttpResult(422, """{"reason":"other"}"""))) {
            answers = mutableListOf(answer)
            val r = rig()
            val model = screen(r, StoreScreenKind.POINTS)
            model.buy("999points")
            testScheduler.advanceUntilIdle()
            assertEquals(3, r.orders.size, "retried as any failure (${answer.status})")
            assertTrue(r.store.finished.isEmpty(), "never finished (${answer.status})")
            assertEquals(PurchaseCopy.pointsFailed("Ecency API error ${answer.status}"), r.env.ui.current.toast?.text)
            assertEquals(setOf("999points"), r.env.purchases.current.unfinished)
            model.close()
        }
    }

    @Test
    fun onTheAppStoreAReSignedReceiptOfAnAcceptedTransactionIsNotANewOrder() = runTest {
        // ePoints dedupes on the receipt's hash only: the same transaction with a new jwsRepresentation would be a second order
        val r = rig(platform = PurchaseService.APP_STORE)
        r.store.finishError = StoreError(StoreError.SERVICE_ERROR, "finish failed")
        val model = screen(r, StoreScreenKind.POINTS)
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(1, r.orders.size)
        val first = r.store.held.single()
        // StoreKit hands the same transaction back with another signed form
        r.store.held[0] = first.copy(token = "re-signed-${first.token}")
        r.store.finishError = null
        r.env.purchases.recover()
        testScheduler.advanceUntilIdle()
        assertEquals(1, r.orders.size, "the same transaction is never ordered twice")
        assertEquals(listOf("re-signed-${first.token}"), r.store.finished)
        model.close()
    }

    @Test
    fun anAskToBuyRequestKeepsBlockingAcrossRecoveryUntilStoreKitReportsIt() = runTest {
        val r = rig(platform = PurchaseService.APP_STORE)
        // StoreKit's `.pending`: a made-up token, and Transaction.unfinished never lists it
        r.store.onSheet = { id ->
            r.store.storeListener!!.onPurchases(listOf(StorePurchase(id, "${StorePurchase.SYNTHETIC_PENDING_PREFIX}$id:1", StorePurchaseState.PENDING, purchasedAtMillis = r.app.now)))
        }
        val model = screen(r, StoreScreenKind.POINTS)
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertTrue(r.store.held.isEmpty())
        // back from the background: the recovery's list is empty, the request still blocks
        r.app.now += PurchaseService.FOREGROUND_RECOVERY_MILLIS
        r.env.purchases.onForeground()
        testScheduler.advanceUntilIdle()
        assertEquals(setOf("999points"), r.env.purchases.current.pending)
        model.dismissAlert()
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(1, r.store.sheets.size, "no second purchase while the request waits")
        assertEquals(StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.PENDING), model.current.alert)

        // approved: the real transaction replaces it and is delivered
        r.store.deliver(r.store.paid("999points"))
        testScheduler.advanceUntilIdle()
        assertEquals(1, r.orders.size)
        // the request is over: the same pack can be bought again at once
        r.store.onSheet = { id -> r.store.deliver(r.store.paid(id)) }
        model.dismissAlert()
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(2, r.store.sheets.size)
        assertEquals(2, r.orders.size)
        r.store.onSheet = { id ->
            r.store.storeListener!!.onPurchases(listOf(StorePurchase(id, "${StorePurchase.SYNTHETIC_PENDING_PREFIX}$id:2", StorePurchaseState.PENDING, purchasedAtMillis = r.app.now)))
        }

        // another request that Apple lets lapse stops blocking after its time
        val sheets = r.store.sheets.size
        model.dismissAlert()
        model.buy("199points")
        testScheduler.advanceUntilIdle()
        r.app.now += PurchaseService.SYNTHETIC_PENDING_MILLIS + 1
        r.env.purchases.recover()
        testScheduler.advanceUntilIdle()
        model.dismissAlert()
        model.buy("199points")
        testScheduler.advanceUntilIdle()
        assertEquals(sheets + 2, r.store.sheets.size)
        model.close()
    }

    @Test
    fun aReSignedAcceptedTransactionNeverTakesALaterBuysTarget() = runTest {
        // the reviewer's scenario: alice's accepted transaction comes back re-signed while a link buy for bob waits
        val r = rig(platform = PurchaseService.APP_STORE)
        r.store.finishError = StoreError(StoreError.SERVICE_ERROR, "finish failed")
        val own = screen(r, StoreScreenKind.POINTS)
        own.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(alice, r.orders.single().json()["user"]!!.jsonPrimitive.content)
        val alices = r.store.held.single()
        r.store.finishError = null
        // bob's sheet opens and stays open
        var complete: (() -> Unit)? = null
        r.store.onSheet = { id -> complete = { r.store.deliver(r.store.paid(id)) } }
        val link = screen(r, StoreScreenKind.POINTS, username = bob)
        link.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(2, r.store.sheets.size, "the accepted one is no earlier purchase: bob's sheet opens")
        // StoreKit hands alice's transaction back re-signed (Transaction.updates) while bob's sheet is up
        r.store.storeListener!!.onPurchases(listOf(alices.copy(token = "re-signed-${alices.token}")))
        testScheduler.advanceUntilIdle()
        assertEquals(1, r.orders.size, "not ordered again")
        assertEquals(bob, PurchaseService.Intents.find(r.env.prefs, "999points")!!.target.user, "bob's target untouched")
        // bob's purchase completes: it goes to bob
        complete!!()
        testScheduler.advanceUntilIdle()
        assertEquals(bob, r.orders.last().json()["user"]!!.jsonPrimitive.content)
        own.close()
        link.close()
    }

    @Test
    fun aMarkFromAnEarlierBuildStillCountsAsAccepted() = runTest {
        // earlier builds marked App Store purchases by the hash of their signed receipt
        val r = rig(platform = PurchaseService.APP_STORE, start = false)
        val p = r.store.paid("999points")
        r.store.held += p
        PurchaseService.Marks.accept(r.env.prefs, PurchaseService.receiptHash(p.token))
        r.env.purchases.start()
        testScheduler.advanceUntilIdle()
        assertTrue(r.orders.isEmpty(), "never posted again")
        assertEquals(listOf(p.token), r.store.finished)
    }

    @Test
    fun aRevokedPurchaseTakesItsBoundTargetWithIt() = runTest {
        answers = mutableListOf(HttpResult(500, ""))
        val r = rig(platform = PurchaseService.APP_STORE)
        val link = screen(r, StoreScreenKind.POINTS, username = bob)
        link.buy("999points")
        testScheduler.advanceUntilIdle()
        val p = r.store.held.single()
        assertEquals(bob, PurchaseService.Intents.find(r.env.prefs, "999points", r.env.purchases.orderKey(p))!!.target.user)
        r.store.held[0] = p.copy(state = StorePurchaseState.REVOKED)
        r.env.purchases.recover()
        testScheduler.advanceUntilIdle()
        assertNull(r.env.prefs.getString(PurchaseService.Intents.KEY), "the refunded purchase's target goes with it")
        link.close()
    }

    @Test
    fun anInterruptedPurchaseIsDeliveredToTheAccountItWasBoughtFor() = runTest {
        val r = rig()
        // the sheet completes but the app dies before the order
        r.store.onSheet = { id -> r.store.held += r.store.paid(id) }
        val model = screen(r, StoreScreenKind.POINTS, username = bob)
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertTrue(r.orders.isEmpty())
        val held = r.store.held.toList()
        model.close()

        val again = rig(prefs = r.env.prefs, start = false)
        again.store.held += held
        again.env.purchases.start()
        testScheduler.advanceUntilIdle()
        assertEquals(bob, again.orders.single().json()["user"]!!.jsonPrimitive.content, "the buyer's target, not the signed-in account (RN)")
        assertEquals("access-spike-alice", again.orders.single().json()["code"]!!.jsonPrimitive.content)
        assertNull(again.env.prefs.getString(PurchaseService.Intents.KEY), "the saved target goes with the order")
    }

    @Test
    fun anUndeliveredPackPaidForAnotherAccountGoesToThatAccountNotToTheNextBuyer() = runTest {
        answers = mutableListOf(HttpResult(500, ""))
        val r = rig()
        // a link purchase for bob: paid, the order failed, the pack waits on the device
        val forBob = screen(r, StoreScreenKind.POINTS, username = bob)
        forBob.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(setOf(bob), r.orders.map { it.json()["user"]!!.jsonPrimitive.content }.toSet())
        forBob.close()
        // alice now buys the same pack for herself: bob's pack is completed for bob, nothing new is bought
        answers = mutableListOf(HttpResult(201, "{}"))
        val own = screen(r, StoreScreenKind.POINTS)
        val sheets = r.store.sheets.size
        own.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(sheets, r.store.sheets.size, "no second charge")
        assertEquals(bob, r.orders.last().json()["user"]!!.jsonPrimitive.content, "the pack goes to the account it was paid for")
        assertEquals(StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.earlierPurchase(bob)), own.current.alert)
        assertEquals(1, r.store.finished.size)
        assertNull(r.env.prefs.getString(PurchaseService.Intents.KEY), "the target went with its order; alice's buy saved none over it")
        own.close()
    }

    @Test
    fun aPendingPurchaseKeepsItsTargetWhenALinkBuysTheSamePackForSomeoneElse() = runTest {
        val r = rig()
        r.store.onSheet = { id -> r.store.deliver(r.store.paid(id, StorePurchaseState.PENDING)) }
        val own = screen(r, StoreScreenKind.POINTS)
        own.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.PENDING), own.current.alert)
        own.close()
        // a link for bob, same pack, while alice's payment is pending: no second purchase, alice's target stays
        val link = screen(r, StoreScreenKind.POINTS, username = bob)
        link.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(1, r.store.sheets.size)
        assertEquals(StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.earlierPending(alice)), link.current.alert)
        assertFalse(link.current.processing)
        assertEquals(alice, PurchaseService.Intents.find(r.env.prefs, "999points")!!.target.user)
        // the payment settles: delivered to alice, the account it was bought for
        r.store.held[0] = r.store.held[0].copy(state = StorePurchaseState.PURCHASED)
        r.app.now += PurchaseService.FOREGROUND_RECOVERY_MILLIS
        r.env.purchases.onForeground()
        testScheduler.advanceUntilIdle()
        assertEquals(alice, r.orders.single().json()["user"]!!.jsonPrimitive.content)
        link.close()
    }

    @Test
    fun oneReceiptIsNeverPostedTwiceAtOnce() = runTest {
        val r = rig()
        val p = r.store.paid("999points")
        r.store.held += p
        r.app.authBackend.gate = { req -> if (req.url.endsWith("purchase-order")) kotlinx.coroutines.delay(1_000) }
        r.env.purchases.onForeground()
        r.app.now += PurchaseService.FOREGROUND_RECOVERY_MILLIS
        r.store.storeListener!!.onPurchases(listOf(p))
        r.env.purchases.onForeground()
        testScheduler.advanceUntilIdle()
        assertEquals(1, r.orders.size)
        assertEquals(listOf(p.token), r.store.finished)
    }

    @Test
    fun anUnfinishedPurchaseOfTheTappedProductIsDeliveredInsteadOfBuyingAgain() = runTest {
        answers = mutableListOf(HttpResult(500, ""))
        val r = rig()
        r.store.held += r.store.paid("999points")
        r.env.purchases.recover()
        testScheduler.advanceUntilIdle()
        assertEquals(setOf("999points"), r.env.purchases.current.unfinished)
        answers = mutableListOf(HttpResult(201, "{}"))
        val model = screen(r, StoreScreenKind.POINTS)
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertTrue(r.store.sheets.isEmpty(), "no second charge")
        assertEquals(1, r.store.finished.size)
        assertEquals(PurchaseCopy.POINTS_SUCCESS, r.env.ui.current.toast?.text)
        model.close()
    }

    @Test
    fun aRevokedPurchaseIsFinishedWithoutAnOrder() = runTest {
        val r = rig()
        r.store.held += r.store.paid("999points", StorePurchaseState.REVOKED)
        r.env.purchases.recover()
        testScheduler.advanceUntilIdle()
        assertTrue(r.orders.isEmpty())
        assertEquals(1, r.store.finished.size)
    }

    @Test
    fun storeFailuresAreToldOnceAndACancelIsNoFailure() = runTest {
        val r = rig()
        val model = screen(r, StoreScreenKind.ACCOUNT_BOOST)
        r.store.onSheet = { id -> r.store.storeListener!!.onError(StoreError(StoreError.USER_CANCELLED, "cancelled", id, 1)) }
        model.buy(PurchaseSkus.BOOST)
        testScheduler.advanceUntilIdle()
        assertNull(model.current.alert)
        assertFalse(model.current.processing)
        assertTrue(r.reporter.reports.isEmpty())
        assertEquals("user cancelled purchase", r.reporter.crumbs.single().first)

        // the same store error through the listener and the request: one report, one alert
        r.app.now += 10_000
        val e = StoreError(StoreError.SERVICE_ERROR, "Service unavailable", PurchaseSkus.BOOST, 2)
        r.store.onSheet = { r.store.storeListener!!.onError(e) }
        r.store.sheetError = null
        model.buy(PurchaseSkus.BOOST)
        testScheduler.advanceUntilIdle()
        r.store.storeListener!!.onError(e)
        testScheduler.advanceUntilIdle()
        val report = r.reporter.reports.single()
        assertEquals(listOf("iap", "service-error"), report.fingerprint)
        assertEquals(mapOf("iap.stage" to "purchase", "iap.code" to "service-error", "iap.product" to "999boosts", "iap.platform" to "play_store"), report.tags)
        assertEquals("IAP service-error: Service unavailable", report.message)
        assertEquals(StoreAlert(PurchaseCopy.WARNING, "Service unavailable"), model.current.alert)

        // billing unavailable on Play: RN's "old version of Google Play" warning
        model.dismissAlert()
        r.app.now += 10_000
        r.store.sheetError = StoreError(StoreError.BILLING_UNAVAILABLE, "unavailable", null, 3)
        model.buy(PurchaseSkus.BOOST)
        testScheduler.advanceUntilIdle()
        assertEquals(StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.GOOGLE_PLAY_VERSION), model.current.alert)
        assertEquals("request", r.reporter.reports.last().tags["iap.stage"])
        model.close()
    }

    @Test
    fun aPurchaseLinkConfirmsItsProductForTheLinkedAccount() = runTest {
        val r = rig()
        val model = screen(r, StoreScreenKind.POINTS, username = bob, productId = "999points")
        val c = model.current.confirm!!
        assertEquals("Confirm Purchase @spike-bob", c.title)
        assertEquals("Buying 1000 POINTS for spike-bob at USD 9.99", c.body)
        assertEquals(bob, model.current.username)
        model.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(bob, r.orders.single().json()["user"]!!.jsonPrimitive.content)
        assertNull(model.current.confirm)
        model.load()
        testScheduler.advanceUntilIdle()
        assertNull(model.current.confirm, "asked once")
        model.close()
    }

    @Test
    fun signedOutOnlyAPaidAccountCanBeBought() = runTest {
        val r = rig(signedIn = null)
        val model = screen(r, StoreScreenKind.POINTS)
        model.buy("999points")
        testScheduler.advanceUntilIdle()
        assertEquals(StoreAlert(PurchaseCopy.NOT_LOGGED_IN, PurchaseCopy.NOT_LOGGED_IN_DESC), model.current.alert)
        assertTrue(r.store.sheets.isEmpty())
        model.close()
    }

    @Test
    fun aPaidAccountIsOrderedWithItsDetailsAndRecoveredWithThem() = runTest {
        val r = rig(signedIn = null)
        r.env.signupPrefill = SignupPrefill("newbie.one", "new@example.com", bob, purchaseOnly = true)
        val register = r.env.registerModel("")
        testScheduler.advanceUntilIdle()
        assertTrue(register.current.optionsOpen, "a purchase link opens the paid options")
        assertTrue(register.current.purchaseOnly)
        assertEquals("Buy Account - $9.99", register.current.paidButton)
        // the sheet completes, the order fails and the app dies: the details survive for the next start
        answers = mutableListOf(HttpResult(500, ""))
        register.buyAccount()
        testScheduler.advanceUntilIdle()
        assertTrue(register.current.alert!!.body.startsWith(PurchaseCopy.REGISTER_FAIL))
        assertFalse(register.current.isRegistering)
        val first = r.orders.first().json()
        assertEquals("ecency", first["user"]!!.jsonPrimitive.content, "signed out: RN's 'ecency'")
        assertEquals(Json.parseToJsonElement("""{"username":"newbie.one","email":"new@example.com","referral":"spike-bob"}"""), first["meta"])
        assertNull(first["code"], "signed out: no code")
        assertEquals(PurchaseCopy.CLAIM_ACCOUNT, register.current.paidButton)

        answers = mutableListOf(HttpResult(201, "{}"))
        val held = r.store.held.toList()
        register.close()
        val again = rig(prefs = r.env.prefs, start = false)
        again.store.held += held
        again.env.purchases.start()
        testScheduler.advanceUntilIdle()
        assertEquals(Json.parseToJsonElement("""{"username":"newbie.one","email":"new@example.com","referral":"spike-bob"}"""), again.orders.single().json()["meta"])
        assertEquals(alice, again.orders.single().json()["user"]!!.jsonPrimitive.content)
        assertEquals(1, again.store.finished.size)
        assertNull(again.env.prefs.getString(PurchaseService.Intents.KEY))
    }

    @Test
    fun aSecondNamesBuyCompletesTheFirstNamesPaidAccountAndLeavesNoSpinner() = runTest {
        val r = rig(signedIn = null)
        r.env.signupPrefill = SignupPrefill("newbie.one", "new@example.com", "", purchaseOnly = true)
        val first = r.env.registerModel("")
        testScheduler.advanceUntilIdle()
        answers = mutableListOf(HttpResult(500, ""))
        first.buyAccount()
        testScheduler.advanceUntilIdle()
        assertFalse(first.current.isRegistering)
        first.close()

        answers = mutableListOf(HttpResult(201, "{}"))
        r.env.signupPrefill = SignupPrefill("newbie.two", "two@example.com", "", purchaseOnly = true)
        val second = r.env.registerModel("")
        testScheduler.advanceUntilIdle()
        assertTrue(second.current.optionsOpen)
        second.buyAccount()
        testScheduler.advanceUntilIdle()
        assertEquals(PurchaseCopy.earlierPurchase("newbie.one"), second.current.alert?.body)
        assertFalse(second.current.isRegistering, "no follow-up comes for the other name: no spinner left")
        assertFalse(second.current.isRegistered, "newbie.two was not registered")
        assertEquals(1, r.store.sheets.size, "no second sheet")
        val last = r.orders.last().json()
        assertEquals("ecency", last["user"]!!.jsonPrimitive.content)
        assertEquals("newbie.one", last["meta"]!!.jsonObject["username"]!!.jsonPrimitive.content)
        second.close()
    }

    @Test
    fun aPaidAccountWithoutItsDetailsOrWithABadNameIsNeverOrdered() = runTest {
        val r = rig()
        r.store.held += r.store.paid(PurchaseSkus.ACCOUNT)
        r.env.purchases.recover()
        testScheduler.advanceUntilIdle()
        assertTrue(r.orders.isEmpty(), "no saved details: left for the register screen (RN)")
        assertTrue(r.store.finished.isEmpty())

        PurchaseService.Intents.save(r.env.prefs, PurchaseSkus.ACCOUNT, PurchaseTarget(meta = com.ecency.core.api.ecency.AccountOrderMeta("ab", "a@b.co", "")), r.app.now)
        r.env.purchases.recover()
        testScheduler.advanceUntilIdle()
        assertTrue(r.orders.isEmpty(), "a name the chain rejects is never ordered")

        // the register screen refuses before the sheet opens
        r.env.signupPrefill = SignupPrefill("ab", "a@b.co", "")
        val register = r.env.registerModel("")
        register.setUsername("ab")
        register.setEmail("a@b.co")
        testScheduler.advanceUntilIdle()
        assertFalse(register.current.optionsOpen)
        register.close()
    }

    @Test
    fun aStoreErrorOfThePaidAccountDropsItsDetailsButNoOtherProductsTarget() = runTest {
        val r = rig()
        PurchaseService.Intents.save(r.env.prefs, PurchaseSkus.ACCOUNT, PurchaseTarget(meta = com.ecency.core.api.ecency.AccountOrderMeta("newbie.one", "a@b.co", "")), r.app.now)
        PurchaseService.Intents.save(r.env.prefs, "999points", PurchaseTarget(bob), r.app.now)
        r.store.storeListener!!.onError(StoreError(StoreError.SERVICE_ERROR, "x", "999points"))
        testScheduler.advanceUntilIdle()
        assertEquals(2, PurchaseService.Intents.all(r.env.prefs).size)
        // a transient failure says nothing about the payment: the details stay for a purchase that may still arrive
        r.store.storeListener!!.onError(StoreError(StoreError.NETWORK_ERROR, "x", PurchaseSkus.ACCOUNT))
        r.store.storeListener!!.onError(StoreError(StoreError.SERVICE_ERROR, "y", PurchaseSkus.ACCOUNT))
        testScheduler.advanceUntilIdle()
        assertEquals(2, PurchaseService.Intents.all(r.env.prefs).size)
        r.store.storeListener!!.onError(StoreError(StoreError.ITEM_UNAVAILABLE, "z", PurchaseSkus.ACCOUNT))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("999points"), PurchaseService.Intents.all(r.env.prefs).map { it.productId })
    }

    @Test
    fun theSpinGameReadsItsStatusAgainWhenAPackArrives() = runTest {
        val r = rig()
        var statusCalls = 0
        val rest = r.app.authBackend.rest
        r.app.authBackend.rest = { req ->
            if (req.url.endsWith("/private-api/get-game")) {
                statusCalls++
                HttpResult(200, """{"status":18,"remaining":0,"next_date":"2026-10-22T00:00:00Z"}""")
            } else {
                rest?.invoke(req)
            }
        }
        val spin = r.env.spinGameModel()
        spin.start()
        testScheduler.advanceUntilIdle()
        assertEquals(1, statusCalls)
        assertTrue(spin.current.showProducts)
        val pack = screen(r, StoreScreenKind.SPINS)
        pack.buy(PurchaseSkus.SPINS)
        testScheduler.advanceUntilIdle()
        assertEquals(2, statusCalls, "RN fetchData={statusCheck}")
        spin.close()
        pack.close()
    }

    @Test
    fun aWipeRemovesTheSavedTargetsAndMarks() = runTest {
        val r = rig()
        PurchaseService.Intents.save(r.env.prefs, "999points", PurchaseTarget(bob), r.app.now)
        PurchaseService.Marks.accept(r.env.prefs, "abc")
        AccountPrefs.clear(r.env.prefs)
        assertTrue(r.env.prefs.keys().none { it.startsWith("purchases.") })
    }

    @Test
    fun theOrderBodyAndItsTextsNeverPrintTheReceipt() {
        val order = com.ecency.core.api.ecency.PurchaseOrder("play_store", "999points", "secret-token", "alice", com.ecency.core.api.ecency.AccountOrderMeta("n", "e@x.co", ""))
        assertFalse("secret-token" in order.toString())
        assertFalse("e@x.co" in order.toString())
        assertFalse("secret-token" in StorePurchase("p", "secret-token", StorePurchaseState.PURCHASED).toString())
        assertEquals(32, PurchaseService.receiptHash("secret-token").length)
    }
}
