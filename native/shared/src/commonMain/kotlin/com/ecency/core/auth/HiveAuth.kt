package com.ecency.core.auth

import com.ecency.core.api.rpc.platformWebSocketClient
import com.ecency.core.hive.KeyRole
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * HiveAuth (HAS) protocol, ported from `hive-auth-wrapper` 1.0.0 as RN `useHiveAuth` drives it.
 *
 * The app talks to a relay over a websocket; the wallet (PKSA) receives the request through the relay and
 * the user opens it with a `has://` link. Payloads are encrypted with crypto-js AES under the session's
 * auth key (a UUID only the app and the wallet know): [CryptoJsCipher].
 */
object HiveAuthProtocol {
    const val DEFAULT_HOST = "wss://hive-auth.arcange.eu/"
    val SUPPORTED_PROTOCOLS = listOf(0.8, 1.0)

    /** RN APP_META (icon undefined, so absent from the JSON). */
    const val APP_NAME = "Ecency"
    const val APP_DESCRIPTION = "Decentralised Social Blogging"

    /** Default request timeout until the relay's `connected` message says otherwise. */
    const val DEFAULT_TIMEOUT_MILLIS = 60_000L

    /** Wallet link schemes RN tries, in order. */
    val SCHEMES = listOf("has", "waves")

    @OptIn(ExperimentalUuidApi::class)
    fun newAuthKey(): String = Uuid.random().toString()

    /** The plaintext of `auth_req.data`: `{"app":{...},"challenge":{...}}` (challenge omitted when null). */
    fun authRequestPayload(challenge: HiveAuthChallenge?): String = buildJsonObject {
        put("app", buildJsonObject { put("name", APP_NAME); put("description", APP_DESCRIPTION) })
        if (challenge != null) put("challenge", buildJsonObject { put("key_type", challenge.keyType); put("challenge", challenge.challenge) })
    }.toString()

    /** The plaintext of `sign_req.data`. */
    fun signRequestPayload(keyType: KeyRole, ops: JsonArray, nonceMillis: Long): String = buildJsonObject {
        put("key_type", keyType.value)
        put("ops", ops)
        put("broadcast", true)
        put("nonce", nonceMillis)
    }.toString()

    /**
     * The link that opens the wallet for an `auth_wait`: `<scheme>://auth_req/<base64(JSON)>` with the
     * account, request uuid, auth key and relay host (RN `btoa(JSON.stringify(payload))`).
     */
    fun authLink(account: String, uuid: String, authKey: String, host: String = DEFAULT_HOST, scheme: String = "has"): String {
        val json = buildJsonObject {
            put("account", account)
            put("uuid", uuid)
            put("key", authKey)
            put("host", host)
        }.toString()
        return "$scheme://auth_req/" + Base64.Default.encode(json.encodeToByteArray())
    }

    /** The link that brings the wallet up for a pending `sign_wait`. */
    fun signLink(scheme: String = "has"): String = "$scheme://sign_req"

