package com.ecency.core.auth

import com.ecency.core.api.EcencyException
import com.ecency.core.api.rpc.RpcErrorException
import com.ecency.core.hive.KeyRole

/**
 * Every failure of the auth layer. Subclasses of [EcencyException], so Swift callers receive them as
 * thrown errors. Messages never contain a secret.
 */
sealed class AuthException(message: String, cause: Throwable? = null) : EcencyException(message, cause) {
    /** The username is not a valid Hive account name. */
    class InvalidUsername : AuthException("invalid Hive username")

    /**
     * The key is one of the account's keys, but its weight is below the authority's threshold: it cannot sign
     * alone (a multi-signature account), so the app has no use for it.
     */
    class KeyBelowThreshold : AuthException("this key cannot sign for the account on its own")

    /**
     * The secret only gives the owner key: an owner private key, or a master password whose posting, active
     * and memo keys are no longer the account's. The app never stores or signs with the owner key, so there is
     * nothing it could keep. [fromMasterPassword] picks the wording.
     */
    class OwnerKeyOnly(val fromMasterPassword: Boolean) : AuthException("the owner key is not accepted")

    /** [KeyLogin.refuseKeySharedWithOwner] is on and the typed key is also the account's owner key. */
    class KeySharedWithOwner : AuthException("this key is also the account's owner key")

    /** No such account on chain (RN `auth.invalid_username`). */
    class UnknownAccount(val username: String) : AuthException("account @$username does not exist")

    /** A master password that derives none of the account's keys (RN `auth.invalid_credentials`). */
    class WrongPassword : AuthException("the password does not match the account")

    /** A valid private key that is not one of the account's keys (RN `auth.invalid_credentials`). */
    class KeyNotOnAccount : AuthException("this key is not a key of the account")

    /** The input is a public key (STM...), not a private key or the master password. */
    class PublicKeyGiven : AuthException("this is a public key, not a private key")

    /** Looks like a WIF private key but its checksum or format is wrong. */
    class InvalidKey : AuthException("this is not a valid private key")

    /** The secret only matches the memo key, which can neither sign nor obtain an Ecency session. */
    class MemoKeyOnly : AuthException("the memo key cannot be used to sign in")

    /** The Ecency/HiveSigner token endpoint refused or failed; [status] is the HTTP status, 0 for network. */
    class TokenExchangeFailed(val status: Int, cause: Throwable? = null) :
        AuthException("token exchange failed (status $status)", cause)

    /** The session's access token has expired and could not be refreshed: sign in again. */
    class SessionExpired(cause: Throwable? = null) : AuthException("session expired, sign in again", cause)

    /** No signed-in account where one is needed. */
    class NotSignedIn : AuthException("no signed-in account")

    /** Tokens or a code turned out to belong to another account than the link or request named. */
    class AccountMismatch(val expected: String, val actual: String) :
        AuthException("credentials belong to @$actual, not @$expected")

    /** The vault holds no account by that name. */
    class UnknownStoredAccount(val username: String) : AuthException("no stored account @$username")

    /** No [role] key available to sign (SDK "No <role> key available"); triggers the auth upgrade. */
    class MissingKey(val role: KeyRole) : AuthException("No ${role.value} key available")

    /**
     * The user declined the auth upgrade or closed a signing screen. [mayHaveSigned]: the screen was HiveSigner's page,
     * which may have broadcast before it was closed, so nothing can be said about the chain.
     */
    class Cancelled(message: String = "cancelled by the user", val mayHaveSigned: Boolean = false) : AuthException(message)

    /** Operations this app cannot sign at all (e.g. a custom_json that needs active and posting signatures). */
    class UnsupportedOperation(message: String) : AuthException(message)

    /** A deep link or callback could not be read. */
    class InvalidLink(message: String) : AuthException(message)

    /** The HiveSigner REST API answered with an error object (e.g. invalid_grant). */
    class HiveSignerApi(val status: Int, val error: String?, val description: String?) :
        AuthException("HiveSigner error ${error ?: status}${description?.let { ": $it" } ?: ""}")

    /** HiveAuth: the wallet rejected, the request expired, or the relay reported an error. */
    class HiveAuth(val reason: Reason, detail: String? = null, cause: Throwable? = null) :
        AuthException("HiveAuth ${reason.name.lowercase()}${detail?.let { ": $it" } ?: ""}", cause) {
        enum class Reason { REJECTED, EXPIRED, ERROR, NOT_CONNECTED, SESSION_EXPIRED, INVALID_RESPONSE }
    }
}

/** RN/SDK `shouldTriggerAuthFallback`: errors after which another signing method may succeed. */
object AuthFallback {
    private val PATTERNS = listOf(
        Regex("no (active|owner|posting|memo) key available", RegexOption.IGNORE_CASE),
        Regex("missing (required )?active authority", RegexOption.IGNORE_CASE),
        Regex("missing (required )?owner authority", RegexOption.IGNORE_CASE),
        Regex("missing (required )?posting authority", RegexOption.IGNORE_CASE),
        Regex("token expired", RegexOption.IGNORE_CASE),
        Regex("invalid token", RegexOption.IGNORE_CASE),
        Regex("\\bunauthorized\\b", RegexOption.IGNORE_CASE),
        Regex("\\bforbidden\\b", RegexOption.IGNORE_CASE),
    )
    private val CODES = setOf("invalid_grant", "unauthorized_access")

    fun shouldTrigger(error: Throwable): Boolean {
        if (error is AuthException.MissingKey) return true
        if (error is AuthException.HiveSignerApi && error.error in CODES) return true
        val texts = buildList {
            add(error.message)
            if (error is RpcErrorException) { add(error.rpcMessage); add(error.data) }
            if (error is AuthException.HiveSignerApi) add(error.description)
        }.filterNotNull()
        return texts.any { text -> PATTERNS.any { it.containsMatchIn(text) } }
    }
}
