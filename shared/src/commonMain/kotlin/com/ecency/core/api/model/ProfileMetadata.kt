package com.ecency.core.api.model

import com.ecency.core.api.InvalidInputException

/**
 * The `posting_json_metadata` a profile edit writes, ported from @ecency/sdk `profile-metadata.ts`
 * (`buildPostingJsonMetadata`, `buildProfileMetadata`, `parseProfileMetadata`). The output must equal the
 * SDK's byte for byte (golden fixtures from the SDK itself), since it replaces the whole on-chain value:
 * existing top-level keys and profile fields survive, edited fields are deep-merged in, `tokens` are cleaned of
 * private fields and `version` is 2.
 */
object ProfileMetadata {
    private val DENIED_KEYS = setOf("__proto__", "constructor", "prototype")

    /** SDK parseProfileMetadata: the `profile` object of [postingJsonMetadata], or an empty object. */
    fun parseProfile(postingJsonMetadata: String?): JsValue.Obj {
        if (postingJsonMetadata.isNullOrEmpty()) return JsValue.Obj()
        val parsed = JsValue.parse(postingJsonMetadata) ?: return JsValue.Obj()
        // `parsed.profile && typeof parsed.profile === "object"`: arrays count as objects there
        val profile = (parsed as? JsValue.Obj)?.get("profile")
        return when (profile) {
            is JsValue.Obj -> profile.copy()
            is JsValue.Arr -> arrayAsObject(profile)
            else -> JsValue.Obj()
        }
    }

    /** SDK parsePostingMetadataRoot: the whole metadata object, or an empty object. */
    private fun parseRoot(postingJsonMetadata: String?): JsValue.Obj {
        if (postingJsonMetadata.isNullOrEmpty()) return JsValue.Obj()
        return (JsValue.parse(postingJsonMetadata) as? JsValue.Obj)?.copy() ?: JsValue.Obj()
    }

    /**
     * SDK buildPostingJsonMetadata with `tokens` undefined (the app's profile edit): [profile] is merged over
     * the existing profile of [existingPostingJsonMetadata], every other top-level key is kept.
     *
     * @throws InvalidInputException where the SDK throws (a `null` entry in `tokens`).
     */
    @Throws(InvalidInputException::class)
    fun buildPostingJsonMetadata(existingPostingJsonMetadata: String?, profile: JsValue.Obj): String {
        val root = parseRoot(existingPostingJsonMetadata)
        val existing = root["profile"] as? JsValue.Obj ?: JsValue.Obj()
        val merged = buildProfile(existing, profile)
        root["profile"] = merged
        return JsValue.stringify(root)!!
    }

    /** SDK buildProfileMetadata(existingProfile, profile, tokens = undefined). */
    @Throws(InvalidInputException::class)
    fun buildProfile(existing: JsValue.Obj, profile: JsValue.Obj): JsValue.Obj {
        // const { tokens: profileTokens, version, ...profileRest } = profile
        val profileTokens = profile["tokens"] ?: JsValue.Undefined
        val rest = JsValue.Obj()
        for (k in profile.keys) if (k != "tokens" && k != "version") rest[k] = profile[k]!!
        val metadata = deepMerge(existing, rest)
        val current = metadata["tokens"]
        if (current != null && current.truthy && current !is JsValue.Arr) metadata["tokens"] = JsValue.Undefined
        if (profileTokens !is JsValue.Undefined) metadata["tokens"] = profileTokens
        val sanitized = sanitizeTokens(metadata["tokens"] ?: JsValue.Undefined)
        metadata["tokens"] = sanitized
        metadata["version"] = JsValue.Num(2.0)
        return metadata
    }

    private fun isPlainObject(v: JsValue?) = v is JsValue.Obj

    private fun deepMerge(target: JsValue.Obj, source: JsValue.Obj): JsValue.Obj {
        val result = target.copy()
        for (key in source.keys) {
            if (key in DENIED_KEYS) continue
            val src = source[key]!!
            val tgt = result[key]
            result[key] = if (isPlainObject(src) && isPlainObject(tgt)) deepMerge(tgt as JsValue.Obj, src as JsValue.Obj) else src
        }
        return result
    }

    /** SDK sanitizeTokens: drops `privateKey` and `username` from each token's `meta`; anything but an array is undefined. */
    private fun sanitizeTokens(tokens: JsValue): JsValue {
        if (tokens !is JsValue.Arr) return JsValue.Undefined
        return JsValue.Arr(
            tokens.items.map { token ->
                // ({ meta, ...rest }) => ... destructures the element
                val (meta, rest) = destructure(token, "meta")
                if (meta == null || !meta.truthy || (meta !is JsValue.Obj && meta !is JsValue.Arr)) {
                    rest.also { it["meta"] = meta ?: JsValue.Undefined }
                } else {
                    val (_, safeMeta) = destructure(meta, "privateKey", "username")
                    rest.also { it["meta"] = safeMeta }
                }
            },
        )
    }

    /**
     * `const { a, b, ...rest } = value` for a JSON value: the first picked property and the rest as a new
     * object. Primitives destructure like their wrapper objects (a string's characters become index keys),
     * `null` throws as in JS.
     */
    private fun destructure(value: JsValue, vararg picked: String): Pair<JsValue?, JsValue.Obj> {
        val source: JsValue.Obj = when (value) {
            is JsValue.Obj -> value
            is JsValue.Arr -> arrayAsObject(value)
            is JsValue.Str -> JsValue.Obj().also { o -> value.value.forEachIndexed { i, c -> o[i.toString()] = JsValue.Str(c.toString()) } }
            is JsValue.Num, is JsValue.Bool -> JsValue.Obj()
            JsValue.Null, JsValue.Undefined -> throw InvalidInputException("Cannot destructure a null token")
        }
        val rest = JsValue.Obj()
        for (k in source.keys) if (k !in picked) rest[k] = source[k]!!
        return source[picked.first()] to rest
    }

    private fun arrayAsObject(a: JsValue.Arr) = JsValue.Obj().also { o -> a.items.forEachIndexed { i, v -> o[i.toString()] = v } }
}
