package com.ecency.core.render.blocks

import com.ecency.core.api.search.PostUrlParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A post's `json_metadata.links_meta` as the first paint of its post link cards (#3705): `{url: {title, summary,
 * image}}`, written by the editors for the post links in the body. Any author writes anything there, so it gives only
 * text that shows until the linked post is read (`PostLinkCards`, whose read replaces it): never an image (an NSFW
 * post's cover, or one the linked post does not have). Keyed by "author/permlink" of each URL; an entry without a
 * title, or that is not an object, gives nothing. Title and summary are cut to [MAX_TITLE] and the feed card's summary
 * length.
 */
internal object LinksMeta {
    const val MAX_TITLE = 256

    fun cards(rawJson: String): Map<String, LinkCard> {
        val meta = try {
            (Json.parseToJsonElement(rawJson) as? JsonObject)?.get("links_meta") as? JsonObject
        } catch (_: Exception) {
            // not reported: json_metadata is free-form
            null
        } ?: return emptyMap()
        val out = mutableMapOf<String, LinkCard>()
        for ((url, value) in meta) {
            val entry = value as? JsonObject ?: continue
            val title = entry.text("title")?.trim()?.take(MAX_TITLE)?.takeIf { it.isNotEmpty() } ?: continue
            val parts = try {
                PostUrlParser.parse(url)
            } catch (_: Exception) {
                // not reported: untrusted link text
                null
            }
            val post = SafeTarget.post(parts?.author, parts?.permlink) ?: continue
            val summary = entry.text("summary")?.trim()?.take(SUMMARY_LENGTH).orEmpty()
            out.getOrPut(key(post.author, post.permlink)) { LinkCard(title, summary, null) }
        }
        return out
    }

    fun key(author: String, permlink: String) = "$author/$permlink"

    /** PostBodyRenderer.SUMMARY_LENGTH (the RN app's), as feed cards. */
    private const val SUMMARY_LENGTH = 150

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
