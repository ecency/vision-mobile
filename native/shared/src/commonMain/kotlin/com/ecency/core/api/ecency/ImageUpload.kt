package com.ecency.core.api.ecency

import com.ecency.core.api.EcencyException
import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.HiveSignerMessages
import com.ecency.core.hive.PrivateKey
import com.ecency.core.l10n.L
import com.ecency.core.secure.VaultAccount
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** An image ready to upload: already converted and resized by the platform, off the main thread. */
class UploadFile(val bytes: ByteArray, val name: String, val mime: String)

/** The account cannot sign uploads (RN SIGN_IMAGE_UNAVAILABLE): no posting key and no token. */
class ImageSigningUnavailable : EcencyException("SIGN_IMAGE_UNAVAILABLE")

/** RN `signImage`: the token that goes into the upload url. */
object ImageSigning {
    /**
     * A key account that holds a posting key signs a `posting` message with it; every other account (HiveSigner,
     * HiveAuth, a key account without a posting key such as an active-key login) sends its access token (RN
     * isHsClientSupported, which goes by the auth type; here the stored keys decide, so an imported key of
     * another role never changes how uploads are signed).
     */
    @Throws(EcencyException::class)
    fun token(account: VaultAccount, nowMillis: Long): String {
        if (!account.signsWithPostingKey) {
            return account.accessToken?.takeIf { it.isNotEmpty() } ?: throw ImageSigningUnavailable()
        }
        val wif = account.postingKey?.takeIf { it.isNotEmpty() } ?: throw ImageSigningUnavailable()
        val key = try {
            PrivateKey.fromWif(wif)
        } catch (_: Exception) {
            throw ImageSigningUnavailable()
        }
        return HiveSignerMessages.imageUploadToken(account.username, key, nowMillis)
    }
}

/** One multipart POST of [file] (form field `file`) to [url]. Injectable: tests and fake-data runs never reach the image host. */
fun interface ImageUploadTransport {
    @Throws(Exception::class)
    suspend fun upload(url: String, authorization: String, file: UploadFile, onProgress: (sent: Long, total: Long) -> Unit): HttpResult

    companion object {
        /** Debug fake-data runs: every upload "succeeds" after a moment with a synthetic url; nothing leaves the device. */
        fun demo(): ImageUploadTransport = ImageUploadTransport { _, _, file, onProgress ->
            val total = file.bytes.size.toLong()
            for (step in 1..4) {
                delay(400)
                onProgress(total * step / 4, total)
            }
            HttpResult(200, "{\"url\":\"https://images.ecency.com/demo/${file.name.filter { it.isLetterOrDigit() || it == '.' || it == '-' }}\"}")
        }

        /** Refuses every upload: the default until an app sets a real transport. */
        fun refusing(): ImageUploadTransport = ImageUploadTransport { _, _, _, _ -> throw EcencyHttpException(0, "uploads are not configured") }
    }
}

/** [ImageUploadTransport] over Ktor (OkHttp, NSURLSession). */
class KtorImageUploadTransport(client: HttpClient) : ImageUploadTransport {
    constructor() : this(com.ecency.core.api.rpc.platformHttpClient())

    // the upload address is the image host's own: a redirect would send the file to wherever the answer names
    private val client = client.config { followRedirects = false }

    @Throws(Exception::class)
    override suspend fun upload(url: String, authorization: String, file: UploadFile, onProgress: (Long, Long) -> Unit): HttpResult {
        val response = client.post(url) {
            header(HttpHeaders.Authorization, authorization)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file", file.bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, file.mime)
                                append(HttpHeaders.ContentDisposition, "filename=\"${file.name.replace("\"", "")}\"")
                            },
                        )
                    },
                ),
            )
            onUpload { sent, total -> onProgress(sent, total ?: file.bytes.size.toLong()) }
        }
        return HttpResult(response.status.value, response.bodyAsText())
    }
}

/**
 * RN uploadImage + useMediaUploadMutation: `POST <host>/hs/<token>` with the file, one try for as long as bytes keep moving (see [untilStalled]; RN: a flat 120 s), up to two more
 * tries after 502/503/504, a timeout or a network error (1 s then 2 s apart). Cancelling the caller cancels the request.
 */
