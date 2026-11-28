package com.ecency.core.auth

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import kotlin.test.Test
import kotlin.test.assertTrue

/** `KtorAuthHttp.send` reads a lost connection as an IOException: Darwin's request failure must be one (Sentry 3JM). */
class DarwinTransportErrorTest {
    @Test
    fun darwinsRequestFailureIsAnIoException() {
        val e: Throwable = DarwinHttpRequestException(NSError.errorWithDomain(NSURLErrorDomain, NSURLErrorNetworkConnectionLost, null))
        assertTrue(e is kotlinx.io.IOException)
    }
}