    /**
     * RN extractPreferredScheme: the wallet scheme the relay's wait message names, if any (`has` or `waves`).
     */
    fun preferredScheme(wait: JsonObject): String? {
        fun schemeOf(e: JsonElement?): String? = when (e) {
            is JsonPrimitive -> e.contentOrNull?.substringBefore("://")?.lowercase()?.takeIf { it in SCHEMES }
            is JsonArray -> e.firstNotNullOfOrNull(::schemeOf)
            else -> null
        }
        val app = wait["app"] as? JsonObject
        listOf(wait["scheme"], wait["host"], wait["redirect"], app?.get("scheme"), app?.get("host"), app?.get("redirect"), wait["hosts"], app?.get("hosts"))
            .firstNotNullOfOrNull(::schemeOf)?.let { return it }
        val name = (app?.get("name") as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return null
        return when {
            "wave" in name -> "waves"
            "has" in name || "hive" in name -> "has"
            else -> null
        }
    }

    private val NOT_CONNECTED = listOf(
        Regex("not\\s+(?:been\\s+)?connected(?:\\s+to\\s+server)?", RegexOption.IGNORE_CASE),
        Regex("websocket\\s+(?:is\\s+)?(?:not\\s+open|closed|not\\s+connected)", RegexOption.IGNORE_CASE),
        Regex("connection\\s+(?:closed|lost|refused|reset)", RegexOption.IGNORE_CASE),
    )

    /** RN isHasNotConnectedError: the relay lost the account session; authenticate again and retry. */
    fun isNotConnectedError(message: String?): Boolean = message != null && NOT_CONNECTED.any { it.containsMatchIn(message) }
}

/** A challenge the wallet signs during authentication. */
data class HiveAuthChallenge(val keyType: String, val challenge: String)

/** The relay's notice that a request waits for the wallet. Open [link] (auth) or bring the wallet up (sign). */
data class HiveAuthWait(val uuid: String, val expiresAtMillis: Long, val link: String, val raw: JsonObject)

/** A HiveAuth session as stored (RN `hiveAuthKey`, `hiveAuthToken`, `hiveAuthExpiry`). */
data class HiveAuthSession(val username: String, val key: String, val token: String?, val expiresAtMillis: Long) {
    fun isExpired(nowMillis: Long) = expiresAtMillis in 1..nowMillis

    override fun toString(): String = "HiveAuthSession(username=$username, expiresAtMillis=$expiresAtMillis, secrets=<redacted>)"
}

/** An approved authentication: the new session and, when one was requested, the challenge signature. */
data class HiveAuthResult(val session: HiveAuthSession, val challengeSignature: String?, val challengePublicKey: String?)

/** An approved sign request; [txId] when the wallet reported one. */
data class HiveAuthSignResult(val uuid: String, val broadcast: Boolean, val txId: String?)

/** One websocket connection to the relay. Text frames only. */
interface HasSocket {
    @Throws(Exception::class)
    suspend fun send(text: String)

    /** Incoming text frames; closed when the connection drops. */
    val incoming: ReceiveChannel<String>

    suspend fun close()

    /** The server's close code once [incoming] is closed, when the engine reported one. */
    val closeCode: Int? get() = null
}

/**
 * The server answered the websocket handshake with HTTP [status] instead of 101. Built from the engine's own report:
 * Ktor's WebSockets plugin (any status the engine hands back as a response: 401 on OkHttp and Darwin) and OkHttp's
 * ProtocolException for the others. Darwin fails other statuses with NSURLErrorBadServerResponse ("There was a bad
 * response from the server."), which carries no status, so there they stay ordinary (retried) failures.
 */
class HasSocketRefused(val status: Int, cause: Throwable? = null) : Exception("websocket handshake refused: $status", cause) {
    companion object {
        // exact wordings: Ktor WebSockets.kt "Handshake exception, expected status code 101 but was 401"; OkHttp
        // RealWebSocket.checkUpgradeSuccess "Expected HTTP 101 response but was '403 Forbidden'"
        private val KTOR = Regex("""^Handshake exception, expected status code 101 but was (\d{3})$""")
        private val OKHTTP = Regex("""^Expected HTTP 101 response but was '(\d{3})[ ']""")

        /** The handshake status [e] reports, or null when it is not a handshake refusal (a timeout, DNS, TLS ...). */
        fun statusOf(e: Throwable): Int? {
            if (e is HasSocketRefused) return e.status
            var t: Throwable? = e
            var hops = 0
            while (t != null && hops++ < 4) {
                val m = t.message.orEmpty()
                (KTOR.find(m) ?: OKHTTP.find(m))?.let { return it.groupValues[1].toInt() }
                t = t.cause
            }
            return null
        }
    }
}

/** Opens [HasSocket]s to a relay host. */
fun interface HasSocketFactory {
    @Throws(Exception::class)
    suspend fun open(host: String): HasSocket
}

/** [HasSocketFactory] over Ktor websockets (OkHttp on Android, NSURLSession on iOS). */
class KtorHasSocketFactory(private val client: HttpClient) : HasSocketFactory {
    constructor() : this(platformWebSocketClient(CONNECT_TIMEOUT_MILLIS).config { install(WebSockets) })

