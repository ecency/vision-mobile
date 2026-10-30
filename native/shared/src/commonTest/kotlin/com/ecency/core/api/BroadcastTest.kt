package com.ecency.core.api

import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.InvalidInputException
import com.ecency.core.api.broadcast.Comments
import com.ecency.core.api.broadcast.HiveBroadcaster
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.broadcast.PayoutType
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.Asset
import com.ecency.core.hive.Beneficiary
import com.ecency.core.hive.ChainTime
import com.ecency.core.hive.Comment
import com.ecency.core.hive.CommentOptions
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.hive.PrivateKey
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Broadcast tests reuse the golden-vector chain state (ref block 12345, prefix 3367463123,
 * expiration 2026-10-06T12:00:00) and throwaway key, so every signature asserted here is the
 * one @ecency/sdk produces for the same transaction. Nothing is ever sent to a real node.
 */
class BroadcastTest {
    private val key = PrivateKey.fromWif(GoldenVectors.WIF)

    // Head is 3 blocks past the reference block 0x06013039 (low 16 bits = 12345).
    private val headNumber = 0x0601303CL
    private val refId = "06013039" + "d368b7c8" + "00".repeat(12) // bytes 4..7 LE = 3367463123
    private val headId = "0601303c" + "11".repeat(16)
    private val now = ChainTime.toEpochSeconds(GoldenVectors.EXPIRATION) * 1000 - 60_000 + 400

    /** Test clock; starts at [now]. */
    private var clock = now

    /** Whether condenser_api.get_content reports the reply as existing. */
    private var contentExists = false

    /** What transaction_status_api.find_transaction answers (null: a node error); expired by default. */
    private var txStatus: String? = "expired_irreversible"

    private val statusLookups = mutableListOf<String>()

    /** The chain's head time in get_dynamic_global_properties. */
    private var chainTime = "2026-10-06T11:59:00"

    private fun chain(onBroadcast: suspend FakeTransport.(Sent) -> HttpResult = { FakeTransport.result(it, "null") }) =
        FakeTransport { req ->
            when (req.method) {
                "condenser_api.get_content" -> {
                    val (author, permlink) = req.params.jsonArray.map { it.jsonPrimitive.content }
                    FakeTransport.result(
                        req,
                        if (contentExists) """{"author":"$author","permlink":"$permlink"}""" else """{"author":"","permlink":""}""",
                    )
                }
                "condenser_api.get_dynamic_global_properties" -> FakeTransport.result(
                    req,
                    """{"head_block_number":$headNumber,"head_block_id":"$headId","time":"$chainTime"}""",
                )
                "condenser_api.get_block_header" -> FakeTransport.result(req, """{"previous":"$refId","timestamp":"2026-10-06T11:58:51"}""")
                "condenser_api.broadcast_transaction" -> onBroadcast(req)
                "transaction_status_api.find_transaction" -> {
                    statusLookups += req.params.jsonObject.getValue("transaction_id").jsonPrimitive.content
                    txStatus?.let { FakeTransport.result(req, """{"status":"$it"}""") } ?: HttpResult(500, "")
                }
                else -> HttpResult(500, "")
            }
        }

    private fun broadcaster(transport: FakeTransport) =
        HiveBroadcaster(HiveApi(clientWith(transport, now = { clock })), nowMillis = { clock })

    private fun FakeTransport.broadcasts() = sent.filter { it.method == "condenser_api.broadcast_transaction" }

    @Test
    fun voteRequestBytesAreExact() = runTest {
        val transport = chain()
        val result = broadcaster(transport).vote("spike-voter", "ecency", "native-golden-vote", 10000, key)
        val vector = GoldenVectors.transactions.first { it.name == "vote_upvote" }
        assertEquals(vector.txId, result.txId)
        assertEquals(
            listOf("condenser_api.get_dynamic_global_properties", "condenser_api.get_block_header", "condenser_api.broadcast_transaction"),
            transport.methods(),
        )
        assertEquals("[${headNumber - 2}]", transport.sent[1].params.toString())
        assertEquals(
            """{"jsonrpc":"2.0","method":"condenser_api.broadcast_transaction","params":[{""" +
                """"expiration":"2026-10-06T12:00:00","extensions":[],""" +
                """"operations":[["vote",{"voter":"spike-voter","author":"ecency","permlink":"native-golden-vote","weight":10000}]],""" +
                """"ref_block_num":12345,"ref_block_prefix":3367463123,""" +
                """"signatures":["1fea2e35cf2ff5dc04891145195f8e53f7bc22fdaca5fb6042613f5841f67c81477942c566197e4e30d1c44e37606ae04f1db1bb36a4cb320bf53d4ac5a9a07d89"]""" +
                """}],"id":3}""",
            transport.sent[2].body,
        )
    }

