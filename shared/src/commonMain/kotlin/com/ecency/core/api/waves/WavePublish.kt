package com.ecency.core.api.waves

import com.ecency.core.EcencyCore
import com.ecency.core.api.EcencyException
import com.ecency.core.api.InvalidInputException
import com.ecency.core.api.broadcast.CommentDraft
import com.ecency.core.api.guarded
import com.ecency.core.api.model.JsValue
import com.ecency.core.api.search.PostUrlParser
import com.ecency.core.hive.Asset
import com.ecency.core.hive.Beneficiary
import com.ecency.core.hive.Comment
import com.ecency.core.hive.CommentOptions
import com.ecency.core.hive.Operation
import com.ecency.core.hive.sha256
import com.ecency.core.hive.toHex
import com.ecency.core.render.js.Js

/** A post link found in a wave's body: RN reads its title into `links_meta`. */
data class WavePostLink(val url: String, val author: String, val permlink: String)

/**
 * Builds a wave the way the RN app publishes one (`quickPostModalContent._submitPost`, `usePostSubmitter`,
 * `utils/editor.ts`) over the SDK's `useComment`: a `comment` under the newest container post, with a permlink
 * derived from the content and RN's json_metadata. Golden vectors run the RN helpers and the SDK's op builders.
 */
object WavePublish {
    const val THREESPEAK_BENEFICIARY_ACCOUNT = "threespeakfund"
    const val THREESPEAK_BENEFICIARY_WEIGHT = 1100

    /** SDK QUEST_MIN_CONTENT_LENGTH: a body at or under it (links excluded) earns no points. */
    const val QUEST_MIN_CONTENT_LENGTH = 25

    /**
     * RN `_submitPost`: the typed text, then the uploaded video's embed address on a line of its own, else the
     * attached image as markdown. A video wins over an image; RN never writes both.
     */
    fun body(text: String, imageUrl: String?, videoEmbedUrl: String? = null): String = when {
        !videoEmbedUrl.isNullOrEmpty() -> "$text\n\n$videoEmbedUrl"
        imageUrl.isNullOrEmpty() -> text
        else -> "$text\n\n ![]($imageUrl)"
    }

    /**
     * The embed RN's `_submitReply` links after a publish: the first `http://` or `https://` address whose host is
     * lowercase letters and dots ending in `3speak.tv`, with an `/embed?` or `/embed/` path, up to the next
     * whitespace, `<`, `"` or `'` (RN's regex is case-sensitive, unlike [hasThreeSpeakEmbed]).
     */
    fun videoEmbed(body: String): String? {
        var i = body.indexOf("http")
        while (i >= 0) {
            var p = i + 4
            if (body.startsWith("s", p)) p++
            if (body.startsWith("://", p)) {
                p += 3
                while (p < body.length && (body[p] in 'a'..'z' || body[p] == '.')) p++
                val after = p + SPEAK_EMBED.length
                if (body.startsWith(SPEAK_EMBED, p) && after < body.length && body[after] in "?/") {
                    var end = after + 1
                    while (end < body.length && !Js.isSpace(body[end]) && body[end] !in "<\"'") end++
                    return body.substring(i, end)
                }
            }
            i = body.indexOf("http", i + 1)
        }
        return null
    }

    private const val SPEAK_EMBED = "3speak.tv/embed"
    private val V_PARAM = Regex("[?&]v=([^&]+)")
    private val AT_FORMAT = Regex("@[^/]+/([a-zA-Z0-9]+)")

    /** RN `extractPermlink` (providers/speak): `?v=owner/permlink`, `@owner/permlink`, else the last path segment. */
    fun extractPermlink(embedUrl: String): String {
        V_PARAM.find(embedUrl)?.groupValues?.get(1)?.split('/')?.last()?.takeIf { it.isNotEmpty() }?.let { return it }
        AT_FORMAT.find(embedUrl)?.let { return it.groupValues[1] }
        return embedUrl.split('/').last().split('?')[0].split('#')[0]
    }

    /** The video permlink RN hands `linkVideoToHive` after publishing [body]; null when RN makes no call. */
    fun videoLinkPermlink(body: String): String? {
        if (!hasThreeSpeakEmbed(body)) return null
        return videoEmbed(body)?.let(::extractPermlink)?.takeIf { it.isNotEmpty() }
    }