    companion object {
        /** RN mattermostWebSocket.ts connectionTimeout: the engine's connect bound matches the callers' own 15 s. */
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
    }

    @Throws(Exception::class)
    override suspend fun open(host: String): HasSocket {
        val session = try {
            client.webSocketSession(host)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw HasSocketRefused.statusOf(e)?.let { HasSocketRefused(it, e) } ?: e
        }
        val channel = Channel<String>(Channel.UNLIMITED)
        val socket = object : HasSocket {
            override suspend fun send(text: String) = session.send(Frame.Text(text))
            override val incoming: ReceiveChannel<String> = channel
            override suspend fun close() = session.close()
            override var closeCode: Int? = null
        }
        session.launch {
            try {
                for (frame in session.incoming) if (frame is Frame.Text) channel.send(frame.readText())
            } finally {
                // the code the server closed with (OkHttp's session and Ktor's default session both record it)
                socket.closeCode = runCatching { withTimeoutOrNull(1_000) { session.closeReason.await() }?.code?.toInt() }.getOrNull()
                channel.close()
            }
        }
        return socket
    }
}

/**
 * The HAS client: `authenticate` (auth_req, auth_wait, auth_ack/nack/err) and `broadcast` (sign_req,
 * sign_wait, sign_ack/nack/err), with the relay's expiry and, when the socket drops while waiting, a
 * reconnect plus `attach_req` for the pending request, as hive-auth-wrapper does.
 *
 * Stricter than the wrapper: messages left over from an earlier request are discarded when a request
 * starts, a wait that times out closes the socket, only the first wait of a request is taken and every
 * later answer must carry its uuid, and both auth_nack and sign_nack count only when their data decrypts
 * to that uuid.
 */
class HiveAuthClient(
    private val sockets: HasSocketFactory,
    private val nowMillis: () -> Long,
    val host: String = HiveAuthProtocol.DEFAULT_HOST,
) {
    private var socket: HasSocket? = null
    private var timeoutMillis = HiveAuthProtocol.DEFAULT_TIMEOUT_MILLIS
    private val pending = ArrayDeque<JsonObject>()
    private val lock = Mutex()

    /** Relay timeout for a request, from its `connected` message. */
    val requestTimeoutMillis: Long get() = timeoutMillis

    private suspend fun connected(): HasSocket = socket ?: try {
        sockets.open(host).also { socket = it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.NOT_CONNECTED, "Failed to connect to HiveAuth server", e)
    }

    suspend fun close() {
        socket?.close()
        socket = null
    }

    /**
     * Authenticates [username]. [onWait] receives the `has://auth_req/...` link to open in the wallet.
     * [challenge] defaults to none; the RN login sends [HiveSignerMessages.hiveAuthChallenge] as a posting
     * challenge, whose signature becomes the Ecency code ([HiveSignerMessages.hiveAuthCode]).
     */
    @Throws(Exception::class)
    suspend fun authenticate(
        username: String,
        challenge: HiveAuthChallenge?,
        authKey: String = HiveAuthProtocol.newAuthKey(),
        token: String? = null,
        onWait: suspend (HiveAuthWait) -> Unit,
    ): HiveAuthResult = lock.withLock { authenticateLocked(username, challenge, authKey, token, onWait) }

    private suspend fun authenticateLocked(
        username: String,
        challenge: HiveAuthChallenge?,
        authKey: String,
        token: String?,
        onWait: suspend (HiveAuthWait) -> Unit,
    ): HiveAuthResult {
        pending.clear()
        val s = connected()
        val data = CryptoJsCipher.encrypt(HiveAuthProtocol.authRequestPayload(challenge), authKey)
        s.send(buildJsonObject { put("cmd", "auth_req"); put("account", username); put("data", data) }.toString())
        val wait = awaitWait("auth_wait")
        onWait(HiveAuthWait(wait.uuid, wait.expire, HiveAuthProtocol.authLink(username, wait.uuid, authKey, host, HiveAuthProtocol.preferredScheme(wait.raw) ?: "has"), wait.raw))
        while (true) {
            val m = awaitFor(wait.uuid, wait.expire, setOf("auth_ack", "auth_nack", "auth_err"))
            when (m.cmd) {
                "auth_ack" -> {
                    val plain = runCatching { CryptoJsCipher.decrypt(m.text("data") ?: "", authKey) }.getOrNull() ?: continue
                    val obj = runCatching { Json.parseToJsonElement(plain).jsonObject }.getOrNull() ?: continue
                    val ch = obj["challenge"] as? JsonObject
                    return HiveAuthResult(
                        HiveAuthSession(
                            username = username,
                            key = authKey,
                            token = obj.text("token") ?: token,
                            expiresAtMillis = runCatching { obj["expire"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0,
                        ),
                        challengeSignature = ch?.text("challenge"),
                        challengePublicKey = ch?.text("pubkey"),
                    )
                }
                "auth_nack" -> {
                    val plain = runCatching { CryptoJsCipher.decrypt(m.text("data") ?: "", authKey) }.getOrNull()
                    if (plain == wait.uuid) throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.REJECTED)
                }
                else -> throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.ERROR, m.text("error"))
            }
        }
    }

    /**
     * Sends [ops] to the wallet for signing and broadcasting with [keyType]. An expired [session] fails
     * before anything is sent (RN "auth expired"). [onWait] receives the `has://sign_req` link.
     */
    @Throws(Exception::class)
    suspend fun broadcast(
        session: HiveAuthSession,
        keyType: KeyRole,
        ops: JsonArray,
        onWait: suspend (HiveAuthWait) -> Unit,
    ): HiveAuthSignResult = lock.withLock { broadcastLocked(session, keyType, ops, onWait) }

    private suspend fun broadcastLocked(
        session: HiveAuthSession,
        keyType: KeyRole,
        ops: JsonArray,
        onWait: suspend (HiveAuthWait) -> Unit,
    ): HiveAuthSignResult {
        if (session.isExpired(nowMillis())) throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.SESSION_EXPIRED)
        require(ops.isNotEmpty()) { "missing or invalid ops" }
        pending.clear()
        val s = connected()
        val data = CryptoJsCipher.encrypt(HiveAuthProtocol.signRequestPayload(keyType, ops, nowMillis()), session.key)
        s.send(
            buildJsonObject {
                put("cmd", "sign_req")
                put("account", session.username)
                put("data", data)
                session.token?.let { put("token", it) }
            }.toString(),
        )
        val wait = awaitWait("sign_wait")
        onWait(HiveAuthWait(wait.uuid, wait.expire, HiveAuthProtocol.signLink(HiveAuthProtocol.preferredScheme(wait.raw) ?: "has"), wait.raw))
        while (true) {
            val m = awaitFor(wait.uuid, wait.expire, setOf("sign_ack", "sign_nack", "sign_err"))
            when (m.cmd) {
                "sign_ack" -> return HiveAuthSignResult(
                    wait.uuid,
                    broadcast = runCatching { m.json["broadcast"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false,
                    txId = m.text("data"),
                )
                // Like auth_nack: only a rejection whose data decrypts to the request uuid is the wallet's.
                "sign_nack" -> {
                    val plain = runCatching { CryptoJsCipher.decrypt(m.text("data") ?: "", session.key) }.getOrNull()
                    if (plain == wait.uuid) throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.REJECTED)
                }
                else -> {
                    val error = runCatching { CryptoJsCipher.decrypt(m.text("error") ?: "", session.key) }.getOrNull()
                    throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.ERROR, error)
                }
            }
        }
    }