    @Test
    fun everyGoldenVectorSignsToTheSdkSignature() = runTest {
        GoldenVectors.transactions.forEach { vector ->
            val transport = chain()
            val result = broadcaster(transport).broadcast(vector.operations, key)
            val tx = transport.sent.last().params.jsonArray.single().jsonObject
            assertEquals(vector.signature, tx.getValue("signatures").jsonArray.single().jsonPrimitive.content, vector.name)
            assertEquals(vector.txId, result.txId, vector.name)
        }
    }

    @Test
    fun commentOptionsWithBeneficiariesJson() {
        val op = CommentOptions(
            "spike-author", "native-golden-post", Asset.parse("1000000.000 HBD"), 10000, true, false,
            listOf(Beneficiary("ecency", 500), Beneficiary("spike-bene", 1000)),
        )
        assertEquals(
            """["comment_options",{"author":"spike-author","permlink":"native-golden-post","max_accepted_payout":"1000000.000 HBD",""" +
                """"percent_hbd":10000,"allow_votes":true,"allow_curation_rewards":false,""" +
                """"extensions":[[0,{"beneficiaries":[{"account":"ecency","weight":500},{"account":"spike-bene","weight":1000}]}]]}]""",
            OperationJson.encode(op).toString(),
        )
        assertEquals(
            """["comment",{"parent_author":"bob","parent_permlink":"p","author":"alice","permlink":"re-bob-1","title":"","body":"hi \"there\"","json_metadata":"{}"}]""",
            OperationJson.encode(Comment("bob", "p", "alice", "re-bob-1", "", "hi \"there\"", "{}")).toString(),
        )
    }

    @Test
    fun taposFallsBackToHeadWhenTheHeaderIsMissingOrWrong() = runTest {
        for (header in listOf("""{"previous":"ffffffff00000000000000000000000000000000"}""", "null")) {
            val transport = FakeTransport { req ->
                when (req.method) {
                    "condenser_api.get_dynamic_global_properties" ->
                        FakeTransport.result(req, """{"head_block_number":$headNumber,"head_block_id":"$headId"}""")
                    else -> FakeTransport.result(req, header)
                }
            }
            val ref = broadcaster(transport).reference()
            assertEquals((headNumber and 0xffff).toInt(), ref.refBlockNum, header)
            assertEquals(0x11111111L, ref.refBlockPrefix, header)
        }
        assertEquals(3367463123L, HiveBroadcaster.referenceOf(0x06013039, refId).refBlockPrefix)
    }

    @Test
    fun duplicateTransactionIsSuccess() = runTest {
        val transport = chain { FakeTransport.error(it, -32003, "Duplicate transaction check failed") }
        broadcaster(transport).vote("spike-voter", "ecency", "native-golden-vote", 10000, key)
    }

    @Test
    fun timeoutThenDuplicateOnTheNextNodeIsSuccess() = runTest {
        var broadcasts = 0
        val transport = chain { req ->
            if (broadcasts++ == 0) awaitCancellation()
            FakeTransport.error(req, -32003, "Duplicate transaction check failed")
        }
        broadcaster(transport).vote("spike-voter", "ecency", "native-golden-vote", 10000, key)
        val nodes = transport.sent.filter { it.method == "condenser_api.broadcast_transaction" }.map { it.node }
        assertEquals(2, nodes.toSet().size)
    }

    @Test
    fun rpcErrorsMapToTypedErrors() = runTest {
        val cases = mapOf(
            "Assert Exception:rc_plugin: Account: alice has 0 RC, needs 5 RC. Please wait to transact, or power up HIVE." to BroadcastException.InsufficientResourceCredits::class,
            "Assert Exception:itr->vote_percent != o.weight: Your current vote on this comment is identical to this vote." to BroadcastException.DuplicateVote::class,
            "Missing Authority:missing required posting authority:Missing Posting Authority alice" to BroadcastException.MissingAuthority::class,
            "Assert Exception:You may only post once every 3 seconds." to BroadcastException.CommentTooSoon::class,
            "Assert Exception:Votes evaluating for comment that is paid out is forbidden." to BroadcastException.PaidOut::class,
            "Assert Exception:something unexpected" to BroadcastException.Rejected::class,
        )
        for ((message, type) in cases) {
            val transport = chain { FakeTransport.error(it, -32003, message) }
            val e = assertFailsWith<BroadcastException> {
                broadcaster(transport).vote("spike-voter", "ecency", "native-golden-vote", 10000, key)
            }
            assertEquals(type, e::class, message)
            // A chain verdict is never re-sent to another node.
            assertEquals(1, transport.sent.count { it.method == "condenser_api.broadcast_transaction" }, message)
        }
        val down = chain { HttpResult(503, "") }
        assertIs<BroadcastException.Network>(
            assertFailsWith<BroadcastException> { broadcaster(down).vote("spike-voter", "ecency", "native-golden-vote", 10000, key) },
        )
    }