    /**
     * RN generateContentBasedPermlink("wave", author|container author|container permlink|body|poll): the same
     * content gives the same permlink, so a resubmit after a timeout edits the wave instead of posting it twice.
     */
    fun permlink(author: String, containerAuthor: String, containerPermlink: String, body: String, pollJson: String = ""): String {
        val key = listOf(author, containerAuthor, containerPermlink, body, pollJson).joinToString("|")
        return "wave-" + sha256(Js.utf8(key)).toHex().take(16)
    }

    /** RN extractHashTags: `/#(\w+)/g`, ASCII word characters. */
    fun hashTags(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '#') {
                var end = i + 1
                while (end < text.length && isWord(text[end])) end++
                if (end > i + 1) {
                    out += text.substring(i + 1, end)
                    i = end
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun isWord(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    private const val URL_BODY = "+&@#/%?=~_|!:,.;-"
    private const val URL_END = "-+&@#/%=~_|"

    private fun isAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /** RN extractUrls: `/(\b(https?|ftp):\/\/[A-Z0-9+&@#/%?=~_|!:,.;-]*[-A-Z0-9+&@#/%=~_|])/gim`. */
    fun extractUrls(body: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < body.length) {
            val end = urlAt(body, i)
            if (end < 0) {
                i++
            } else {
                out += body.substring(i, end)
                i = end
            }
        }
        return out
    }

    /** The end of a URL match starting at [start], or -1. */
    private fun urlAt(s: String, start: Int): Int {
        // \b before a scheme letter: the previous character is not a word character
        if (start > 0 && isWord(s[start - 1])) return -1
        val schemeEnd = when {
            Js.regionMatchesLower(s, start, "https://") -> start + 8
            Js.regionMatchesLower(s, start, "http://") -> start + 7
            Js.regionMatchesLower(s, start, "ftp://") -> start + 6
            else -> return -1
        }
        var run = schemeEnd
        while (run < s.length && (isAlnum(s[run]) || s[run] in URL_BODY)) run++
        // the last character must be one a URL may end with: back off over trailing punctuation
        var end = run
        while (end > schemeEnd && !(isAlnum(s[end - 1]) || s[end - 1] in URL_END)) end--
        return if (end > schemeEnd) end else -1
    }

    private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".heic", ".webp")

    /** RN extractImageUrls over [urls]: `/(https?:\/\/.*\.(?:png|jpg|jpeg|gif|heic|webp))/gim` matches the URL. */
    fun imageUrls(urls: List<String>): List<String> = urls.filter { url ->
        val lower = Js.lower(url)
        val http = lower.indexOf("http://").takeIf { it >= 0 }?.let { it + 7 }
        val https = lower.indexOf("https://").takeIf { it >= 0 }?.let { it + 8 }
        val from = listOfNotNull(http, https).minOrNull() ?: return@filter false
        IMAGE_EXTENSIONS.any { lower.indexOf(it, from) >= 0 }
    }

    /** SDK hasThreeSpeakEmbed: `/https?:\/\/([a-z0-9-]+\.)*3speak\.tv\/embed[?/]/i`. */
    fun hasThreeSpeakEmbed(body: String): Boolean = THREESPEAK.containsMatchIn(body)

    private val THREESPEAK = Regex("""[hH][tT][tT][pP][sS]?://([a-zA-Z0-9-]+\.)*3[sS][pP][eE][aA][kK]\.[tT][vV]/[eE][mM][bB][eE][dD][?/]""")

    /** SDK measureQuestContentLength: code points left once `https?://\S+` is removed (case-sensitive, untrimmed). */
    fun questContentLength(body: String): Int {
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val skip = when {
                body.startsWith("https://", i) -> 8
                body.startsWith("http://", i) -> 7
                else -> 0
            }
            if (skip > 0 && i + skip < body.length && !Js.isSpace(body[i + skip])) {
                i += skip
                while (i < body.length && !Js.isSpace(body[i])) i++
            } else {
                sb.append(body[i])
                i++
            }
        }
        return Waves.codePointCount(sb.toString())
    }

    /** SDK earnsQuestContentCredit. */
    fun earnsQuestCredit(body: String): Boolean = questContentLength(body) > QUEST_MIN_CONTENT_LENGTH

