package com.ecency.core.auth

import com.ecency.core.api.broadcast.TxReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * hive:// signing links, a port of the `hive-uri` library (0.2.8) the RN app uses plus RN's own helpers in
 * `utils/hive-uri.ts`. Payloads stay JSON ([JsonArray] operations, [JsonObject] transactions); turning them
 * into binary operations is the signer's job.
 */
object HiveUri {
    const val PREFIX = "hive://"

    /** Protocol parameters (`?nb=&s=&cb=&a=`). */
    data class Params(
        val callback: String? = null,
        val noBroadcast: Boolean = false,
        val authority: String? = null,
        val signer: String? = null,
    )

    /** A decoded link: its action type (tx, op, ops, msg), the (unresolved) transaction and parameters. */
    data class Decoded(val type: String, val tx: JsonElement, val params: Params)

    /** RN normalizeHiveUri: `ecency://sign/...` (any case) becomes `hive://sign/...`; everything is trimmed. */
    fun normalize(uri: String): String {
        val trimmed = uri.trim()
        return if (trimmed.lowercase().startsWith("ecency://sign/")) PREFIX + trimmed.substring("ecency://".length) else trimmed
    }

    /** RN isHiveUri: true for hive:// and ecency://sign/ links. */
    fun isHiveUri(uri: String): Boolean = normalize(uri).startsWith(PREFIX)