    private class Msg(val json: JsonObject) {
        val cmd: String = json.text("cmd").orEmpty()
        fun text(k: String) = json.text(k)
    }

    private class Wait(val uuid: String, val expire: Long, val raw: JsonObject)

    /** Waits for [waitCmd] (or a relay `error`) within the relay timeout. */
    private suspend fun awaitWait(waitCmd: String): Wait {
        val deadline = nowMillis() + timeoutMillis
        val m = next(deadline) { it.cmd == waitCmd || it.cmd == "error" } ?: run {
            dropSocket()
            throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.EXPIRED)
        }
        if (m.cmd == "error") {
            val text = m.text("error")
            val reason = if (HiveAuthProtocol.isNotConnectedError(text)) AuthException.HiveAuth.Reason.NOT_CONNECTED else AuthException.HiveAuth.Reason.ERROR
            throw AuthException.HiveAuth(reason, text)
        }
        val uuid = m.text("uuid") ?: throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.INVALID_RESPONSE, "missing uuid")
        val expire = runCatching { m.json["expire"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: deadline
        return Wait(uuid, expire, m.json)
    }

    /** Waits for one of [cmds] about [uuid] until [expire]; reattaches once if the socket drops. */
    private suspend fun awaitFor(uuid: String, expire: Long, cmds: Set<String>): Msg {
        var reattached = false
        while (true) {
            val m = try {
                next(expire) { it.cmd in cmds && it.text("uuid") == uuid }
            } catch (e: SocketClosed) {
                if (reattached) throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.NOT_CONNECTED, "connection lost")
                reattached = true
                attach(uuid, expire)
                continue
            }
            if (m == null) {
                dropSocket()
                throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.EXPIRED)
            }
            return m
        }
    }

    private suspend fun attach(uuid: String, expire: Long) {
        socket = null
        val s = connected()
        s.send(buildJsonObject { put("cmd", "attach_req"); put("uuid", uuid) }.toString())
        val m = next(minOf(expire, nowMillis() + timeoutMillis)) { (it.cmd == "attach_ack" || it.cmd == "attach_nack") && it.text("uuid") == uuid }
        if (m == null || m.cmd == "attach_nack") throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.NOT_CONNECTED, "attach failed")
    }

    private class SocketClosed : Exception("socket closed")

    /** A request that timed out leaves the relay in an unknown state: close the socket so the next request starts fresh. */
    private suspend fun dropSocket() {
        val s = socket
        socket = null
        pending.clear()
        runCatching { s?.close() }
    }

    /**
     * The next message matching [accept], from those already received or the socket, until [deadline]
     * (epoch ms). Other protocol messages are kept for later; `connected` updates the timeout.
     */
    private suspend fun next(deadline: Long, accept: (Msg) -> Boolean): Msg? {
        pending.firstOrNull { accept(Msg(it)) }?.let { pending.remove(it); return Msg(it) }
        val s = connected()
        while (true) {
            val left = deadline - nowMillis()
            if (left <= 0) return null
            val text = withTimeoutOrNull(left) {
                s.incoming.receiveCatching()
            } ?: return null
            if (text.isClosed) {
                socket = null
                throw SocketClosed()
            }
            val json = runCatching { Json.parseToJsonElement(text.getOrThrow()).jsonObject }.getOrNull() ?: continue
            val m = Msg(json)
            if (m.cmd == "connected") {
                runCatching { json["timeout"]?.jsonPrimitive?.doubleOrNull }.getOrNull()?.let { timeoutMillis = (it * 1000).toLong() }
                continue
            }
            if (accept(m)) return m
            if (m.cmd.isNotEmpty()) pending.addLast(json)
        }
    }
}

private fun JsonObject.text(k: String): String? = runCatching { get(k)?.jsonPrimitive?.contentOrNull }.getOrNull()
