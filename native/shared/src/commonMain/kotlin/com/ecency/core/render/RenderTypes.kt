package com.ecency.core.render

/** render-helper `RenderOptions`. Every flag defaults to off, as in JS. */
data class RenderOptions(
    /** Video embeds render as iframes directly, without a play button overlay. */
    val embedVideosDirectly: Boolean = false,
    /** Web path only: `@user` / `#tag` chips render as inert `<span>` instead of `<a>`. */
    val inertAuthorAndTagChips: Boolean = false,
    /** Web path only: absolute origin for profile section links (e.g. `https://ecency.com`). */
    val externalProfileBase: String? = null,
    /** Keep a validated author pixel width/height pair on body images. */
    val preserveImageDimensions: Boolean = false,
)

/** render-helper `SeoContext`: decides `rel` on external links (web path). */
data class SeoContext(
    val authorReputation: Double? = null,
    val postPayout: Double? = null,
)

/** The fields of a Hive post the renderer reads. [jsonMetadata] is the raw chain string. */
data class Entry(
    val author: String? = null,
    val permlink: String? = null,
    val lastUpdate: String? = null,
    val body: String,
    val jsonMetadata: String? = null,
)
