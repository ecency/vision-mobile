package com.ecency.core.legacy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deletes the key-bearing parts of the RN app's storage once the vault holds the accounts.
 * [LegacyMigration.run] calls it only after a verified migration; it never touches anything else
 * (settings, drafts, cache stay for later imports).
 *
 * Removed: stale copies of the whole RN storage ([LegacyStorage.hasStaleCopies]), "user" (PIN-encrypted keys and tokens), "sc_accounts" (HiveSigner tokens), "auth" (unsalted
 * PIN hash), and inside "persist:root" the encrypted app and unlock PINs (`application.pin`,
 * `application.encUnlockPin`) and every account's `local` copy of its encrypted keys.
 *
 * `keep` (the migration) names accounts whose RN secrets have no other copy (not imported, or imported without some
 * of them): their entries in "user" and "sc_accounts" and their `local` copies stay, and so do entries without a
 * username (nothing could read them) and, while any entry stays, the PINs that open them. Null (the PIN wipe)
 * removes all of it.
 */
object LegacyCleanup {
    private val SECRET_KEYS = listOf("user", "sc_accounts", "auth")
    /** Lists of per-account entries, keyed by "username". */
    private val ACCOUNT_LISTS = setOf("user", "sc_accounts")
    private val PIN_FIELDS = listOf("pin", "encUnlockPin")
    private val json = Json { ignoreUnknownKeys = true }

    /** True when the storage still holds any of the secrets [scrub] removes. */
    @Throws(LegacyImportError::class)
    fun hasSecrets(storage: LegacyStorage, keep: Set<String>? = null): Boolean =
        storage.hasStaleCopies() || SECRET_KEYS.any { key -> storage.read(key)?.let { scrubbed(key, it, keep) != it } == true } ||
            storage.read(ROOT)?.let { scrubbedRoot(it, pinsNeeded(storage, keep), dropping(keep)) != it } == true

    /** @return true when anything was removed. */
    @Throws(LegacyImportError::class)
    fun scrub(storage: LegacyStorage, keep: Set<String>? = null): Boolean {
        var changed = false
        // whole stale copies first (README "In-place upgrade from RN 3.x"): nothing reads them
        if (storage.hasStaleCopies()) {
            storage.removeStaleCopies()
            changed = true
        }
        for (key in SECRET_KEYS) {
            val raw = storage.read(key) ?: continue
            val left = scrubbed(key, raw, keep)
            if (left == raw) continue
            if (left == null) storage.remove(key) else storage.write(key, left)
            changed = true
        }
        val root = storage.read(ROOT) ?: return changed
        val scrubbed = scrubbedRoot(root, pinsNeeded(storage, keep), dropping(keep))
        when {
            scrubbed == null -> storage.remove(ROOT) // unparsable: cannot keep the safe parts
            scrubbed != root -> storage.write(ROOT, scrubbed)
            else -> return changed
        }
        return true
    }

    /**
     * Deletes only the RN entries of [names] (accounts the user removed in the app): theirs in "user" and
     * "sc_accounts" and their `local` copies. The PINs and everything else stay.
     */
    @Throws(LegacyImportError::class)
    fun forget(storage: LegacyStorage, names: Set<String>) {
        for (key in ACCOUNT_LISTS) {
            val raw = storage.read(key) ?: continue
            val list = runCatching { json.parseToJsonElement(raw) as JsonArray }.getOrNull() ?: continue
            val left = list.filter { e -> nameOf(e, "username")?.let { it in names } != true }
            if (left.size != list.size) storage.write(key, JsonArray(left).toString())
        }
        val root = storage.read(ROOT) ?: return
        val left = scrubbedRoot(root, keepPins = true) { it != null && it in names } ?: return
        if (left != root) storage.write(ROOT, left)
    }

    private const val ROOT = "persist:root"

    /** The `local` copies to drop: all but those of accounts in [keep] (all when null). */
    private fun dropping(keep: Set<String>?): (String?) -> Boolean = { it == null || keep == null || it !in keep }

    /** Kept "user" entries are encrypted with one of the PINs. */
    private fun pinsNeeded(storage: LegacyStorage, keep: Set<String>?): Boolean =
        keep != null && storage.read("user")?.let { scrubbed("user", it, keep) } != null

    /**
     * What stays of a secret key: null to remove it, else a list without the entries of accounts not in [keep].
     * A list that cannot be parsed is kept whole unless [keep] is null.
     */
    private fun scrubbed(key: String, raw: String, keep: Set<String>?): String? {
        if (keep == null || key !in ACCOUNT_LISTS) return null
        val list = runCatching { json.parseToJsonElement(raw) as JsonArray }.getOrNull() ?: return raw
        val left = list.filter { e -> nameOf(e, "username")?.let { it in keep } ?: true }
        return when {
            left.isEmpty() -> null
            left.size == list.size -> raw
            else -> JsonArray(left).toString()
        }
    }

    /** persist:root without PINs (unless [keepPins]) and the `local` copies of accounts [drop] names; null when it cannot be parsed. */
    private fun scrubbedRoot(raw: String, keepPins: Boolean, drop: (String?) -> Boolean): String? = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val out = root.toMutableMap()
        reducer(root, "application")?.let { app ->
            if (!keepPins && PIN_FIELDS.any { it in app }) out["application"] = JsonPrimitive(JsonObject(app - PIN_FIELDS.toSet()).toString())
        }
        reducer(root, "account")?.let { account ->
            val cleaned = JsonObject(account.mapValues { (_, v) -> withoutLocal(v, drop) })
            if (cleaned != account) out["account"] = JsonPrimitive(cleaned.toString())
        }
        if (out == root) raw else JsonObject(out).toString()
    }.getOrNull()

    /** redux-persist v6 stores each reducer as a JSON string. */
    private fun reducer(root: JsonObject, name: String): JsonObject? =
        root[name]?.jsonPrimitive?.contentOrNull?.let { json.parseToJsonElement(it).jsonObject }

    /** Drops "local" from an account object, or from each account in a list, when [drop] names the account. */
    private fun withoutLocal(e: JsonElement, drop: (String?) -> Boolean): JsonElement = when (e) {
        is JsonObject -> if ("local" in e && drop(nameOf(e, "name"))) JsonObject(e - "local") else e
        is JsonArray -> JsonArray(e.map { withoutLocal(it, drop) })
        else -> e
    }

    private fun nameOf(e: JsonElement, field: String): String? =
        runCatching { (e as? JsonObject)?.get(field)?.jsonPrimitive?.contentOrNull }.getOrNull()
}
