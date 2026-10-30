package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.CryptoJsCipher
import com.ecency.core.auth.FakeAuthHttp
import com.ecency.core.auth.HasSocket
import com.ecency.core.auth.HasSocketFactory
import com.ecency.core.auth.HiveSignerMessages
import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/** Throwaway keys of the app-layer test accounts: every role derives from a public test password. */
object AppTestKeys {
    const val PASSWORD = "P5AppLayerThrowawayPasswordNotReal"
    fun key(user: String, role: KeyRole): PrivateKey = PrivateKey.fromLogin(user, PASSWORD, role)
    fun wif(user: String, role: KeyRole) = key(user, role).toWif()
    fun pub(user: String, role: KeyRole) = key(user, role).publicKey().toString()

    /** condenser_api.get_accounts JSON for [user] with every role's key; [postingApps] in posting account_auths. */
    fun accountJson(user: String, postingApps: List<String> = emptyList()): String {
        fun auth(role: KeyRole, apps: List<String> = emptyList()) =
            """{"weight_threshold":1,"account_auths":[${apps.joinToString(",") { "[\"$it\",1]" }}],"key_auths":[["${pub(user, role)}",1]]}"""
        return """{"name":"$user","owner":${auth(KeyRole.OWNER)},"active":${auth(KeyRole.ACTIVE)},"posting":${auth(KeyRole.POSTING, postingApps)},""" +
            """"memo_key":"${pub(user, KeyRole.MEMO)}","json_metadata":"","posting_json_metadata":"","reputation":0,"post_count":1}"""
    }
}

/**
 * Ecency's token endpoint and HiveSigner's API, scripted: a signed code exchanges for tokens of its author,
 * `refresh-<user>` refreshes, `hs-code-<user>` is a HiveSigner OAuth code, a HiveAuth code exchanges for its
 * author. [down] makes every call a network failure, [refuse] answers 401.
 */
class FakeAuthBackend {
    var down = false
    var refuse = false

    /** Answers Ecency private API calls (`/private-api/...`); null leaves them to the token logic (HTTP 400). */
    var privateApi: ((AuthHttpRequest) -> HttpResult)? = null
    private val recorder = FakeAuthHttp { request -> answer(request) }

    /** Runs before a request is answered: suspend here to hold an answer back (a slow network). */
    var gate: (suspend (AuthHttpRequest) -> Unit)? = null
    val http = com.ecency.core.auth.AuthHttp { request ->
        gate?.invoke(request)
        recorder.send(request)
    }
    val sent: List<AuthHttpRequest> get() = recorder.sent

    /** Answers Ecency REST calls (private API, search, translation, polls) first; null falls through to the token fake. */
    var rest: ((AuthHttpRequest) -> HttpResult?)? = null

    private fun answer(request: AuthHttpRequest): HttpResult {
        rest?.invoke(request)?.let { return it }
        if (down) throw IllegalStateException("offline")
        if (request.url.contains("/private-api/")) privateApi?.let { return it(request) }
        if (refuse) return HttpResult(401, """{"error":"invalid_grant"}""")
        val body = Json.parseToJsonElement(request.body ?: "{}").jsonObject
        if (request.url.endsWith("/api/broadcast")) return HttpResult(200, """{"result":{"id":"hs-tx-1"}}""")
        val code = body["code"]?.jsonPrimitive?.content ?: return HttpResult(400, "{}")
        val user = when {
            code.startsWith("refresh-") -> code.removePrefix("refresh-")
            code.startsWith("hs-code-") -> code.removePrefix("hs-code-")
            else -> runCatching { HiveSignerMessages.decode(code).authors.first() }
                .recoverCatching { HiveSignerMessages.decode(code, standardBase64 = true).authors.first() }
                .getOrNull() ?: return HttpResult(400, "{}")
        }
        return FakeAuthHttp.tokens(user, access = "access-$user", refresh = "refresh-$user")
    }
}

/**
 * A HiveAuth relay double. `auth_req` gets an `auth_wait`; the test approves with [approveAuth] (it reads the
 * auth key from the wallet link, as a wallet would) or rejects. `sign_req` gets a `sign_wait` and, with
 * [autoApproveSign], a `sign_ack`.
 */
class FakeHasRelay : HasSocketFactory {
    val sent = mutableListOf<JsonObject>()
    private var channel = Channel<String>(Channel.UNLIMITED)
    var autoApproveSign = true
    var waitExpire = 4_000_000_000_000L

    fun push(json: JsonObject) {
        channel.trySend(json.toString())
    }

    /** Chat websockets (`/api/mattermost/websocket`) go here instead of the relay double. */
    var chat: HasSocketFactory? = null

    /** Every address opened (tests check where a token went). */
    val opened = mutableListOf<String>()

    override suspend fun open(host: String): HasSocket {
        opened += host
        chat?.takeIf { "/api/mattermost/websocket" in host }?.let { return it.open(host) }
        val ch = channel
        return object : HasSocket {
            override suspend fun send(text: String) {
                val json = Json.parseToJsonElement(text).jsonObject
                sent += json
                when (json["cmd"]?.jsonPrimitive?.content) {
                    "auth_req" -> push(buildJsonObject { put("cmd", "auth_wait"); put("uuid", "auth-1"); put("expire", waitExpire) })
                    "sign_req" -> {
                        push(buildJsonObject { put("cmd", "sign_wait"); put("uuid", "sign-1"); put("expire", waitExpire) })
                        if (autoApproveSign) push(buildJsonObject { put("cmd", "sign_ack"); put("uuid", "sign-1"); put("broadcast", true); put("data", "has-tx-1") })
                    }
                }
            }
            override val incoming: ReceiveChannel<String> = ch
            override suspend fun close() {
                ch.close()
                channel = Channel(Channel.UNLIMITED)
            }
        }
    }

    /** The wallet approves the login in [link], signing the challenge with [user]'s posting key. */
    fun approveAuth(link: String, user: String, signWith: PrivateKey) {
        val payload = Json.parseToJsonElement(Base64.Default.decode(link.substringAfter("auth_req/")).decodeToString()).jsonObject
        val key = payload["key"]!!.jsonPrimitive.content
        val req = sent.last { it["cmd"]?.jsonPrimitive?.content == "auth_req" }
        val challenge = Json.parseToJsonElement(CryptoJsCipher.decrypt(req["data"]!!.jsonPrimitive.content, key)).jsonObject["challenge"]!!
            .jsonObject["challenge"]!!.jsonPrimitive.content
        val signature = HiveSignerMessages.sign(challenge, signWith)
        val data = """{"token":"has-token-$user","expire":${waitExpire + 1},"challenge":{"challenge":"$signature","pubkey":"${signWith.publicKey()}"}}"""
        push(buildJsonObject { put("cmd", "auth_ack"); put("uuid", "auth-1"); put("data", CryptoJsCipher.encrypt(data, key)) })
    }

    fun rejectAuth(link: String) {
        val key = Json.parseToJsonElement(Base64.Default.decode(link.substringAfter("auth_req/")).decodeToString()).jsonObject["key"]!!.jsonPrimitive.content
        push(buildJsonObject { put("cmd", "auth_nack"); put("uuid", "auth-1"); put("data", CryptoJsCipher.encrypt("auth-1", key)) })
    }
}
