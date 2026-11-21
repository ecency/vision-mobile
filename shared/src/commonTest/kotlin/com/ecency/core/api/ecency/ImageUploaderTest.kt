package com.ecency.core.api.ecency

import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** #3722: the upload token goes in an `Authorization: Bearer` header, never in the address (imagehoster#84). */
class ImageUploaderTest {
    private class Host(vararg answers: () -> HttpResult) : ImageUploadTransport {
        val requests = mutableListOf<Pair<String, String>>()
        private val queue = ArrayDeque(answers.toList())

        override suspend fun upload(url: String, authorization: String, file: UploadFile, onProgress: (Long, Long) -> Unit): HttpResult {
            requests += url to authorization
            return (queue.removeFirstOrNull() ?: { HttpResult(200, """{"url":"https://images.example/a.jpg"}""") })()
        }
    }

    private val file = UploadFile(ByteArray(10), "a.jpg", "image/jpeg")
    private val token = "eyJzaWduZWRfbWVzc2FnZSI6eyJ0eXBlIjoiY29kZSJ9fQ"

    @Test
    fun theTokenTravelsInABearerHeaderAndNotInTheAddress() = runTest {
        val host = Host()
        val url = ImageUploader(host, "https://images.example") { 0 }.upload(file, token)
        assertEquals("https://images.example/a.jpg", url)
        val (address, authorization) = host.requests.single()
        assertEquals("https://images.example/hs", address)
        assertEquals("Bearer $token", authorization)
        assertFalse(token in address, "the address holds no token: $address")
    }

    @Test
    fun aRetriedUploadKeepsTheHeaderAndAnAddressWithoutTheToken() = runTest {
        val host = Host({ HttpResult(503, "") }, { throw RuntimeException("connection reset for https://images.example/hs") }, { HttpResult(200, """{"url":"https://images.example/b.jpg"}""") })
        assertEquals("https://images.example/b.jpg", ImageUploader(host, "https://images.example") { 0 }.upload(file, token))
        assertEquals(3, host.requests.size)
        for ((address, authorization) in host.requests) {
            assertEquals("https://images.example/hs", address)
            assertEquals("Bearer $token", authorization)
        }
    }
}