    @Test
    fun replyPermlinkMatchesTheAppFormat() {
        // 2026-03-04T05:06:07.089Z. Zero-padded (the app's unpadded form collides), dots dropped from the author.
        val t = ChainTime.toEpochSeconds("2026-03-04T05:06:07") * 1000 + 89
        assertEquals("re-bobsmith-20260304t050607089z", Comments.replyPermlink("bob.smith", t))
        val jan11 = Comments.replyPermlink("alice", ChainTime.toEpochSeconds("2026-01-11T01:02:03") * 1000)
        val nov1 = Comments.replyPermlink("alice", ChainTime.toEpochSeconds("2026-11-01T01:02:03") * 1000)
        assertTrue(jan11 != nov1, "unpadded '2026111' would collide")
        assertFailsWith<InvalidInputException> { Comments.replyPermlink(" ", t) }
        val t2 = ChainTime.toEpochSeconds("2026-12-31T23:59:59") * 1000 + 999
        assertEquals("re-alice-20261231t235959999z", Comments.replyPermlink("alice", t2))
    }

    @Test
    fun replyDraftOperationsAndMetadata() {
        val t = ChainTime.toEpochSeconds("2026-10-06T12:00:00") * 1000
        val plain = Comments.reply(
            author = "bob", parentAuthor = "alice", parentPermlink = "post",
            body = "Nice ![x](https://img.example/a.png) see https://site.example/page.", parentTags = listOf("hive-100001"), nowMillis = t,
        )
        assertEquals("re-alice-20261006t120000000z", plain.permlink)
        val comment = assertIs<Comment>(plain.operations.single())
        assertEquals("alice", comment.parentAuthor)
        assertEquals("", comment.title)
        assertEquals(
            """{"image":["https://img.example/a.png"],"links":["https://site.example/page"],"tags":["hive-100001"],"app":"ecency/4.0.0-mobile","format":"markdown+html"}""",
            comment.jsonMetadata,
        )

        val withOptions = Comments.reply(
            author = "bob", parentAuthor = "alice", parentPermlink = "post", body = "hi", nowMillis = t,
            payout = PayoutType.POWER_UP,
            beneficiaries = listOf(Beneficiary("zed", 100), Beneficiary("ecency", 300), Beneficiary("zed", 200)),
        )
        val options = assertIs<CommentOptions>(withOptions.operations[1])
        assertEquals(0, options.percentHbd)
        assertEquals(listOf("ecency" to 300, "zed" to 100), options.beneficiaries.map { it.account to it.weight })
        assertTrue(assertIs<Comment>(withOptions.operations[0]).jsonMetadata.contains(""""tags":["ecency"]"""))

        val declined = Comments.reply(author = "bob", parentAuthor = "alice", parentPermlink = "post", body = "hi", nowMillis = t, payout = PayoutType.DECLINE)
        assertEquals("0.000 HBD", assertIs<CommentOptions>(declined.operations[1]).maxAcceptedPayout.toString())
        assertFailsWith<InvalidInputException> { Comments.reply("bob", "alice", "post", "  ", nowMillis = t) }
        // Over 100% in beneficiaries: a typed error, not an IllegalArgumentException that would crash Swift.
        assertFailsWith<InvalidInputException> {
            Comments.reply("bob", "alice", "post", "hi", nowMillis = t, beneficiaries = listOf(Beneficiary("a", 6000), Beneficiary("b", 6000)))
        }
    }

    @Test
    fun retryAfterNetworkErrorResendsTheSameSignedTransaction() = runTest {
        var attempts = 0
        val transport = chain { req ->
            if (attempts++ < 3) awaitCancellation() // every node times out: the vote may have landed
            FakeTransport.error(req, -32003, "Duplicate transaction check failed")
        }
        val b = broadcaster(transport)
        val e = assertFailsWith<BroadcastException.Network> { b.vote("spike-voter", "ecency", "native-golden-vote", 10000, key) }
        assertTrue(e.pending!!.mayHaveLanded)
        clock += 5_000 // a re-sign now would get a different expiration, hence different bytes
        val result = b.vote("spike-voter", "ecency", "native-golden-vote", 10000, key)
        val bodies = transport.broadcasts().map { it.params.toString() }
        assertEquals(4, bodies.size)
        assertEquals(1, bodies.toSet().size, "every send carries the same signed transaction")
        assertEquals(GoldenVectors.transactions.first { it.name == "vote_upvote" }.txId, result.txId)
        assertEquals(1, transport.sent.count { it.method == "condenser_api.get_dynamic_global_properties" }, "signed once")
    }

