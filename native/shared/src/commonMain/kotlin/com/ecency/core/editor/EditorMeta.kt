package com.ecency.core.editor

import com.ecency.core.EcencyCore
import com.ecency.core.api.model.JsValue
import com.ecency.core.api.search.PostUrlParser
import com.ecency.core.hive.Asset
import com.ecency.core.hive.Beneficiary
import com.ecency.core.hive.Comment
import com.ecency.core.hive.CommentOptions
import com.ecency.core.hive.Operation
import com.ecency.core.l10n.L
import com.ecency.core.render.js.Js
import com.ecency.core.text.DiffMatchPatch

/** RN RewardTypes: the editor's "Reward" option and its `makeOptions` key. */
enum class RewardType(val key: String, private val labelText: () -> String) {
    DEFAULT("default", { L.editor.rewardDefault }),
    POWER_UP("sp", { L.editor.rewardPowerUp }),
    DECLINE("dp", { L.editor.rewardDecline }),
    ;

    val label: String get() = labelText()

    companion object {
        fun ofKey(key: String?): RewardType = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** A beneficiary row as the editor keeps it (RN editor.beneficiariesMap, without the author row). */
data class BeneficiaryRoute(val account: String, val weight: Int)

/** RN PollDraft (providers/ecency/ecency.types.ts); [endTime] is an ISO instant as `Date.toISOString` writes it. */
data class PollDraft(
    val title: String = "",
    val choices: List<String> = listOf("", ""),
    val endTime: String = "",
    val voteChange: Boolean = false,
    val hideVotes: Boolean = false,
    val hideResults: Boolean = true,
    val interpretation: String = NUMBER_OF_VOTES,
    val maxChoicesVoted: Int = 1,
    val token: String? = null,
    val communityMembership: List<String> = emptyList(),
    val accountAge: Int = 100,
) {
    /** RN pollsWizardContent: a question and no empty choice. */
    val isValid: Boolean get() = title.isNotEmpty() && choices.none { it.isEmpty() }

    companion object {
        const val NUMBER_OF_VOTES = "number_of_votes"
        const val TOKENS = "tokens"
        const val HP_TOKEN = "HIVE:HP"
        const val DEFAULT_DAYS = 7
    }
}

/** A post a body links to, for `links_meta` (the SDK entry's `title`, `summary`, `image`; the bridge has only the title). */
data class LinkedPost(val title: String?, val summary: String? = null, val image: String? = null)

/** A video's thumbnail lives outside the body, keyed by the embed address it belongs to (RN VideoThumb). */
data class VideoThumb(val embedUrl: String, val thumbUrl: String)

/**
 * RN `src/utils/editor.ts` in Kotlin: what the editor writes on chain. Every function mirrors its JS
 * namesake, including JS property order in the metadata, and is checked against the RN source offline.
 */
object EditorMeta {
    const val DEFAULT_PARENT_PERMLINK = "hive-125125"

    /** SDK POLLS_PROTOCOL_VERSION. */
    const val POLLS_PROTOCOL_VERSION = 1.1
    const val THREESPEAK_ACCOUNT = "threespeakfund"
    const val THREESPEAK_WEIGHT = 1100
    const val ECENCY_SUPPORT_ACCOUNT = "ecency"
    const val DEFAULT_SUPPORT_PERCENT = 5
    const val MAX_BENEFICIARY_ROUTES = 8
    const val MAX_TOTAL_WEIGHT = 10000

    fun appString(appVersion: String = EcencyCore.VERSION) = "ecency/$appVersion-mobile"

    /** getWordsCount. */
    fun wordsCount(text: String): Int {
        if (text.isEmpty()) return 0
        val t = Js.trim(text)
        var words = 1
        var inSpace = false
        for (c in t) {
            val space = Js.isSpace(c)
            if (space && !inSpace) words++
            inSpace = space
        }
        return words
    }

    // ---- urls ----

    private fun inSetA(c: Char) = Js.isAsciiAlpha(c) || Js.isDigit(c) || c in "+&@#/%?=~_|!:,.;-"

    private fun inSetB(c: Char) = Js.isAsciiAlpha(c) || Js.isDigit(c) || c in "-+&@#/%=~_|"

    /** extractUrls: /(\b(https?|ftp):\/\/[A-Z0-9+&@#/%?=~_|!:,.;-]*[-A-Z0-9+&@#/%=~_|])/gim, scanned by hand. */
    fun extractUrls(body: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < body.length) {
            val scheme = when {
                Js.regionMatchesLower(body, i, "https://") -> 8
                Js.regionMatchesLower(body, i, "http://") -> 7
                Js.regionMatchesLower(body, i, "ftp://") -> 6
                else -> 0
            }
            if (scheme == 0 || (i > 0 && Js.isWordChar(body[i - 1]))) {
                i++
                continue
            }
            var end = i + scheme
            while (end < body.length && inSetA(body[end])) end++
            while (end > i + scheme && !inSetB(body[end - 1])) end--
            if (end > i + scheme) {
                out += body.substring(i, end)
                i = end
            } else {
                i++
            }
        }
        return out
    }

    private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".heic", ".webp")

