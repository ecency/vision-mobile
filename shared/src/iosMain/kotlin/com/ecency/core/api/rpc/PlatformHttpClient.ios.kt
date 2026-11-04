package com.ecency.core.api.rpc

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/*
 * NSURLSession's request timeout (60 s by default) is set explicitly above HiveRpcClient's own
 * per-attempt timeouts (10 s reads, 15 s broadcasts), which are the ones that must fire.
 */
actual fun platformHttpClient(): HttpClient = HttpClient(Darwin) {
    expectSuccess = false
    engine {
        configureSession {
            timeoutIntervalForRequest = 60.0
            timeoutIntervalForResource = 120.0
        }
    }
}

actual fun platformWebSocketClient(connectTimeoutMillis: Long): HttpClient = platformHttpClient()