    @Test
    fun aDepartedAccountsRetriesAreDroppedAndTheOthersKept() = runTest {
        var down = true
        val transport = chain { req ->
            if (down) awaitCancellation() // every node times out: the votes may have landed
            FakeTransport.result(req, "null")
        }
        val b = broadcaster(transport)
        assertFailsWith<BroadcastException.Network> { b.vote("spike-voter", "ecency", "native-golden-vote", 10000, key) }
        assertFailsWith<BroadcastException.Network> { b.vote("stays", "ecency", "native-golden-vote", 10000, key) }
        down = false
        b.forgetAccount("spike-voter")
        clock += 5_000 // a new signature gets a new expiration
        b.vote("spike-voter", "ecency", "native-golden-vote", 10000, key)
        b.vote("stays", "ecency", "native-golden-vote", 10000, key)
        fun bodies(voter: String) = transport.broadcasts().map { it.params.toString() }.filter { "\"$voter\"" in it }.toSet()
        assertEquals(2, bodies("spike-voter").size, "the departed account's retry was dropped: its key is gone, the vote signs anew")
        assertEquals(1, bodies("stays").size, "the account that stays resumes its own transaction")

        // the wipe drops every retry
        down = true
        clock += 5_000
        assertFailsWith<BroadcastException.Network> { b.vote("stays", "ecency", "other", 10000, key) }
        down = false
        b.clear()
        val signings = transport.sent.count { it.method == "condenser_api.get_dynamic_global_properties" }
        b.vote("stays", "ecency", "other", 10000, key)
        assertEquals(signings + 1, transport.sent.count { it.method == "condenser_api.get_dynamic_global_properties" }, "signed anew")
    }

    @Test
    fun identicalVoteAfterAnUncertainSendIsSuccess() = runTest {
        var attempts = 0
        val transport = chain { req ->
            if (attempts++ < 3) awaitCancellation()
            FakeTransport.error(req, -32003, "Assert Exception:Your current vote on this comment is identical to this vote.")
        }
        val b = broadcaster(transport)
        assertFailsWith<BroadcastException.Network> { b.vote("spike-voter", "ecency", "native-golden-vote", 10000, key) }
        clock += 120_000 // the first transaction expired: a new one is signed, and the chain has our vote
        b.vote("spike-voter", "ecency", "native-golden-vote", 10000, key)
        assertEquals(2, transport.broadcasts().map { it.params.toString() }.toSet().size)

        // Without an uncertain earlier send, an identical vote is still reported as such.
        val fresh = chain { FakeTransport.error(it, -32003, "Assert Exception:Your current vote on this comment is identical to this vote.") }
        assertFailsWith<BroadcastException.DuplicateVote> { broadcaster(fresh).vote("spike-voter", "ecency", "native-golden-vote", 10000, key) }
    }

    @Test
    fun commentThatLandedIsNotPostedAgainAfterExpiry() = runTest {
        val transport = chain { awaitCancellation() }
        val b = broadcaster(transport)
        val draft = Comments.reply("bob", "alice", "post", "hi", nowMillis = clock, beneficiaries = listOf(Beneficiary("ecency", 500)))
        assertFailsWith<BroadcastException.Network> { b.comment(draft, key) }
        val sentBefore = transport.broadcasts().size
        clock += 120_000
        contentExists = true // it did land
        b.comment(draft, key)
        assertEquals(sentBefore, transport.broadcasts().size, "nothing re-sent: comment_options would now fail")
        assertEquals(1, transport.sent.count { it.method == "condenser_api.get_content" })
    }

    @Test
    fun commentThatDidNotLandIsResignedWithTheSamePermlink() = runTest {
        var attempts = 0
        val transport = chain { req -> if (attempts++ < 3) awaitCancellation() else FakeTransport.result(req, "null") }
        val b = broadcaster(transport)
        val draft = Comments.reply("bob", "alice", "post", "hi", nowMillis = clock)
        val e = assertFailsWith<BroadcastException.Network> { b.comment(draft, key) }
        clock += 120_000
        e.pending!!.send()
        val ops = transport.broadcasts().map {
            it.params.jsonArray.single().jsonObject.getValue("operations").jsonArray.single().jsonArray[1].jsonObject.getValue("permlink").jsonPrimitive.content
        }
        assertEquals(listOf(draft.permlink), ops.toSet().toList())
        assertEquals(2, transport.broadcasts().map { it.params.toString() }.toSet().size, "re-signed after expiry")
    }

