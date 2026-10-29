package com.ecency.core.auth

import com.ecency.core.hive.KeyRole
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HiveAuthTest {
    private val user = AuthGoldenVectors.USER
    private val key = AuthGoldenVectors.AUTH_KEY

    @Test
    fun payloadsMatchTheWrapper() {
        val challenge = HiveAuthChallenge("posting", AuthGoldenVectors.HIVE_AUTH_CHALLENGE)
        assertEquals(AuthGoldenVectors.AUTH_PAYLOAD, HiveAuthProtocol.authRequestPayload(challenge))
        assertEquals(AuthGoldenVectors.AUTH_PAYLOAD_NO_CHALLENGE, HiveAuthProtocol.authRequestPayload(null))
        val ops = Json.parseToJsonElement(AuthGoldenVectors.SIGN_OP).jsonArray
        assertEquals(AuthGoldenVectors.SIGN_PAYLOAD, HiveAuthProtocol.signRequestPayload(KeyRole.POSTING, ops, 1759800000999))
        assertEquals(AuthGoldenVectors.AUTH_LINK, HiveAuthProtocol.authLink(user, AuthGoldenVectors.AUTH_LINK_UUID, key))
        assertEquals("has://sign_req", HiveAuthProtocol.signLink())
        assertEquals(36, HiveAuthProtocol.newAuthKey().length)
    }

    @Test
    fun preferredSchemeAndNotConnectedErrors() {
        fun wait(s: String) = Json.parseToJsonElement(s).jsonObject
        assertEquals("waves", HiveAuthProtocol.preferredScheme(wait("""{"app":{"redirect":"waves://x"}}""")))
        assertEquals("has", HiveAuthProtocol.preferredScheme(wait("""{"hosts":["https://a","has://b"]}""")))
        assertEquals("waves", HiveAuthProtocol.preferredScheme(wait("""{"app":{"name":"Hive Waves"}}""")))
        assertEquals("has", HiveAuthProtocol.preferredScheme(wait("""{"app":{"name":"Hive Keychain"}}""")))
        assertNull(HiveAuthProtocol.preferredScheme(wait("""{"uuid":"x"}""")))
        assertTrue(HiveAuthProtocol.isNotConnectedError("Account has not been connected through HAS"))
        assertTrue(HiveAuthProtocol.isNotConnectedError("WebSocket is not open"))
        assertTrue(!HiveAuthProtocol.isNotConnectedError("user rejected"))
    }

    /** A relay double: [onSend] answers each sent JSON message by pushing replies. */
    private class FakeRelay(val onSend: FakeRelay.(JsonObject) -> Unit) : HasSocketFactory {
        val sent = mutableListOf<JsonObject>()
        var opened = 0
        var channel = Channel<String>(Channel.UNLIMITED)

        fun push(vararg pairs: Pair<String, Any?>) {
            channel.trySend(buildJsonObject { pairs.forEach { (k, v) -> when (v) { is Number -> put(k, v); is Boolean -> put(k, v); null -> Unit; else -> put(k, v.toString()) } } }.toString())
        }

        fun drop() {
            channel.close()
            channel = Channel(Channel.UNLIMITED)
        }

        override suspend fun open(host: String): HasSocket {
            opened++
            val ch = channel
            return object : HasSocket {
                override suspend fun send(text: String) {
                    val json = Json.parseToJsonElement(text).jsonObject
                    sent += json
                    onSend(json)
                }
                override val incoming: ReceiveChannel<String> = ch
                override suspend fun close() { ch.close() }
            }
        }
    }

    private fun TestScope.client(relay: FakeRelay) = HiveAuthClient(relay, { testScheduler.currentTime })

    private fun decrypt(message: JsonObject, k: String = key) =
        Json.parseToJsonElement(CryptoJsCipher.decrypt(message["data"]!!.jsonPrimitive.content, k)).jsonObject

    @Test
    fun authenticateApproved() = runTest {
        val relay = FakeRelay { m ->
            if (m["cmd"]!!.jsonPrimitive.content == "auth_req") {
                push("cmd" to "connected", "timeout" to 30, "protocol" to 1)
                push("cmd" to "auth_wait", "uuid" to "u1", "expire" to 50_000)
                // A stray ack for another request and one that does not decrypt are ignored.
                push("cmd" to "auth_ack", "uuid" to "u0", "data" to CryptoJsCipher.encrypt("{}", key))
                push("cmd" to "auth_ack", "uuid" to "u1", "data" to CryptoJsCipher.encrypt("{}", "wrong-key"))
                push("cmd" to "auth_ack", "uuid" to "u1", "data" to CryptoJsCipher.encrypt("""{"token":"has-token","expire":9000000,"challenge":{"challenge":"sig","pubkey":"STMx"}}""", key))
            }
        }
        var link: String? = null
        val result = client(relay).authenticate(user, HiveAuthChallenge("posting", "text"), authKey = key) { link = it.link }
        assertEquals(HiveAuthSession(user, key, "has-token", 9_000_000), result.session)
        assertEquals("sig", result.challengeSignature)
        assertEquals("STMx", result.challengePublicKey)
        assertEquals(HiveAuthProtocol.authLink(user, "u1", key), link)
        val req = relay.sent.single()
        assertEquals(user, req["account"]!!.jsonPrimitive.content)
        assertEquals("text", decrypt(req)["challenge"]!!.jsonObject["challenge"]!!.jsonPrimitive.content)
        assertTrue(Base64.Default.decode(link!!.removePrefix("has://auth_req/")).decodeToString().contains(key))
    }

    @Test
    fun authenticateRejectedOnlyWithTheRightUuid() = runTest {
        val relay = FakeRelay {
            push("cmd" to "auth_wait", "uuid" to "u1", "expire" to 50_000)
            push("cmd" to "auth_nack", "uuid" to "u1", "data" to CryptoJsCipher.encrypt("not-the-uuid", key))
            push("cmd" to "auth_nack", "uuid" to "u1", "data" to CryptoJsCipher.encrypt("u1", key))
        }
        val e = assertFailsWith<AuthException.HiveAuth> { client(relay).authenticate(user, null, key) {} }
        assertEquals(AuthException.HiveAuth.Reason.REJECTED, e.reason)
    }

    @Test
    fun aNackThatDoesNotCarryTheUuidIsIgnored() = runTest {
        val relay = FakeRelay {
            push("cmd" to "auth_wait", "uuid" to "u1", "expire" to 50_000)
            push("cmd" to "auth_nack", "uuid" to "u1", "data" to CryptoJsCipher.encrypt("forged", key))
            push("cmd" to "auth_ack", "uuid" to "u1", "data" to CryptoJsCipher.encrypt("""{"expire":7}""", key))
        }
        assertEquals(7, client(relay).authenticate(user, null, key) {}.session.expiresAtMillis)
    }

    @Test
    fun authenticateExpiresAtTheRelayDeadline() = runTest {
        val relay = FakeRelay { push("cmd" to "auth_wait", "uuid" to "u1", "expire" to 5_000) }
        val e = assertFailsWith<AuthException.HiveAuth> { client(relay).authenticate(user, null, key) {} }
        assertEquals(AuthException.HiveAuth.Reason.EXPIRED, e.reason)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun noWaitWithinTheTimeoutExpires() = runTest {
        val relay = FakeRelay { }
        val e = assertFailsWith<AuthException.HiveAuth> { client(relay).authenticate(user, null, key) {} }
        assertEquals(AuthException.HiveAuth.Reason.EXPIRED, e.reason)
        assertEquals(HiveAuthProtocol.DEFAULT_TIMEOUT_MILLIS, testScheduler.currentTime)
    }

    @Test
    fun relayErrorsAreTyped() = runTest {
        val relay = FakeRelay { push("cmd" to "error", "error" to "Account has not been connected through HAS") }
        val e = assertFailsWith<AuthException.HiveAuth> { client(relay).authenticate(user, null, key) {} }
        assertEquals(AuthException.HiveAuth.Reason.NOT_CONNECTED, e.reason)
        val relay2 = FakeRelay {
            push("cmd" to "auth_wait", "uuid" to "u1", "expire" to 50_000)
            push("cmd" to "auth_err", "uuid" to "u1", "error" to "boom")
        }
        assertEquals(AuthException.HiveAuth.Reason.ERROR, assertFailsWith<AuthException.HiveAuth> { client(relay2).authenticate(user, null, key) {} }.reason)
    }

    @Test
    fun broadcastApprovedSendsEncryptedOps() = runTest {
        val relay = FakeRelay {
            push("cmd" to "sign_wait", "uuid" to "s1", "expire" to 50_000)
            push("cmd" to "sign_ack", "uuid" to "s1", "broadcast" to true, "data" to "txid-1")
        }
        val ops = Json.parseToJsonElement(AuthGoldenVectors.SIGN_OP).jsonArray
        var link: String? = null
        val r = client(relay).broadcast(HiveAuthSession(user, key, "has-token", 0), KeyRole.ACTIVE, ops) { link = it.link }
        assertEquals(HiveAuthSignResult("s1", true, "txid-1"), r)
        assertEquals("has://sign_req", link)
        val req = relay.sent.single()
        assertEquals("sign_req", req["cmd"]!!.jsonPrimitive.content)
        assertEquals("has-token", req["token"]!!.jsonPrimitive.content)
        val payload = decrypt(req)
        assertEquals("active", payload["key_type"]!!.jsonPrimitive.content)
        assertEquals(ops, payload["ops"])
    }

    @Test
    fun broadcastFailures() = runTest {
        val ops = Json.parseToJsonElement(AuthGoldenVectors.SIGN_OP).jsonArray
        val nack = FakeRelay {
            push("cmd" to "sign_wait", "uuid" to "s1", "expire" to 50_000)
            push("cmd" to "sign_nack", "uuid" to "s1", "data" to CryptoJsCipher.encrypt("s1", key))
        }
        assertEquals(AuthException.HiveAuth.Reason.REJECTED, assertFailsWith<AuthException.HiveAuth> { client(nack).broadcast(HiveAuthSession(user, key, null, 0), KeyRole.POSTING, ops) {} }.reason)
        val err = FakeRelay {
            push("cmd" to "sign_wait", "uuid" to "s1", "expire" to 50_000)
            push("cmd" to "sign_err", "uuid" to "s1", "error" to CryptoJsCipher.encrypt("insufficient RC", key))
        }
        val e = assertFailsWith<AuthException.HiveAuth> { client(err).broadcast(HiveAuthSession(user, key, null, 0), KeyRole.POSTING, ops) {} }
        assertTrue(e.message!!.contains("insufficient RC"))
        // An expired session fails before anything is sent.
        val idle = FakeRelay { }
        kotlinx.coroutines.delay(10)
        val expired = assertFailsWith<AuthException.HiveAuth> { client(idle).broadcast(HiveAuthSession(user, key, null, 1), KeyRole.POSTING, ops) {} }
        assertEquals(AuthException.HiveAuth.Reason.SESSION_EXPIRED, expired.reason)
        assertTrue(idle.sent.isEmpty())
    }

    @Test
    fun aSignNackThatDoesNotCarryTheUuidIsIgnored() = runTest {
        val ops = Json.parseToJsonElement(AuthGoldenVectors.SIGN_OP).jsonArray
        val relay = FakeRelay {
            push("cmd" to "sign_wait", "uuid" to "s1", "expire" to 50_000)
            push("cmd" to "sign_nack", "uuid" to "s1")
            push("cmd" to "sign_nack", "uuid" to "s1", "data" to CryptoJsCipher.encrypt("s1", "another-key"))
            push("cmd" to "sign_ack", "uuid" to "s1", "broadcast" to true)
        }
        assertTrue(client(relay).broadcast(HiveAuthSession(user, key, null, 0), KeyRole.POSTING, ops) {}.broadcast)
    }

    @Test
    fun aTimedOutRequestDropsTheSocketAndItsLeftovers() = runTest {
        val ops = Json.parseToJsonElement(AuthGoldenVectors.SIGN_OP).jsonArray
        var first = true
        val relay = FakeRelay {
            if (first) {
                first = false
                // Never waits: the request times out. A late wait for it must not be taken by the next request.
                return@FakeRelay
            }
            push("cmd" to "sign_wait", "uuid" to "s2", "expire" to 500_000)
            push("cmd" to "sign_ack", "uuid" to "s2", "broadcast" to true)
        }
        val c = client(relay)
        assertEquals(AuthException.HiveAuth.Reason.EXPIRED, assertFailsWith<AuthException.HiveAuth> {
            c.broadcast(HiveAuthSession(user, key, null, 0), KeyRole.POSTING, ops) {}
        }.reason)
        relay.push("cmd" to "sign_wait", "uuid" to "stale", "expire" to 500_000)
        relay.drop()
        val r = c.broadcast(HiveAuthSession(user, key, null, 0), KeyRole.POSTING, ops) {}
        assertEquals("s2", r.uuid)
        assertEquals(2, relay.opened)
    }

    @Test
    fun leftoversOfAnEarlierRequestAreDiscarded() = runTest {
        val ops = Json.parseToJsonElement(AuthGoldenVectors.SIGN_OP).jsonArray
        var n = 0
        val relay = FakeRelay {
            n++
            if (n == 1) {
                push("cmd" to "sign_wait", "uuid" to "s1", "expire" to 50_000)
                push("cmd" to "sign_wait", "uuid" to "old", "expire" to 50_000)
                push("cmd" to "sign_ack", "uuid" to "s1", "broadcast" to true)
            } else {
                push("cmd" to "sign_wait", "uuid" to "s2", "expire" to 50_000)
                push("cmd" to "sign_ack", "uuid" to "s2", "broadcast" to true)
            }
        }
        val c = client(relay)
        assertEquals("s1", c.broadcast(HiveAuthSession(user, key, null, 0), KeyRole.POSTING, ops) {}.uuid)
        // The extra wait queued during the first request is not taken as the second request's wait.
        assertEquals("s2", c.broadcast(HiveAuthSession(user, key, null, 0), KeyRole.POSTING, ops) {}.uuid)
    }

    @Test
    fun aStaleWaitFromAnEarlierAuthenticateIsNotTakenByTheNextOne() = runTest {
        var n = 0
        val relay = FakeRelay {
            n++
            if (n == 1) {
                push("cmd" to "auth_wait", "uuid" to "u1", "expire" to 50_000)
                // a second wait arrives for the first request and stays queued after it finished
                push("cmd" to "auth_wait", "uuid" to "old", "expire" to 50_000)
                push("cmd" to "auth_ack", "uuid" to "u1", "data" to CryptoJsCipher.encrypt("""{"expire":1}""", key))
            } else {
                push("cmd" to "auth_wait", "uuid" to "u2", "expire" to 50_000)
                push("cmd" to "auth_ack", "uuid" to "u2", "data" to CryptoJsCipher.encrypt("""{"expire":2}""", key))
            }
        }
        val c = client(relay)
        assertEquals(1, c.authenticate(user, null, key) {}.session.expiresAtMillis)
        var waited: String? = null
        assertEquals(2, c.authenticate(user, null, key) { waited = it.uuid }.session.expiresAtMillis)
        assertEquals("u2", waited, "the new request waits on its own uuid")
    }

    @Test
    fun reattachesWhenTheSocketDrops() = runTest {
        val ops = Json.parseToJsonElement(AuthGoldenVectors.SIGN_OP).jsonArray
        val relay = FakeRelay { m ->
            when (m["cmd"]!!.jsonPrimitive.content) {
                "sign_req" -> {
                    push("cmd" to "sign_wait", "uuid" to "s1", "expire" to 50_000)
                    drop()
                }
                "attach_req" -> {
                    push("cmd" to "attach_ack", "uuid" to "s1")
                    push("cmd" to "sign_ack", "uuid" to "s1", "broadcast" to true)
                }
            }
        }
        val r = client(relay).broadcast(HiveAuthSession(user, key, null, 0), KeyRole.POSTING, ops) {}
        assertTrue(r.broadcast)
        assertEquals(2, relay.opened)
        assertEquals(listOf("sign_req", "attach_req"), relay.sent.map { it["cmd"]!!.jsonPrimitive.content })
    }

    @Test
    fun failedAttachIsNotConnected() = runTest {
        val ops = Json.parseToJsonElement(AuthGoldenVectors.SIGN_OP).jsonArray
        val relay = FakeRelay { m ->
            when (m["cmd"]!!.jsonPrimitive.content) {
                "sign_req" -> { push("cmd" to "sign_wait", "uuid" to "s1", "expire" to 50_000); drop() }
                "attach_req" -> push("cmd" to "attach_nack", "uuid" to "s1")
            }
        }
        val e = assertFailsWith<AuthException.HiveAuth> { client(relay).broadcast(HiveAuthSession(user, key, null, 0), KeyRole.POSTING, ops) {} }
        assertEquals(AuthException.HiveAuth.Reason.NOT_CONNECTED, e.reason)
    }

    @Test
    fun connectFailureIsTyped() = runTest {
        val c = HiveAuthClient({ throw IllegalStateException("offline") }, { 0 })
        assertEquals(AuthException.HiveAuth.Reason.NOT_CONNECTED, assertFailsWith<AuthException.HiveAuth> { c.authenticate(user, null, key) {} }.reason)
    }
}
