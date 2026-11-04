package com.ecency.core.auth

import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HiveSignerTest {
    @Test
    fun authorizeUrlIsRns() {
        assertEquals(
            "https://hivesigner.com/oauth2/authorize?client_id=ecency.app&redirect_uri=http%3A%2F%2F127.0.0.1%3A3000%2Fauth" +
                "&response_type=code&scope=vote%2Ccomment%2Cdelete_comment%2Ccomment_options%2Ccustom_json%2Cclaim_reward_balance%2Caccount_update%2Coffline",
            HiveSigner.authorizeUrl(),
        )
    }

    @Test
    fun parsesLoginCallbacks() {
        assertEquals(HiveSignerCallback.Code("abc.def-_"), HiveSigner.parseCallback("http://127.0.0.1:3000/auth?code=abc.def-_&state=x"))
        val token = HiveSigner.parseCallback("http://127.0.0.1:3000/auth?access_token=secret-xyz&username=alice&expires_in=604800")
        assertEquals(HiveSignerCallback.AccessToken("secret-xyz", "alice", 604800), token)
        assertFalse("secret-xyz" in token.toString())
        assertEquals(HiveSignerCallback.Error("access_denied", "no"), HiveSigner.parseCallback("http://127.0.0.1:3000/auth?error=access_denied&error_description=no"))
        assertNull(HiveSigner.parseCallback("https://hivesigner.com/login"))
        assertNull(HiveSigner.parseCallback("http://127.0.0.1:3000/auth"))
        assertNull(HiveSigner.parseCallback("http://127.0.0.1:3000/auth?code="))
        // Only the redirect URI itself, with exact parameter names.
        assertNull(HiveSigner.parseCallback("https://evil.example/?code=planted"))
        assertNull(HiveSigner.parseCallback("https://hivesigner.com/login?next=x%3Fcode%3Dplanted"))
        assertNull(HiveSigner.parseCallback("http://127.0.0.1:3000/authx?code=planted"))
        assertNull(HiveSigner.parseCallback("http://127.0.0.1:3000/auth?xcode=planted"))
        assertEquals(HiveSignerCallback.Code("c1"), HiveSigner.parseCallback("http://127.0.0.1:3000/auth?state=a&code=c1#f"))
    }

    @Test
    fun hotSignUrlMatchesRnModal() {
        val ops = Json.parseToJsonElement(AuthGoldenVectors.SIGN_OP).jsonArray
        val uri = HiveUri.encodeOps(ops)
        assertEquals("https://hivesigner.com/" + uri.removePrefix("hive://") + "?redirect_uri=http%3A%2F%2F127.0.0.1%3A3000%2Fauth", HiveSigner.hotSignUrl(ops))
        val withParams = AuthGoldenVectors.uris.first { it.name == "op_params" }.uri
        assertTrue(HiveSigner.hotSignUrl(withParams).endsWith("&a=active&redirect_uri=http%3A%2F%2F127.0.0.1%3A3000%2Fauth"))
        assertFailsWith<IllegalArgumentException> { HiveSigner.hotSignUrl("https://x") }
    }

    @Test
    fun parsesSignResultsLikeRn() {
        assertEquals(HiveSignerSignResult("abc", 12, 3), HiveSigner.parseSignResult("http://127.0.0.1:3000/auth?sig=s&id=abc&block=12&txn=3"))
        assertEquals(HiveSignerSignResult("abc", null, null), HiveSigner.parseSignResult("http://127.0.0.1:3000/auth?id=abc&block=x&txn=-1"))
        assertNull(HiveSigner.parseSignResult("http://127.0.0.1:3000/auth?sig=s"))
        assertEquals(HiveSignerSignResult(), HiveSigner.parseSignResult("https://hivesigner.com/sign/success"))
        assertEquals(HiveSignerSignResult(), HiveSigner.parseSignResult("https://hivesigner.com/x?success=true"))
        assertNull(HiveSigner.parseSignResult("https://hivesigner.com/sign/op/abc"))
        // Success pages only on HiveSigner's own host, with the exact path or parameter.
        assertNull(HiveSigner.parseSignResult("https://evil.example/sign/success"))
        assertNull(HiveSigner.parseSignResult("https://evil.example/?success=true"))
        assertNull(HiveSigner.parseSignResult("https://hivesigner.com/sign/successful"))
        assertNull(HiveSigner.parseSignResult("https://hivesigner.com/x?notsuccess=true"))
        assertNull(HiveSigner.parseSignResult("http://127.0.0.1:3000/authx?id=abc"))
        assertNull(HiveSigner.parseSignResult(null))
    }

    @Test
    fun apiBroadcastSendsTheTokenAndOps() = runTest {
        val http = FakeAuthHttp { HttpResult(200, """{"result":{"id":"tx1"}}""") }
        val ops = Json.parseToJsonElement(AuthGoldenVectors.SIGN_OP) as JsonArray
        val result = HiveSignerApi(http).broadcast("tok", ops)
        assertEquals("tx1", result!!.jsonObject["id"].toString().trim('"'))
        val req = http.sent.single()
        assertEquals("https://hivesigner.com/api/broadcast", req.url)
        assertEquals("tok", req.headers["Authorization"])
        assertEquals(ops, Json.parseToJsonElement(req.body!!).jsonObject["operations"])
    }

    @Test
    fun apiErrorsAreTypedAndTriggerTheFallback() = runTest {
        val http = FakeAuthHttp { HttpResult(401, """{"error":"invalid_grant","error_description":"token expired"}""") }
        val e = assertFailsWith<AuthException.HiveSignerApi> { HiveSignerApi(http).me("tok") }
        assertEquals("invalid_grant", e.error)
        assertTrue(AuthFallback.shouldTrigger(e))
        val ok = FakeAuthHttp { HttpResult(200, """{"user":"alice"}""") }
        assertEquals("alice", HiveSignerApi(ok).me("tok"))
        val bodyError = FakeAuthHttp { HttpResult(200, """{"error":"server_error"}""") }
        assertIs<AuthException.HiveSignerApi>(runCatching { HiveSignerApi(bodyError).me("t") }.exceptionOrNull())
    }

    @Test
    fun uriComponentMatchesJavaScript() {
        assertEquals("a%20b%2Fc%3F%26%3D!~*'()-_.%F0%9F%90%9D", UriComponent.encode("a b/c?&=!~*'()-_.🐝"))
        assertEquals("a b/🐝", UriComponent.decode("a%20b%2F%F0%9F%90%9D"))
        assertFailsWith<IllegalArgumentException> { UriComponent.decode("%E0%A4%A") }
        assertFailsWith<IllegalArgumentException> { UriComponent.decode("%+1") }
    }
}