    @Test
    fun aTransferThatMayHaveLandedIsNeverSignedAgainUntilTheChainSaysItDidNot() = runTest {
        val transfer = com.ecency.core.hive.Transfer("spike-voter", "ecency", Asset.parse("1.000 HIVE"), "")
        var down = true
        val transport = chain { req -> if (down) awaitCancellation() else FakeTransport.result(req, "null") }
        val b = broadcaster(transport)
        val first = assertFailsWith<BroadcastException.Network> { b.broadcastIdempotent("t1", listOf(transfer), key) }
        assertTrue(first.pending!!.mayHaveLanded && !first.undecided)
        val firstTx = first.pending!!.signed!!.id
        clock += 120_000 // past its failover deadline: a resend could only say "expired"
        down = false

        // the chain cannot tell yet (unknown, too old, or the lookup failed): nothing new is signed or sent
        for (status in listOf("unknown", "expired_reversible", null, "garbage")) {
            txStatus = status
            val sentBefore = transport.broadcasts().size
            val e = assertFailsWith<BroadcastException.Network> { b.broadcastIdempotent("t1", listOf(transfer), key) }
            assertTrue(e.undecided, "status=$status")
            assertEquals(BroadcastException.UNDECIDED_MESSAGE, e.message)
            assertEquals(sentBefore, transport.broadcasts().size, "status=$status")
            assertEquals(firstTx, e.pending!!.signed!!.id)
        }
        // it is in: success with the earlier id, nothing re-sent
        for (status in listOf("within_mempool", "within_reversible_block", "within_irreversible_block")) {
            txStatus = status
            val p = b.prepare(listOf(transfer), key)
            val e = assertFailsWith<BroadcastException.Network> { down = true; p.send() }.also { down = false }
            clock += 120_000
            val id = e.pending!!.signed!!.id
            val sentBefore = transport.broadcasts().size
            assertEquals(id, p.send().txId, status)
            assertEquals(sentBefore, transport.broadcasts().size, status)
        }
        // the chain says it expired: only now a new transaction is signed and sent
        txStatus = "expired_irreversible"
        val result = b.broadcastIdempotent("t1", listOf(transfer), key)
        assertTrue(result.txId != firstTx)
        assertTrue(firstTx in statusLookups)
    }

    private val transferOp = com.ecency.core.hive.Transfer("spike-voter", "ecency", Asset.parse("1.000 HIVE"), "")

    private fun distinct(transport: FakeTransport) = transport.broadcasts().map { it.params.toString() }.toSet().size

    /** Three timeouts (the send may have landed), then every resend answered "expired". */
    private fun expiringChain(): FakeTransport {
        var attempts = 0
        return chain { req ->
            if (attempts++ < 3) awaitCancellation()
            FakeTransport.error(req, -32003, "Assert Exception:now < trx.expiration: transaction expired")
        }
    }

    @Test
    fun anExpiredResendAsksTheChainBeforeSigningAgain() = runTest {
        // the earlier send is in: success with its id, nothing new signed
        txStatus = "within_irreversible_block"
        var transport = expiringChain()
        var b = broadcaster(transport)
        val first = assertFailsWith<BroadcastException.Network> { b.broadcastIdempotent("t", listOf(transferOp), key) }
        val firstId = first.pending!!.signed!!.id
        clock += 5_000 // still inside the failover window: the same transaction is re-sent and answered "expired"
        assertEquals(firstId, b.broadcastIdempotent("t", listOf(transferOp), key).txId)
        assertEquals(1, distinct(transport), "one transaction only")
        assertEquals(listOf(firstId), statusLookups)

        // the chain cannot tell: nothing new signed
        for (status in listOf("unknown", "expired_reversible")) {
            txStatus = status
            transport = expiringChain()
            b = broadcaster(transport)
            assertFailsWith<BroadcastException.Network> { b.broadcastIdempotent("t", listOf(transferOp), key) }
            clock += 5_000
            val e = assertFailsWith<BroadcastException.Network> { b.broadcastIdempotent("t", listOf(transferOp), key) }
            assertTrue(e.undecided, status)
            assertEquals(1, distinct(transport), status)
        }

        // irreversibly expired: one new transaction
        txStatus = "expired_irreversible"
        transport = expiringChain()
        b = broadcaster(transport)
        assertFailsWith<BroadcastException.Network> { b.broadcastIdempotent("t", listOf(transferOp), key) }
        clock += 5_000
        assertFailsWith<BroadcastException.Rejected> { b.broadcastIdempotent("t", listOf(transferOp), key) }
        assertEquals(2, distinct(transport), "re-signed once, after the chain ruled the first one out")
    }

    @Test
    fun onlyAnIrreversibleExpiryRulesAnEarlierSendOut() = runTest {
        val transport = chain { awaitCancellation() }
        val b = broadcaster(transport)
        assertFailsWith<BroadcastException.Network> { b.broadcastIdempotent("t", listOf(transferOp), key) }
        clock += 120_000
        txStatus = "expired_reversible"
        assertTrue(assertFailsWith<BroadcastException.Network> { b.broadcastIdempotent("t", listOf(transferOp), key) }.undecided)
        assertEquals(1, distinct(transport))
    }