    /** RN shouldShowShortContentHint for a composer that only creates. */
    fun showsShortContentHint(username: String?, body: String): Boolean =
        !username.isNullOrEmpty() && Js.trim(body).isNotEmpty() && !earnsQuestCredit(body)

    /** The links of [body] RN reads a post title for (`postUrlParser` gives an author and a permlink). */
    fun postLinks(body: String): List<WavePostLink> {
        val urls = extractUrls(body)
        val images = imageUrls(urls)
        return urls.filterNot { it in images }.take(5).mapNotNull { url ->
            val parts = try {
                PostUrlParser.parse(url)
            } catch (e: Exception) {
                null
            }
            val author = parts?.author?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val permlink = parts.permlink?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            WavePostLink(url, author, permlink)
        }.distinctBy { it.url }
    }

    /**
     * RN extractMetadata({ body, fetchRatios: true, postType: wave }) then makeJsonMetadata(meta, tags), as the
     * text `JSON.stringify` gives: `links` (at most 5 non-image URLs), `links_meta` (the linked posts' titles;
     * [linked] maps each [postLinks] URL to its post's title, null for a post that could not be read), `image`
     * (at most 10: the body's images, then [videoThumbs], the uploaded video's thumbnail), `image_ratios` (width /
     * height of the first 5 from [ratios], null when unknown), `type`, `tags`, `app`, `format`.
     */
    fun metadata(
        body: String,
        tags: List<String>,
        linked: Map<String, String?> = emptyMap(),
        ratios: Map<String, Double> = emptyMap(),
        appVersion: String = EcencyCore.VERSION,
        poll: com.ecency.core.editor.PollDraft? = null,
        videoThumbs: List<String> = emptyList(),
    ): String {
        val urls = extractUrls(body)
        val bodyImages = imageUrls(urls)
        // RN: links are filtered against the body's images only; the thumbnails join `image` after them
        val images = bodyImages + videoThumbs
        val links = urls.filterNot { it in bodyImages }.take(5)
        val out = JsValue.Obj()
        // RN extractMetadata: `content_type` is the object's first key; it only has a value with a poll
        val pollMeta = poll?.takeIf { it.title.isNotEmpty() }?.let { com.ecency.core.editor.EditorMeta.pollMeta(it) }
        if (pollMeta != null) out["content_type"] = JsValue.Str("poll")
        out["links"] = JsValue.Arr(links.map { JsValue.Str(it) })
        val postUrls = postLinks(body).map { it.url }.toSet()
        val meta = JsValue.Obj()
        for (url in links) {
            if (url !in postUrls) continue
            val title = linked[url]
            meta[url] = if (title == null) JsValue.Null else JsValue.Obj().also { it["title"] = JsValue.Str(title) }
        }
        if (links.any { it in postUrls }) out["links_meta"] = meta
        if (images.isNotEmpty()) {
            val shown = images.take(10)
            out["image"] = JsValue.Arr(shown.map { JsValue.Str(it) })
            out["image_ratios"] = JsValue.Arr(shown.take(5).map { url ->
                val r = ratios[url]
                if (r == null || r.isNaN() || r.isInfinite()) JsValue.Null else JsValue.Num(r)
            })
        }
        // the poll fields (the editor's poll model, RN's `{ ...meta, ...pollMeta }`), before `type`
        pollMeta?.let { meta -> meta.keys.forEach { key -> if (key != "content_type") out[key] = meta[key]!! } }
        out["type"] = JsValue.Str("wave")
        out["tags"] = JsValue.Arr(tags.map { JsValue.Str(it) })
        out["app"] = JsValue.Str("ecency/$appVersion-mobile")
        out["format"] = JsValue.Str("markdown+html")
        return JsValue.stringify(out) ?: "{}"
    }