    /** One url against /(https?:\/\/.*\.(?:png|jpg|jpeg|gif|heic|webp))/gim. */
    fun isImageUrl(url: String): Boolean {
        var i = 0
        while (i < url.length) {
            val scheme = if (Js.regionMatchesLower(url, i, "https://")) 8 else if (Js.regionMatchesLower(url, i, "http://")) 7 else 0
            if (scheme > 0) {
                val from = i + scheme
                // `.` stops at a line terminator
                var stop = from
                while (stop < url.length && !Js.isLineTerminator(url[stop])) stop++
                val rest = url.substring(from, stop)
                if (IMAGE_EXTENSIONS.any { Js.indexOfCi(rest, it) >= 0 }) return true
            }
            i++
        }
        return false
    }

    fun extractImageUrls(body: String): List<String> = extractUrls(body).filter(::isImageUrl)

    private val THREESPEAK_EMBED by lazy {
        com.ecency.core.render.methods.JsRegex.compile("[hH][tT][tT][pP][sS]?://([a-zA-Z0-9-]+\\.)*3[sS][pP][eE][aA][kK]\\.[tT][vV]/[eE][mM][bB][eE][dD][?/]")
    }

    /** SDK hasThreeSpeakEmbed. */
    fun hasThreeSpeakEmbed(text: String): Boolean = text.contains("3speak", ignoreCase = true) && THREESPEAK_EMBED.containsMatchIn(text)

    /** RN extractVideoEmbedUrls: the 3Speak embed addresses among the body's urls. */
    fun extractVideoEmbedUrls(body: String): List<String> = extractUrls(body).filter(::hasThreeSpeakEmbed)

    /**
     * RN collectVideoThumbUrls: the thumbnails of the videos whose embed address is still in [body] (exact
     * match), without the ones that are images of the body anyway.
     */
    fun collectVideoThumbUrls(videoThumbs: List<VideoThumb>, body: String): List<String> {
        if (body.isEmpty()) return emptyList()
        val urls = extractUrls(body)
        val inBody = urls.toSet()
        val images = urls.filter(::isImageUrl).toSet()
        return videoThumbs.filter { it.embedUrl.isNotEmpty() && it.embedUrl in inBody }.map { it.thumbUrl }.distinct().filter { it.isNotEmpty() && it !in images }
    }

    /** RN restoreVideoThumbs: a reopened draft with exactly one video gets its first meta image back as that video's thumbnail. */
    fun restoreVideoThumbs(body: String, metaImages: List<String>): List<VideoThumb> {
        val thumb = metaImages.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val embeds = extractVideoEmbedUrls(body)
        return if (embeds.size == 1) listOf(VideoThumb(embeds[0], thumb)) else emptyList()
    }

    // ---- beneficiaries ----