    @Test
    fun anUndecidedActionEndsUnconfirmedOnlyByTheChainsClock() = runTest {
        val transport = chain { awaitCancellation() }
        val b = broadcaster(transport)
        assertFailsWith<BroadcastException.Network> { b.broadcastAction("act-1", listOf(transferOp), key) }
        val expiration = transport.broadcasts().first().params.jsonArray.single().jsonObject.getValue("expiration").jsonPrimitive.content
        txStatus = "expired_reversible"
        // the device clock far ahead: by it the transaction is long expired; the chain says otherwise
        clock += 60_000 + HiveBroadcaster.AGE_OUT_MILLIS * 6
        assertTrue(assertFailsWith<BroadcastException.Network> { b.broadcastAction("act-1", listOf(transferOp), key) }.undecided)
        // the chain's own clock just short of expiration + 10 min: still undecided
        chainTime = ChainTime.fromEpochSeconds(ChainTime.toEpochSeconds(expiration) + HiveBroadcaster.AGE_OUT_MILLIS / 1000 - 1)
        assertTrue(assertFailsWith<BroadcastException.Network> { b.broadcastAction("act-1", listOf(transferOp), key) }.undecided)
        // past it: closed
        chainTime = ChainTime.fromEpochSeconds(ChainTime.toEpochSeconds(expiration) + HiveBroadcaster.AGE_OUT_MILLIS / 1000)
        val end = assertFailsWith<BroadcastException.Unconfirmed> { b.broadcastAction("act-1", listOf(transferOp), key) }
        assertEquals(BroadcastException.UNCONFIRMED_MESSAGE, end.message)
        val lookups = statusLookups.size
        // the same action again: refused at once, nothing asked or sent
        assertFailsWith<BroadcastException.Unconfirmed> { b.broadcastAction("act-1", listOf(transferOp), key) }
        assertEquals(lookups, statusLookups.size)
        assertEquals(1, distinct(transport))
        assertEquals(false, com.ecency.core.app.AppError.from(end).retryable)
    }

    @Test
    fun anUnknownStatusIsNotExpiredYetAndNeedsNoChainHead() = runTest {
        val transport = chain { awaitCancellation() }
        val b = broadcaster(transport)
        assertFailsWith<BroadcastException.Network> { b.broadcastAction("a", listOf(transferOp), key) }
        clock += 120_000
        // "unknown" with the expiration given: by the chain's clock the transaction has not expired, whatever the head says
        txStatus = "unknown"
        chainTime = "2030-01-01T00:00:00"
        val heads = transport.methods().count { it == "condenser_api.get_dynamic_global_properties" }
        assertTrue(assertFailsWith<BroadcastException.Network> { b.broadcastAction("a", listOf(transferOp), key) }.undecided)
        assertEquals(heads, transport.methods().count { it == "condenser_api.get_dynamic_global_properties" }, "no head read")
        // an answer that is not conclusive about the clock still reads it (and here closes the send)
        txStatus = "expired_reversible"
        assertFailsWith<BroadcastException.Unconfirmed> { b.broadcastAction("a", listOf(transferOp), key) }
        assertEquals(heads + 1, transport.methods().count { it == "condenser_api.get_dynamic_global_properties" })
    }

    @Test
    fun aPendingKnowsUntilWhenItMayBeResent() = runTest {
        val transport = chain { awaitCancellation() }
        val b = broadcaster(transport)
        val e = assertFailsWith<BroadcastException.Network> { b.broadcastAction("a", listOf(transferOp), key) }
        val expiration = transport.broadcasts().first().params.jsonArray.single().jsonObject.getValue("expiration").jsonPrimitive.content
        assertEquals(ChainTime.toEpochSeconds(expiration) * 1000 - 2_000 - 5_000, e.pending!!.resendDeadline)
        assertNull(b.prepare(listOf(transferOp), key).resendDeadline)
    }

    @Test
    fun tooOldClosesAtOnceAndAChainHeadFailureNever() = runTest {
        val transport = chain { awaitCancellation() }
        val b = broadcaster(transport)
        assertFailsWith<BroadcastException.Network> { b.broadcastAction("a", listOf(transferOp), key) }
        clock += 120_000
        txStatus = null // the lookup fails, and so does the chain head (malformed time): undecided
        chainTime = "not a time"
        assertTrue(assertFailsWith<BroadcastException.Network> { b.broadcastAction("a", listOf(transferOp), key) }.undecided)
        txStatus = "too_old"
        assertFailsWith<BroadcastException.Unconfirmed> { b.broadcastAction("a", listOf(transferOp), key) }
        assertEquals(1, distinct(transport))
    }

