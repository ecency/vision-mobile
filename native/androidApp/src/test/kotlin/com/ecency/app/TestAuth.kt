package com.ecency.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.AuthServices
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.HasSocket
import com.ecency.core.auth.HasSocketFactory
import com.ecency.core.auth.HiveSignerMessages
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections

/**
 * Ecency's token endpoint and a HiveAuth relay for UI tests, nothing on the network: a locally signed code
 * exchanges for tokens of its author, `refresh-<user>` refreshes; the relay answers `auth_req` with an
 * `auth_wait` (expiring at [hiveAuthExpire]) and never approves, so the sheet stays on the QR.
 */
class TestAuth {
    val requests: MutableList<AuthHttpRequest> = Collections.synchronizedList(mutableListOf())
    val relaySent: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var hiveAuthExpire = 1_791_288_000_000L + 60_000

    /** Answers Ecency REST calls first (signup, referrals, announcements, HiveSigner's `/api/broadcast`...); null falls through to the token fake. */
    @Volatile var rest: ((AuthHttpRequest) -> HttpResult?)? = null

    private val http = AuthHttp { request ->
        requests += request
        rest?.invoke(request)?.let { return@AuthHttp it }
        val code = Json.parseToJsonElement(request.body ?: "{}").jsonObject["code"]?.jsonPrimitive?.content.orEmpty()
        val user = if (code.startsWith("refresh-")) code.removePrefix("refresh-")
        else runCatching { HiveSignerMessages.decode(code).authors.first() }.getOrNull()
        if (user == null) HttpResult(400, "{}")
        else HttpResult(200, """{"username":"$user","access_token":"access-$user","refresh_token":"refresh-$user","expires_in":604800}""")
    }

    private val sockets = HasSocketFactory {
        val incoming = Channel<String>(Channel.UNLIMITED)
        object : HasSocket {
            override suspend fun send(text: String) {
                relaySent += text
                if ("\"auth_req\"" in text) incoming.trySend("""{"cmd":"auth_wait","uuid":"ui-test","expire":$hiveAuthExpire}""")
            }
            override val incoming: ReceiveChannel<String> = incoming
            override suspend fun close() { incoming.close() }
        }
    }

    val services = AuthServices(http, sockets)
}

/** MainActivity's first-run terms, as accepted (RN welcome screen). */
fun acceptTerms() {
    androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        .getSharedPreferences("ecency.ui", android.content.Context.MODE_PRIVATE).edit().putBoolean("termsAccepted", true).commit()
    // the shared welcome gate: this version's welcome screen was already passed
    androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        .getSharedPreferences("ecency.prefs", android.content.Context.MODE_PRIVATE).edit()
        .putString(com.ecency.core.app.WelcomeGate.LAST_VERSION, BuildConfig.VERSION_NAME)
        .putString(com.ecency.core.app.WelcomeGate.TERMS, "true").commit()
}
