package com.ecency.core.api.waves

import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.hive.PrivateKey
import com.ecency.core.hive.Transaction
import com.ecency.core.hive.toHex
import com.ecency.core.render.blocks.SafeTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Parity with @ecency/sdk and the RN composer helpers (native/tools/golden/generate-waves.mjs). */
class WavesGoldenTest {
    private val host = "https://ecency.com"

    // the generator's inputs, in its order
    private val feedParams = listOf(
        WavesFeedParams() to null,
        WavesFeedParams(observer = " Spike-Alice ") to null,
        WavesFeedParams(following = "Spike-Alice", observer = "spike-alice") to "MjAyNi0xMC0xMlQxNjo0MDo1NyswMDowMHw2NDk0NjY=",
        WavesFeedParams(tag = " hive gaming&x=1 ", observer = "spike-alice") to null,
        WavesFeedParams(tag = "пчела🐝") to "a b+c/d==",
        WavesFeedParams(containers = listOf("ecency.waves", "peak.snaps"), observer = "spike-alice") to null,
        WavesFeedParams(author = " Spike-Bob ") to null,
        WavesFeedParams(limit = 5, tag = "") to null,
    )

    @Test
    fun feedUrlsMatchTheSdk() {
        assertEquals(WavesGoldens.feedUrls.size, feedParams.size)
        WavesGoldens.feedUrls.forEachIndexed { i, case ->
            assertEquals(case.url, WavesApi.feedUrl(host, feedParams[i].first, feedParams[i].second), case.name)
        }
    }

    @Test
    fun shortsUrlsMatchTheSdk() {
        val params = listOf(
            WavesFeedParams() to null,
            WavesFeedParams(observer = "Spike-Alice") to "abc==",
            WavesFeedParams(containers = listOf("ecency.waves"), tag = "art", author = "Spike-Bob", observer = "spike-alice", limit = 7) to null,
        )
        WavesGoldens.shortsUrls.forEachIndexed { i, case ->
            assertEquals(case.url, WavesApi.shortsUrl(host, params[i].first, params[i].second), case.name)
        }
    }

    @Test
    fun otherUrlsMatchTheSdk() {
        assertEquals(WavesGoldens.PROMOTED_URL, WavesApi.promotedUrl(host))
        assertEquals(WavesGoldens.TRENDING_TAGS_URL, WavesApi.trendingTagsUrl(host, null, 24))
        assertEquals(WavesGoldens.TRENDING_TAGS_HOST_URL, WavesApi.trendingTagsUrl(host, " ecency.waves ", 12))
    }

    @Test
    fun rowsAreNormalizedAsTheSdkDoes() {
        val rows = (Json.parseToJsonElement(WavesGoldens.ROWS_JSON) as JsonArray).mapNotNull(WaveEntry::fromJson)
        assertEquals(WavesGoldens.normalizedRows.size, rows.size)
        WavesGoldens.normalizedRows.forEachIndexed { i, want ->
            val got = rows[i]
            assertEquals(want.author, got.post.author)
            assertEquals(want.permlink, got.post.permlink)
            assertEquals(want.created, got.rawCreated)
            assertEquals(want.host, got.host)
            assertEquals(want.cursor, got.cursor)
            assertEquals(want.maxAcceptedPayout, got.post.maxAcceptedPayout)
            assertEquals(want.pending, got.post.pendingPayoutValue)
            assertEquals(want.authorPayout, got.post.authorPayoutValue)
            assertEquals(want.curatorPayout, got.post.curatorPayoutValue)
        }
        // what the app adds on top: chain time, the host as the parent, a comment's depth
        assertEquals("2026-10-12T16:40:57", rows[0].post.created)
        assertEquals("ecency.waves", rows[0].post.parentAuthor)
        assertEquals(1, rows[0].post.depth)
        assertEquals(3, rows[0].netVotes)
        assertEquals(1, rows[0].tipCount)
        assertEquals(false, rows[0].hasActiveVotes)
        assertEquals(0.12, rows[0].post.totalPayout)
        assertNull(rows[2].post.parentAuthor)
    }

    @Test
    fun timestampsBecomeChainTime() {
        assertEquals("2026-10-12T16:40:57", WaveEntry.chainTime("2026-10-12T16:40:57+00:00"))
        assertEquals("2026-10-12T16:40:57", WaveEntry.chainTime("2026-10-12T16:40:57Z"))
        assertEquals("2026-10-12T16:40:57", WaveEntry.chainTime("2026-10-12T16:40:57"))
        assertEquals("2026-10-12T14:40:57", WaveEntry.chainTime("2026-10-12T16:40:57.123+02:00"))
        assertEquals("2026-10-13T00:10:57", WaveEntry.chainTime("2026-10-12T16:40:57-0730"))
        assertEquals("", WaveEntry.chainTime("yesterday"))
    }

    @Test
    fun pagingRuleMatchesTheSdk() {
        WavesGoldens.paging.forEach { assertEquals(it.next, WavesApi.nextCursor(it.limit, it.cursors), it.name) }
    }

