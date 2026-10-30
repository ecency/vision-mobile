package com.ecency.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Golden cases from RN `utils/authRequest.test.ts`, `deepLinkParser.test.ts` (auth) and useLinkProcessor. */
class AuthLinksTest {
    @Test
    fun recognisesOnlyAuthRequests() {
        assertTrue(AuthLinks.isAuthRequest("ecency://auth-request?callback=honeyback%3A%2F%2Fhive"))
        assertTrue(AuthLinks.isAuthRequest("ECENCY://Auth-Request?callback=x%3A%2F%2Fy"))
        assertFalse(AuthLinks.isAuthRequest("ecency://login?callback=x"))
        assertFalse(AuthLinks.isAuthRequest("https://ecency.com/auth-request"))
        assertFalse(AuthLinks.isAuthRequest("not a url"))
    }

    @Test
    fun parsesAuthRequests() {
        assertEquals(
            AuthLink.AuthRequest("honeyback://hive", "r1", "good-karma"),
            AuthLinks.parseAuthRequest("ecency://auth-request?app=Trusted&callback=honeyback%3A%2F%2Fhive&request_id=r1&username=%40Good-Karma"),
        )
        assertEquals(AuthLink.AuthRequest("honeyback://hive", null, null), AuthLinks.parseAuthRequest("ecency://auth-request?redirect_uri=honeyback%3A%2F%2Fhive"))
        assertEquals("x://y", AuthLinks.parseAuthRequest("ecency://auth-request?return_url=x%3A%2F%2Fy")?.callback)
        assertNull(AuthLinks.parseAuthRequest("ecency://auth-request?request_id=r1"))
        assertNull(AuthLinks.parseAuthRequest("ecency://auth-request?callback=http%3A%2F%2Fevil.example%2Fc"))
        assertNull(AuthLinks.parseAuthRequest("ecency://auth-request?callback=javascript%3Aalert(1)"))
        assertEquals(
            "https://games-api.ecency.com/v1/hive/callback",
            AuthLinks.parseAuthRequest("ecency://auth-request?callback=https%3A%2F%2Fgames-api.ecency.com%2Fv1%2Fhive%2Fcallback")?.callback,
        )
        assertNull(AuthLinks.parseAuthRequest("garbage"))
    }

    @Test
    fun authRequestUsernameMustBeAHiveName() {
        val base = "ecency://auth-request?callback=honeyback%3A%2F%2Fhive"
        assertEquals("ecency.waves", AuthLinks.parseAuthRequest("$base&username=ecency.waves")?.username)
        assertNull(AuthLinks.parseAuthRequest("$base&username=")?.username)
        assertNull(AuthLinks.parseAuthRequest("$base&username=you%0A%0AEcency%20verified%20this%20app"))
        assertNull(AuthLinks.parseAuthRequest("$base&username=ab"))
        assertNull(AuthLinks.parseAuthRequest("$base&username=%40you%20there"))
        listOf("good-karma", "ecency.waves", "abc", "a1-b2.c3d").forEach { assertTrue(AuthLinks.isHiveAccountName(it), it) }
        listOf("", "ab", "Good-Karma", "1abc", "a".repeat(17), "you\n\nverified", "you there").forEach { assertFalse(AuthLinks.isHiveAccountName(it), it) }
    }

