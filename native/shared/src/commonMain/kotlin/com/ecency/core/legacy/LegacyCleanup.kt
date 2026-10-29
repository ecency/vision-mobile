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
 */
object LegacyCleanup {
    private val SECRET_KEYS = listOf("user", "sc_accounts", "auth")
    private val PIN_FIELDS = listOf("pin", "encUnlockPin")
    private val json = Json { ignoreUnknownKeys = true }

    /** True when the storage still holds any of the secrets [scrub] removes. */
    @Throws(LegacyImportError::class)
    fun hasSecrets(storage: LegacyStorage): Boolean =
        storage.hasStaleCopies() || SECRET_KEYS.any { storage.read(it) != null } ||
            storage.read(ROOT)?.let { scrubbedRoot(it) != it } == true

    /** @return true when anything was removed. */
    @Throws(LegacyImportError::class)
    fun scrub(storage: LegacyStorage): Boolean {
        var changed = false
        // whole stale copies first (README "In-place upgrade from RN 3.x"): nothing reads them
        if (storage.hasStaleCopies()) {
            storage.removeStaleCopies()
            changed = true
        }
        for (key in SECRET_KEYS) {
            if (storage.read(key) != null) {
                storage.remove(key)
                changed = true
            }
        }
        val root = storage.read(ROOT) ?: return changed
        val scrubbed = scrubbedRoot(root)
        when {
            scrubbed == null -> storage.remove(ROOT) // unparsable: cannot keep the safe parts
            scrubbed != root -> storage.write(ROOT, scrubbed)
            else -> return changed
        }
        return true
    }

    private const val ROOT = "persist:root"

    /** persist:root without PINs and account `local` copies; null when it cannot be parsed. */
    private fun scrubbedRoot(raw: String): String? = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val out = root.toMutableMap()
        reducer(root, "application")?.let { app ->
            if (PIN_FIELDS.any { it in app }) out["application"] = JsonPrimitive(JsonObject(app - PIN_FIELDS.toSet()).toString())
        }
        reducer(root, "account")?.let { account ->
            val cleaned = JsonObject(account.mapValues { (_, v) -> withoutLocal(v) })
            if (cleaned != account) out["account"] = JsonPrimitive(cleaned.toString())
        }
        if (out == root) raw else JsonObject(out).toString()
    }.getOrNull()

    /** redux-persist v6 stores each reducer as a JSON string. */
    private fun reducer(root: JsonObject, name: String): JsonObject? =
        root[name]?.jsonPrimitive?.contentOrNull?.let { json.parseToJsonElement(it).jsonObject }

    /** Drops "local" from an account object, or from each account in a list. */
    private fun withoutLocal(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> if ("local" in e) JsonObject(e - "local") else e
        is JsonArray -> JsonArray(e.map(::withoutLocal))
        else -> e
    }
}
