package com.ecency.core.api.rpc

import com.ecency.core.app.ChatSocket
import kotlin.test.Test
import kotlin.test.assertEquals

class WebSocketClientJvmTest {
    @Test
    fun theWebsocketConnectTimeoutIsTheChatSocketsOwn() {
        // OkHttp giving up first (its default 10 s) would turn RN's final 15 s timeout into a retried failure
        val ok = webSocketOkHttp(com.ecency.core.auth.KtorHasSocketFactory.CONNECT_TIMEOUT_MILLIS)
        assertEquals(ChatSocket.CONNECT_TIMEOUT_MILLIS, ok.connectTimeoutMillis.toLong())
        assertEquals(15_000L, ChatSocket.CONNECT_TIMEOUT_MILLIS)
        assertEquals(0, ok.readTimeoutMillis)
    }
}