    /** SDK enforceThreeSpeakBeneficiary: a body with a 3Speak embed routes 11% to threespeakfund. */
    fun enforceThreeSpeakBeneficiary(list: List<BeneficiaryRoute>, body: String): List<BeneficiaryRoute> {
        if (!hasThreeSpeakEmbed(body)) return list
        val existing = list.firstOrNull { it.account == THREESPEAK_ACCOUNT }
        return when {
            existing == null -> list + BeneficiaryRoute(THREESPEAK_ACCOUNT, THREESPEAK_WEIGHT)
            existing.weight == THREESPEAK_WEIGHT -> list
            else -> list.map { if (it.account == THREESPEAK_ACCOUNT) it.copy(weight = THREESPEAK_WEIGHT) else it }
        }
    }

    fun isEcencySupport(account: String) = Js.toLowerCase(account) == ECENCY_SUPPORT_ACCOUNT

    /**
     * RN injectEcencySupportBeneficiary: the saved "Support Ecency" [percent] as a route, unless the list has
     * an ecency route already, would pass 8 routes or 100%.
     */
    fun injectEcencySupport(list: List<BeneficiaryRoute>, percent: Double): List<BeneficiaryRoute> {
        if (!percent.isFinite() || percent <= 0) return list
        if (list.any { isEcencySupport(it.account) }) return list
        val weight = kotlin.math.floor(percent * 100).toInt()
        val next = list + BeneficiaryRoute(ECENCY_SUPPORT_ACCOUNT, weight)
        if (next.size > MAX_BENEFICIARY_ROUTES) return list
        if (next.sumOf { it.weight } > MAX_TOTAL_WEIGHT) return list
        return next
    }

    /**
     * Why hived would refuse these routes for a post by [author], or null: 8 routes at most, each weight 1 to
     * 10000, 100% in total at most, no account twice, never the author. Checked on the final list (after the
     * 3Speak rule and the Ecency support route), so the user reads this instead of a raw chain error.
     */
    fun beneficiaryProblem(list: List<BeneficiaryRoute>, author: String): String? = when {
        list.size > MAX_BENEFICIARY_ROUTES -> L.native.editorMeta.tooManyBeneficiaries(max = MAX_BENEFICIARY_ROUTES.toString())
        list.any { it.weight !in 1..MAX_TOTAL_WEIGHT } -> L.native.editorMeta.aBeneficiarySShare
        list.sumOf { it.weight } > MAX_TOTAL_WEIGHT -> L.native.editorMeta.beneficiariesAddUpTo
        list.map { it.account }.distinct().size != list.size -> L.native.editorMeta.aBeneficiaryIsListed
        list.any { it.account == author } -> L.native.editorMeta.youCannotBeA
        list.any { it.account.isBlank() || it.account.length > 16 } -> L.native.editorMeta.aBeneficiaryHasAn
        else -> null
    }

    /**
     * makeOptions plus the SDK's own sort: the `comment_options` of a new post. Beneficiaries are de-duplicated by
     * account (first wins) and sorted by account. RN sorts with localeCompare; for account names that is the
     * byte order hived requires, which is what is used here.
     */
    fun commentOptions(author: String, permlink: String, reward: RewardType, beneficiaries: List<BeneficiaryRoute>): CommentOptions =
        CommentOptions(
            author = author,
            permlink = permlink,
            maxAcceptedPayout = Asset.parse(if (reward == RewardType.DECLINE) "0.000 HBD" else "1000000.000 HBD"),
            percentHbd = if (reward == RewardType.POWER_UP) 0 else 10000,
            allowVotes = true,
            allowCurationRewards = true,
            beneficiaries = beneficiaries.sortedBy { it.account }.distinctBy { it.account }.map { Beneficiary(it.account, it.weight) },
        )

    // ---- metadata ----

    private fun strings(list: List<String>) = JsValue.Arr(list.map { JsValue.Str(it) })

    /** `new Date(iso).getTime()` for the ISO instants the app writes; null (JS NaN) for anything else. */
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun isoMillis(iso: String): Long? = try {
        kotlin.time.Instant.parse(iso).toEpochMilliseconds()
    } catch (_: Exception) {
        null
    }

