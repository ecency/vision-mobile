package com.ecency.core.app

data class RcOfferState(
    /** The offer is up: "Top up RC with Points", "Boost account" or cancel ([RcErrors] holds the copy). */
    val open: Boolean = false,
)

/** Where the user went from the offer. */
enum class RcOfferChoice { TOP_UP, BOOST, CANCEL }

/**
 * RN ui.rcOffer (applicationScreen): one app-wide offer raised by any broadcast that failed for lack of resource
 * credits (a vote, a reblog, a follow or mute, a comment, a wave, a post) instead of a dead-end toast. Each platform
 * draws it once at the shell, over the unlocked app only, and answers with [choose]. One per app.
 */
class RcOfferModel internal constructor(dispatchers: AppDispatchers) : StateModel<RcOfferState>(RcOfferState(), dispatchers) {
    /** Opens the offer (a second failure while it is up keeps the one offer). */
    fun raise() {
        if (!current.open) setState { RcOfferState(open = true) }
    }

    /**
     * Closes the offer. Returns the screen to open for [choice]: the Points top-up (RN REDEEM, rc_topup) or the
     * account boost; null for cancel (also a sheet swiped away: RN acts only on an explicit choice).
     */
    fun choose(choice: RcOfferChoice): AppScreen? {
        if (!current.open) return null
        setState { RcOfferState(open = false) }
        return when (choice) {
            RcOfferChoice.TOP_UP -> AppScreen.of(RouteName.REDEEM, RcErrors.TOP_UP_REDEEM_TYPE)
            RcOfferChoice.BOOST -> AppScreen.of(RouteName.ACCOUNT_BOOST)
            RcOfferChoice.CANCEL -> null
        }
    }

    /** Sign-out and wipe: an offer about the previous account's credits goes away. */
    fun clear() {
        if (current.open) setState { RcOfferState() }
    }

    /** The app's one model: screens do not own it. */
    override fun close() {}
}

/**
 * Raises the app-wide offer when [error] is a refusal for lack of resource credits (RN isInsufficientRcError over
 * the whole error chain). True when it did: the caller shows nothing else for this failure.
 */
fun AppEnvironment.offerRcIfShort(error: Throwable): Boolean {
    if (!RcErrors.isInsufficientRc(error)) return false
    rcOffer.raise()
    return true
}
