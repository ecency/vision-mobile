package com.ecency.core.app

import com.ecency.core.api.EcencyApi
import com.ecency.core.api.FakeTransport
import com.ecency.core.api.NODES
import com.ecency.core.api.Sent
import com.ecency.core.api.model.Post
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.render.blocks.Block
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.InMemorySecureStore
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.KeyVaultAsync
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.CoroutineContext

/** Dispatches through [delegate] and records whether a block is running on it right now. */
class TrackingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var active = false
        private set
    var dispatches = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches++
        delegate.dispatch(context, Runnable {
            active = true
            try {
                block.run()
            } finally {
                active = false
            }
        })
    }
}

/** Wraps a renderer and records, per call, whether it ran on the render dispatcher. */
class RecordingRenderer(private val inner: PostBodyRenderer, private val render: TrackingDispatcher) : PostBodyRenderer {
    val blockCalls = mutableListOf<String>()
    val offRenderCalls = mutableListOf<String>()

    override fun blocks(post: Post): List<Block> {
        blockCalls += post.permlink
        if (!render.active) offRenderCalls += "blocks ${post.permlink}"
        return inner.blocks(post)
    }

    override fun summary(post: Post): String {
        if (!render.active) offRenderCalls += "summary ${post.permlink}"
        return inner.summary(post)
    }

    override fun cardImage(post: Post): String? {
        if (!render.active) offRenderCalls += "cardImage ${post.permlink}"
        return inner.cardImage(post)
    }
}

/**
 * An [AppEnvironment] over a fake transport, an in-memory vault, test dispatchers (main and
 * render are distinct dispatchers on the test scheduler) and a fixed clock.
 */
class TestApp(
    scope: TestScope,
    debugKeyImport: Boolean = true,
    legacy: LegacyMigrationSource? = null,
    /** Runs the vault and other blocking calls on their own test dispatcher (the platforms do: IO is not main). */
    separateIo: Boolean = false,
    /** Wraps the IO dispatcher (e.g. [HoldingDispatcher], to hold vault reads back). */
    ioWrap: ((kotlinx.coroutines.CoroutineDispatcher) -> kotlinx.coroutines.CoroutineDispatcher)? = null,
    val handler: suspend FakeTransport.(Sent) -> HttpResult,
) {
    val transport = FakeTransport(handler)
    val store = InMemorySecureStore()
    val main = StandardTestDispatcher(scope.testScheduler, "main")
    val render = TrackingDispatcher(StandardTestDispatcher(scope.testScheduler, "render"))
    val io = (if (separateIo) StandardTestDispatcher(scope.testScheduler, "io") else main).let { ioWrap?.invoke(it) ?: it }
    val renderer = RecordingRenderer(InterimPostBodyRenderer(PROXY), render)
    val authBackend = FakeAuthBackend()
    val relay = FakeHasRelay()
    var now = NOW

    /** The environment's clock; [now] unless a test makes it follow the virtual time. */
    var clock: () -> Long = { now }

    /** The lock's monotonic clock, moved by tests. */
    var monotonic = 0L
    val env = AppEnvironment(
        api = EcencyApi(NODES, transport),
        vault = KeyVaultAsync(KeyVault(store), io),
        renderer = renderer,
        dispatchers = AppDispatchers(main, render, io),
        proxyBase = PROXY,
        debugKeyImport = debugKeyImport,
        nowMillis = { clock() },
        security = SecurityConfig(pinIterations = 16, monotonicMillis = { monotonic }),
        legacy = legacy,
        auth = AuthServices(authBackend.http, relay),
    )

    init {
        // the welcome screen is behind the tests' user unless a test says otherwise
        env.setWelcomeDone(true)
    }

    /** Stores a posting-key account and makes it current (bypassing import validation). */
    fun signIn(username: String = USER) {
        val vault = KeyVault(store)
        vault.save(VaultAccount(username, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF))
        vault.setCurrentUsername(username)
    }

    fun broadcasts() = transport.sent.filter { it.method == "condenser_api.broadcast_transaction" }

    companion object {
        const val PROXY = "https://i.ecency.com"
        const val USER = "spike-voter"

        /** 2026-10-06T12:00:00Z */
        const val NOW = 1_791_288_000_000L

        // Chain state for signing (values from BroadcastTest; nothing reaches a real node).
        private const val HEAD = 0x0601303CL
        private val REF_ID = "06013039" + "d368b7c8" + "00".repeat(12)
        private val HEAD_ID = "0601303c" + "11".repeat(16)

        /** Answers the chain-state calls a broadcast needs; null for anything else. */
        fun chain(request: Sent): HttpResult? = when (request.method) {
            "condenser_api.get_dynamic_global_properties" -> FakeTransport.result(
                request, """{"head_block_number":$HEAD,"head_block_id":"$HEAD_ID","time":"2026-10-06T11:59:00"}""",
            )
            "condenser_api.get_block_header" -> FakeTransport.result(request, """{"previous":"$REF_ID"}""")
            "condenser_api.get_content" -> FakeTransport.result(request, """{"author":"","permlink":""}""")
            else -> null
        }
    }
}

/** A dispatcher that runs through [delegate], or keeps the work back while [hold] is on, until [release]. */
class HoldingDispatcher(private val delegate: kotlinx.coroutines.CoroutineDispatcher) : kotlinx.coroutines.CoroutineDispatcher() {
    var hold = false
    private val held = ArrayDeque<Pair<kotlin.coroutines.CoroutineContext, Runnable>>()

    val heldCount: Int get() = held.size

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        if (hold) held.addLast(context to block) else delegate.dispatch(context, block)
    }

    /** Lets the oldest held piece of work run. */
    fun releaseOne() {
        val (c, b) = held.removeFirst()
        delegate.dispatch(c, b)
    }
}
