package com.ecency.core.auth

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.EcencyTimeoutException
import com.ecency.core.api.search.SearchApi
import com.ecency.core.api.search.SearchSort
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real transport over a fake engine: redirects, the user agent across hosts, and the per-request bound. */
class KtorAuthHttpTest {
    private val build = "SecretBuild/9"

    @Test
    fun aRedirectFromAnEcencyHostIsNotFollowedSoTheBuildNeverReachesTheOtherHost() = runBlocking<Unit> {
        val seen = mutableListOf<Pair<String, String?>>()
        val engine = MockEngine { request ->
            seen += request.url.toString() to request.headers[HttpHeaders.UserAgent]
            if (request.url.host == "ecency.com" || request.url.host == "hivesigner.com") respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example/collect?token=abc"))
            else respond("{}", HttpStatusCode.OK)
        }
        val http = KtorAuthHttp(HttpClient(engine), build)
        for (status in listOf(HttpStatusCode.Found, HttpStatusCode.MovedPermanently, HttpStatusCode.TemporaryRedirect, HttpStatusCode.PermanentRedirect)) {
            seen.clear()
            val e = assertFailsWith<EcencyHttpException> {
                KtorAuthHttp(
                    HttpClient(MockEngine { request ->
                        seen += request.url.toString() to request.headers[HttpHeaders.UserAgent]
                        respond("", status, headersOf(HttpHeaders.Location, "https://elsewhere.example/collect?token=abc"))
                    }),
                    build,
                ).send(AuthHttpRequest("POST", "https://ecency.com/private-api/drafts", body = "{\"code\":\"secret\"}"))
            }
            assertEquals(status.value, e.status, "a redirect is an error")
            assertTrue("elsewhere" !in e.message.orEmpty() && "abc" !in e.message.orEmpty(), "the address it named stays out of the message")
            assertEquals(listOf<Pair<String, String?>>("https://ecency.com/private-api/drafts" to build), seen.toList(), "one request, to Ecency only")
            // the methods an HTTP stack follows by itself (GET and HEAD, also for 301 and 302) are not followed either
            for (method in listOf("GET", "HEAD")) {
                seen.clear()
                val refused = assertFailsWith<EcencyHttpException>("$method ${status.value}") {
                    KtorAuthHttp(
                        HttpClient(MockEngine { request ->
                            seen += "${request.method.value} ${request.url}" to request.headers[HttpHeaders.UserAgent]
                            if (request.url.host == "ecency.com") respond("", status, headersOf(HttpHeaders.Location, "https://elsewhere.example/collect?token=abc"))
                            else respond("{}", HttpStatusCode.OK)
                        }),
                        build,
                    ).send(AuthHttpRequest(method, "https://ecency.com/private-api/pro-members"))
                }
                assertEquals(status.value, refused.status, "$method ${status.value}")
                assertTrue("elsewhere" !in refused.message.orEmpty() && "abc" !in refused.message.orEmpty())
                assertEquals(listOf<Pair<String, String?>>("$method https://ecency.com/private-api/pro-members" to build), seen.toList(), "$method ${status.value}: one request, to Ecency only")
            }
        }
        seen.clear()
        assertFailsWith<EcencyHttpException> { http.send(AuthHttpRequest("GET", "https://ecency.com/private-api/pro-members")) }
        assertTrue(seen.none { "elsewhere" in it.first }, "nothing went to the redirect's host: $seen")
        // the same for the other first-party host: told the build, and a redirect from it is followed nowhere
        seen.clear()
        assertFailsWith<EcencyHttpException> { http.send(AuthHttpRequest("GET", "https://hivesigner.com/api/me")) }
        assertEquals(listOf<Pair<String, String?>>("https://hivesigner.com/api/me" to build), seen.toList())
        // and a direct call to someone else carries no build user agent at all
        seen.clear()
        assertEquals(200, http.send(AuthHttpRequest("GET", "https://3speak.tv/apiv2/x")).status)
        assertTrue(seen.single().second != build)
    }

