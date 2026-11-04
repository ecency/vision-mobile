package com.ecency.core.api.broadcast

import com.ecency.core.api.model.Post
import com.ecency.core.hive.AccountUpdate2
import com.ecency.core.hive.Asset
import com.ecency.core.hive.Comment
import com.ecency.core.hive.CommentOptions
import com.ecency.core.hive.CustomJson
import com.ecency.core.hive.DeleteComment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Operations behind the post options menu, as the @ecency/sdk builders write them (condenser JSON, ready for
 * `SigningModel.broadcast`). Community moderation is not here: the menu's community pin and mute go through
 * [CommunityOps] (`env.communities`), the one place for every hivemind community operation.
 */
object PostActionOps {
    /** SDK buildReblogOp; [undo] adds `delete: "delete"`. */
    fun reblog(account: String, author: String, permlink: String, undo: Boolean): JsonArray = ops(
        CustomJson(
            emptyList(), listOf(account), "follow",
            JsonArray(
                listOf(
                    JsonPrimitive("reblog"),
                    buildJsonObject {
                        put("account", account)
                        put("author", author)
                        put("permlink", permlink)
                        if (undo) put("delete", "delete")
                    },
                ),
            ).toString(),
        ),
    )

    /** SDK buildIgnoreOp: the personal "Mute / Block" of an author. */
    fun ignore(follower: String, following: String): JsonArray = ops(
        CustomJson(
            emptyList(), listOf(follower), "follow",
            JsonArray(
                listOf(
                    JsonPrimitive("follow"),
                    buildJsonObject {
                        put("follower", follower)
                        put("following", following)
                        put("what", buildJsonArray { add(JsonPrimitive("ignore")) })
                    },
                ),
            ).toString(),
        ),
    )

    fun delete(author: String, permlink: String): JsonArray = ops(DeleteComment(author, permlink))

    /**
     * Pin (or unpin) [reply] as the pinned comment of [parent]: the parent re-posted unchanged with
     * `pinned_reply` set or removed in its json_metadata (RN useUpdateReply, no comment_options).
     */
    fun pinReply(parent: Post, reply: Post?): JsonArray {
        val meta = parseObject(parent.metadata.rawJson)
        val next = if (reply == null) JsonObject(meta - "pinned_reply")
        else JsonObject(meta + ("pinned_reply" to JsonPrimitive("${reply.author}/${reply.permlink}")))
        return ops(
            Comment(
                parent.parentAuthor.orEmpty(),
                parent.parentPermlink?.takeIf { it.isNotEmpty() } ?: parent.category,
                parent.author, parent.permlink, parent.title, parent.body, next.toString(),
            ),
        )
    }

    /** SDK usePollVote: custom_json "polls" {poll, action: "vote", choices}. */
    fun pollVote(username: String, pollTrxId: String, choices: List<Int>): JsonArray = ops(
        CustomJson(
            emptyList(), listOf(username), "polls",
            buildJsonObject {
                put("poll", pollTrxId)
                put("action", "vote")
                put("choices", buildJsonArray { choices.forEach { add(JsonPrimitive(it)) } })
            }.toString(),
        ),
    )

    /** RN makeCrossPostMessage. */
    fun crossPostBody(original: Post, poster: String, message: String): String =
        "This is a cross post of [@${original.author}/${original.permlink}](/@${original.author}/${original.permlink}) by @$poster.<br><br>$message"

    /**
     * RN useCrossPostMutation: a root post in [community] that points at [original], payout declined and no
     * curation rewards. [appVersion] is the app's version for `app` ("ecency/<version>-mobile").
     */
    fun crossPost(poster: String, original: Post, community: String, message: String, appVersion: String): JsonArray {
        val permlink = "${original.permlink}-$community"
        val meta = buildJsonObject {
            put("original_author", original.author)
            put("original_permlink", original.permlink)
            put("tags", buildJsonArray { add(JsonPrimitive("cross-post")) })
            put("app", "ecency/$appVersion-mobile")
            put("format", "markdown+html")
        }
        return ops(
            Comment("", community, poster, permlink, original.title, crossPostBody(original, poster, message), meta.toString()),
            CommentOptions(poster, permlink, Asset.parse("0.000 HBD"), 10000, allowVotes = true, allowCurationRewards = false),
        )
    }

    private fun ops(vararg operations: com.ecency.core.hive.Operation): JsonArray = JsonArray(operations.map { OperationJson.encode(it) })

    internal fun parseObject(text: String?): JsonObject {
        if (text.isNullOrBlank()) return JsonObject(emptyMap())
        val parsed: JsonElement? = com.ecency.core.api.json.SafeJson.parseOrNull(text)
        return when (parsed) {
            is JsonObject -> parsed
            // condenser can hand json_metadata over double encoded
            is JsonPrimitive -> if (parsed.isString) parseObject(parsed.content).takeIf { it.isNotEmpty() } ?: JsonObject(emptyMap()) else JsonObject(emptyMap())
            else -> JsonObject(emptyMap())
        }
    }
}
