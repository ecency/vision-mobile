package com.ecency.core.api.speak

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The file a tus upload reads, piece by piece (never whole in memory). [read] is called off the main thread. */
interface TusSource {
    val name: String
    val size: Long

    /** [length] bytes from [offset], or null when the file cannot be read (any more). */
    fun read(offset: Long, length: Int): ByteArray?
}

/** A tus upload failed for good. [status] is the HTTP status that ended it, 0 for none (network, timeout, a bad answer). */
open class TusException(val status: Int, message: String) : Exception(message)

/** The file being uploaded could not be read any more (deleted, moved): nothing a retry can send. */
class TusSourceGone : TusException(0, "file not readable")

/** Where an upload lives on the server, so a later try continues it instead of starting over. */
data class TusUpload(val url: String, val createHeaders: Map<String, String> = emptyMap())

/**
 * A small client for the tus 1.0 resumable upload protocol (creation, PATCH with offset, HEAD to resume), over
 * the app's HTTP seam. RN uses tus-js-client with 5 to 20 MB chunks and up to three parallel parts; here the
 * file goes up in order, in pieces of [chunkSize], each read from the file when it is its turn.
 *
 * - The server's offset is the truth: every PATCH must be answered with an `Upload-Offset` that moved forward
 *   and not past what was sent, and after any failure the offset is asked for again (HEAD) before more is sent.
 * - A piece is given as long as its bytes keep moving: only when nothing went out and no answer came for
 *   [stallMillis] does it count as stalled and is treated like a failure (as the image upload; RN sets no
 *   per-chunk timeout). [pieceCapMillis] is the generous ceiling for one piece.
 * - Failures are retried after RN's delays (0, 3, 5, 10, 20 s); a piece that went through resets the count.
 *   401, 403, 404, 410 and 413 end the upload at once.
 * - Cancelling the caller stops it between and during requests.
 * - Nothing is ever sent to another host than the endpoint's: a `Location` that names one is refused.
 */