    @Test
    fun acceptableCallbacks() {
        listOf("honeyback://hive", "anyapp://callback", "https://example.com/cb").forEach { assertTrue(AuthLinks.isAcceptableCallback(it), it) }
        listOf(
            "http://example.com/cb", "ftp://example.com/cb", "ws://example.com/cb", "wss://example.com/cb",
            "intent://collect#Intent;scheme=evil;end", "mailto:someone@example.com", "tel:+123", "data:text/html,x",
            "javascript:alert(1)", "ecency://login?callback=evil%3A%2F%2Fx", "hive://sign/op/abc", "nope",
            // RN #3579: schemes a deny-list misses
            "android-app://com.evil/https/evil.example", "itms-services://?action=download-manifest&url=https://evil.example/m.plist",
            "x-safari-https://evil.example", "itms-apps://apps.apple.com/app/id1", "market://details?id=com.evil",
            "googlechrome://evil.example", "prefs:root=General", "app-settings:", "x-apple-health://", "ms-settings:",
            "file:///etc/passwd", "content://com.android.contacts", "vbscript:msgbox", "sms://+1", "facetime://x",
            // opaque or hostless forms and odd scheme names
            "anyapp:callback", "https:///path", "https:\\\\evil.example", "app:///x", "https:evil.example", "1app://cb", "app_x://cb", "HTTPS://", " javascript:alert(1)",
        ).forEach { assertFalse(AuthLinks.isAcceptableCallback(it), it) }
        listOf("HONEYBACK://hive", "my-app://cb?x=1", "com.example.app://oauth", "x-callback-app://cb").forEach { assertTrue(AuthLinks.isAcceptableCallback(it), it) }
    }

    @Test
    fun audienceAndRequesterLabel() {
        assertEquals("honeyback://hive", AuthLinks.callbackAudience("honeyback://hive"))
        assertEquals("honeyback://hive", AuthLinks.callbackAudience("HONEYBACK://Hive/link?x=1"))
        assertEquals("https://games-api.ecency.com", AuthLinks.callbackAudience("https://games-api.ecency.com/v1/hive/callback?x=1"))
        assertEquals("https://games-api.ecency.com/v1/cb", AuthLinks.requesterLabel("https://games-api.ecency.com/v1/cb?x=1"))
        assertEquals("honeyback://hive", AuthLinks.requesterLabel("honeyback://hive"))
    }

    @Test
    fun callbackUrlsMatchUrlSearchParamsSet() {
        AuthGoldenVectors.callbacks.forEach { v ->
            assertEquals(v.url, AuthLinks.callbackUrl(v.callback, v.requestId, v.payload), v.callback)
        }
        assertNull(AuthLinks.callbackUrl("not a url", null, emptyList()))
    }

    @Test
    fun classifiesInRnOrder() {
        assertIs<AuthLink.AuthTransfer>(AuthLinks.parse("ecency://auth-transfer?username=alice&accessToken=a&refreshToken=r"))
        assertIs<AuthLink.AuthRequest>(AuthLinks.parse("ecency://auth-request?callback=honeyback%3A%2F%2Fhive"))
        assertEquals(AuthLink.Invalid("invalid_request"), AuthLinks.parse("ecency://auth-request?callback=http%3A%2F%2Fx"))
        val vote = AuthGoldenVectors.uris.first { it.name == "op_vote" }.uri
        val sign = AuthLinks.parse(" ECENCY://" + vote.removePrefix("hive://") + " ") as AuthLink.HiveSign
        assertEquals(vote, sign.uri)
        assertEquals(HiveUri.decode(vote), sign.decoded)
        assertEquals(vote, (AuthLinks.parse(vote) as AuthLink.HiveSign).uri)
        assertEquals(AuthLink.Invalid("invalid_link"), AuthLinks.parse("hive://sign/op/abc"))
        assertNull(AuthLinks.parse("https://ecency.com/hive/@alice/my-first-post"))
        assertNull(AuthLinks.parse("ecency://@alice/my-post"))
        assertNull(AuthLinks.parse(""))
    }

    @Test
    fun authTransfer() {
        val t = AuthLinks.parse("ecency://auth-transfer?username=alice&accessToken=a&refreshToken=r&expiresIn=60") as AuthLink.AuthTransfer
        assertEquals(60, t.expiresInSeconds)
        assertFalse("a," in t.toString() || "refreshToken=r" in t.toString())
        assertEquals(86400, (AuthLinks.parse("ecency://auth-transfer?username=alice&accessToken=a&refreshToken=r") as AuthLink.AuthTransfer).expiresInSeconds)
        assertEquals(AuthLink.Invalid("invalid_params"), AuthLinks.parse("ecency://auth-transfer?username=alice&accessToken=a"))
        assertEquals(AuthLink.Invalid("invalid_params"), AuthLinks.parse("ecency://auth-transfer?username=Bad%20Name&accessToken=a&refreshToken=r"))
    }