class ImageUploader(
    private val transport: ImageUploadTransport,
    private val host: String = DEFAULT_HOST,
    private val retryDelayMillis: (attempt: Int) -> Long = { minOf(1000L shl it, 10_000L) },
) {
    /** The uploaded image's url. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun upload(file: UploadFile, token: String, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }): String {
        if (file.bytes.size > MAX_BYTES) throw EcencyHttpException(413)
        var failures = 0
        while (true) {
            val error: EcencyHttpException = try {
                val response = withTimeout(MAX_TRY_MILLIS) { untilStalled { moved -> transport.upload("$host/hs/$token", host, file) { sent, total -> moved(sent); onProgress(sent, total) } } }
                if (response.status in 200..299) {
                    return runCatching { Json.parseToJsonElement(response.body) }.getOrNull().obj()?.str("url")?.takeIf { it.isNotEmpty() }
                        ?: throw EcencyHttpException(response.status, "Returning response missing media data")
                }
                EcencyHttpException(response.status)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                EcencyHttpException(0, "timeout", e)
            } catch (e: UploadStalled) {
                EcencyHttpException(0, "timeout", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EcencyException) {
                throw e
            } catch (e: Exception) {
                // The address holds the upload token, and HTTP stacks put the address into their messages: neither
                // the message nor the cause of what leaves here carries it, only the kind of failure.
                EcencyHttpException(0, "network error (${e::class.simpleName ?: "unknown"})")
            }
            val retryable = error.status == 0 || error.status in RETRY_STATUSES
            if (!retryable || failures >= MAX_RETRIES) throw error
            delay(retryDelayMillis(failures))
            failures++
        }
    }

    /** No byte and no answer for [STALL_MILLIS]: retried like a timeout. */
    private class UploadStalled : Exception("upload stalled")

    /**
     * Runs one upload try, giving up with a "timeout" error once no further byte went out and no answer came for
     * [STALL_MILLIS]: a large image on a slow connection keeps going for as long as it moves, a dead connection
     * (the HTTP client has no timeout of its own on Android) is noticed within a minute.
     */
    private suspend fun untilStalled(run: suspend (moved: (Long) -> Unit) -> HttpResult): HttpResult = coroutineScope {
        val sent = MutableStateFlow(0L)
        var stalled = false
        val work = async { run { bytes -> sent.update { maxOf(it, bytes) } } }
        // restarted by every new high mark; equal or lower counts are no progress
        val watchdog = launch {
            sent.collectLatest {
                delay(STALL_MILLIS)
                stalled = true
                work.cancel()
            }
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            if (!stalled) throw e
            throw UploadStalled()
        } finally {
            watchdog.cancel()
        }
    }

    companion object {
        /** Ecency's image host (the RN build's NEW_IMAGE_API). */
        const val DEFAULT_HOST = "https://images.ecency.com"

        /** RN MAX_IMAGE_UPLOAD_SIZE. */
        const val MAX_BYTES = 30_000_000

        /** RN MAX_IMAGES_PER_PICK, MAX_IMAGE_DIMENSION and COMPRESS_QUALITY, applied by the platform pickers. */
        const val MAX_IMAGES_PER_PICK = 5
        const val MAX_DIMENSION = 1920
        const val JPEG_QUALITY = 85

        /** One try gives up when no byte went out and no answer came for this long. */
        const val STALL_MILLIS = 60_000L

        /**
         * The longest one try may take while it still moves (30 MB at about 50 KB/s). RN: a flat 120 s. On iOS the
         * session's resource timeout (120 s, `PlatformHttpClient.ios.kt`) ends a try first, so there a try is
         * capped at 120 s whatever it moves; this bound is the one that applies on Android.
         */
        const val MAX_TRY_MILLIS = 600_000L
        const val MAX_RETRIES = 2
        private val RETRY_STATUSES = setOf(502, 503, 504)

        /** RN's upload error copy (`alert.decrypt_fail_alert`, `payloadTooLarge`, `quotaExceeded`, `invalidImage`, `fail`). */
        fun message(e: Throwable): String = when {
            e is ImageSigningUnavailable -> L.alert.decryptFailAlert
            e is EcencyHttpException && e.status == 413 -> L.alert.payloadTooLarge
            e is EcencyHttpException && e.status == 429 -> L.alert.qoutaExceeded
            e is EcencyHttpException && e.status == 400 -> L.alert.invalidImage
            else -> L.alert.fail
        }
    }
}
