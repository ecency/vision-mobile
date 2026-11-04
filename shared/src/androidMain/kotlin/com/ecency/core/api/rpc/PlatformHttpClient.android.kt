package com.ecency.core.api.rpc

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/*
 * OkHttp's default 10 s read/write timeouts would fire before HiveRpcClient's own (10 s reads,
 * 15 s broadcasts) and turn a slow broadcast into a plain node failure. They are disabled; each
 * attempt is bounded by HiveRpcClient. The connect timeout stays: a failed connect is a
 * pre-connection failure, safe to fail over.
 */
actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    engine {
        config {
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(0, TimeUnit.MILLISECONDS)
            writeTimeout(0, TimeUnit.MILLISECONDS)
            callTimeout(0, TimeUnit.MILLISECONDS)
        }
    }
}

/** The OkHttp client behind [platformWebSocketClient] (tests read its timeouts). */
internal fun webSocketOkHttp(connectTimeoutMillis: Long): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .writeTimeout(0, TimeUnit.MILLISECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()

actual fun platformWebSocketClient(connectTimeoutMillis: Long): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    engine { preconfigured = webSocketOkHttp(connectTimeoutMillis) }
}