    @Test
    fun aNewActionWithTheSameOperationsIsItsOwnTransaction() = runTest {
        var down = true
        val transport = chain { req -> if (down) awaitCancellation() else FakeTransport.result(req, "null") }
        val b = broadcaster(transport)
        val first = assertFailsWith<BroadcastException.Network> { b.broadcastAction("act-1", listOf(transferOp), key) }
        down = false
        clock += 1_000
        txStatus = "within_irreversible_block"
        // a second, deliberate transfer of the same amount: sent, not answered with the first one's id
        val second = b.broadcastAction("act-2", listOf(transferOp), key)
        assertTrue(second.txId != first.pending!!.signed!!.id)
        assertEquals(2, distinct(transport))
        // the retry of the first action resumes it: same transaction
        assertEquals(first.pending!!.signed!!.id, b.broadcastAction("act-1", listOf(transferOp), key).txId)
        assertEquals(2, distinct(transport))
    }

    @Test
    fun anOperationsKeyLongPastItsExpiryIsANewActionOnlyForRepeatSafeOperations() = runTest {
        val vote = com.ecency.core.hive.Vote("spike-voter", "ecency", "p", 10000)
        var down = true
        val transport = chain { req -> if (down) awaitCancellation() else FakeTransport.result(req, "null") }
        val b = broadcaster(transport)
        val first = assertFailsWith<BroadcastException.Network> { b.broadcastIdempotent("ops", listOf(vote), key) }
        down = false
        txStatus = "within_irreversible_block"
        clock += 60_000 + HiveBroadcaster.AGE_OUT_MILLIS
        val later = b.broadcastIdempotent("ops", listOf(vote), key)
        assertTrue(later.txId != first.pending!!.signed!!.id, "not answered with the old id")
        assertTrue(statusLookups.isEmpty(), "the old pending was dropped, not looked up")

        // a transfer keyed by its operations stays the retry it may be: asked about, never signed again blindly
        down = true
        val t = assertFailsWith<BroadcastException.Network> { b.broadcastIdempotent("ops-t", listOf(transferOp), key) }
        down = false
        clock += 60_000 + HiveBroadcaster.AGE_OUT_MILLIS
        assertEquals(t.pending!!.signed!!.id, b.broadcastIdempotent("ops-t", listOf(transferOp), key).txId)
        assertTrue(t.pending!!.signed!!.id in statusLookups)
    }

    @Test
    fun aLaggingNodesRejectionAfterAnUncertainSendIsNoVerdict() = runTest {
        // node a takes the transaction but times out; node b rejects it with a TaPoS assert (a few blocks behind)
        var firstRound = true
        val transport = chain { req ->
            when {
                req.node == NODES[0] -> awaitCancellation()
                firstRound -> FakeTransport.error(req, -32003, "Assert Exception:trx.ref_block_prefix == tapos_block_summary.block_id._hash[1]: ")
                else -> FakeTransport.result(req, "null")
            }
        }
        val b = broadcaster(transport)
        txStatus = "within_irreversible_block"
        val first = assertFailsWith<BroadcastException.Network> { b.broadcastAction("act-x", listOf(transferOp), key) }
        assertTrue(first.pending!!.mayHaveLanded, "uncertain, not rejected")
        firstRound = false
        clock += 5_000
        b.broadcastAction("act-x", listOf(transferOp), key)
        assertEquals(1, distinct(transport), "the same transaction, never a second signature")
        // without an uncertain attempt before it, the same answer is the chain's verdict
        val fresh = chain { FakeTransport.error(it, -32003, "Assert Exception:trx.ref_block_prefix == tapos_block_summary.block_id._hash[1]: ") }
        assertFailsWith<BroadcastException.Rejected> { broadcaster(fresh).broadcastAction("act-z", listOf(transferOp), key) }
        // and a later "Duplicate transaction" is still success
        val dup = chain { req -> if (req.node == NODES[0]) awaitCancellation() else FakeTransport.error(req, -32003, "Duplicate transaction check failed") }
        broadcaster(dup).broadcastAction("act-d", listOf(transferOp), key)
    }

