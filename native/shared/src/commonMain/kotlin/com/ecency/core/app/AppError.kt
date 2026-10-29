package com.ecency.core.app

import com.ecency.core.api.EcencyException
import com.ecency.core.api.InvalidInputException
import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.rpc.AllNodesFailedException
import com.ecency.core.api.rpc.NodeFailureException
import com.ecency.core.api.rpc.RpcTimeoutException
import com.ecency.core.auth.AuthException
import com.ecency.core.l10n.L
import com.ecency.core.secure.SecureStoreException

/** What went wrong, coarse enough for the UI to pick an icon, a message and whether to offer retry. */
enum class ErrorKind {
    /** No node answered (offline, timeouts). Retrying is safe. */
    NETWORK,

    /** The post or account does not exist. */
    NOT_FOUND,

    /** Signing needs a login, or the stored key cannot sign this. */
    AUTH,

    /** Not enough Resource Credits. */
    RESOURCE_CREDITS,

    /** Hive's 3 second comment cooldown. Retrying shortly is safe. */
    TOO_SOON,

    /** Voting on or editing a paid-out entry. */
    PAID_OUT,

    /** The same vote is already on chain. */
    DUPLICATE_VOTE,

    /** Bad input (blank reply, malformed key, weight out of range). */
    INVALID_INPUT,

    /** The device's secure storage failed. */
    STORAGE,

    /** The chain or a node refused for another reason. */
    REJECTED,

    /** The user closed a signing screen or declined (auth upgrade, HiveSigner page, wallet). Show nothing. */
    CANCELLED,

    UNKNOWN,
}

/** A failure as the UI shows it: a [kind], a user-facing [message] and whether a retry button makes sense. */
/**
 * [undecided]: a send that may have gone through and the chain could not yet say (BroadcastException.Network's
 * `undecided`). Code tests this flag, never [message], which is in the current language.
 */
data class AppError(val kind: ErrorKind, val message: String, val retryable: Boolean, val undecided: Boolean) {
    /** Not an undecided send (Swift has no default arguments). */
    constructor(kind: ErrorKind, message: String, retryable: Boolean) : this(kind, message, retryable, false)

