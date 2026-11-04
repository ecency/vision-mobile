package com.ecency.core.editor

import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.model.JsValue
import com.ecency.core.auth.HiveSignerMessages
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.hive.PrivateKey
import com.ecency.core.hive.Transaction
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor's ported logic against RN's own sources, speakingurl and the SDK (EditorGoldenVectors, generated offline). */
class EditorGoldenTest {
    private val key = PrivateKey.fromWif(GoldenVectors.WIF)
    private val v = EditorGoldenVectors

    @Test
    fun slugsAndPermlinksMatchSpeakingurlAndRn() {
        assertTrue(v.slugs.size > 300)
        v.slugs.forEach { s ->
            assertEquals(s.slug, Slug.getSlug(s.title), "slug of ${s.title}")
            assertEquals(s.permlink, Slug.generatePermlink(s.title, false) { v.RANDOM }, "permlink of ${s.title}")
            assertEquals(s.randomPermlink, Slug.generatePermlink(s.title, true) { v.RANDOM }, "random permlink of ${s.title}")
        }
    }

    @Test
    fun toolbarFormatsWriteWhatRnWrites() {
        assertTrue(v.formats.size > 600)
        v.formats.forEachIndexed { i, f -> assertEquals(f.expected, f.run(), "${f.name} #$i") }
        v.words.forEach { (text, index, word) -> assertEquals(word, EditorFormats.wordAt(text, index), "word at $index of $text") }
        v.links.forEach { assertEquals(it.expected, EditorFormats.linkSnippet(it.label, it.url, it.type)) }
        v.webLinks.forEach { (url, valid) -> assertEquals(valid, EditorFormats.isWebLink(url), url) }
    }

    @Test
    fun mediaInsertsAndPlaceholderSweepsMatchRn() {
        assertTrue(v.media.size > 600)
        v.media.forEachIndexed { i, m ->
            val actual = EditorFormats.media(m.body, m.selection, m.items, m.otherPending)
            // RN skips the write only when nothing changed; an unchanged result with the same selection is null here
            val expected = m.expected?.takeUnless { it.text == m.body && it.selection == m.selection }
            assertEquals(expected, actual, "media #$i ${m.body} ${m.selection} ${m.items}")
        }
        v.sweeps.forEach { s ->
            assertEquals(s.text to s.newCaret, EditorFormats.sweepPlaceholders(s.body, s.caret), "sweep ${s.body}")
            assertEquals(s.names, EditorFormats.placeholderNames(s.body))
        }
    }

    @Test
    fun urlExtractionMatchesRn() {
        v.urls.forEach { u ->
            assertEquals(u.urls, EditorMeta.extractUrls(u.body), u.body)
            assertEquals(u.images, EditorMeta.extractImageUrls(u.body), u.body)
            assertEquals(u.threeSpeak, EditorMeta.hasThreeSpeakEmbed(u.body), u.body)
            assertEquals(u.words, EditorMeta.wordsCount(u.body), u.body)
        }
    }

    private suspend fun linked(author: String, permlink: String): LinkedPost? {
        val k = "$author/$permlink"
        if (!v.posts.containsKey(k)) throw IllegalStateException("fetch failed")
        return v.posts[k]
    }

    private fun ratio(url: String): Double? = v.sizes[url]?.let { it.first.toDouble() / it.second }

    @Test
    fun jsonMetadataIsByteIdenticalToRn() = runTest {
        v.metas.forEach { m ->
            val meta = EditorMeta.extractMetadata(m.body, m.thumbUrl, m.videoThumbUrls, m.fetchRatios, m.postType, m.contentType, m.poll, ::linked) { ratio(it) }
            assertEquals(m.json, EditorMeta.stringify(EditorMeta.makeJsonMetadata(meta, m.tags, v.APP_VERSION)), m.body)
        }
    }

    @Test
    fun editMetadataMergesOverThePostsOwnLikeRn() = runTest {
        v.updates.forEach { u ->
            val old = JsValue.parse(u.oldJson) as JsValue.Obj
            val type = (old["type"] as? JsValue.Str)?.value
            val content = (old["content_type"] as? JsValue.Str)?.value
            val meta = EditorMeta.extractMetadata(u.body, u.thumbUrl, fetchRatios = true, postType = type, contentType = content, linkedPost = ::linked) { ratio(it) }
            assertEquals(u.json, EditorMeta.stringify(EditorMeta.makeJsonMetadataForUpdate(old, meta, u.tags)!!), u.oldJson)
        }
        assertNull(EditorMeta.makeJsonMetadataForUpdate(JsValue.Str("not an object"), JsValue.Obj(), emptyList()))
    }

