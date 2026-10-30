package com.ecency.core.api.broadcast

import com.ecency.core.api.InvalidInputException
import com.ecency.core.hive.CustomJson
import kotlinx.serialization.json.JsonArray

/** Community properties for [CommunityOps.updateProps] (SDK `CommunityProps`, hivemind `updateProps`). */
data class CommunityProps(
    val title: String,
    val about: String,
    val lang: String,
    val description: String,
    /** The rules; hivemind calls them flag_text. */
    val flagText: String,
    val isNsfw: Boolean,
)

/**
 * The hivemind community operations, as `custom_json` with id `community` signed by the posting key. One place
 * for every screen that moderates or subscribes (communities, members, settings and the post menu), ported from
 * @ecency/sdk `operations/builders/community.ts`: the `json` field is byte-identical to the SDK's
 * `JSON.stringify(...)` (golden vectors in `CommunityGoldenVectors`).
 *
 * Broadcast with `env.signing.broadcast(username, CommunityOps.ops(op))`. Missing parameters throw
 * [InvalidInputException], where the SDK throws an Error.
 */
object CommunityOps {
    const val ID = "community"

    fun subscribe(username: String, community: String): CustomJson {
        require("subscribe", username, community)
        return op(username, "subscribe", "community" to community)
    }

    fun unsubscribe(username: String, community: String): CustomJson {
        require("unsubscribe", username, community)
        return op(username, "unsubscribe", "community" to community)
    }

    /** Sets [account]'s role (owner, admin, mod, member, guest, muted); guest also removes from the team. */
    fun setRole(username: String, community: String, account: String, role: String): CustomJson {
        require("setRole", username, community, account, role)
        return op(username, "setRole", "community" to community, "account" to account, "role" to role)
    }

    fun updateProps(username: String, community: String, props: CommunityProps): CustomJson {
        require("updateProps", username, community)
        val p = "{" + listOf(
            "title" to JsJson.quote(props.title),
            "about" to JsJson.quote(props.about),
            "lang" to JsJson.quote(props.lang),
            "description" to JsJson.quote(props.description),
            "flag_text" to JsJson.quote(props.flagText),
            "is_nsfw" to props.isNsfw.toString(),
        ).joinToString(",") { (k, v) -> "${JsJson.quote(k)}:$v" } + "}"
        return CustomJson(emptyList(), listOf(username), ID, "[\"updateProps\",{\"community\":${JsJson.quote(community)},\"props\":$p}]")
    }

    /** pinPost, or unpinPost when [pin] is false. */
    fun pinPost(username: String, community: String, account: String, permlink: String, pin: Boolean): CustomJson {
        require("pinPost", username, community, account, permlink)
        return op(username, if (pin) "pinPost" else "unpinPost", "community" to community, "account" to account, "permlink" to permlink)
    }

    /**
     * mutePost, or unmutePost when [mute] is false. Hivemind's schema has [notes] (the reason) on both and asserts
     * "notes cannot be blank", no padding and at most 120 characters; the SDK builder does not check, this one does.
     */
    fun mutePost(username: String, community: String, account: String, permlink: String, notes: String, mute: Boolean): CustomJson {
        require("mutePost", username, community, account, permlink)
        requireNotes("mutePost", notes)
        return op(
            username, if (mute) "mutePost" else "unmutePost",
            "community" to community, "account" to account, "permlink" to permlink, "notes" to notes,
        )
    }

    /** muteUser, or unmuteUser when [mute] is false. */
    fun muteUser(username: String, community: String, account: String, notes: String, mute: Boolean): CustomJson {
        require("muteUser", username, community, account)
        requireNotes("muteUser", notes)
        return op(username, if (mute) "muteUser" else "unmuteUser", "community" to community, "account" to account, "notes" to notes)
    }

    fun flagPost(username: String, community: String, account: String, permlink: String, notes: String): CustomJson {
        require("flagPost", username, community, account, permlink)
        requireNotes("flagPost", notes)
        return op(username, "flagPost", "community" to community, "account" to account, "permlink" to permlink, "notes" to notes)
    }

    /** The condenser operations array [com.ecency.core.app.SigningModel.broadcast] takes. */
    fun ops(vararg ops: CustomJson): JsonArray = JsonArray(ops.map(OperationJson::encode))

    private fun op(username: String, action: String, vararg fields: Pair<String, String>): CustomJson {
        val body = fields.joinToString(",") { (k, v) -> "${JsJson.quote(k)}:${JsJson.quote(v)}" }
        return CustomJson(emptyList(), listOf(username), ID, "[${JsJson.quote(action)},{$body}]")
    }

    /** Hivemind `_read_notes`: not blank, no leading or trailing white space, at most [NOTES_MAX] code points. */
    private fun requireNotes(action: String, notes: String) {
        if (notes.isBlank()) throw InvalidInputException("$action: notes cannot be blank")
        if (notes != notes.trim()) throw InvalidInputException("$action: invalid padding: notes")
        if (notes.codePointCount() > NOTES_MAX) throw InvalidInputException("$action: exceeds max len: notes")
    }

    const val NOTES_MAX = 120

    private fun require(action: String, vararg values: String) {
        if (values.any { it.isEmpty() }) throw InvalidInputException("$action: missing required parameters")
    }
}

/** Unicode code points in the string (a surrogate pair is one, a lone surrogate one), as Python's `len`. */
fun String.codePointCount(): Int {
    var n = 0
    var i = 0
    while (i < length) {
        i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        n++
    }
    return n
}

/** The first [max] code points, never cutting a surrogate pair in half. */
fun String.takeCodePoints(max: Int): String {
    var n = 0
    var i = 0
    while (i < length && n < max) {
        i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        n++
    }
    return substring(0, i)
}

/** JavaScript's `JSON.stringify` for one string (well-formed JSON.stringify: lone surrogates are escaped). */
internal object JsJson {
    private const val HEX = "0123456789abcdef"

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> escape(sb, c)
                c.isHighSurrogate() -> if (i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                    sb.append(c).append(s[i + 1])
                    i++
                } else {
                    escape(sb, c)
                }
                c.isLowSurrogate() -> escape(sb, c)
                else -> sb.append(c)
            }
            i++
        }
        return sb.append('"').toString()
    }

    private fun escape(sb: StringBuilder, c: Char) {
        val code = c.code
        sb.append("\\u")
        for (shift in intArrayOf(12, 8, 4, 0)) sb.append(HEX[(code shr shift) and 0xF])
    }
}
