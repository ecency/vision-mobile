package com.ecency.core.api.speak

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.ClientIdentity
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A tus 1.0 server in memory: creation, HEAD and PATCH with the protocol's offset rule. Nothing leaves the process. */
class FakeTusServer(val endpoint: String = "https://embed.3speak.tv/uploads") : AuthHttp {
    val requests = mutableListOf<AuthHttpRequest>()
    var stored = ByteArray(0)
    var length = -1L
    var created = 0
    var location: String? = "/uploads/abc123"
    var createHeaders = mapOf<String, String>()

    /** Runs before a PATCH is applied; may throw (a lost connection) or answer by itself. */
    var onPatch: (suspend (AuthHttpRequest, FakeTusServer) -> HttpResult?)? = null
    var onHead: ((AuthHttpRequest) -> HttpResult?)? = null
    var gone = false

    val uploadUrl: String get() = TusClient.resolve(endpoint, location)!!

    fun patches() = requests.filter { it.method == "PATCH" }

    override suspend fun send(request: AuthHttpRequest): HttpResult {
        requests += request
        check(request.headers["Tus-Resumable"] == "1.0.0") { "every tus request names the protocol version" }
        return when (request.method) {
            "POST" -> {
                created++
                length = request.headers.getValue("Upload-Length").toLong()
                stored = ByteArray(0)
                gone = false
                HttpResult(201, "", buildMap { location?.let { put("location", it) }; putAll(createHeaders) })
            }
            "HEAD" -> onHead?.invoke(request) ?: if (gone) HttpResult(404, "") else HttpResult(200, "", mapOf("upload-offset" to stored.size.toString(), "upload-length" to length.toString()))
            "PATCH" -> {
                onPatch?.invoke(request, this)?.let { return it }
                if (gone) return HttpResult(404, "")
                // the protocol's rule: the offset must be exactly where the upload stands
                if (request.headers.getValue("Upload-Offset").toLong() != stored.size.toLong()) return HttpResult(409, "")
                stored += request.bytes!!
                HttpResult(204, "", mapOf("upload-offset" to stored.size.toString()))
            }
            else -> HttpResult(405, "")
        }
    }
}