    @Test
    fun aCallThatGetsNoAnswerGivesUpAfterTheBound() = runTest {
        val never = CompletableDeferred<Unit>()
        val engine = MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                never.await()
                respond("{}", HttpStatusCode.OK)
            }
        }
        val http = KtorAuthHttp(HttpClient(engine), build)
        // a GET (the Pro members list) and the sign-in's token exchange
        val get = async { runCatching { http.send(AuthHttpRequest("GET", "https://ecency.com/private-api/pro-members")) } }
        testScheduler.advanceTimeBy(KtorAuthHttp.TIMEOUT_MILLIS - 1)
        assertTrue(get.isActive, "still waiting just before the bound")
        testScheduler.advanceTimeBy(2)
        assertIs<EcencyTimeoutException>(get.await().exceptionOrNull())

        val started = currentTime
        val login = async { runCatching { EcencyTokenApi(http).exchange("code", 0) } }
        testScheduler.advanceTimeBy(KtorAuthHttp.TIMEOUT_MILLIS - 1)
        assertTrue(login.isActive)
        testScheduler.advanceTimeBy(2)
        val failed = assertIs<AuthException.TokenExchangeFailed>(login.await().exceptionOrNull())
        assertEquals(0, failed.status)
        assertEquals(KtorAuthHttp.TIMEOUT_MILLIS + 1, currentTime - started, "a stalled exchange is not tried three more times")

        // search goes through the same transport
        val search = async { runCatching { SearchApi(http).search("x", SearchSort.RELEVANCE) } }
        testScheduler.advanceTimeBy(KtorAuthHttp.TIMEOUT_MILLIS + 1)
        assertTrue(search.await().isFailure)
        never.complete(Unit)
    }

    @Test
    fun aTokenExchangeStillRetriesAServerErrorButNotATimeout() = runTest {
        var calls = 0
        val failing = AuthHttp {
            calls++
            throw IllegalStateException("connection reset")
        }
        assertFailsWith<AuthException.TokenExchangeFailed> { EcencyTokenApi(failing).exchange("code", 0) }
        assertEquals(4, calls, "a network error is retried as before")
        calls = 0
        val stalled = AuthHttp {
            calls++
            throw EcencyTimeoutException()
        }
        assertFailsWith<AuthException.TokenExchangeFailed> { EcencyTokenApi(stalled).exchange("code", 0) }
        assertEquals(1, calls)
    }

    @Test
    fun aRefusedRedirectKeepsItsStatusThroughEveryCallerAndIsNeverRetried() = runBlocking<Unit> {
        var calls = 0
        val redirecting = KtorAuthHttp(
            HttpClient(MockEngine {
                calls++
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example/collect?token=abc"))
            }),
            build,
        )
        suspend fun status(block: suspend () -> Any?): Int {
            calls = 0
            val e = assertFailsWith<EcencyHttpException> { block() }
            assertEquals(1, calls, "asked once")
            assertTrue(e.isRedirect && !e.isNetwork, "an answer, not a lost connection")
            assertTrue("network" !in e.message.orEmpty() && "elsewhere" !in e.message.orEmpty(), e.message)
            // what the user reads names the cause, not their connection
            assertEquals(com.ecency.core.app.AppError.UNEXPECTED_REDIRECT, com.ecency.core.app.AppError.from(e).message)
            return e.status
        }
        assertEquals(302, status { com.ecency.core.api.EcencyRestApi(redirecting).get("https://ecency.com/private-api/public/bots") })
        assertEquals(302, status { com.ecency.core.api.waves.WavesApi(redirecting).promoted() })
        assertEquals(302, status { com.ecency.core.api.BadActors.load(redirecting) })

        // search carries the status in its own error (signup: SignupTest)
        calls = 0
        assertEquals(302, assertFailsWith<com.ecency.core.api.search.SearchApiException> { SearchApi(redirecting).search("x", SearchSort.RELEVANCE) }.status)
        assertEquals(1, calls)

        // the sign-in's token exchange: one call, the status kept, an accurate message
        calls = 0
        val failed = assertFailsWith<AuthException.TokenExchangeFailed> { EcencyTokenApi(redirecting).exchange("code", 0) }
        assertEquals(302, failed.status)
        assertEquals(1, calls, "a redirect is not tried three more times")
        assertEquals(com.ecency.core.app.AppError.UNEXPECTED_REDIRECT, com.ecency.core.app.AppError.from(failed).message)
        // a lost connection is still status 0 and "network"
        val lost = assertFailsWith<EcencyHttpException> { com.ecency.core.api.EcencyRestApi(AuthHttp { throw IllegalStateException("reset") }).get("https://ecency.com/x") }
        assertEquals(0, lost.status)
        assertTrue(lost.isNetwork)
    }

    @Test
    fun onlyFirstPartyHostsOnTheirDefaultPortAreTold() {
        fun told(url: String) = ClientIdentity.headers(AuthHttpRequest("GET", url), build)["User-Agent"]
        for (url in listOf("https://ecency.com/x", "https://ecency.com:443/x", "https://images.ecency.com/hs/t", "https://translate.ecency.com/translate", "https://poll.ecency.com/rpc/poll", "https://i.ecency.com/p/x",
            "https://hivesigner.com/api/me", "https://hivesigner.com:443/api/broadcast", "https://api.hivesigner.com/x")) {
            assertEquals(build, told(url), url)
        }
        for (url in listOf(
            "https://ecency.com:8443/x", "https://ecency.com:80/x", "https://images.ecency.com:444/x", "http://ecency.com/x", "http://ecency.com:443/x",
            "https://ecency.com.evil.example/x", "https://evil-ecency.com/x", "https://notecency.com/x", "https://ecency.co/x", "https://ecency.com.co/x",
            "https://xn--ecency-9ya.com/x", "https://3speak.tv/x",
            "https://evilhivesigner.com/x", "https://hivesigner.com.evil.example/x", "https://nothivesigner.com/x", "https://hivesigner.co/x",
            "http://hivesigner.com/x", "https://hivesigner.com:8443/x", "https://hivesigner.com@evil.example/x",
            "https://.hivesigner.com/x", "https://.ecency.com/x", "https://a..ecency.com/x",
        )) {
            assertNull(told(url), url)
        }
    }
}