    @Test
    fun postingKeyRequests() {
        assertEquals(
            AuthLink.PostingKeyRequest("good-karma", "honeyback://hive", "r1"),
            AuthLinks.parse("ecency://login?username=%40Good-Karma&redirect_uri=honeyback%3A%2F%2Fhive&request_id=r1"),
        )
        assertEquals(AuthLink.Invalid("invalid_params"), AuthLinks.parse("ecency://login?username=alice"))
        assertEquals(AuthLink.Invalid("invalid_params"), AuthLinks.parse("ecency://login?username=a%20b&callback=x%3A%2F%2Fy"))
        // Stricter than RN: the posting key never goes to a plain-text or page callback.
        assertEquals(AuthLink.Invalid("invalid_callback"), AuthLinks.parse("ecency://login?username=alice&callback=http%3A%2F%2Fevil.example"))
    }

    private fun ops(uri: String) = (HiveUri.decode(uri).tx as JsonObject)["operations"]!!.jsonArray

    @Test
    fun transferLinksBuildRnsHiveUri() {
        val t = AuthLinks.parse("ecency://transfer?to=%40ecency&from=spike-user&asset=hive&amount=1.5&memo=hi&callback=app%3A%2F%2Fcb&request_id=7") as AuthLink.Transfer
        assertFalse(t.invalid)
        assertEquals("spike-user", t.from)
        val op = ops(t.hiveUri!!).single().jsonArray
        assertEquals("transfer", op[0].jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"from":"spike-user","to":"ecency","amount":"1.500 HIVE","memo":"hi"}"""), op[1])
        assertEquals("app://cb", HiveUri.decode(t.hiveUri!!).params.callback)

        val points = AuthLinks.parse("ecency://transfer?to=ecency&asset=POINTS&amount=10") as AuthLink.Transfer
        val custom = ops(points.hiveUri!!).single().jsonArray[1].jsonObject
        assertEquals("ecency_point_transfer", custom["id"]!!.jsonPrimitive.content)
        assertEquals("""["__signer"]""", custom["required_auths"].toString())
        assertEquals(
            """{"sender":"__signer","receiver":"ecency","amount":"10.000 POINT","memo":""}""",
            custom["json"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun everyCallbackIsFilteredByTheParser() {
        // ecency://transfer: an unacceptable callback makes the link invalid and is never kept.
        assertEquals(
            AuthLink.Invalid("invalid_callback", null, "7"),
            AuthLinks.parse("ecency://transfer?to=ecency&asset=HIVE&amount=1&callback=http%3A%2F%2Fevil.example&request_id=7"),
        )
        assertEquals(
            AuthLink.Invalid("invalid_callback", null, "7"),
            AuthLinks.parse("ecency://transfer?callback=javascript%3Aalert(1)&request_id=7"),
        )
        // hive:// cb=
        val vote = Json.parseToJsonElement("""["vote",{"voter":"__signer","author":"a","permlink":"p","weight":1}]""").jsonArray
        assertEquals(AuthLink.Invalid("invalid_callback"), AuthLinks.parse(HiveUri.encodeOp(vote, HiveUri.Params(callback = "http://evil.example/cb"))))
        assertEquals(AuthLink.Invalid("invalid_callback"), AuthLinks.parse(HiveUri.encodeOp(vote, HiveUri.Params(callback = "intent://x#Intent;end"))))
        val ok = AuthLinks.parse(HiveUri.encodeOp(vote, HiveUri.Params(callback = "honeyback://hive"))) as AuthLink.HiveSign
        assertEquals("honeyback://hive", ok.decoded.params.callback)
        // Every Invalid the parser returns carries only an acceptable callback, or none.
        listOf(
            "ecency://transfer?callback=app%3A%2F%2Fcb",
            "ecency://transfer?callback=http%3A%2F%2Fx",
            "ecency://login?username=alice&callback=http%3A%2F%2Fx",
            "ecency://auth-request?callback=data%3Atext%2Fhtml%2Cx",
        ).forEach { url ->
            val link = AuthLinks.parse(url) as AuthLink.Invalid
            assertTrue(link.callback == null || AuthLinks.isAcceptableCallback(link.callback!!), url)
        }
    }

    @Test
    fun transferLinkEdgeCases() {
        val recipientOnly = AuthLinks.parse("ecency://transfer?to=@alice") as AuthLink.Transfer
        assertTrue(recipientOnly.isRecipientOnly)
        assertEquals("alice", recipientOnly.to)
        assertEquals("HIVE", recipientOnly.asset)
        assertEquals(AuthLink.Invalid("invalid_params", "app://cb", "1"), AuthLinks.parse("ecency://transfer?callback=app%3A%2F%2Fcb&request_id=1"))
        listOf("amount=0&asset=HIVE", "amount=-1&asset=HIVE", "amount=abc&asset=HIVE", "amount=1&asset=BTC").forEach {
            val t = AuthLinks.parse("ecency://transfer?to=alice&$it") as AuthLink.Transfer
            assertTrue(t.invalid, it)
        }
    }

    @Test
    fun webAuthAndSignupPaths() {
        assertEquals(AuthLink.CodeLogin("alice", "abc123"), AuthLinks.parse("https://ecency.com/auth?username=alice&code=abc123"))
        assertEquals(AuthLink.Signup("alice", null, null), AuthLinks.parse("https://ecency.com/signup?referral=alice"))
        assertNull(AuthLinks.parse("https://evil.example/auth?username=alice&code=abc"))
        assertNull(AuthLinks.parse("https://ecency.com/@alice/auth"))
    }

    @Test
    fun loginCodeExpiryIsSevenDays() {
        val day = 86_400_000L
        val ts = 1_759_800_000L
        val code = Base64.Default.encode("""{"signed_message":{"type":"code"},"authors":["alice"],"timestamp":$ts}trailing""".encodeToByteArray())
        assertNull(AuthLinks.checkLoginCode(code, "alice", ts * 1000 + 7 * day))
        assertEquals(AuthLinks.CodeProblem.EXPIRED, AuthLinks.checkLoginCode(code, "alice", ts * 1000 + 7 * day + 1))
        val v = AuthGoldenVectors.messages.first()
        assertNull(AuthLinks.checkLoginCode(v.code, AuthGoldenVectors.USER, v.millis))
        assertEquals(AuthLinks.CodeProblem.MALFORMED, AuthLinks.checkLoginCode("%%%", "alice", 0))
        val noTs = Base64.Default.encode("{}".encodeToByteArray())
        assertEquals(AuthLinks.CodeProblem.EXPIRED, AuthLinks.checkLoginCode(noTs, "alice", 0))
    }

    @Test
    fun loginCodeMustBeForTheLinkAccountAndNotFromTheFuture() {
        val ts = 1_759_800_000L
        val code = Base64.Default.encode("""{"authors":["alice"],"timestamp":$ts}""".encodeToByteArray())
        assertEquals(AuthLinks.CodeProblem.WRONG_ACCOUNT, AuthLinks.checkLoginCode(code, "bob", ts * 1000))
        val noAuthor = Base64.Default.encode("""{"timestamp":$ts}""".encodeToByteArray())
        assertEquals(AuthLinks.CodeProblem.WRONG_ACCOUNT, AuthLinks.checkLoginCode(noAuthor, "alice", ts * 1000))
        // a phone whose clock is half an hour slow still signs in; more than an hour is refused
        assertNull(AuthLinks.checkLoginCode(code, "alice", ts * 1000 - 30 * 60_000))
        assertNull(AuthLinks.checkLoginCode(code, "alice", ts * 1000 - 60 * 60_000))
        assertEquals(AuthLinks.CodeProblem.FUTURE, AuthLinks.checkLoginCode(code, "alice", ts * 1000 - 60 * 60_000 - 1))
    }
}
