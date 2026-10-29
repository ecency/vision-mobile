package com.ecency.core.api.broadcast

import com.ecency.core.EcencyCore
import com.ecency.core.api.EcencyException
import com.ecency.core.api.InvalidInputException
import com.ecency.core.api.guarded
import com.ecency.core.hive.Asset
import com.ecency.core.hive.Beneficiary
import com.ecency.core.hive.ChainTime
import com.ecency.core.hive.Comment
import com.ecency.core.hive.CommentOptions
import com.ecency.core.hive.Operation
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Reward options, as the app's editor offers them (utils/editor.ts makeOptions). */
enum class PayoutType {
    /** 50% HBD / 50% HP. */
    DEFAULT,

    /** 100% Hive Power. */
    POWER_UP,

    /** No payout. */
    DECLINE,
}

/** A reply ready to broadcast: its [permlink] and the operations that create it. */
data class CommentDraft(
    val author: String,
    val permlink: String,
    val parentAuthor: String,
    val parentPermlink: String,
    val operations: List<Operation>,
)

/** Builds replies the way the app's editor does. */
object Comments {
    private val URL = Regex("""https?://[^\s<>"')\]]+""")
    private val IMAGE = Regex("""\.(png|jpe?g|gif|webp|heic|avif)(\?\S*)?$""", RegexOption.IGNORE_CASE)

    /**
     * The app's generateUniquePermlink shape, `re-<parent author without dots>-<date>t<time>z`,
     * with two deliberate differences: every component is zero-padded (yyyyMMdd't'HHmmssSSS'z')
     * because the app's unpadded form collides (Jan 11 and Nov 1 both read "2026111"), and UTC
     * is used instead of device local time, so the result does not depend on the device zone.
     */
    @Throws(EcencyException::class)
    fun replyPermlink(parentAuthor: String, nowMillis: Long): String = guarded {
        if (parentAuthor.isBlank()) throw InvalidInputException("missing parent author")
        val time = ChainTime.fromEpochSeconds(nowMillis / 1000) // YYYY-MM-DDTHH:MM:SS
        val date = time.substring(0, 10).replace("-", "")
        val clock = time.substring(11).replace(":", "")
        val ms = (nowMillis % 1000).toString().padStart(3, '0')
        "re-${parentAuthor.replace(".", "")}-${date}t$clock${ms}z".lowercase()
    }

    /**
     * Reply json_metadata as the app's makeJsonMetadata: the parent's tags (or "ecency"),
     * `app` "ecency/<version>-mobile", format "markdown+html", plus image and link URLs found
     * in the body (the app also fetches link previews and image ratios; not done here).
     */
    fun replyMetadata(parentTags: List<String>, body: String, appVersion: String = EcencyCore.VERSION): String {
        val urls = URL.findAll(body).map { it.value.trimEnd('.', ',') }.distinct().toList()
        val images = urls.filter { IMAGE.containsMatchIn(it) }
        val links = urls.filterNot { it in images }.take(5)
        return buildJsonObject {
            if (images.isNotEmpty()) putJsonArray("image") { images.forEach { add(JsonPrimitive(it)) } }
            if (links.isNotEmpty()) putJsonArray("links") { links.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("tags") { parentTags.ifEmpty { listOf("ecency") }.forEach { add(JsonPrimitive(it)) } }
            put("app", "ecency/$appVersion-mobile")
            put("format", "markdown+html")
        }.toString()
    }

    /**
     * A reply to [parentAuthor]/[parentPermlink]. A comment_options operation is added only when
     * the payout is not the default or there are beneficiaries (sorted and de-duplicated by
     * account, as hived requires and the app does).
     */
    @Throws(EcencyException::class)
    fun reply(
        author: String,
        parentAuthor: String,
        parentPermlink: String,
        body: String,
        parentTags: List<String> = emptyList(),
        nowMillis: Long,
        payout: PayoutType = PayoutType.DEFAULT,
        beneficiaries: List<Beneficiary> = emptyList(),
        appVersion: String = EcencyCore.VERSION,
    ): CommentDraft = replyWithMetadata(author, parentAuthor, parentPermlink, body, parentTags, nowMillis, payout, beneficiaries, appVersion, null)

    /** [reply] with the json_metadata the caller built (the editor's RN-identical metadata); null: [replyMetadata]. */
    @Throws(EcencyException::class)
    fun replyWithMetadata(
        author: String,
        parentAuthor: String,
        parentPermlink: String,
        body: String,
        parentTags: List<String>,
        nowMillis: Long,
        payout: PayoutType,
        beneficiaries: List<Beneficiary>,
        appVersion: String,
        jsonMetadata: String?,
    ): CommentDraft = guarded {
        if (author.isBlank() || parentAuthor.isBlank() || parentPermlink.isBlank()) throw InvalidInputException("missing author or parent")
        if (body.isBlank()) throw InvalidInputException("empty reply")
        val permlink = replyPermlink(parentAuthor, nowMillis)
        val ops = mutableListOf<Operation>(
            Comment(parentAuthor, parentPermlink, author, permlink, "", body, jsonMetadata ?: replyMetadata(parentTags, body, appVersion)),
        )
        val uniqueBeneficiaries = beneficiaries.distinctBy { it.account }.sortedBy { it.account }
        if (payout != PayoutType.DEFAULT || uniqueBeneficiaries.isNotEmpty()) {
            ops += CommentOptions(
                author = author,
                permlink = permlink,
                maxAcceptedPayout = Asset.parse(if (payout == PayoutType.DECLINE) "0.000 HBD" else "1000000.000 HBD"),
                percentHbd = if (payout == PayoutType.POWER_UP) 0 else 10000,
                allowVotes = true,
                allowCurationRewards = true,
                beneficiaries = uniqueBeneficiaries,
            )
        }
        CommentDraft(author, permlink, parentAuthor, parentPermlink, ops)
    }

    /**
     * [draft] without its comment_options: for an edit of a comment that already exists, since
     * comment_options can be set only once (beneficiaries never change after creation).
     */
    fun withoutOptions(draft: CommentDraft): CommentDraft = draft.copy(operations = draft.operations.filterNot { it is CommentOptions })
}
