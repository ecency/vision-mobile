package com.ecency.core.auth

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.NODES
import com.ecency.core.api.clientWith
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.AppTestKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChainAuthoritiesTest {
    @Test
    fun theConfirmedReadAsksHealthyNodesFirst() = runTest {
        val transport = FakeTransport { req ->
            if (req.node == NODES[0]) HttpResult(500, "")
            else FakeTransport.result(req, "[" + AppTestKeys.accountJson("alice") + "]")
        }
        val client = clientWith(transport)
        // the pool has learned node-a is down
        repeat(3) { client.health.recordFailure(NODES[0]) }
        val confirmed = RpcChainAccounts(client).confirmedAuthorities("alice")
        assertEquals("alice", confirmed?.username)
        assertFalse(NODES[0] in transport.sent.map { it.node }, "a node known to be down is not asked ahead of healthy ones")
    }
}
