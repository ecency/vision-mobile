package com.ecency.core.auth

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey

/** spike-user's keys: every role derives from the golden-vector throwaway master password. */
object TestKeys {
    fun key(role: KeyRole): PrivateKey = PrivateKey.fromLogin(AuthGoldenVectors.USER, AuthGoldenVectors.PASSWORD, role)
    fun wif(role: KeyRole): String = key(role).toWif()
    fun pub(role: KeyRole): String = key(role).publicKey().toString()

    /** Authorities with every role's key, unless replaced. */
    fun authorities(
        owner: List<String> = listOf(pub(KeyRole.OWNER)),
        active: List<String> = listOf(pub(KeyRole.ACTIVE)),
        posting: List<String> = listOf(pub(KeyRole.POSTING)),
        memo: String? = pub(KeyRole.MEMO),
        postingApps: List<String> = emptyList(),
    ) = ChainAuthorities(AuthGoldenVectors.USER, owner, active, posting, memo, postingApps)

    /** A different throwaway key that is on no account. */
    val stranger: PrivateKey = PrivateKey.fromSeed("ecency-native-auth-test-stranger-throwaway")
}

/** Scripted [AuthHttp]: records requests, answers with [handler]. */
class FakeAuthHttp(private val handler: (AuthHttpRequest) -> HttpResult) : AuthHttp {
    val sent = mutableListOf<AuthHttpRequest>()

    /** Runs before each answer: a test holds the request open here to interleave something with it. */
    var beforeAnswer: (suspend (AuthHttpRequest) -> Unit)? = null

    override suspend fun send(request: AuthHttpRequest): HttpResult {
        sent += request
        beforeAnswer?.invoke(request)
        return handler(request)
    }

    companion object {
        fun tokens(username: String = AuthGoldenVectors.USER, access: String = "access-1", refresh: String = "refresh-1", expiresIn: Long = 604800) =
            HttpResult(200, """{"username":"$username","access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn}""")
    }
}