    companion object {
        /** Maps any failure from the data layer, the vault or the app layer to what the UI shows. */
        fun from(error: Throwable): AppError = when (error) {
            is AppException -> error.error
            is AuthException -> fromAuth(error)
            is BroadcastException.Network -> AppError(ErrorKind.NETWORK, error.message ?: NETWORK_MESSAGE, true, undecided = error.undecided)
            // closed for good: a retry of this action would send nothing, the user checks their wallet
            is BroadcastException.Unconfirmed -> AppError(ErrorKind.NETWORK, error.message.orEmpty(), false)
            is BroadcastException.DuplicateVote -> AppError(ErrorKind.DUPLICATE_VOTE, error.message.orEmpty(), false)
            is BroadcastException.InsufficientResourceCredits -> AppError(ErrorKind.RESOURCE_CREDITS, error.message.orEmpty(), false)
            is BroadcastException.MissingAuthority -> AppError(ErrorKind.AUTH, error.message.orEmpty(), false)
            is BroadcastException.CommentTooSoon -> AppError(ErrorKind.TOO_SOON, error.message.orEmpty(), true)
            is BroadcastException.PaidOut -> AppError(ErrorKind.PAID_OUT, error.message.orEmpty(), false)
            is BroadcastException.InvalidOperation -> AppError(ErrorKind.INVALID_INPUT, error.message.orEmpty(), false)
            is BroadcastException.TooLarge -> AppError(ErrorKind.INVALID_INPUT, L.native.appError.thisReplyIsToo, false)
            is BroadcastException.Rejected -> AppError(ErrorKind.REJECTED, error.message?.ifBlank { null } ?: L.native.appError.theNetworkRefusedThis, false)
            is AllNodesFailedException, is NodeFailureException, is RpcTimeoutException ->
                AppError(ErrorKind.NETWORK, NETWORK_MESSAGE, true)
            is com.ecency.core.api.EcencyHttpException ->
                if (error.isNetwork) AppError(ErrorKind.NETWORK, NETWORK_MESSAGE, true)
                else if (error.isRedirect) AppError(ErrorKind.UNKNOWN, UNEXPECTED_REDIRECT, true)
                else AppError(ErrorKind.UNKNOWN, L.aiAssist.errorGeneric, true)
            is InvalidInputException -> AppError(ErrorKind.INVALID_INPUT, error.message ?: L.native.appError.invalidInput, false)
            is SecureStoreException -> AppError(ErrorKind.STORAGE, L.native.appError.secureStorageIsUnavailable, false)
            is EcencyException -> AppError(ErrorKind.UNKNOWN, L.aiAssist.errorGeneric, true)
            else -> AppError(ErrorKind.UNKNOWN, L.aiAssist.errorGeneric, true)
        }

        /** Sign-in and signing failures with the RN app's copy (en-US `auth.*`, `hiveauth.*`, `alert.*`). */
        private fun fromAuth(e: AuthException): AppError = when (e) {
            is AuthException.InvalidUsername, is AuthException.UnknownAccount -> AppError(ErrorKind.AUTH, INVALID_USERNAME, false)
            is AuthException.WrongPassword, is AuthException.KeyNotOnAccount, is AuthException.InvalidKey ->
                AppError(ErrorKind.AUTH, INVALID_CREDENTIALS, false)
            is AuthException.PublicKeyGiven -> AppError(ErrorKind.AUTH, PUBLIC_KEY_GIVEN, false)
            is AuthException.MemoKeyOnly -> AppError(ErrorKind.AUTH, MEMO_KEY_ONLY, false)
            is AuthException.KeyBelowThreshold -> AppError(ErrorKind.AUTH, KEY_BELOW_THRESHOLD, false)
            is AuthException.KeySharedWithOwner -> AppError(ErrorKind.AUTH, KEY_SHARED_WITH_OWNER_REFUSED, false)
            is AuthException.OwnerKeyOnly -> AppError(ErrorKind.AUTH, if (e.fromMasterPassword) OWNER_ONLY_PASSWORD else OWNER_KEY_REFUSED, false)
            is AuthException.TokenExchangeFailed ->
                if (e.status == 0 || e.status >= 500) AppError(ErrorKind.NETWORK, NETWORK_MESSAGE, true)
                else if (e.status in 300..399) AppError(ErrorKind.UNKNOWN, UNEXPECTED_REDIRECT, true)
                else AppError(ErrorKind.AUTH, UNKNOWN_AUTH_ERROR, false)
            is AuthException.SessionExpired -> AppError(ErrorKind.AUTH, SESSION_EXPIRED, false)
            is AuthException.NotSignedIn -> AppError(ErrorKind.AUTH, L.native.appError.pleaseSignInFirst, false)
            is AuthException.AccountMismatch -> AppError(ErrorKind.AUTH, L.native.appError.theseCredentialsBelongTo(actual = e.actual, expected = e.expected), false)
            is AuthException.UnknownStoredAccount -> AppError(ErrorKind.AUTH, L.native.appError.isNotSignedIn(username = e.username), false)
            // RN's sentence, then where to get the key from (not RN)
            is AuthException.MissingKey -> AppError(ErrorKind.AUTH, L.native.appError.keyRequired(role = CopyLists.role(e.role), hint = MISSING_KEY_HINT), false)
            is AuthException.Cancelled -> AppError(ErrorKind.CANCELLED, L.native.appError.cancelled, false)
            is AuthException.UnsupportedOperation -> AppError(ErrorKind.INVALID_INPUT, e.message.orEmpty(), false)
            is AuthException.InvalidLink -> AppError(ErrorKind.INVALID_INPUT, L.qr.unsupportedAlertDesc, false)
            is AuthException.HiveSignerApi -> AppError(ErrorKind.AUTH, e.description ?: e.error ?: UNKNOWN_AUTH_ERROR, false)
            is AuthException.HiveAuth -> when (e.reason) {
                AuthException.HiveAuth.Reason.REJECTED -> AppError(ErrorKind.AUTH, L.native.appError.theRequestWasRejected, false)
                AuthException.HiveAuth.Reason.EXPIRED -> AppError(ErrorKind.AUTH, L.native.appError.theRequestExpiredBefore, true)
                AuthException.HiveAuth.Reason.SESSION_EXPIRED -> AppError(ErrorKind.AUTH, HIVEAUTH_EXPIRED, false)
                AuthException.HiveAuth.Reason.NOT_CONNECTED -> AppError(ErrorKind.NETWORK, L.native.appError.couldNotReachThe, true)
                else -> AppError(ErrorKind.AUTH, L.hiveauth.authFail, false)
            }
        }

        // RN en-US auth.invalid_username, auth.invalid_credentials, auth.unknow_error, alert.auth_expired, hiveauth.expired
        val INVALID_USERNAME: String get() = L.auth.invalidUsername
        val INVALID_CREDENTIALS: String get() = L.auth.invalidCredentials
        /** The server answered with a redirect, which the app never follows: not the user's connection, not their credentials. */
        val UNEXPECTED_REDIRECT: String get() = L.native.appError.unexpectedRedirect
        val MISSING_KEY_HINT: String get() = L.native.appError.missingKeyHint
        val KEY_SHARED_WITH_OWNER_REFUSED: String get() = L.native.appError.thisKeyIsAlso
        /** Not RN, which signs in with an owner key and stores it. */
        val OWNER_KEY_REFUSED: String get() = L.native.appError.thisIsTheOwner
        val OWNER_ONLY_PASSWORD: String get() = L.native.appError.thisPasswordOnlyMatches
        val KEY_BELOW_THRESHOLD: String get() = L.native.appError.thisKeyCannotSign
        /** RN #3600: a pasted public key gets its own message, not "invalid credentials". */
        val PUBLIC_KEY_GIVEN: String get() = L.native.appError.publicKeyGiven
        val MEMO_KEY_ONLY: String get() = L.native.appError.memoKeyOnly
        val UNKNOWN_AUTH_ERROR: String get() = L.auth.unknowError
        val SESSION_EXPIRED: String get() = L.alert.authExpired
        val HIVEAUTH_EXPIRED: String get() = L.hiveauth.expired

        /**
         * As [from] for a failed vote or reply: a network failure there may have reached a node,
         * so the message says the action may have gone through and that retrying is safe (the
         * retry resends the same transaction).
         */
        fun fromBroadcast(error: Throwable): AppError = from(error).let {
            if (it.kind == ErrorKind.NETWORK) it.copy(message = MAY_HAVE_LANDED_MESSAGE) else it
        }

        val MAY_HAVE_LANDED_MESSAGE: String get() = L.native.appError.networkErrorThisMay
        val NETWORK_MESSAGE: String get() = L.native.appError.networkMessage
    }
}

/** An app-layer failure carrying the [AppError] the UI should show. Swift sees it as a thrown error. */
open class AppException(val error: AppError, cause: Throwable? = null) : EcencyException(error.message, cause)