    @Test
    fun anyRejectionOfAResendAsksTheChainFirst() = runTest {
        for ((status, expect) in listOf("within_irreversible_block" to "ok", "unknown" to "undecided", "expired_irreversible" to "rejected")) {
            txStatus = status
            var attempts = 0
            val transport = chain { req ->
                if (attempts++ < 3) awaitCancellation()
                FakeTransport.error(req, -32003, "Assert Exception:Account does not have sufficient funds for transfer.")
            }
            val b = broadcaster(transport)
            assertFailsWith<BroadcastException.Network> { b.broadcastAction("act-$status", listOf(transferOp), key) }
            clock += 5_000
            val result = runCatching { b.broadcastAction("act-$status", listOf(transferOp), key) }
            when (expect) {
                "ok" -> assertTrue(result.isSuccess, "$status: ${result.exceptionOrNull()}")
                "undecided" -> assertTrue((result.exceptionOrNull() as? BroadcastException.Network)?.undecided == true, "$status: ${result.exceptionOrNull()}")
                else -> assertIs<BroadcastException.Rejected>(result.exceptionOrNull(), status)
            }
            if (expect == "undecided") {
                // the pending stays: the next retry asks again and still signs nothing new
                runCatching { b.broadcastAction("act-$status", listOf(transferOp), key) }
            }
            assertEquals(1, distinct(transport), "$status: never a second signature")
        }
    }

    @Test
    fun invalidOperationsAndOversizedTransactionsFailBeforeSending() = runTest {
        val transport = chain()
        val b = broadcaster(transport)
        assertFailsWith<BroadcastException.InvalidOperation> { b.vote("spike-voter", "ecency", "p", 10001, key) }
        assertFailsWith<BroadcastException.InvalidOperation> { b.broadcast(emptyList(), key) }
        assertEquals(0, transport.sent.size)
        val huge = Comment("alice", "post", "bob", "re-big", "", "x".repeat(70_000), "{}")
        val e = assertFailsWith<BroadcastException.TooLarge> { b.broadcast(listOf(huge), key) }
        assertTrue(e.size > HiveBroadcaster.MAX_TRANSACTION_SIZE)
        assertEquals(0, transport.broadcasts().size)
    }

    @Test
    fun chainHeadFailuresAreBroadcastErrors() = runTest {
        val down = FakeTransport { HttpResult(503, "") }
        assertIs<BroadcastException.Network>(
            assertFailsWith<BroadcastException> { broadcaster(down).vote("spike-voter", "ecency", "native-golden-vote", 10000, key) },
        )
        val malformed = FakeTransport { FakeTransport.result(it, """{"head_block_number":"x"}""") }
        assertIs<BroadcastException.Network>(
            assertFailsWith<BroadcastException> { broadcaster(malformed).reference() },
        )
    }

    @Test
    fun chainTimeRoundTrips() {
        listOf("1970-01-01T00:00:00", "2000-02-29T23:59:59", "2026-10-06T12:00:00", "2100-03-01T00:00:01").forEach {
            assertEquals(it, ChainTime.fromEpochSeconds(ChainTime.toEpochSeconds(it)))
        }
    }

    @Test
    fun aGatewayErrorAfterTheNodeTookATransferIsLookedUpNeverSentAgain() = runTest {
        for (status in listOf(502, 504)) {
            clock = now
            statusLookups.clear()
            val transfer = com.ecency.core.hive.Transfer("spike-voter", "ecency", Asset.parse("1.000 HIVE"), "")
            // hived took it, the proxy in front answered with a gateway error
            val transport = chain { HttpResult(status, "<html>$status Gateway</html>") }
            val b = broadcaster(transport)
            val first = assertFailsWith<BroadcastException.Network> { b.broadcastAction("t-$status", listOf(transfer), key) }
            assertTrue(first.pending!!.mayHaveLanded, "HTTP $status: the transaction may be on chain")
            val firstTx = first.pending!!.signed!!.id
            val sentBefore = transport.broadcasts().size
            clock += 120_000 // past the failover deadline: a retry may not sign anew without asking the chain
            txStatus = "within_irreversible_block"
            val result = b.broadcastAction("t-$status", listOf(transfer), key)
            assertEquals(firstTx, result.txId, "HTTP $status")
            assertEquals(sentBefore, transport.broadcasts().size, "HTTP $status: nothing sent a second time")
            assertEquals(listOf(firstTx), statusLookups.toList(), "HTTP $status: looked up on chain")
        }
    }

    @Test
    fun aConnectFailureBeforeSendingAllowsAPlainRetry() = runTest {
        val transfer = com.ecency.core.hive.Transfer("spike-voter", "ecency", Asset.parse("1.000 HIVE"), "")
        var down = true
        val transport = chain { req ->
            if (down) throw IllegalStateException("Failed to connect to ${req.node} (Connection refused)")
            FakeTransport.result(req, "null")
        }
        val b = broadcaster(transport)
        val first = assertFailsWith<BroadcastException.Network> { b.broadcastAction("t1", listOf(transfer), key) }
        assertTrue(!first.pending!!.mayHaveLanded, "nothing reached a node")
        val firstTx = first.pending!!.signed!!.id
        clock += 120_000
        down = false
        val result = b.broadcastAction("t1", listOf(transfer), key)
        assertTrue(result.txId != firstTx, "signed anew")
        assertTrue(statusLookups.isEmpty(), "no chain lookup needed")
    }
}
