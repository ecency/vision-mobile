package com.ecency.core.app

import com.ecency.core.api.json.SafeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The last deposit memo each account sent to each exchange ([ExchangeAccounts]), on this device only
 * (`transfer.exchangeMemos`: account, then exchange, then memo). Kept after a transfer went through, offered on the
 * transfer screen the next time the same account sends to the same exchange, never filled in by itself: a wrong
 * deposit memo can lose the funds, and an account may have several. Gone when the account leaves the device.
 * The memo was public on chain already; no secret is kept ([MemoSecrets] refuses those before they are sent).
 */
class ExchangeMemos internal constructor(private val env: AppEnvironment) {
    private val prefs: Prefs get() = env.prefs

    fun get(account: String, exchange: String): String? = read()[account]?.get(exchange.lowercase())

    /** After a transfer from [account] to [exchange] with [memo] went through. */
    fun save(account: String, exchange: String, memo: String) {
        val m = memo.trim()
        // nothing secret, and no `#` memo (meant to be encrypted, #3633): only what went on chain as plain text
        if (m.isEmpty() || m.startsWith("#") || MemoSecrets.contains(m)) return
        val all = read().toMutableMap()
        all[account] = (all[account].orEmpty() + (exchange.lowercase() to m))
        write(all)
    }

    /** Accounts that left the device (null: all of them). */
    fun forget(accounts: List<String>?) {
        if (accounts == null) return write(emptyMap())
        val all = read()
        if (accounts.any { it in all }) write(all - accounts.toSet())
    }

    private fun read(): Map<String, Map<String, String>> {
        val raw = runCatching { prefs.getString(PREFS_KEY) }.getOrNull() ?: return emptyMap()
        val o = SafeJson.parseOrNull(raw) as? JsonObject ?: return emptyMap()
        return o.mapNotNull { (account, v) ->
            val inner = (v as? JsonObject)?.mapNotNull { (ex, memo) -> runCatching { memo.jsonPrimitive.contentOrNull }.getOrNull()?.let { ex to it } }?.toMap()
            inner?.let { account to it }
        }.toMap()
    }

    /** A preferences failure only means the memo is not remembered. */
    private fun write(all: Map<String, Map<String, String>>) {
        val json = if (all.isEmpty()) null else JsonObject(all.mapValues { (_, m) -> JsonObject(m.mapValues { JsonPrimitive(it.value) }) }).toString()
        runCatching { prefs.putString(PREFS_KEY, json) }
    }

    companion object {
        const val PREFS_KEY = "transfer.exchangeMemos"
    }
}