class BytesSource(private val bytes: ByteArray, override val name: String = "clip one.mp4") : TusSource {
    var reads = 0
    var unreadable = false
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray? {
        reads++
        return if (unreadable) null else bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

class TusClientTest {
    private val file = ByteArray(2500) { (it * 7 % 251).toByte() }

    private fun client(server: FakeTusServer, chunk: Int = 1000) = TusClient(server, chunkSize = chunk)

    @Test
    fun creationThenPiecesInOrderEachFromTheServersOffset() = runTest {
        val server = FakeTusServer()
        val source = BytesSource(file)
        val tus = client(server)
        val upload = tus.create(server.endpoint, "tok", source)
        assertEquals("https://embed.3speak.tv/uploads/abc123", upload.url)
        val create = server.requests.single()
        assertEquals("POST", create.method)
        assertEquals("Bearer tok", create.headers["Authorization"])
        assertEquals("2500", create.headers["Upload-Length"])
        assertEquals("filename Y2xpcCBvbmUubXA0", create.headers["Upload-Metadata"])
        val progress = mutableListOf<Long>()
        tus.send(upload, "tok", source, 0) { sent, total -> assertEquals(2500, total); progress += sent }
        assertContentEquals(file, server.stored)
        assertEquals(listOf("0", "1000", "2000"), server.patches().map { it.headers["Upload-Offset"] })
        assertEquals(listOf(1000, 1000, 500), server.patches().map { it.bytes!!.size }, "a piece is never larger than the chunk size")
        assertTrue(server.patches().all { it.contentType == "application/offset+octet-stream" && it.headers["Authorization"] == "Bearer tok" })
        assertEquals(listOf(0L, 1000L, 2000L, 2500L), progress)
    }

    @Test
    fun aLostAnswerAsksTheServerWhereItStandsAndGoesOnFromThere() = runTest {
        val server = FakeTusServer()
        val source = BytesSource(file)
        val tus = client(server)
        val upload = tus.create(server.endpoint, "tok", source)
        var failed = false
        // the second piece arrives, but its answer is lost
        server.onPatch = { request, s ->
            if (!failed && request.headers["Upload-Offset"] == "1000") {
                failed = true
                s.stored += request.bytes!!
                throw IllegalStateException("connection lost")
            }
            null
        }
        tus.send(upload, "tok", source, 0)
        assertContentEquals(file, server.stored, "nothing sent twice, nothing missing")
        assertEquals(listOf("PATCH", "PATCH", "HEAD", "PATCH"), server.requests.drop(1).map { it.method })
        assertEquals("2000", server.patches().last().headers["Upload-Offset"], "from the server's offset, not from where the client thought it was")
    }

    @Test
    fun aPartlyStoredPieceIsContinuedFromTheServersOffset() = runTest {
        val server = FakeTusServer()
        val source = BytesSource(file)
        val tus = client(server)
        val upload = tus.create(server.endpoint, "tok", source)
        var cut = false
        server.onPatch = { request, s ->
            if (!cut) {
                cut = true
                s.stored += request.bytes!!.copyOf(300)
                HttpResult(502, "")
            } else {
                null
            }
        }
        tus.send(upload, "tok", source, 0)
        assertContentEquals(file, server.stored)
        assertEquals(listOf("0", "300", "1300", "2300"), server.patches().map { it.headers["Upload-Offset"] })
    }

    @Test
    fun aPieceTheServerTookOnlyPartOfGoesOnFromWhatItTook() = runTest {
        val server = FakeTusServer()
        val source = BytesSource(file)
        val tus = client(server)
        val upload = tus.create(server.endpoint, "tok", source)
        var first = true
        // a 204 whose offset is short of what was sent: the rest of the piece has to go again
        server.onPatch = { request, s ->
            if (first) {
                first = false
                s.stored += request.bytes!!.copyOf(400)
                HttpResult(204, "", mapOf("upload-offset" to "400"))
            } else {
                null
            }
        }
        tus.send(upload, "tok", source, 0)
        assertContentEquals(file, server.stored)
        assertEquals(listOf("0", "400", "1400", "2400"), server.patches().map { it.headers["Upload-Offset"] })
        assertTrue(server.requests.none { it.method == "HEAD" }, "the answer itself said where the upload stands")
    }

    @Test
    fun anOffsetThatDidNotMoveOrJumpedAheadIsNeverTrusted() = runTest {
        for (lie in listOf("0", "999999", "-5", "soon", null)) {
            val server = FakeTusServer()
            val source = BytesSource(file)
            val tus = client(server)
            val upload = tus.create(server.endpoint, "tok", source)
            var lied = false
            server.onPatch = { _, _ ->
                if (!lied) {
                    lied = true
                    HttpResult(204, "", lie?.let { mapOf("upload-offset" to it) } ?: emptyMap())
                } else {
                    null
                }
            }
            tus.send(upload, "tok", source, 0)
            assertContentEquals(file, server.stored, "offset $lie")
            assertTrue(server.requests.any { it.method == "HEAD" }, "offset $lie: the server is asked")
        }
    }

    @Test
    fun resumingStartsFromHeadAndAGoneUploadSaysSo() = runTest {
        val server = FakeTusServer()
        val source = BytesSource(file)
        val tus = client(server)
        val upload = tus.create(server.endpoint, "tok", source)
        server.stored = file.copyOf(1700)
        assertEquals(1700L, tus.offset(upload, "tok"))
        tus.send(upload, "tok", source, 1700)
        assertContentEquals(file, server.stored)
        assertEquals(1, server.patches().size)
        server.gone = true
        assertNull(tus.offset(upload, "tok"))
        // a wrong starting offset from the caller is refused by the server and corrected by HEAD
        val again = FakeTusServer()
        val second = client(again)
        val u = second.create(again.endpoint, "tok", source)
        again.stored = file.copyOf(500)
        second.send(u, "tok", source, 0)
        assertContentEquals(file, again.stored)
        assertFailsWith<TusException> { second.send(u, "tok", source, 99_999) }
    }

    @Test
    fun retriesFollowRnsDelaysAndThenGiveUp() = runTest {
        val server = FakeTusServer()
        val source = BytesSource(file)
        val tus = client(server)
        val upload = tus.create(server.endpoint, "tok", source)
        server.onPatch = { _, _ -> HttpResult(500, "") }
        val error = assertFailsWith<TusException> { tus.send(upload, "tok", source, 0) }
        assertEquals(500, error.status)
        assertEquals(6, server.patches().size, "one try and five retries")
        assertEquals(0L + 3000 + 5000 + 10000 + 20000, currentTime)
        assertTrue(server.stored.isEmpty())
    }

    @Test
    fun aStalledPieceIsRetriedAndAPieceThatWentThroughResetsTheCount() = runTest {
        val server = FakeTusServer()
        val source = BytesSource(ByteArray(8000) { it.toByte() })
        val tus = client(server)
        val upload = tus.create(server.endpoint, "tok", source)
        var n = 0
        // every piece stalls once (the transport gives up on it), then goes through
        server.onPatch = { _, _ -> if (n++ % 2 == 0) throw com.ecency.core.api.EcencyTimeoutException() else null }
        tus.send(upload, "tok", source, 0)
        assertEquals(8000, server.stored.size)
        assertEquals(16, server.patches().size, "eight pieces, each stalled once: more failures in all than one piece may have")
    }

    @Test
    fun aSlowPieceThatKeepsMovingIsNotCutOffAndASilentOneIs() = runTest {
        val server = FakeTusServer()
        val source = BytesSource(file)
        val tus = TusClient(server, chunkSize = 2500)
        val upload = tus.create(server.endpoint, "tok", source)
        // five minutes for one piece on a slow link, a little going out every half minute
        server.onPatch = { request, _ ->
            repeat(10) { step ->
                kotlinx.coroutines.delay(30_000)
                request.onSent!!((step + 1) * 250L)
            }
            null
        }
        tus.send(upload, "tok", source, 0)
        assertContentEquals(file, server.stored)
        assertEquals(1, server.patches().size, "no retry: it never stalled")
        assertEquals(300_000L, currentTime)
        // nothing moves and no answer comes: after the stall time the piece is given up and tried again
        val quiet = FakeTusServer()
        val second = TusClient(quiet, chunkSize = 2500)
        val u = second.create(quiet.endpoint, "tok", source)
        var first = true
        quiet.onPatch = { _, _ ->
            if (first) {
                first = false
                kotlinx.coroutines.delay(3_600_000)
            }
            null
        }
        val started = currentTime
        second.send(u, "tok", source, 0)
        assertContentEquals(file, quiet.stored)
        assertEquals(2, quiet.patches().size)
        assertEquals(TusClient.STALL_MILLIS, currentTime - started)
    }

    @Test
    fun creationCarriesNoBodyAndNoContentTypeAsRn() = runTest {
        val server = FakeTusServer()
        client(server).create(server.endpoint, "tok", BytesSource(file))
        val create = server.requests.single()
        assertNull(create.bytes)
        assertNull(create.body)
        assertNull(create.contentType)
        assertFalse(create.headers.keys.any { it.equals("Content-Type", ignoreCase = true) })
        // RN's tus options name one metadata field, the file name; so does this client
        assertEquals(listOf("filename"), create.headers.getValue("Upload-Metadata").split(',').map { it.trim().substringBefore(' ') })
    }

    @Test
    fun refusalsThatCannotHealEndTheUploadAtOnce() = runTest {
        // 418 and 422: any definite refusal, not only the ones tus names
        for (status in listOf(401, 403, 413, 418, 422)) {
            val server = FakeTusServer()
            val source = BytesSource(file)
            val tus = client(server)
            val upload = tus.create(server.endpoint, "tok", source)
            server.onPatch = { _, _ -> HttpResult(status, "") }
            assertEquals(status, assertFailsWith<TusException> { tus.send(upload, "tok", source, 0) }.status)
            assertEquals(1, server.patches().size)
            assertEquals(0L, currentTime)
        }
        val server = FakeTusServer()
        val tus = client(server)
        server.location = null
        assertFailsWith<TusException> { tus.create(server.endpoint, "tok", BytesSource(file)) }
    }

    @Test
    fun aRefusedRedirectEndsTheUploadAtOnceWithItsStatus() = runTest {
        val redirect = { com.ecency.core.api.EcencyHttpException(302, "Unexpected redirect (302)") }
        // a piece
        val server = FakeTusServer()
        val source = BytesSource(file)
        val tus = client(server)
        val upload = tus.create(server.endpoint, "tok", source)
        server.onPatch = { _, _ -> throw redirect() }
        assertEquals(302, assertFailsWith<TusException> { tus.send(upload, "tok", source, 0) }.status)
        assertEquals(1, server.patches().size, "asked once: no retry through the delays")
        assertEquals(0, server.requests.count { it.method == "HEAD" }, "and no offset question either")
        assertEquals(0L, currentTime)
        // the offset question
        server.onHead = { throw redirect() }
        assertEquals(302, assertFailsWith<TusException> { tus.offset(upload, "tok") }.status)
        // the answers that may heal are still retried
        assertFalse(TusClient.isFatal(0))
        for (status in listOf(404, 408, 409, 423, 429, 460, 500, 503)) assertFalse(TusClient.isFatal(status), "$status")
        for (status in listOf(301, 302, 307, 308, 400, 401, 403, 410, 413, 415, 422)) assertTrue(TusClient.isFatal(status), "$status")
    }

    @Test
    fun theUploadStopsWhenItMayNotProceedOrTheFileIsGone() = runTest {
        val server = FakeTusServer()
        val source = BytesSource(file)
        val tus = client(server)
        val upload = tus.create(server.endpoint, "tok", source)
        var allowed = 1
        assertFailsWith<TusException> { tus.send(upload, "tok", source, 0, proceed = { allowed-- > 0 }) }
        assertEquals(1, server.patches().size, "nothing more is sent after the owner left")
        source.unreadable = true
        assertFailsWith<TusException> { tus.send(upload, "tok", source, 1000) }
        assertEquals(1, server.patches().size)
    }

    @Test
    fun theUploadAddressStaysOnTheEndpointsHost() {
        val endpoint = "https://embed.3speak.tv/uploads"
        assertEquals("https://embed.3speak.tv/uploads/x1", TusClient.resolve(endpoint, "/uploads/x1"))
        assertEquals("https://embed.3speak.tv/x1", TusClient.resolve(endpoint, "x1"))
        assertEquals("https://EMBED.3speak.tv/files/x1", TusClient.resolve(endpoint, "https://EMBED.3speak.tv/files/x1"))
        for (bad in listOf("https://evil.example/x", "http://embed.3speak.tv/x", "//evil.example/x", "https://embed.3speak.tv@evil.example/x", "ftp://embed.3speak.tv/x", "", " ", null)) {
            assertNull(TusClient.resolve(endpoint, bad), "$bad")
        }
        assertNull(TusClient.resolve("http://embed.3speak.tv/uploads", "/x"))
    }

    @Test
    fun theBuildsUserAgentNeverGoesTo3Speak() {
        // the transport adds the app's user agent for Ecency's hosts only; the tus client sets none itself
        for (url in listOf("https://embed.3speak.tv/uploads", "https://embed.3speak.tv/uploads/abc", "https://3speak.tv/x", "https://ecency.com.3speak.tv/x")) {
            val request = AuthHttpRequest("PATCH", url, mapOf("Tus-Resumable" to "1.0.0"), null, bytes = ByteArray(1))
            assertFalse(ClientIdentity.headers(request, "Ecency/4.0.0 test").keys.any { it.equals("User-Agent", ignoreCase = true) }, url)
        }
        assertTrue("User-Agent" in ClientIdentity.headers(AuthHttpRequest("POST", "https://ecency.com/api/threespeak/upload-token"), "Ecency/4.0.0 test"))
    }

    @Test
    fun proxyAnswersAreChecked() {
        assertTrue(SpeakApi.isSpeakHost("https://embed.3speak.tv/uploads"))
        assertFalse(SpeakApi.isSpeakHost("https://embed.3speak.tv.evil.example/uploads"))
        assertFalse(SpeakApi.isSpeakHost("https://evil3speak.tv/uploads"))
        assertFalse(SpeakApi.isSpeakHost("http://embed.3speak.tv/uploads"))
        assertTrue(SpeakApi.isEmbedUrl("https://play.3speak.tv/embed?v=alice/abcd1234"))
        assertFalse(SpeakApi.isEmbedUrl("https://play.3speak.tv/embed?v=alice/abcd1234)![x](https://evil.example/a.png"))
        assertFalse(SpeakApi.isEmbedUrl("https://play.3speak.tv/watch?v=alice/abcd1234"))
        assertFalse(SpeakApi.isEmbedUrl("https://evil.example/embed?v=3speak.tv/embed/"))
        assertEquals("abcd1234", SpeakApi.permlinkOf("https://play.3speak.tv/embed?v=alice/abcd1234"))
        assertEquals("abcd1234", SpeakApi.permlinkOf("https://play.3speak.tv/embed?v=alice/abcd1234&mode=iframe"))
        assertEquals("abcd1234", SpeakApi.permlinkOf("https://3speak.tv/embed/@alice/abcd1234"))
    }
}
