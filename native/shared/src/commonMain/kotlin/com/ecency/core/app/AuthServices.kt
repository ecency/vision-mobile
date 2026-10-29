package com.ecency.core.app

import com.ecency.core.auth.AuthException
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.EcencyTokenApi
import com.ecency.core.auth.HasSocketFactory
import com.ecency.core.auth.HiveAuthProtocol
import com.ecency.core.auth.HiveSignerConfig
import com.ecency.core.auth.KtorAuthHttp
import com.ecency.core.auth.KtorHasSocketFactory

/**
 * The network pieces of sign-in and signing: Ecency's token endpoint and HiveSigner's API ([http]), the
 * HiveAuth relay ([sockets]) and the HiveSigner client settings. [platform] talks to the real services;
 * [offline] refuses every call (tests and fake-data runs pass scripted fakes instead).
 */
class AuthServices(
    val http: AuthHttp,
    val sockets: HasSocketFactory,
    val hiveSigner: HiveSignerConfig = HiveSignerConfig.RN,
    val tokenHost: String = EcencyTokenApi.DEFAULT_HOST,
    val hiveAuthHost: String = HiveAuthProtocol.DEFAULT_HOST,
) {
    /** Without the optional settings (Swift sees no default arguments). */
    constructor(http: AuthHttp, sockets: HasSocketFactory) : this(http, sockets, HiveSignerConfig.RN)

    companion object {
        fun platform(): AuthServices = AuthServices(KtorAuthHttp(), KtorHasSocketFactory())

        /** As [platform], naming the app with the build's user agent (null or blank: the default). */
        fun platform(userAgent: String?): AuthServices =
            AuthServices(KtorAuthHttp(com.ecency.core.auth.ClientIdentity.userAgent(userAgent)), KtorHasSocketFactory())

        /** Every call fails as a network error: nothing leaves the process. */
        fun offline(): AuthServices = AuthServices(
            AuthHttp { throw AuthException.TokenExchangeFailed(0) },
            HasSocketFactory { throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.NOT_CONNECTED, "offline") },
        )
    }
}
