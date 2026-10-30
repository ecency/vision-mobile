package com.ecency.core.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RcOfferTest {
    private val rc = "Account: spike-voter has 12 RC, needs 3400 RC. Please wait to transact, or power up HIVE."

    @Test
    fun onlyAnRcShortfallAnywhereInTheErrorChainRaisesTheOffer() = runTest {
        val app = TestApp(this) { TestApp.chain(it) ?: com.ecency.core.api.rpc.HttpResult(500, "") }
        // RN RC_SHORTFALL: either half of the node's message, found on any cause
        assertFalse(app.env.offerRcIfShort(IllegalStateException("missing required posting authority")))
        assertFalse(app.env.offerRcIfShort(IllegalStateException("rejected: out of bandwidth")))
        assertFalse(app.env.rcOffer.current.open)
        assertTrue(app.env.offerRcIfShort(RuntimeException("wrapped", IllegalStateException(rc))))
        assertTrue(app.env.rcOffer.current.open)
    }

    @Test
    fun eachChoiceClosesTheOfferAndOnlyTopUpAndBoostOpenAScreen() = runTest {
        val app = TestApp(this) { TestApp.chain(it) ?: com.ecency.core.api.rpc.HttpResult(500, "") }
        val offer = app.env.rcOffer
        assertNull(offer.choose(RcOfferChoice.TOP_UP), "nothing to choose while closed")
        offer.raise()
        offer.raise()
        assertTrue(offer.current.open)
        assertEquals(AppScreen.of(RouteName.REDEEM, RcErrors.TOP_UP_REDEEM_TYPE), offer.choose(RcOfferChoice.TOP_UP))
        assertFalse(offer.current.open)
        offer.raise()
        assertEquals(AppScreen.of(RouteName.ACCOUNT_BOOST), offer.choose(RcOfferChoice.BOOST))
        offer.raise()
        assertNull(offer.choose(RcOfferChoice.CANCEL))
        assertFalse(offer.current.open)
        // sign-out: an offer about the previous account's credits goes away
        offer.raise()
        app.env.forgetSessionState()
        assertFalse(offer.current.open)
    }
}
