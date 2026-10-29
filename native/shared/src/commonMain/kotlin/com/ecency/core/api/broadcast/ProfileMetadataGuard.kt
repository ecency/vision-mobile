package com.ecency.core.api.broadcast

import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.Account
import com.ecency.core.api.model.JsValue
import com.ecency.core.api.model.Profile
import com.ecency.core.api.model.ProfileMetadata
import com.ecency.core.hive.AccountUpdate2
import com.ecency.core.l10n.L
import kotlinx.serialization.json.JsonArray

/**
 * The one guard of every profile write (account_update2: profile edit, pin and unpin to blog). Some nodes
 * answer `get_accounts` with an empty or cut posting_json_metadata; an update built on that base would replace
 * the whole profile (name, about, avatar, cover, website, location, tokens) with the fields being changed. RN
 * guards the same way (postOptionsModal `_updatePinnedPost` with src/utils/profileMergeBase.ts, and the SDK's
 * getAccountFull for the edit).
 *
 * [safeBase] returns the base to merge onto, or null when the write must be refused. [operation] is the one
 * builder: it merges the caller's profile over that base with [ProfileMetadata.buildPostingJsonMetadata]
 * (the SDK's, byte for byte), so unknown keys and the wallet token list survive every write.
 */
object ProfileMetadataGuard {
    /** Shown when a write is refused (toast for the post menu, alert for the profile edit). */
    val REFUSED: String get() = L.native.profileMetadataGuard.refused

    /** The profile fields hivemind's `bridge.get_profile` exposes (metadata.profile). */
    private val KNOWN_KEYS = listOf("name", "about", "website", "location", "profile_image", "cover_image")

    /**
     * A checked base of a profile write.
     *
     * @property postingJsonMetadata the on-chain posting_json_metadata the write keeps every other key of.
     * @property profile the profile the write starts from: the posting metadata's, or the legacy
     * `json_metadata` one while the posting metadata has none. A fresh copy, free to change.
     */
    class Base(val postingJsonMetadata: String, val profile: JsValue.Obj)

    /**
     * The profile a write starts from and the edit form shows: the posting metadata's, or, while that has no
     * keys, the legacy `json_metadata` one, so a write from such an account carries its fields into the posting
     * metadata instead of dropping them. A legacy profile without the `profile` wrapper (fields at the root,
     * which hivemind reads too) gives its profile fields only.
     */
    fun effectiveProfile(postingJsonMetadata: String?, jsonMetadata: String?): JsValue.Obj {
        val posting = ProfileMetadata.parseProfile(postingJsonMetadata)
        if (posting.keys.isNotEmpty()) return posting
        val legacy = ProfileMetadata.parseProfile(jsonMetadata)
        if (legacy.keys.isNotEmpty()) return legacy
        val root = jsonMetadata?.takeIf { it.isNotBlank() }?.let(::parse) as? JsValue.Obj ?: return legacy
        if (root.containsKey("profile")) return legacy
        return JsValue.Obj().also { seed -> for (k in ROOT_KEYS) root[k]?.let { seed[k] = it } }
    }

    private val ROOT_KEYS = KNOWN_KEYS + listOf("blacklist_description", "muted_list_description")

    private fun parse(text: String): JsValue? = SafeJson.parseOrNull(text)?.let(JsValue::from)

    /** [safeBase] over an account row just read from a node. */
    fun safeBase(fresh: Account, hivemind: Profile?): Base? = safeBase(fresh.postingJsonMetadata, fresh.jsonMetadata, hivemind)