    /** convertToPollMeta. */
    fun pollMeta(poll: PollDraft): JsValue.Obj = JsValue.Obj().apply {
        set("content_type", JsValue.Str("poll"))
        set("question", JsValue.Str(Js.trim(poll.title)))
        set("choices", strings(poll.choices))
        set("preferred_interpretation", JsValue.Str(poll.interpretation))
        set("end_time", isoMillis(poll.endTime)?.let { JsValue.Num(kotlin.math.floor(it / 1000.0)) } ?: JsValue.Num(Double.NaN))
        set("allow_vote_changes", JsValue.Bool(poll.voteChange))
        set("hive_votes", JsValue.Bool(poll.hideVotes))
        set("ui_hide_res_until_voted", JsValue.Bool(poll.hideResults))
        set("max_choices_voted", JsValue.Num(poll.maxChoicesVoted.toDouble()))
        set("filters", JsValue.Obj().apply { set("account_age", JsValue.Num(poll.accountAge.toDouble())) })
        set("token", poll.token?.let { JsValue.Str(it) } ?: JsValue.Undefined)
        set("community_membership", strings(poll.communityMembership))
        set("version", JsValue.Num(POLLS_PROTOCOL_VERSION))
    }

    /**
     * RN sorts the images with a one-argument comparator, `(item) => item === thumb ? -1 : 1`. What V8 leaves for
     * that (checked against V8 for 1 to 63 images, every thumbnail position) is the thumbnail first and the rest
     * in their order, which is what this returns.
     */
    internal fun sortThumbFirst(items: List<String>, thumb: String): List<String> = items.sortedBy { it != thumb }

