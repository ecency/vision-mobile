package com.ecency.core.app

import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.broadcast.ChainRefusal
import com.ecency.core.api.rpc.RpcErrorException
import com.ecency.core.auth.AuthException
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #3660: what hived (1.28.8) refuses, read the same way from the node, HiveSigner and HiveAuth. */
class ChainRefusalTest {
    @BeforeTest
    fun quiet() = ErrorReports.install({ CrashReporter.None }, { 0L })

    @AfterTest
    fun uninstall() = ErrorReports.install({ CrashReporter.None }, { 0L })

    private fun node(message: String, data: String? = null) =
        BroadcastException.from(RpcErrorException("condenser_api.broadcast_transaction", "node", -32000, message, data))

    private fun viaNode(message: String, data: String? = null) = AppError.fromBroadcast(node(message, data), "test.node")

    private fun viaHiveSigner(description: String) =
        AppError.from(AuthException.HiveSignerApi(500, "server_error", description), "test.hivesigner")

    private fun viaHiveAuth(detail: String) =
        AppError.from(AuthException.HiveAuth(AuthException.HiveAuth.Reason.ERROR, detail), "test.hiveauth")

    /** hived's own text (the rendered message, or the format with its context in `data`) for each refusal. */
    private val refusals = listOf(
        ChainRefusal.POST_COOLDOWN to "Assert Exception:( now - auth.last_root_post ) > HIVE_MIN_ROOT_COMMENT_INTERVAL: You may only post once every 5 minutes.",
        ChainRefusal.COMMENT_COOLDOWN to "Assert Exception: You may only comment once every 3 seconds.",
        ChainRefusal.EDIT_COOLDOWN to "Assert Exception: Can only perform one comment edit per block.",
        ChainRefusal.IDENTICAL_VOTE to "Assert Exception: Your current vote on this comment is identical to this vote.",
        ChainRefusal.IDENTICAL_VOTE to "Assert Exception: You have already voted in a similar way.",
        ChainRefusal.VOTE_TOO_SMALL to "Assert Exception: Voting weight is too small, please accumulate more voting power or Hive Power.",
        ChainRefusal.VOTE_TOO_SMALL to "Assert Exception: Account does not have enough power to vote.",
        ChainRefusal.PAID_OUT to "Assert Exception: Votes evaluating for comment that is paid out is forbidden.",
        ChainRefusal.PAID_OUT to "Assert Exception: Updating parameters for comment that is paid out is forbidden.",
        ChainRefusal.PAID_OUT to "Assert Exception: Cannot delete comment after payout.",
        ChainRefusal.DELETE_VOTED to "Assert Exception: Cannot delete a comment with net positive votes.",
        ChainRefusal.DELETE_REPLIES to "Assert Exception: Cannot delete a comment with replies.",
        ChainRefusal.TITLE_TOO_LONG to "String is too large. Size: 262, Max: 255 {\"context\":\"Title size limit exceeded.\"}",
        ChainRefusal.BODY_EMPTY to "Assert Exception: Body is empty",
        ChainRefusal.NOT_UTF8 to "Assert Exception: Body not formatted in UTF8",
        ChainRefusal.NOT_UTF8 to "Assert Exception: JSON Metadata must be UTF-8",
        ChainRefusal.BENEFICIARIES_REFUSED to "Assert Exception: Beneficiaries must be specified in sorted order (account ascending)",
        ChainRefusal.OPTIONS_LOCKED to "Assert Exception: Comment already has beneficiaries specified.",
        ChainRefusal.OPTIONS_LOCKED to "Assert Exception: Comment must not have been voted on before specifying beneficiaries.",
        ChainRefusal.PAYOUT_RAISED to "Assert Exception: A comment cannot accept a greater payout.",
        ChainRefusal.INSUFFICIENT_FUNDS to "Assert Exception: Account does not have sufficient funds for transfer.",
        ChainRefusal.MISSING_POSTING to "missing required posting authority:Missing Posting Authority spike-voter",
        ChainRefusal.MISSING_ACTIVE to "missing required active authority:Missing Active Authority spike-voter",
        ChainRefusal.MISSING_OWNER to "missing required owner authority:Missing Owner Authority spike-voter",
    )

    @Test
    fun everyRefusalReadsTheSameFromTheNodeHiveSignerAndHiveAuth() {
        for ((refusal, text) in refusals) {
            assertEquals(refusal, ChainRefusal.of(text), text)
            for (error in listOf(viaNode(text), viaHiveSigner(text), viaHiveAuth(text))) {
                assertEquals(refusal.message, error.message, text)
                assertEquals(refusal.kind, error.kind, text)
            }
        }
    }

    @Test
    fun theRefusalIsFoundInTheNodesDataToo() {
        // hived renders some asserts with placeholders and puts the words that matter in `data`
        assertEquals(ChainRefusal.TITLE_TOO_LONG.message, viaNode("Assert Exception", "{\"stack\":[{\"format\":\"Title size limit exceeded.\"}]}").message)
    }

    @Test
    fun anUnknownRefusalSaysTheNetworkRefusedItAndQuotesTheNode() {
        assertNull(ChainRefusal.of("Assert Exception: body is too odd"))
        val error = viaNode("Assert Exception: body is too odd")
        assertEquals(ErrorKind.REJECTED, error.kind)
        assertEquals(L.native.chainRefusal.refusedWith(reason = "Assert Exception: body is too odd"), error.message)
    }

    @Test
    fun resourceCreditsAreOneRuleForTheKindAndTheOfferWhicheverWaySigned() {
        val rc = "Account: spike-voter has 12 RC, needs 3400 RC. Please wait to transact or power up HIVE."
        val failures = listOf(
            node(rc),
            AuthException.HiveSignerApi(500, "server_error", rc),
            AuthException.HiveAuth(AuthException.HiveAuth.Reason.ERROR, rc),
        )
        for (e in failures) {
            assertEquals(ErrorKind.RESOURCE_CREDITS, AppError.from(e, "test.rc").kind, e::class.simpleName)
            assertTrue(RcErrors.isInsufficientRc(e), "the Top up / Boost offer opens for ${e::class.simpleName}")
        }
        // RN RC_SHORTFALL: "bandwidth" alone is not a shortfall, for the kind as for the offer
        val bandwidth = node("rejected: out of bandwidth")
        assertFalse(AppError.from(bandwidth, "test.rc").kind == ErrorKind.RESOURCE_CREDITS)
        assertFalse(RcErrors.isInsufficientRc(bandwidth))
    }

    @Test
    fun anExpiredHiveSignerLoginSaysSignInAgain() {
        val error = AppError.from(AuthException.HiveSignerApi(401, "invalid_grant", "The token has invalid role"), "test.hivesigner")
        assertEquals(ErrorKind.AUTH, error.kind)
        assertEquals(L.native.chainRefusal.hiveSignerExpired, error.message)
    }

    @Test
    fun aHiveAuthWalletErrorKeepsTheWalletsReason() {
        val error = viaHiveAuth("The wallet could not reach a node")
        assertEquals(L.native.chainRefusal.walletRefused(reason = "The wallet could not reach a node"), error.message)
    }

    @Test
    fun aTransactionOverTheLimitNamesItsSizeAndTheLimitNeverAReply() {
        val error = AppError.fromBroadcast(BroadcastException.TooLarge(70_000), "test.size")
        assertEquals(L.native.chainRefusal.tooLarge(size = LocaleFormat.integer(69), max = LocaleFormat.integer(64)), error.message)
        assertFalse(error.message.contains("reply", ignoreCase = true))
    }
}