    /**
     * @param postingJsonMetadata the account's posting_json_metadata as just read from a node.
     * @param jsonMetadata its legacy json_metadata from the same read.
     * @param hivemind what hivemind shows for the account (`bridge.get_profile`), null when it could not be read.
     * @param ownWrite this process broadcast a posting profile for the account that no node has shown yet.
     * @return the base when the read agrees with hivemind, null when the write must be refused. The read is
     * put through hivemind's own rules first ([hivemindSource], [hivemindView]), so like is compared with like:
     * - hivemind reads the posting metadata of this row: its six fields must hold at least as many non-empty
     *   values as hivemind shows. Count only, on purpose: hivemind lags a block or more behind a write, so right
     *   after a save its fields differ from the node's, and a field match would lock the user out of a second
     *   edit or a pin. A present but stale posting row costs at most the last edit;
     * - hivemind reads the legacy `json_metadata` of this row (the posting one is blank, `{}` or unreadable to
     *   it): the six fields must be what hivemind shows, field by field. A node that blanks only
     *   posting_json_metadata leaves an old legacy profile with just as many values, and counting would write
     *   it over the current one;
     * - hivemind shows something that is not this row's posting fields but is exactly its legacy fields: the
     *   posting row is a stale snapshot hived made at the hardfork, and the legacy profile is the base
     *   ([legacyOverSnapshot]). Not when [ownWrite]: after the app's own first write of a posting profile,
     *   hivemind still showing the legacy values is only lag;
     * - an empty base only when hivemind shows nothing at all.
     * Also refused: no hivemind answer, posting metadata that is not a JSON object, a `profile` that is not an
     * object.
     */
    fun safeBase(postingJsonMetadata: String?, jsonMetadata: String?, hivemind: Profile?, ownWrite: Boolean = false): Base? {
        if (hivemind == null) return null
        val text = postingJsonMetadata.orEmpty()
        if (text.isNotBlank()) {
            // unreadable metadata is never treated as empty: the builder would write over it
            val root = parse(text) as? JsValue.Obj ?: return null
            val profile = root["profile"]
            if (profile != null && profile !is JsValue.Null && profile !is JsValue.Obj) return null
        }
        val known = hivemind.profile
        val shown = mapOf(
            "name" to known.displayName, "about" to known.about, "location" to known.location, "website" to known.website,
            "profile_image" to known.profileImage, "cover_image" to known.coverImage,
        ).mapValues { it.value.orEmpty().trim() }
        val knownCount = shown.values.count { it.isNotEmpty() }
        val source = hivemindSource(text, jsonMetadata)
        val view = hivemindView(source.fields)
        if (source.legacy) {
            // null: a value hivemind shows as text the app cannot reproduce exactly (a number); not compared
            if (!KNOWN_KEYS.all { k -> view[k]?.let { it.trim() == shown[k] } ?: true }) return null
        } else {
            fun matches(v: Map<String, String?>) = KNOWN_KEYS.all { k -> v[k]?.let { it.trim() == shown[k] } ?: true }
            if (!ownWrite && knownCount > 0 && !matches(view)) {
                // A posting row hived copied from json_metadata at the hardfork and nobody wrote since: the
                // account kept editing with account_update, hivemind has no posting row of its own and shows
                // the legacy profile (the current one), and this row is a stale snapshot. Told apart by what
                // hivemind shows: not this row's fields, but exactly the legacy ones.
                val legacyFields = jsonMetadata?.takeIf { it.isNotEmpty() }?.let(::castJsonb)?.let(::pickProfile) as? JsValue.Obj
                if (legacyFields != null && matches(hivemindView(legacyFields))) return legacyOverSnapshot(text, legacyFields, jsonMetadata)
            }
            val freshCount = KNOWN_KEYS.count { k -> view[k]?.trim()?.isNotEmpty() ?: true }
            if (freshCount < knownCount) return null
        }
        val base = Base(if (text.isBlank()) "" else text, effectiveProfile(text.takeIf { it.isNotBlank() }, jsonMetadata))
        if (base.profile.keys.isEmpty() && (knownCount != 0 || hivemind.hasProfileValues)) return null
        return base
    }