class TusClient(
    private val http: AuthHttp,
    private val chunkSize: Int = CHUNK_BYTES,
    private val stallMillis: Long = STALL_MILLIS,
    private val retryDelays: List<Long> = RETRY_DELAYS,
    private val pieceCapMillis: Long = PIECE_CAP_MILLIS,
) {
    private class Progress { @kotlin.concurrent.Volatile var moves = 0L }

    /**
     * Runs one request that reports its progress; gives up (null) when neither a byte moved nor an answer came
     * for [stallMillis]. A slow link that keeps moving is never cut off by this.
     */
    private suspend fun untilStalled(run: suspend (moved: (Long) -> Unit) -> HttpResult?): HttpResult? = coroutineScope {
        val progress = Progress()
        val work = async { run { progress.moves++ } }
        val watchdog = launch {
            while (true) {
                val before = progress.moves
                delay(stallMillis)
                if (progress.moves == before) {
                    work.cancel()
                    break
                }
            }
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            // the watchdog stopped it (a stall), unless the caller itself was cancelled
            coroutineContext.ensureActive()
            null
        } finally {
            watchdog.cancel()
        }
    }

    private fun headers(token: String, extra: Map<String, String> = emptyMap()) =
        mapOf("Tus-Resumable" to VERSION, "Authorization" to "Bearer $token") + extra

    /**
     * Null: no answer (a lost connection, a timeout), which may heal. A redirect the transport refused is an
     * answer: it comes back with its status, and [isFatal] ends the upload on it at once.
     */
    private suspend fun send(request: AuthHttpRequest): HttpResult? = try {
        http.send(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        (e as? com.ecency.core.api.EcencyHttpException)?.takeIf { it.isRedirect }?.let { HttpResult(it.status, "") }
    }

    /** Creation: `POST endpoint` with the length and the file name; the answer's `Location` is the upload. */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun create(endpoint: String, token: String, source: TusSource): TusUpload {
        val metadata = "filename " + Base64.encode(source.name.encodeToByteArray())
        val response = send(
            AuthHttpRequest(
                // as tus-js-client in RN: creation carries headers only, no body and no content type
                "POST", endpoint, headers(token, mapOf("Upload-Length" to source.size.toString(), "Upload-Metadata" to metadata)), null,
                timeoutMillis = REQUEST_TIMEOUT_MILLIS,
            ),
        ) ?: throw TusException(0, "no answer")
        if (response.status != 201) throw TusException(response.status, "upload was not created")
        val url = resolve(endpoint, response.headers["location"]) ?: throw TusException(0, "no upload address")
        return TusUpload(url, response.headers)
    }

    /** HEAD: how much of the upload the server has. Null when the upload is gone (404, 410). */
    suspend fun offset(upload: TusUpload, token: String): Long? {
        val response = send(AuthHttpRequest("HEAD", upload.url, headers(token), null, timeoutMillis = REQUEST_TIMEOUT_MILLIS)) ?: throw TusException(0, "no answer")
        return when (response.status) {
            200, 204 -> response.headers["upload-offset"]?.toLongOrNull()?.takeIf { it >= 0 } ?: throw TusException(0, "no offset")
            404, 410 -> null
            else -> throw TusException(response.status, "offset not available")
        }
    }

    /**
     * Sends [source] to [upload] from the server's own offset on. [proceed] is asked before every piece (false
     * ends the upload: the account changed). Returns when the server holds every byte.
     */
    suspend fun send(upload: TusUpload, token: String, source: TusSource, from: Long, proceed: () -> Boolean = { true }, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }) {
        var offset = from
        var attempt = 0
        if (offset < 0 || offset > source.size) throw TusException(0, "bad offset")
        onProgress(offset, source.size)
        while (offset < source.size) {
            if (!proceed()) throw TusException(0, "stopped")
            val length = minOf(chunkSize.toLong(), source.size - offset).toInt()
            val chunk = source.read(offset, length)?.takeIf { it.size == length } ?: throw TusSourceGone()
            val response = untilStalled { moved ->
                send(
                    AuthHttpRequest(
                        "PATCH", upload.url, headers(token, mapOf("Upload-Offset" to offset.toString())), null,
                        bytes = chunk, contentType = "application/offset+octet-stream", timeoutMillis = pieceCapMillis, onSent = moved,
                    ),
                )
            }
            val next = response?.takeIf { it.status == 204 }?.headers?.get("upload-offset")?.toLongOrNull()
            if (next != null && next > offset && next <= offset + length) {
                offset = next
                attempt = 0
                onProgress(offset, source.size)
                continue
            }
            // not accepted (or accepted with an offset that makes no sense): never guess, ask the server
            val status = response?.status ?: 0
            if (isFatal(status)) throw TusException(status, "upload refused")
            if (attempt >= retryDelays.size) throw TusException(status, "upload failed")
            delay(retryDelays[attempt++])
            offset = recover(upload, token, source.size, attemptsLeft = { attempt < retryDelays.size }, wait = { delay(retryDelays[attempt++]) })
        }
    }

    /** The server's offset after a failed piece; its own failures use up the same retries. */
    private suspend fun recover(upload: TusUpload, token: String, size: Long, attemptsLeft: () -> Boolean, wait: suspend () -> Unit): Long {
        while (true) {
            try {
                val at = offset(upload, token) ?: throw TusException(404, "upload is gone")
                if (at > size) throw TusException(0, "bad offset")
                return at
            } catch (e: TusException) {
                if (isFatal(e.status) || e.status == 404 || !attemptsLeft()) throw e
                wait()
            }
        }
    }

    companion object {
        const val VERSION = "1.0.0"

        /** One PATCH carries this much at most (and this much is held in memory at a time). */
        const val CHUNK_BYTES = 2 * 1024 * 1024

        /** A piece counts as stalled when no byte went out and no answer came for this long (the image upload's rule). */
        const val STALL_MILLIS = 60_000L

        /** The ceiling for one piece however slowly it moves (the transport's own maximum). */
        const val PIECE_CAP_MILLIS = 10 * 60_000L

        /** Creation and HEAD carry no file data: a plain bound. */
        const val REQUEST_TIMEOUT_MILLIS = 60_000L

        /** RN TUS_RETRY_DELAYS. */
        val RETRY_DELAYS = listOf(0L, 3000L, 5000L, 10000L, 20000L)

        /** A 4xx that may pass on another try: a timing answer (408, 429), or tus saying "ask me where I stand" (404 and 409 go through HEAD, 423 is a lock, 460 a checksum). */
        private val RETRYABLE_4XX = setOf(404, 408, 409, 423, 429, 460)

        /** An answer that asking again cannot change: a redirect (never followed) or a definite refusal. */
        internal fun isFatal(status: Int): Boolean = status in 300..399 || (status in 400..499 && status !in RETRYABLE_4XX)

        private fun origin(url: String): String? {
            if (!url.startsWith("https://")) return null
            val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
            if (authority.isEmpty() || '@' in authority || url.any { it <= ' ' || it == '\\' }) return null
            return "https://$authority"
        }

        /**
         * The upload's address from a `Location` header: an absolute https address on the endpoint's own host, or
         * a path on it. Anything else (another host, another scheme) is refused: the token and the file go nowhere else.
         */
        fun resolve(endpoint: String, location: String?): String? {
            val base = origin(endpoint) ?: return null
            val l = location?.trim().orEmpty()
            if (l.isEmpty() || l.any { it <= ' ' || it == '\\' }) return null
            return when {
                l.startsWith("https://") -> l.takeIf { origin(it).equals(base, ignoreCase = true) }
                l.startsWith("//") || "://" in l.substringBefore('?') -> null
                l.startsWith("/") -> base + l
                else -> endpoint.substringBefore('?').substringBeforeLast('/') + "/" + l
            }
        }
    }
}
