package com.ecency.core.api

import com.ecency.core.api.model.Account
import com.ecency.core.api.model.ModerationReason
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.model.PostMetadata
import com.ecency.core.api.model.Profile
import com.ecency.core.api.model.Reputation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelParsingTest {
    private fun meta(json: String) = PostMetadata.parse(Json.parseToJsonElement(json))

    @Test
    fun parsesAFullBridgeEntry() {
        val post = assertNotNull(
            Post.fromJson(Json.parseToJsonElement(Fixtures.entry("alice", "hello", votes = listOf("bob", "carol"), pending = "1.250 HBD", pinned = true))),
        )
        assertEquals(PostKey("alice", "hello"), post.key)
        assertEquals("Title of hello", post.title)
        assertEquals(listOf("hive-100001", "test"), post.metadata.tags)
        assertEquals("ecency/4.0.0-mobile", post.metadata.app)
        assertEquals(listOf("bob", "carol"), post.activeVotes.map { it.voter })
        assertEquals(1.25, post.totalPayout)
        assertEquals("hive-100001", post.community)
        assertEquals("Test Community", post.communityTitle)
        assertTrue(post.stats.isPinned)
        assertEquals(2, post.stats.totalVotes)
        assertEquals(null, post.authorTitle) // blank reads as absent
    }

    @Test
    fun missingFieldsTakeTheSdkDefaults() {
        val post = assertNotNull(Post.fromJson(Json.parseToJsonElement("""{"author":"alice","permlink":"p","active_votes":null,"stats":null}""")))
        assertEquals("", post.title)
        assertEquals("0.000 HBD", post.pendingPayoutValue)
        assertEquals("1000000.000 HBD", post.maxAcceptedPayout)
        assertEquals(emptyList(), post.activeVotes)
        assertEquals(0, post.children)
        assertEquals(PostMetadata(), post.metadata)
    }

    @Test
    fun entriesWithoutIdentityAreSkipped() {
        assertNull(Post.fromJson(Json.parseToJsonElement("""{"author":"alice"}""")))
        assertNull(Post.fromJson(Json.parseToJsonElement("""{"author":"","permlink":"p"}""")))
        assertNull(Post.fromJson(Json.parseToJsonElement("""[1,2]""")))
        assertNull(Post.fromJson(null))
    }

    @Test
    fun numbersAsStringsAndOddShapesAreTolerated() {
        val post = assertNotNull(
            Post.fromJson(
                Json.parseToJsonElement(
                    """{"author":"alice","permlink":"p","children":"4","depth":"2","net_rshares":"-123456789012",
                    "author_reputation":"61.5","is_paidout":"true","percent_hbd":10000.0,
                    "active_votes":[{"voter":"bob","rshares":"-77"},{"voter":""},"garbage",{"rshares":5}],
                    "reblogs":["bob","carol","dave"],"replies":["bob/re-1",{"author":"carol","permlink":"re-2"},"nonsense",7],
                    "pending_payout_value":{"amount":"2500","precision":3,"nai":"@@000000013"},
                    "author_payout_value":"not an asset","beneficiaries":[{"account":"ecency","weight":"500"},{"weight":1}]}""",
                ),
            ),
        )
        assertEquals(4, post.children)
        assertEquals(2, post.depth)
        assertEquals(-123456789012L, post.netRshares)
        assertTrue(post.isDownvoted)
        assertEquals(61, post.authorReputation) // floored like the app
        assertTrue(post.isPaidout)
        assertEquals(10000, post.percentHbd)
        assertEquals(listOf("bob" to -77L), post.activeVotes.map { it.voter to it.rshares })
        assertEquals(3, post.reblogs)
        assertEquals(listOf(PostKey("bob", "re-1"), PostKey("carol", "re-2")), post.replies)
        assertEquals("2.500 HBD", post.pendingPayoutValue)
        assertEquals("0.000 HBD", post.authorPayoutValue)
        assertEquals(2.5, post.totalPayout)
        assertEquals(listOf("ecency" to 500), post.beneficiaries.map { it.account to it.weight })
    }

    @Test
    fun metadataAsAJsonStringOrDoubleEncoded() {
        val once = """{"json_metadata":"{\"tags\":[\"one\",\"two\"],\"app\":\"peakd/1.0\"}"}"""
        assertEquals(listOf("one", "two"), PostMetadata.parse(Json.parseToJsonElement(once).let { (it as kotlinx.serialization.json.JsonObject)["json_metadata"] }).tags)
        val twice = JsonPrimitive(JsonPrimitive("""{"tags":["x"]}""").toString())
        assertEquals(listOf("x"), PostMetadata.parse(twice).tags)
    }

    @Test
    fun metadataThatIsNotAnObjectReadsAsEmpty() {
        listOf("\"\"", "\"not json\"", "\"{broken\"", "null", "42", "[\"a\"]", "\"[1,2]\"", "true").forEach {
            assertEquals(PostMetadata(), PostMetadata.parse(Json.parseToJsonElement(it)), it)
        }
    }

    @Test
    fun tagsInEveryShape() {
        assertEquals(listOf("a", "b"), meta("""{"tags":"a b"}""").tags)
        assertEquals(listOf("a", "b"), meta("""{"tags":"a,b"}""").tags)
        assertEquals(listOf("a", "b"), meta("""{"tags":"[\"a\",\"b\"]"}""").tags)
        assertEquals(listOf("a", "c"), meta("""{"tags":["a",null,1,{"x":1},"c","  ","a"]}""").tags)
        assertEquals(emptyList(), meta("""{"tags":null}""").tags)
        assertEquals(emptyList(), meta("""{"tags":{"0":"a"}}""").tags)
    }

    @Test
    fun appImagesAndPinnedReply() {
        val m = meta(
            """{"app":{"name":"leothreads","version":"0.3"},"image":"https://img.example/a.png",
            "links":["https://a.example"],"pinned_reply":"bob/re-hello","original_author":"carol","original_permlink":"orig",
            "description":"  ","format":null}""",
        )
        assertEquals("leothreads/0.3", m.app)
        assertEquals(listOf("https://img.example/a.png"), m.images)
        assertEquals(listOf("https://a.example"), m.links)
        assertEquals(PostKey("bob", "re-hello"), m.pinnedReply)
        assertEquals("carol", m.originalAuthor)
        assertNull(m.description)
        assertNull(m.format)
        assertNull(meta("""{"pinned_reply":"no-slash"}""").pinnedReply)
        assertEquals(listOf("https://img.example/b.jpg"), meta("""{"image":["https://img.example/b.jpg","data:x",7]}""").images)
    }

    @Test
    fun crossPostDetection() {
        val post = assertNotNull(
            Post.fromJson(
                Json.parseToJsonElement(
                    Fixtures.entry(
                        "alice",
                        "cp",
                        metadata = """{"tags":["cross-post","x"],"original_author":"bob","original_permlink":"orig"}""",
                    ),
                ),
            ),
        )
        assertTrue(post.isCrossPost)
        assertTrue(!Post.fromJson(Json.parseToJsonElement(Fixtures.entry("alice", "plain")))!!.isCrossPost)
    }

    @Test
    fun accountsAndProfiles() {
        val account = assertNotNull(
            Account.fromJson(
                Json.parseToJsonElement(
                    """{"name":"alice","reputation":"95832978796820","post_count":12,"balance":"1.000 HIVE",
                    "hbd_balance":"2.000 HBD","vesting_shares":"3.000000 VESTS","memo_key":"STM1",
                    "posting":{"weight_threshold":1,"account_auths":[],"key_auths":[["STM5posting",1]]},
                    "posting_json_metadata":"","json_metadata":"{\"profile\":{\"name\":\"Alice\",\"profile_image\":\"https://img.example/a.png\",\"about\":\"\"}}"}""",
                ),
            ),
        )
        assertEquals("Alice", account.profile.displayName)
        assertEquals("https://img.example/a.png", account.profile.profileImage)
        assertNull(account.profile.about)
        assertEquals(listOf("STM5posting"), account.postingPublicKeys)
        assertEquals(69, account.reputation) // raw 9.58e13 scales to 69.83, floored
        assertEquals(25, Reputation.display(0.0))
        assertEquals(-2, Reputation.display(-1e12)) // 25 - 9 * (log10(1e12) - 9)
        assertEquals(64, Reputation.display(64.99))

        val profile = assertNotNull(
            Profile.fromJson(
                Json.parseToJsonElement(
                    """{"name":"alice","reputation":64.2,"post_count":"7","stats":{"followers":3,"following":"2"},
                    "metadata":{"profile":"not an object"}}""",
                ),
            ),
        )
        assertEquals(3, profile.followers)
        assertEquals(2, profile.following)
        assertEquals(7, profile.postCount)
        assertNull(profile.profile.displayName)
    }

    @Test
    fun deeplyNestedMetadataIsIgnoredNotACrash() {
        val deepString = JsonPrimitive("[".repeat(5_000))
        assertEquals(PostMetadata(), PostMetadata.parse(deepString))
        val balanced = JsonPrimitive("{\"tags\":" + "[".repeat(5_000) + "]".repeat(5_000) + "}")
        assertEquals(PostMetadata(), PostMetadata.parse(balanced))
        val entry = """{"author":"alice","permlink":"p","json_metadata":${Json.encodeToString("[".repeat(100_000))},
            "posting_json_metadata":"x"}"""
        assertEquals(emptyList(), Post.fromJson(Json.parseToJsonElement(entry))!!.metadata.tags)
        // Brackets inside strings do not count towards the depth.
        assertEquals(listOf("[[[["), meta("""{"tags":["[[[["]}""").tags)
    }

    @Test
    fun moderationReasonFollowsTheSdkRules() {
        fun post(extra: String) = Post.fromJson(Json.parseToJsonElement("""{"author":"alice","permlink":"p",$extra}"""))!!
        assertEquals(ModerationReason.MOD_MUTED, post(""""stats":{"gray":true},"author_reputation":70""").moderationReason)
        assertTrue(post(""""stats":{"hide":true}""").isMuted)
        assertEquals(
            ModerationReason.DOWNVOTED,
            post(""""net_rshares":-20000000000,"stats":{"total_votes":5},"author_reputation":70""").moderationReason,
        )
        assertNull(post(""""net_rshares":-20000000000,"stats":{"total_votes":4},"author_reputation":70""").moderationReason)
        // Without stats, active_votes count instead (the SDK's countVotes).
        val voters = (1..5).joinToString(",") { """{"voter":"v$it","rshares":-1}""" }
        assertEquals(ModerationReason.DOWNVOTED, post(""""net_rshares":-20000000000,"active_votes":[$voters]""").moderationReason)
        assertEquals(
            ModerationReason.LOW_TRUST,
            post(""""author_reputation":20,"body":"buy now at https://shop.example/deal."""").moderationReason,
        )
        assertNull(post(""""author_reputation":20,"body":"see https://ecency.com/@bob and https://img.example/a.png"""").moderationReason)
        assertNull(post(""""body":"https://shop.example"""").moderationReason, "unknown reputation is not low trust")
        assertNull(post(""""author_reputation":70,"body":"https://shop.example"""").moderationReason)
        assertFalse(post(""""author_reputation":70""").isMuted)
    }

    @Test
    fun numericPayoutFallbackAndDeclinedPayout() {
        fun post(extra: String) = Post.fromJson(Json.parseToJsonElement("""{"author":"alice","permlink":"p",$extra}"""))!!
        assertEquals(3.25, post(""""payout":3.25""").totalPayout, "numeric-only shape")
        assertEquals(1.5, post(""""total_payout":1.5""").totalPayout)
        // With max_accepted_payout the asset strings win, as in the app.
        assertEquals(
            0.5,
            post(""""payout":3.25,"max_accepted_payout":"1000000.000 HBD","pending_payout_value":"0.500 HBD"""").totalPayout,
        )
        assertTrue(post(""""max_accepted_payout":"0.000 HBD"""").isDeclinedPayout)
        assertFalse(post(""""max_accepted_payout":"1000000.000 HBD"""").isDeclinedPayout)
    }
}