    /**
     * RN `JSON.stringify(pollDraft)`, the poll's part of the wave's content key ([permlink]): the wizard's
     * object in the order it builds it (title, choices, filters, interpretation, voteChange, hideVotes,
     * hideResults, communityMembership, token when set, maxChoicesVoted, endTime).
     */
    fun pollKey(poll: com.ecency.core.editor.PollDraft?): String {
        if (poll == null) return ""
        val o = JsValue.Obj()
        o["title"] = JsValue.Str(poll.title)
        o["choices"] = JsValue.Arr(poll.choices.map { JsValue.Str(it) })
        o["filters"] = JsValue.Obj().also { it["accountAge"] = JsValue.Num(poll.accountAge.toDouble()) }
        o["interpretation"] = JsValue.Str(poll.interpretation)
        o["voteChange"] = JsValue.Bool(poll.voteChange)
        o["hideVotes"] = JsValue.Bool(poll.hideVotes)
        o["hideResults"] = JsValue.Bool(poll.hideResults)
        o["communityMembership"] = JsValue.Arr(poll.communityMembership.map { JsValue.Str(it) })
        poll.token?.let { o["token"] = JsValue.Str(it) }
        o["maxChoicesVoted"] = JsValue.Num(poll.maxChoicesVoted.toDouble())
        o["endTime"] = JsValue.Str(poll.endTime)
        return JsValue.stringify(o).orEmpty()
    }

    /**
     * RN `[...parentTags, ...hashtags]` with `parentTags = parentPost.json_metadata.tags || ['ecency']`: the
     * container's tags, then the body's hashtags. Only a container without tags ([containerTags] null) gets
     * "ecency"; an empty array is truthy in JS and stays empty.
     */
    fun tags(containerTags: List<String>?, body: String): List<String> =
        (containerTags ?: listOf("ecency")) + hashTags(body)

    /**
     * `json_metadata.tags` of the container as RN's `||` reads it: the array's strings (an empty array stays
     * empty), a non-empty string as its characters (what spreading a string gives), and null for anything
     * absent or falsy, which is when RN falls back to "ecency".
     */
    fun containerTags(rawJsonMetadata: String): List<String>? {
        val tags = (com.ecency.core.api.json.SafeJson.parseOrNull(rawJsonMetadata) as? kotlinx.serialization.json.JsonObject)?.get("tags")
        return when {
            tags is kotlinx.serialization.json.JsonArray ->
                tags.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p !is kotlinx.serialization.json.JsonNull }?.content }
            tags is kotlinx.serialization.json.JsonPrimitive && tags.isString && tags.content.isNotEmpty() -> {
                val out = mutableListOf<String>()
                var i = 0
                while (i < tags.content.length) {
                    val n = if (tags.content[i].isHighSurrogate() && i + 1 < tags.content.length && tags.content[i + 1].isLowSurrogate()) 2 else 1
                    out += tags.content.substring(i, i + n)
                    i += n
                }
                out
            }
            else -> null
        }
    }

    /**
     * The wave ready to broadcast: the `comment` under [containerAuthor]/[containerPermlink] and, when the body
     * embeds a 3Speak video, the `comment_options` with 3Speak's beneficiary (SDK enforceThreeSpeakBeneficiary).
     */
    @Throws(EcencyException::class)
    fun draft(
        author: String,
        containerAuthor: String,
        containerPermlink: String,
        containerTags: List<String>?,
        body: String,
        linked: Map<String, String?> = emptyMap(),
        ratios: Map<String, Double> = emptyMap(),
        appVersion: String = EcencyCore.VERSION,
        poll: com.ecency.core.editor.PollDraft? = null,
        videoThumbs: List<String> = emptyList(),
    ): CommentDraft = guarded {
        if (author.isBlank() || containerAuthor.isBlank() || containerPermlink.isBlank()) throw InvalidInputException("missing author or container")
        if (Js.trim(body).isEmpty()) throw InvalidInputException("empty wave")
        val permlink = permlink(author, containerAuthor, containerPermlink, body, pollKey(poll))
        val ops = mutableListOf<Operation>(
            Comment(containerAuthor, containerPermlink, author, permlink, "", body, metadata(body, tags(containerTags, body), linked, ratios, appVersion, poll, videoThumbs)),
        )
        if (hasThreeSpeakEmbed(body)) {
            ops += CommentOptions(
                author = author,
                permlink = permlink,
                maxAcceptedPayout = Asset.parse("1000000.000 HBD"),
                percentHbd = 10000,
                allowVotes = true,
                allowCurationRewards = true,
                beneficiaries = listOf(Beneficiary(THREESPEAK_BENEFICIARY_ACCOUNT, THREESPEAK_BENEFICIARY_WEIGHT)),
            )
        }
        CommentDraft(author, permlink, containerAuthor, containerPermlink, ops)
    }
}
