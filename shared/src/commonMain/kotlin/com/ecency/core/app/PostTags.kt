package com.ecency.core.app

import com.ecency.core.api.model.Post

/** One pill of the post screen's tags row (RN postElements Tags, basicUIElements tagContainer). */
data class PostTag(
    /** The tag as written: the tag results' query, or the community account. */
    val name: String,
    /** What the pill shows: a community's title, else the tag itself. */
    val label: String,
    val isCommunity: Boolean,
    /** The first pill (RN isPin), drawn in the primary blue. */
    val pinned: Boolean,
) {
    /** RN tagContainer: a community opens its screen, any other tag its results. */
    val screen: AppScreen get() = if (isCommunity) AppScreen.Community(name) else AppScreen.TagResult(name)

    /** What a screen reader says for the pill: its label and whether it is a community or a tag. */
    val accessibilityLabel: String get() = if (isCommunity) ReadingUiCopy.tagCommunity(label) else ReadingUiCopy.tagTopic(label)
}

/** RN postDisplayView's footer tags, shown above the "Posted by" line once the body is in. */
object PostTags {
    /** RN communityValidation isCommunity (unanchored start), which tagContainer tests before looking a title up. */
    private val COMMUNITY = Regex("hive-[1-3]\\d{4,6}$")

    fun isCommunity(name: String): Boolean = COMMUNITY.containsMatchIn(name)

    /**
     * `json_metadata.tags` (read leniently by [com.ecency.core.api.model.PostMetadata]: an array or a string, junk and
     * blank entries dropped, each once), with the category first when it is not already among them (exact case, as RN).
     */
    fun names(post: Post): List<String> {
        val tags = post.metadata.tags
        val category = post.category
        return if (category.isNotBlank() && category !in tags) listOf(category) + tags else tags
    }

    /** The community tags whose title is fetched (RN getCommunity per pill): all but the post's own, when it has its title. */
    fun lookups(post: Post): List<String> = names(post).filter { isCommunity(it) && ownTitle(post, it) == null }

    /**
     * The row's pills. [titles] holds what [lookups] found: a title, or null for no such community. A community
     * tag shows the post's `community_title` when it is the post's community, else its fetched title, else its name
     * (still opening the community, RN's fallback when the lookup fails). As RN, a lookup that found no community, or
     * one titled as its name, makes a plain tag.
     */
    fun of(post: Post, titles: Map<String, String?> = emptyMap()): List<PostTag> = names(post).mapIndexed { i, name ->
        val own = ownTitle(post, name)
        val (label, community) = when {
            !isCommunity(name) -> name to false
            own != null -> own to true
            name in titles -> titles[name]?.takeIf { it.isNotBlank() }.let { t -> if (t == null) name to false else t to (t != name) }
            else -> name to true
        }
        PostTag(name, label, community, pinned = i == 0)
    }

    private fun ownTitle(post: Post, name: String): String? =
        post.communityTitle?.takeIf { name == post.community && it.isNotBlank() }
}
