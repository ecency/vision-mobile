package com.ecency.core.auth

import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EcencyTokensTest {
    @Test
    fun exchangePostsTheCodeToTheSdkEndpoint() = runTest {
        val http = FakeAuthHttp { FakeAuthHttp.tokens(expiresIn = 100) }
        val s = EcencyTokenApi(http).exchange("the-code", nowMillis = 1_000)
        val req = http.sent.single()
        assertEquals("POST", req.method)
        assertEquals("https://ecency.com/auth-api/hs-token-refresh", req.url)
        assertEquals("the-code", Json.parseToJsonElement(req.body!!).jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(TokenSession("spike-user", "access-1", "refresh-1", 101_000), s)
        assertFalse("access-1" in s.toString())
        assertFalse("the-code" in req.toString())
    }

    @Test
    fun retriesThreeTimesLikeRn() = runTest {
        var calls = 0
        val http = FakeAuthHttp { if (++calls < 4) HttpResult(502, "bad gateway") else FakeAuthHttp.tokens() }
        EcencyTokenApi(http).exchange("c", 0)
        assertEquals(4, calls)
        assertEquals(1400L, testScheduler.currentTime)
    }

    @Test
    fun failsTypedAfterTheLastRetry() = runTest {
        val http = FakeAuthHttp { HttpResult(401, """{"error":"invalid_grant"}""") }
        val e = assertFailsWith<AuthException.TokenExchangeFailed> { EcencyTokenApi(http).exchange("c", 0) }
        assertEquals(401, e.status)
        // A refusal is not retried; network errors and 5xx are.
        assertEquals(1, http.sent.size)
        val down = FakeAuthHttp { HttpResult(503, "") }
        assertFailsWith<AuthException.TokenExchangeFailed> { EcencyTokenApi(down).exchange("c", 0) }
        assertEquals(4, down.sent.size)
        var n = 0
        val flaky = FakeAuthHttp { if (++n < 3) throw IllegalStateException("reset") else FakeAuthHttp.tokens() }
        EcencyTokenApi(flaky).exchange("c", 0)
        assertEquals(3, n)
        // A non-2xx status fails even when the body looks like tokens.
        val odd = FakeAuthHttp { FakeAuthHttp.tokens().copy(status = 400) }
        assertEquals(400, assertFailsWith<AuthException.TokenExchangeFailed> { EcencyTokenApi(odd, retryDelaysMillis = emptyList()).exchange("c", 0) }.status)
    }

    @Test
    fun networkErrorsAndBadBodiesAreTyped() = runTest {
        val net = FakeAuthHttp { throw IllegalStateException("offline") }
        val e = assertFailsWith<AuthException.TokenExchangeFailed> { EcencyTokenApi(net, retryDelaysMillis = emptyList()).exchange("c", 0) }
        assertEquals(0, e.status)
        val noToken = FakeAuthHttp { HttpResult(200, """{"username":"a"}""") }
        assertFailsWith<AuthException.TokenExchangeFailed> { EcencyTokenApi(noToken, retryDelaysMillis = emptyList()).exchange("c", 0) }
        val noExpiry = FakeAuthHttp { HttpResult(200, """{"username":"alice","access_token":"t"}""") }
        val s = EcencyTokenApi(noExpiry, retryDelaysMillis = emptyList()).exchange("c", 0)
        assertNull(s.refreshToken)
        assertEquals(0, s.expiresAtMillis)
        assertFalse(s.isExpired(Long.MAX_VALUE))
    }

    @Test
    fun expiry() {
        val s = TokenSession("a", "t", "r", 1_000)
        assertFalse(s.isExpired(1_000))
        assertTrue(s.isExpired(1_001))
    }

    @Test
    fun privateApiAttachesTheCode() = runTest {
        val http = FakeAuthHttp { HttpResult(200, "{}") }
        val api = EcencyPrivateApi(http, { if (it == "alice") "tok" else null })
        api.post("/notifications", "alice", buildJsonObject { put("filter", "rvotes") })
        val req = http.sent.single()
        assertEquals("https://ecency.com/private-api/notifications", req.url)
        val body = Json.parseToJsonElement(req.body!!).jsonObject
        assertEquals("tok", body["code"]!!.jsonPrimitive.content)
        assertEquals("rvotes", body["filter"]!!.jsonPrimitive.content)
        assertFailsWith<AuthException.NotSignedIn> { api.post("x", "bob") }
    }
}