    @Test
    fun thumbnailSortLeavesV8sOrder() {
        v.sorts.forEach { s ->
            val items = List(s.size) { "i$it" }
            val thumb = if (s.thumbAt < 0) "missing" else items[s.thumbAt]
            assertEquals(s.order, EditorMeta.sortThumbFirst(items, thumb).joinToString(",") { it.drop(1) }, "n=${s.size} thumb=${s.thumbAt}")
        }
    }

    @Test
    fun editBodyIsThePatchOnlyWhenShorterThanTheOldBody() {
        assertTrue(v.edits.any { it.isPatch } && v.edits.any { !it.isPatch })
        v.edits.forEach { e -> assertEquals(e.body, EditorMeta.editBody(e.oldBody, e.newBody), e.oldBody.take(30)) }
    }

    @Test
    fun beneficiaryRulesMatchRnAndTheSdk() {
        v.support.forEach { s -> assertEquals(s.expected, EditorMeta.injectEcencySupport(s.list, s.percent), "${s.list} ${s.percent}") }
        v.speak.forEach { s -> assertEquals(s.expected, EditorMeta.enforceThreeSpeakBeneficiary(s.list, s.body), "${s.list}") }
    }

    private fun tx(ops: List<com.ecency.core.hive.Operation>) = Transaction(
        refBlockNum = GoldenVectors.REF_BLOCK_NUM,
        refBlockPrefix = GoldenVectors.REF_BLOCK_PREFIX,
        expiration = GoldenVectors.EXPIRATION,
        operations = ops,
    )

    @Test
    fun newPostOperationsAreByteIdenticalToTheSdk() = runTest {
        assertEquals(12, v.newPosts.size)
        v.newPosts.forEach { p ->
            val permlink = Slug.generatePermlink(p.title, false) { v.RANDOM }
            assertEquals(p.permlink, permlink)
            val meta = EditorMeta.extractMetadata(p.body, fetchRatios = true, linkedPost = ::linked) { ratio(it) }
            val json = EditorMeta.stringify(EditorMeta.makeJsonMetadata(meta, EditorMeta.publishTags(p.tags), v.APP_VERSION))
            val ops = EditorMeta.newPostOperations(v.AUTHOR, permlink, p.title, p.body, p.tags, json, p.reward, p.beneficiaries)
            assertEquals(2, ops.size, "a new post always carries comment_options")
            assertEquals(p.opsJson, JsonArray(ops.map(OperationJson::encode)).toString(), p.title)
            // RN's makeOptions object is the comment_options body (what a schedule sends as `options`)
            assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(p.optionsJson), OperationJson.encode(ops[1])[1], p.title)
            val t = tx(ops)
            assertEquals(p.txId, t.id(), p.title)
            assertEquals(p.signature, t.sign(key).toHex(), p.title)
        }
    }

    @Test
    fun anEditIsOneCommentWithoutOptions() {
        val ops = EditorMeta.editOperations(v.AUTHOR, "golden-post", "", "hive-125125", "Edited", "@@ -1,4 +1,4 @@\n-old \n+new \n", "{\"tags\":[\"hive-125125\"],\"app\":\"peakd/1\",\"format\":\"markdown\"}")
        assertEquals(1, ops.size)
        assertFalse(ops.any { it is com.ecency.core.hive.CommentOptions })
        assertEquals(v.EDIT_OPS_JSON, JsonArray(ops.map(OperationJson::encode)).toString())
        val t = tx(ops)
        assertEquals(v.EDIT_TX_ID, t.id())
        assertEquals(v.EDIT_SIGNATURE, t.sign(key).toHex())
    }

    @Test
    fun imageUploadTokenMatchesRnsSignImage() {
        v.signs.forEach { s ->
            assertEquals(s.token, HiveSignerMessages.imageUploadToken(s.account, key, s.nowMillis), s.account)
            // the SDK's own (randomised) signature over the same message recovers to the same key
            val decoded = HiveSignerMessages.decode(s.sdkSignedToken)
            assertEquals(HiveSignerMessages.decode(s.token).unsignedJson, decoded.unsignedJson)
            assertEquals(key.publicKey().toString(), HiveSignerMessages.recoverSigner(decoded.unsignedJson, decoded.signatures.single()).toString())
        }
    }
}