    @Test
    fun publishedWavesAreByteIdenticalToRnAndTheSdk() {
        val key = PrivateKey.fromWif(GoldenVectors.WIF)
        assertEquals(WavesGoldens.PUBLIC_KEY, key.publicKey().toString())
        WavesGoldens.publish.forEach { c ->
            val body = WavePublish.body(c.text, c.image, c.video)
            assertEquals(c.body, body, c.name)
            assertEquals(c.permlink, WavePublish.permlink(c.author, c.containerAuthor, c.containerPermlink, body), c.name)
            // the links the app would read a title for are exactly the ones RN did
            assertEquals(c.linked.keys, WavePublish.postLinks(body).map { it.url }.toSet(), c.name)
            val draft = WavePublish.draft(c.author, c.containerAuthor, c.containerPermlink, c.containerTags, body, c.linked, c.ratios, WavesGoldens.APP_VERSION, videoThumbs = listOfNotNull(c.videoThumb))
            assertEquals(c.permlink, draft.permlink, c.name)
            assertEquals(c.jsonMetadata, (draft.operations[0] as com.ecency.core.hive.Comment).jsonMetadata, c.name)
            assertEquals(c.ops, JsonArray(draft.operations.map(OperationJson::encode)).toString(), c.name)
            val tx = Transaction(WavesGoldens.REF_BLOCK_NUM, WavesGoldens.REF_BLOCK_PREFIX, WavesGoldens.EXPIRATION, draft.operations)
            assertEquals(c.txId, tx.id(), c.name)
            assertEquals(c.digest, tx.digest().toHex(), c.name)
            assertEquals(c.signature, tx.sign(key).toHex(), c.name)
            // RN linkVideoToHive after the broadcast: the video's permlink, or no call
            val linked = c.link?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }
            assertEquals((linked?.get("permlink") as? kotlinx.serialization.json.JsonPrimitive)?.content, WavePublish.videoLinkPermlink(body), c.name)
            if (linked != null) {
                assertEquals(linked["hive_tags"].toString(), JsonArray(WavePublish.tags(c.containerTags, body).map { kotlinx.serialization.json.JsonPrimitive(it) }).toString(), c.name)
            }
        }
    }

    @Test
    fun theVideoLinkedAfterAPublishIsTheOneRnFinds() {
        WavesGoldens.videoLinks.forEach { (body, permlink) -> assertEquals(permlink, WavePublish.videoLinkPermlink(body), body) }
    }

    @Test
    fun composerHelpersMatchRnAndTheSdk() {
        WavesGoldens.hashTags.forEach { (text, tags) -> assertEquals(tags, WavePublish.hashTags(text), text) }
        WavesGoldens.quest.forEach {
            assertEquals(it.length, WavePublish.questContentLength(it.body), it.body)
            assertEquals(it.earns, WavePublish.earnsQuestCredit(it.body), it.body)
        }
        assertEquals(WavesGoldens.QUEST_MIN_CONTENT_LENGTH, WavePublish.QUEST_MIN_CONTENT_LENGTH)
        WavesGoldens.threeSpeakEmbeds.forEach { (text, has) -> assertEquals(has, WavePublish.hasThreeSpeakEmbed(text), text) }
        WavesGoldens.normalizedTags.forEach { (raw, tag) -> assertEquals(tag, Waves.normalizeTag(raw), raw) }
    }

    @Test
    fun composeLinksMatchRn() {
        WavesGoldens.compose.forEach {
            val text = Waves.composeText(it.url)
            if (it.matches) assertEquals(it.text, text, it.url) else assertNull(text, it.url)
        }
    }

    @Test
    fun theWebPlayerFallbackIsAnAllowedEmbedAndStreamsMustBeHttps() {
        assertNotNull(SafeTarget.embed(WavesApi.threeSpeakWatchUrl("spike-alice", "abcd1234")))
        assertEquals("https://play.3speak.tv/api/embed?v=spike-alice/a%20b", WavesApi.threeSpeakEmbedApi("spike-alice", "a b"))
        assertEquals("https://play.3speak.tv/hls?u=x", WavesApi.safeStream("https://play.3speak.tv/hls?u=x"))
        assertNull(WavesApi.safeStream("http://play.3speak.tv/hls?u=x"))
        assertNull(WavesApi.safeStream("https://user:pw@play.3speak.tv/hls"))
        assertNull(WavesApi.safeStream("file:///etc/hosts"))
    }

    @Test
    fun hostsAndSourcesAreRns() {
        assertEquals(listOf("hive.flow", "ecency.waves"), Waves.HOSTS)
        assertEquals(true, Waves.isWavesHost("peak.snaps"))
        assertEquals(false, Waves.isWavesHost("ecency"))
        assertEquals(false, Waves.isWavesHost(null))
        assertEquals("Threads", Waves.sourceLabel("leothreads"))
        assertEquals("someone", Waves.sourceLabel("someone"))
        assertEquals(listOf("Shorts", "Waves", "Threads", "Snaps", "Moments", "Speaks"), Waves.SOURCE_OPTIONS.map { it.label })
    }
}