    /**
     * extractMetadata. [linkedPost] answers `links_meta` for body links that are post links (null: not found or
     * failed); [imageRatio] gives width / height of an image (null: unknown, written as JSON null like JS NaN).
     */
    suspend fun extractMetadata(
        body: String,
        thumbUrl: String? = null,
        videoThumbUrls: List<String> = emptyList(),
        fetchRatios: Boolean = false,
        postType: String? = null,
        contentType: String? = null,
        poll: PollDraft? = null,
        linkedPost: suspend (author: String, permlink: String) -> LinkedPost? = { _, _ -> null },
        imageRatio: suspend (url: String) -> Double? = { null },
    ): JsValue.Obj {
        var out = JsValue.Obj()
        out["content_type"] = contentType?.let { JsValue.Str(it) } ?: JsValue.Undefined
        val urls = extractUrls(body)
        val imageUrls = urls.filter(::isImageUrl)
        var matched = imageUrls + videoThumbUrls
        val links = urls.filter { it !in imageUrls }.take(5)
        out["links"] = strings(links)
        var linksMeta: JsValue.Obj? = null
        for (url in links) {
            val parts = try {
                PostUrlParser.parse(url)
            } catch (_: Exception) {
                null
            }
            val author = parts?.author
            val permlink = parts?.permlink
            if (author.isNullOrEmpty() || permlink.isNullOrEmpty()) continue
            val post = try {
                linkedPost(author, permlink)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val meta = linksMeta ?: JsValue.Obj().also { linksMeta = it }
            meta[url] = post?.let {
                JsValue.Obj().apply {
                    set("title", it.title?.let { t -> JsValue.Str(t) } ?: JsValue.Undefined)
                    set("summary", it.summary?.let { t -> JsValue.Str(t) } ?: JsValue.Undefined)
                    set("image", it.image?.let { t -> JsValue.Str(t) } ?: JsValue.Undefined)
                }
            } ?: JsValue.Null
        }
        linksMeta?.let { out["links_meta"] = it }
        if (matched.isNotEmpty()) {
            if (!thumbUrl.isNullOrEmpty()) matched = sortThumbFirst(matched, thumbUrl)
            val images = matched.take(10)
            out["image"] = strings(images)
            if (fetchRatios) {
                out["image_ratios"] = JsValue.Arr(
                    images.take(5).map { url ->
                        val ratio = try {
                            imageRatio(url)
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        JsValue.Num(ratio ?: Double.NaN)
                    },
                )
            }
        }
        if (poll != null && poll.title.isNotEmpty()) {
            val merged = out.copy()
            val meta = pollMeta(poll)
            meta.keys.forEach { merged[it] = meta[it]!! }
            merged["content_type"] = JsValue.Str("poll")
            out = merged
        }
        out["type"] = JsValue.Str(postType?.takeIf { it.isNotEmpty() } ?: "post")
        return out
    }

    /** makeJsonMetadata: `Object.assign({}, meta, {tags, app, format})`. */
    fun makeJsonMetadata(meta: JsValue.Obj, tags: List<String>, appVersion: String = EcencyCore.VERSION): JsValue.Obj = meta.copy().apply {
        set("tags", strings(tags))
        set("app", JsValue.Str(appString(appVersion)))
        set("format", JsValue.Str("markdown+html"))
    }

    /**
     * makeJsonMetadataForUpdate: the post's own metadata with the new values over it (its `app` and `format`
     * stay). Null when [oldJson] is not an object; RN then falls back to [makeJsonMetadata].
     */
    fun makeJsonMetadataForUpdate(oldJson: JsValue?, meta: JsValue.Obj, tags: List<String>): JsValue.Obj? {
        val old = oldJson as? JsValue.Obj ?: return null
        val merged = (old["meta"] as? JsValue.Obj)?.copy() ?: JsValue.Obj()
        meta.keys.forEach { merged[it] = meta[it]!! }
        return old.copy().apply {
            merged.keys.forEach { set(it, merged[it]!!) }
            set("tags", strings(tags))
        }
    }

    /** cleanAiTools: only the truthy disclosure flags, null when there are none. */
    fun cleanAiTools(aiTools: JsValue?): JsValue.Obj? {
        val o = aiTools as? JsValue.Obj ?: return null
        val out = JsValue.Obj()
        if (o["media_generation"]?.truthy == true) out["media_generation"] = JsValue.Bool(true)
        if (o["writing_edit"]?.truthy == true) out["writing_edit"] = JsValue.Bool(true)
        return out.takeIf { it.keys.isNotEmpty() }
    }

    fun stringify(meta: JsValue.Obj): String = JsValue.stringify(meta) ?: "{}"

    // ---- edits ----

    /** createPatch: the diff-match-patch patch text from [oldBody] to [newBody]; null when [oldBody] is empty. */
    fun createPatch(oldBody: String, newBody: String): String? {
        if (oldBody.isEmpty()) return null
        val dmp = DiffMatchPatch()
        return dmp.patchToText(dmp.patchMake(oldBody, newBody))
    }

    /**
     * The body an edit broadcasts (RN _submitEdit): the patch from the old body to the trimmed new one when it is
     * shorter (UTF-16 units) than the old body in UTF-8 bytes, else the new body as typed.
     */
    fun editBody(oldBody: String, newBody: String): String {
        val patch = createPatch(oldBody, Js.trim(newBody))
        return if (!patch.isNullOrEmpty() && patch.length < Js.utf8(oldBody).size) patch else newBody
    }

    // ---- operations ----

    /** RN _submitPost's tags: empty entries and single spaces dropped. */
    fun publishTags(tags: List<String>): List<String> = tags.filter { it.isNotEmpty() && it != " " }

    /**
     * A new post as the SDK's useComment builds it for RN: `comment` then `comment_options`, always both.
     * [jsonMetadata] is the stringified metadata; the parent permlink is the first tag or hive-125125.
     */
    fun newPostOperations(
        author: String,
        permlink: String,
        title: String,
        body: String,
        tags: List<String>,
        jsonMetadata: String,
        reward: RewardType,
        beneficiaries: List<BeneficiaryRoute>,
    ): List<Operation> = listOf(
        Comment("", publishTags(tags).firstOrNull() ?: DEFAULT_PARENT_PERMLINK, author, permlink, title, body, jsonMetadata),
        commentOptions(author, permlink, reward, beneficiaries),
    )

    /** An edit of a post or reply: one `comment`, never `comment_options` (create-only on chain). */
    fun editOperations(
        author: String,
        permlink: String,
        parentAuthor: String,
        parentPermlink: String,
        title: String,
        body: String,
        jsonMetadata: String,
    ): List<Operation> = listOf(Comment(parentAuthor, parentPermlink, author, permlink, title, body, jsonMetadata))
}