    /** RN isWebUrl: http(s) urls a WebView may load; everything else goes to the OS. */
    fun isWebUrl(url: String): Boolean = Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url.trim())

    fun encodeTx(tx: JsonObject, params: Params = Params()) = "${PREFIX}sign/tx/" + B64u.encode(tx.toString()) + encodeParams(params)
    fun encodeOp(op: JsonArray, params: Params = Params()) = "${PREFIX}sign/op/" + B64u.encode(op.toString()) + encodeParams(params)
    fun encodeOps(ops: JsonArray, params: Params = Params()) = "${PREFIX}sign/ops/" + B64u.encode(ops.toString()) + encodeParams(params)
    fun encodeMsg(msg: JsonElement, params: Params = Params()) = "${PREFIX}sign/msg/" + B64u.encode(msg.toString()) + encodeParams(params)

    private fun encodeParams(p: Params): String {
        val parts = buildList {
            if (p.noBroadcast) add("nb=")
            p.signer?.takeIf { it.isNotEmpty() }?.let { add("s=$it") }
            p.callback?.takeIf { it.isNotEmpty() }?.let { add("cb=" + B64u.encode(it)) }
            p.authority?.takeIf { it.isNotEmpty() }?.let { add("a=$it") }
        }
        return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
    }

    /**
     * hive-uri `decode`. Op and ops links become a transaction with `__ref_block_num`, `__ref_block_prefix`
     * and `__expiration` placeholders.
     * @throws AuthException.InvalidLink for a wrong scheme, action or payload.
     */
    @Throws(AuthException::class)
    fun decode(uri: String): Decoded {
        if (!uri.startsWith("hive:")) throw AuthException.InvalidLink("Invalid protocol, expected 'hive:'")
        val afterScheme = uri.removePrefix("hive:").removePrefix("//")
        val beforeHash = afterScheme.substringBefore('#')
        val authorityAndPath = beforeHash.substringBefore('?')
        val query = if ('?' in beforeHash) beforeHash.substringAfter('?') else ""
        val host = authorityAndPath.substringBefore('/').lowercase()
        if (host != "sign") throw AuthException.InvalidLink("Invalid action, expected 'sign'")
        val segments = authorityAndPath.substringAfter('/', "").split('/')
        val type = segments.getOrElse(0) { "" }
        val raw = segments.getOrNull(1) ?: throw AuthException.InvalidLink("Invalid payload")
        val payload = try {
            Json.parseToJsonElement(B64u.decode(raw))
        } catch (e: Exception) {
            throw AuthException.InvalidLink("Invalid payload")
        }
        val tx: JsonElement = when (type) {
            "tx", "msg" -> payload
            "op", "ops" -> buildJsonObject {
                put("ref_block_num", JsonPrimitive("__ref_block_num"))
                put("ref_block_prefix", JsonPrimitive("__ref_block_prefix"))
                put("expiration", JsonPrimitive("__expiration"))
                put("extensions", JsonArray(emptyList()))
                put("operations", if (type == "ops") payload else JsonArray(listOf(payload)))
            }
            else -> throw AuthException.InvalidLink("Invalid signing action '$type'")
        }
        val query2 = query.split('&').filter { it.isNotEmpty() }.associate { part ->
            val pieces = part.split('=')
            decodeComponent(pieces[0]) to decodeComponent(pieces.getOrElse(1) { "" })
        }
        val params = Params(
            callback = query2["cb"]?.takeIf { it.isNotEmpty() }?.let {
                try { B64u.decode(it) } catch (e: Exception) { throw AuthException.InvalidLink("Invalid callback") }
            },
            noBroadcast = "nb" in query2,
            authority = query2["a"]?.takeIf { it.isNotEmpty() },
            signer = query2["s"]?.takeIf { it.isNotEmpty() },
        )
        return Decoded(type, tx, params)
    }

    private fun decodeComponent(s: String): String = try {
        UriComponent.decode(s)
    } catch (e: Exception) {
        throw AuthException.InvalidLink("Invalid parameter")
    }

    private val RESOLVE = Regex("__(ref_block_(num|prefix)|expiration|signer)")

    /**
     * hive-uri `resolveTransaction` plus RN's integer conversion of the reference fields: placeholders in
     * every string become the reference block, expiration and signer.
     * @throws AuthException.InvalidLink when the link names a signer other than [signer].
     */
    @Throws(AuthException::class)
    fun resolveTransaction(tx: JsonObject, params: Params, reference: TxReference, expiration: String, signer: String): JsonObject {
        val chosen = params.signer ?: signer
        if (chosen != signer) throw AuthException.InvalidLink("Signer '$chosen' not available")
        val ctx = mapOf(
            "__ref_block_num" to reference.refBlockNum.toString(),
            "__ref_block_prefix" to reference.refBlockPrefix.toString(),
            "__expiration" to expiration,
            "__signer" to chosen,
        )
        fun walk(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.mapValues { walk(it.value) })
            is JsonArray -> JsonArray(e.map(::walk))
            is JsonPrimitive -> if (e.isString) JsonPrimitive(RESOLVE.replace(e.content) { ctx.getValue(it.value) }) else e
            JsonNull -> e
        }
        val resolved = walk(tx) as JsonObject
        fun int(k: String) = resolved[k]?.jsonPrimitive?.contentOrNull?.let { Regex("^\\s*[+-]?\\d+").find(it)?.value?.trim()?.toLongOrNull() }
        return JsonObject(
            resolved + mapOf(
                "ref_block_num" to (int("ref_block_num")?.let(::JsonPrimitive) ?: JsonNull),
                "ref_block_prefix" to (int("ref_block_prefix")?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )
    }

    /** hive-uri `resolveCallback`: `{{sig}}`, `{{id}}`, `{{block}}`, `{{txn}}`, `{{data}}` placeholders. */
    fun resolveCallback(url: String, values: Map<String, String>): String =
        Regex("\\{\\{(sig|id|block|txn|data)\\}\\}").replace(url) { values[it.groupValues[1]].orEmpty() }

    /** RN resolveTransaction's expiration: two minutes from now, "YYYY-MM-DDTHH:MM:SS" UTC. */
    fun expirationFrom(nowMillis: Long): String =
        com.ecency.core.hive.ChainTime.fromEpochSeconds((nowMillis + 2 * 60_000) / 1000)

    /** Builds ops as `[[name, {fields}], ...]`. */
    fun ops(vararg ops: Pair<String, JsonObject>): JsonArray = buildJsonArray {
        ops.forEach { (name, body) -> add(buildJsonArray { add(JsonPrimitive(name)); add(body) }) }
    }
}