    /**
     * The base for a stale hardfork snapshot in posting while hivemind reads the legacy profile: the profile
     * fields are the legacy ones entirely (a field the legacy profile lacks was deleted since the snapshot and
     * stays deleted), every other key of the legacy profile wins, and what only the snapshot holds beyond the
     * profile fields (tokens, a pin, unknown keys) is kept. The posting metadata the write merges into carries
     * that profile, so the builder cannot bring a snapshot field back.
     */
    private fun legacyOverSnapshot(postingText: String, legacyFields: JsValue.Obj, jsonMetadata: String?): Base {
        val root = (parse(postingText) as JsValue.Obj).copy()
        val snapshot = root["profile"] as? JsValue.Obj ?: JsValue.Obj()
        // a legacy profile without the wrapper gives its profile fields only (effectiveProfile's rule)
        val current = effectiveProfile(null, jsonMetadata).takeIf { it.keys.isNotEmpty() } ?: legacyFields
        val profile = JsValue.Obj()
        for (k in current.keys) profile[k] = current[k]!!
        for (k in snapshot.keys) if (k !in ROOT_KEYS && !profile.containsKey(k)) profile[k] = snapshot[k]!!
        root["profile"] = profile.copy()
        return Base(JsValue.stringify(root)!!, profile)
    }

    /**
     * Hivemind's answer is, field by field, what it would show for a row with this metadata. Null when a
     * field cannot be compared at all is never returned: a value that is not a string is skipped.
     */
    internal fun hivemindShows(postingJsonMetadata: String?, jsonMetadata: String?, hivemind: Profile): Boolean {
        val known = hivemind.profile
        val shown = mapOf(
            "name" to known.displayName, "about" to known.about, "location" to known.location, "website" to known.website,
            "profile_image" to known.profileImage, "cover_image" to known.coverImage,
        ).mapValues { it.value.orEmpty().trim() }
        val view = hivemindView(hivemindSource(postingJsonMetadata, jsonMetadata).fields)
        return KNOWN_KEYS.all { k -> view[k]?.let { it.trim() == shown[k] } ?: true }
    }

    /** The object hivemind reads the profile fields from, and whether it is the legacy `json_metadata` one. */
    internal class Source(val fields: JsValue.Obj?, val legacy: Boolean)

    /**
     * Which metadata hivemind's `bridge.get_profile` reads, a port of the first half of
     * `hivemind_postgrest_utilities.extract_profile_metadata` (hive/db/sql_scripts/postgrest/utilities):
     * the posting metadata when it is not empty, casts to jsonb (a NUL anywhere makes the cast fail) and is
     * 3 characters or more as jsonb text (`{}`, `[]`, `""` and short numbers are not), its `profile` when it has
     * that key, else its root; otherwise the legacy metadata, its `profile` or else its root. Fields exist only
     * on an object.
     */
    internal fun hivemindSource(postingJsonMetadata: String?, jsonMetadata: String?): Source {
        if (!postingJsonMetadata.isNullOrEmpty()) {
            val root = castJsonb(postingJsonMetadata)
            val tooShort = when (root) {
                null -> true
                is JsValue.Obj -> root.keys.isEmpty()
                is JsValue.Arr -> root.items.isEmpty()
                is JsValue.Str -> root.value.isEmpty()
                is JsValue.Num -> JsValue.stringify(root).orEmpty().length < 3
                else -> false
            }
            if (!tooShort) {
                val picked = pickProfile(root!!)
                // `->'profile'` on an array that holds the string "profile" is SQL NULL: the legacy one is read
                if (picked !== NO_VALUE) return Source(picked as? JsValue.Obj, legacy = false)
            }
        }
        val legacy = jsonMetadata?.takeIf { it.isNotEmpty() }?.let(::castJsonb)?.let(::pickProfile)
        return Source(legacy as? JsValue.Obj, legacy = true)
    }

    private val NO_VALUE = JsValue.Undefined

