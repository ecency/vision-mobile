package com.ecency.core.app

import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.broadcast.ProfileMetadataGuard
import com.ecency.core.l10n.L10n
import com.ecency.core.l10n.PseudoLocales
import kotlinx.serialization.json.JsonArray
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Copy is presentation only: code never decides by comparing a text that came from the catalog, because the language
 * can change between producing a value and checking it. Each case below is produced in one language and checked in
 * another.
 */
class CopyIdentityTest {
    @AfterTest
    fun english() = L10n.setLanguage(L10n.SOURCE)

    @Test
    fun anUndecidedSendIsAFlagNotAMessage() {
        val error = AppError.from(BroadcastException.Network(IllegalStateException("x"), null, true))
        PseudoLocales.install(PseudoLocales.ACCENTED)
        assertTrue(error.undecided)
        assertNotEquals(BroadcastException.UNDECIDED_MESSAGE, error.message, "the message was English, the language changed")
        assertFalse(AppError.from(BroadcastException.Network(IllegalStateException("x"), null, false)).undecided)
    }

    @Test
    fun aProfileReadRefusalIsAType() {
        val refused = ProfileWrites.refused()
        PseudoLocales.install(PseudoLocales.ACCENTED)
        assertTrue(ProfileWrites.isRefusal(refused))
        val lookalike = AppException(AppError(ErrorKind.NETWORK, ProfileMetadataGuard.REFUSED, true))
        assertFalse(ProfileWrites.isRefusal(lookalike), "the same text is not a refusal")
    }

    @Test
    fun aSwapOutcomeIsTyped() {
        val pending = TradeState(outcome = TradeOutcome.PENDING)
        PseudoLocales.install(PseudoLocales.ACCENTED)
        assertTrue(pending.pending)
        assertEquals(TradeCopy.PENDING, pending.result)
        assertFalse(TradeState(outcome = TradeOutcome.SUCCESSFUL).pending)
    }

    @Test
    fun answersToAnAppThatOpenedALinkStayEnglish() {
        PseudoLocales.install(PseudoLocales.ACCENTED)
        assertEquals("Username bob is not logged in on Ecency.", CallbackText.notLoggedIn("bob"))
        assertEquals("Username bob doesn't have a posting private key stored on Ecency.", CallbackText.postingKeyUnavailable("bob"))
        assertEquals("Unable to unlock stored keys for bob.", CallbackText.pinRequired("bob"))
        assertEquals("User declined to share the posting private key with https://app.example.", CallbackText.declined("https://app.example"))
        assertEquals("User declined to share the posting private key.", CallbackText.declined(null))
    }

    @Test
    fun answersToTheDappPageStayEnglish() {
        PseudoLocales.install(PseudoLocales.ACCENTED)
        assertEquals("No valid operations found.", BridgeGuards.refusal(JsonArray(emptyList()), signsHere = true))
        assertEquals("Operations that need the owner key cannot be signed in Ecency.", BridgeGuards.OWNER_REFUSED)
    }
}