    /** jsonb `?` 'profile' then `->'profile'`, else the value itself. */
    private fun pickProfile(root: JsValue): JsValue = when {
        root is JsValue.Obj && root.containsKey("profile") -> root["profile"]!!
        root is JsValue.Arr && root.items.any { it == JsValue.Str("profile") } -> NO_VALUE
        else -> root
    }

    /** `text::jsonb`: the parsed value, or null where the cast fails (not JSON, or a NUL in any key or string). */
    private fun castJsonb(text: String): JsValue? {
        val value = parse(text) ?: return null
        fun hasNul(v: JsValue): Boolean = when (v) {
            is JsValue.Str -> '\u0000' in v.value
            is JsValue.Arr -> v.items.any(::hasNul)
            is JsValue.Obj -> v.keys.any { k -> '\u0000' in k || hasNul(v[k]!!) }
            else -> false
        }
        return value.takeIf { !hasNul(it) }
    }

    /**
     * The six fields as hivemind shows them, a port of the second half of `extract_profile_metadata`, in its
     * order and without trimming (the SQL does not trim):
     * - name: kept unless it starts with `@` or holds the text `\x00`; over 20 characters, the first 17 and "...";
     * - about, location: kept unless they hold `\x00`; over 160 / 30, the first 157 / 27 and "...";
     * - website: kept when 100 characters or fewer, with `http://` in front unless it starts with http(s)://
     *   (an empty website is shown as "http://");
     * - profile_image, cover_image: kept when they start with http(s):// and are 1024 or fewer.
     * A missing key or a JSON null is "". Any other value that is not a string is null here: hivemind shows
     * its text, which is never empty, but the app does not reproduce it and never compares it. Lengths are in
     * code points, as Postgres counts them.
     */
    internal fun hivemindView(fields: JsValue.Obj?): Map<String, String?> {
        val escapedNul = "\\x00"
        fun cut(v: String, max: Int) = if (v.codePointCount() > max) v.takeCodePoints(max - 3) + "..." else v
        fun http(v: String) = v.startsWith("http://") || v.startsWith("https://")
        fun field(key: String, rule: (String) -> String): String? = when (val v = fields?.get(key)) {
            null, is JsValue.Null, is JsValue.Undefined -> ""
            is JsValue.Str -> rule(v.value)
            else -> null
        }
        return mapOf(
            "name" to field("name") { if (it.startsWith("@") || escapedNul in it) "" else cut(it, 20) },
            "about" to field("about") { if (escapedNul in it) "" else cut(it, 160) },
            "location" to field("location") { if (escapedNul in it) "" else cut(it, 30) },
            "website" to field("website") { if (it.codePointCount() > 100) "" else if (http(it)) it else "http://$it" },
            "profile_image" to field("profile_image") { if (http(it) && it.codePointCount() <= 1024) it else "" },
            "cover_image" to field("cover_image") { if (http(it) && it.codePointCount() <= 1024) it else "" },
        )
    }

    /** The posting_json_metadata of a write: [profile] merged over [base], every other key kept, version 2. */
    fun postingJsonMetadata(base: Base, profile: JsValue.Obj): String =
        ProfileMetadata.buildPostingJsonMetadata(base.postingJsonMetadata.takeIf { it.isNotEmpty() }, profile)

    /** The `account_update2` operation of a write (metadata only, as @ecency/sdk useAccountUpdate). */
    fun operation(account: String, base: Base, profile: JsValue.Obj): JsonArray =
        JsonArray(listOf(OperationJson.encode(AccountUpdate2(account, "", postingJsonMetadata(base, profile)))))

    /**
     * RN `_updatePinnedPost`'s profile: `{ ...baseProfile, pinned }`; unpin ([pinned] null) writes
     * `"pinned":null` as RN.
     */
    fun pinnedProfile(base: Base, pinned: String?): JsValue.Obj = base.profile.copy().also {
        it["pinned"] = pinned?.let { p -> JsValue.Str(p) } ?: JsValue.Null
    }
}
