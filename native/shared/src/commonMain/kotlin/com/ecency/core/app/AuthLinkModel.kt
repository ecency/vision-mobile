package com.ecency.core.app

import com.ecency.core.auth.AuthException
import com.ecency.core.auth.AuthLink
import com.ecency.core.auth.AuthLinks
import com.ecency.core.auth.FormattedHiveUriTx
import com.ecency.core.auth.HiveSignerMessages
import com.ecency.core.auth.HiveUri
import com.ecency.core.auth.HiveUriTx
import com.ecency.core.auth.LoginMethod
import com.ecency.core.auth.loginMethod
import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException

/** One labelled value of an operation, as the sign confirmation lists it. */
data class LinkField(val name: String, val value: String)

/** A question an auth link asks the user. Nothing happens until [AuthLinkModel.confirm]. */
sealed interface LinkPrompt {
    /**
     * RN qr.confirmTransaction: a hive:// or ecency://sign link, or an ecency://transfer with an amount, ready to
     * sign as [username] with [authority] ("posting" or "active"; owner operations never get here).
     */
    data class SignConfirm(
        val username: String,
        val title: String,
        val operation: String,
        val authority: String,
        val fields: List<LinkField>,
        /** The app that asked (callback scheme, host and path), if it gave a callback. */
        val requester: String?,
    ) : LinkPrompt

    /** RN getFormattedTx errors (qr.* copy) and unusable links. */
    data class Refused(val title: String, val message: String) : LinkPrompt

    /** RN deep_link.posting_key_request: another app asks for the posting key of [username]. */
    data class PostingKeyRequest(val username: String, val requester: String) : LinkPrompt

    /** RN deep_link.auth_request_*: another app asks for a sign-in proof (never a key). */
    data class AuthRequest(val username: String?, val requester: String) : LinkPrompt

    /** RN qr.auth_transfer_confirm. [accountStored]: the account is already signed in here (its keys stay). */
    data class AuthTransfer(
        val username: String,
        val accountStored: Boolean,
        /** How the stored account signs: a KEY account keeps its keys, a HiveSigner or HiveAuth one is replaced. */
        val storedMethod: com.ecency.core.auth.LoginMethod? = null,
    ) : LinkPrompt

    /** RN deep_login_alert: "Easy Login @username". */
    data class CodeLogin(val username: String) : LinkPrompt
}

/** Where a link wants the app to go. */
sealed interface LinkNavigation {
    /** The login screen (signed out, or a link for an account that is not here). */
    data class Login(val username: String?) : LinkNavigation

    /** The register screen (RN route REGISTER); [url] is the same request on ecency.com, [referral] the screen's saved argument. */
    data class Signup(val url: String, val referral: String = "") : LinkNavigation

    /** An app screen (an `ecency://transfer` with a recipient only: the transfer screen prefilled with it). */
    data class Screen(val screen: AppScreen) : LinkNavigation
}

data class AuthLinkState(
    val prompt: LinkPrompt? = null,
    /** A sign or login is running after confirm. */
    val isWorking: Boolean = false,
    /** A callback URL to open in the other app. Only built from a callback the parser accepted. */
    val callback: String? = null,
    val navigation: LinkNavigation? = null,
    /** A short message for a toast (RN alert.successful, failures). */
    val notice: String? = null,
)

/**
 * Sign-in, signing and hand-off links (RN useLinkProcessor) for both UIs: [handle] parses with [AuthLinks.parse]
 * and asks the user first; [confirm] / [decline] act. Callbacks are only ever the parser-validated callback of
 * the link with RN's payload (`status`, `error`, `id`, `username`, `posting_key`, `code`, `request_id`).
 *
 * Guards: a posting key leaves the app only after explicit confirmation (and the PIN when the lock is on), for
 * an account signed in with a master password or posting key; owner operations are refused; auth-transfer
 * tokens are redeemed for the account the link names (see [com.ecency.core.auth.AccountManager]).
 */
class AuthLinkModel internal constructor(private val env: AppEnvironment) :
    StateModel<AuthLinkState>(AuthLinkState(), env.dispatchers) {

    private var pending: Pending? = null

    private sealed interface Pending {
        class Sign(val username: String, val ops: JsonArray, val authority: KeyRole, val callback: String?, val requestId: String?) : Pending {
            /** One link confirmed once is one action: its retry resumes it, a second link with the same operations is its own. */
            val actionId: String = com.ecency.core.secure.secureRandomBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }
        class PostingKey(val link: AuthLink.PostingKeyRequest) : Pending
        class Proof(val link: AuthLink.AuthRequest) : Pending
        class Transfer(val link: AuthLink.AuthTransfer) : Pending
        class Code(val link: AuthLink.CodeLogin) : Pending
    }

    /** True when [url] was an auth link (handled here); false for everything else (the regular link router). */
    fun handle(url: String): Boolean = open(url, pageHost = null, quiet = false)

    /**
     * As [handle], for a link a web page opened ([pageHost]: the dApp browser's tab host, as its web view reports it).
     * A signing prompt then says which page asked, when the link names no requesting app itself. A page is content:
     * its requester hears nothing before the user was asked, as [handleFromContent].
     */
    fun handle(url: String, pageHost: String?): Boolean = open(url, pageHost, quiet = pageHost != null)

    /**
     * A link from content (a link in a post body or a chat message, a `hiveuri` push): handled like [handle], except
     * that its requester hears nothing unless the user was asked first. Without that, a post could name an account
     * in an `ecency://login` link and learn from the error its callback gets (not_logged_in, posting_key_unavailable)
     * whether that account is on this device. External deep links keep RN's answers.
     */
    fun handleFromContent(url: String): Boolean = open(url, pageHost = null, quiet = true)

    /**
     * A tapped `hiveuri` push: as [handleFromContent], and a link the app refuses (an operation it cannot sign, a
     * malformed or foreign link) shows nothing at all. Any app can hand the exported activity a push-shaped intent,
     * so only a link the user can actually confirm ever reaches the screen.
     */
    fun handleFromPush(url: String): Boolean = open(url, pageHost = null, quiet = true, silentRefusal = true)

    private fun open(url: String, pageHost: String?, quiet: Boolean, silentRefusal: Boolean = false): Boolean {
        val fromContent = quiet
        val link = AuthLinks.parse(url) ?: return false
        if (current.isWorking) {
            // one request at a time: the new requester hears it, the user sees why nothing happened
            callbackOf(link)?.let { (cb, id) -> answer(cb, id, listOf("status" to "error", "error" to "busy"), quiet = fromContent) }
            setState { it.copy(notice = BUSY) }
            return true
        }
        // a question still open is declined (its requester gets user_cancelled) before the new one replaces it,
        // and so is a link still waiting for the unlock or the accounts
        if (pending != null) decline()
        waiting?.let { (job, earlier, earlierFromContent) ->
            job.cancel()
            // a content link still waiting was never asked about: its requester hears nothing
            callbackOf(earlier)?.let { (cb, id) -> answer(cb, id, listOf("status" to "error", "error" to "user_cancelled"), quiet = earlierFromContent) }
        }
        waiting = null
        setState { it.copy(prompt = null, navigation = null) }
        lateinit var job: Job
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                // a link that started the app waits until the accounts were read and nothing is hidden by the lock
                // (once the lock started, also while its start failed closed)
                env.session.state.first { !it.isLoading }
                if (env.appLock.hasStarted) env.appLock.state.first { !it.hidesContent } else env.appLock.state.first { !it.isLocked }
                if (waiting?.first === job) waiting = null
                // carried with the link, never shared: another link routed meanwhile cannot change it
                route(link, pageHost, quiet = fromContent, silent = silentRefusal)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refuse(TRANSACTION_FAILED, AppError.from(e).message, silentRefusal)
            }
        }
        waiting = Triple(job, link, fromContent)
        job.start()
        return true
    }

    /** The link waiting for the accounts or the unlock, with its job and whether it came from content. */
    private var waiting: Triple<Job, AuthLink, Boolean>? = null

    /** [quiet]: the link came from content, its requester hears nothing before the user was asked ([handleFromContent]). */
    /** [silent]: a refusal shows nothing (a tapped push, [handleFromPush]); carried with this one link. */
    private suspend fun route(link: AuthLink, pageHost: String?, quiet: Boolean, silent: Boolean) {
        val user = env.session.current.username
        when (link) {
            is AuthLink.Invalid -> {
                link.callback?.let { answer(it, link.requestId, listOf("status" to "error", "error" to link.reason), quiet) }
                refuse(UNSUPPORTED_TITLE, UNSUPPORTED_MESSAGE, silent)
            }
            is AuthLink.HiveSign -> {
                if (user == null) return goLogin(null)
                sign(user, link.decoded, link.decoded.params.callback, null, pageHost, quiet, silent)
            }
            is AuthLink.Transfer -> {
                if (link.isRecipientOnly) {
                    // RN: TRANSFER with transfer_token, the link's asset (HIVE by default) and the recipient
                    if (user == null) return goLogin(null)
                    val to = com.ecency.core.render.blocks.SafeTarget.account(link.to) ?: return refuse(INVALID_OP_TITLE, INVALID_OP_MESSAGE, silent)
                    val asset = link.asset.uppercase()
                    val screen = when (asset) {
                        "POINT", "POINTS" -> AppScreen.Transfer("ecency_point_transfer", "POINT", "points", to)
                        "HBD" -> AppScreen.Transfer("transfer", "HBD", "hive", to)
                        else -> AppScreen.Transfer("transfer", "HIVE", "hive", to)
                    }
                    setState { it.copy(navigation = LinkNavigation.Screen(screen)) }
                    return
                }
                if (link.invalid || link.hiveUri == null) {
                    link.callback?.let { answer(it, link.requestId, listOf("status" to "error", "error" to "invalid_params"), quiet) }
                    return refuse(INVALID_AMOUNT_TITLE, INVALID_AMOUNT_MESSAGE, silent)
                }
                if (user == null) return goLogin(link.from)
                if (link.from != null && link.from != user) {
                    link.callback?.let { answer(it, link.requestId, listOf("status" to "error", "error" to "account_mismatch"), quiet) }
                    return refuse(INVALID_OP_TITLE, L.native.authLink.mustBeSignedBy(account = link.from), silent)
                }
                // the transfer's own callback answers; the hive-uri inside carries the same one
                sign(user, HiveUri.decode(link.hiveUri), link.callback, link.requestId, pageHost, quiet, silent)
            }
            is AuthLink.PostingKeyRequest -> postingKeyRequest(link, user, quiet)
            is AuthLink.AuthRequest -> {
                pending = Pending.Proof(link)
                setState { it.copy(prompt = LinkPrompt.AuthRequest(link.username, AuthLinks.requesterLabel(link.callback))) }
            }
            is AuthLink.AuthTransfer -> {
                val stored = env.accounts.storedAccount(link.username)
                pending = Pending.Transfer(link)
                setState { it.copy(prompt = LinkPrompt.AuthTransfer(link.username, stored != null, stored?.method)) }
            }
            is AuthLink.CodeLogin -> {
                val username = link.username?.let { com.ecency.core.auth.KeyLogin.normalizeUsername(it) }
                val code = link.code
                if (username == null || code.isNullOrEmpty() || !AuthLinks.isHiveAccountName(username)) {
                    return refuse(FAIL_TITLE, DEEP_LOGIN_MALFORMED, silent)
                }
                when (AuthLinks.checkLoginCode(code, username, env.nowMillis())) {
                    null -> {
                        pending = Pending.Code(link)
                        setState { it.copy(prompt = LinkPrompt.CodeLogin(username)) }
                    }
                    AuthLinks.CodeProblem.EXPIRED -> refuse(FAIL_TITLE, DEEP_LOGIN_EXPIRED, silent)
                    else -> refuse(FAIL_TITLE, DEEP_LOGIN_MALFORMED, silent)
                }
            }
            is AuthLink.Signup -> {
                val params = listOfNotNull(
                    link.referredUser?.let { "referral" to it },
                    link.username?.let { "username" to it },
                    link.email?.let { "email" to it },
                )
                val url = AuthLinks.callbackUrl(SIGNUP_URL, null, params) ?: SIGNUP_URL
                // RN deepLinkParser: the register screen with the link's values. Only a well-formed account name
                // is taken as the referral; username and email are prefilled and validated by the screen itself.
                val referral = link.referredUser?.lowercase()?.takeIf { com.ecency.core.secure.KeyVault.isValidUsername(it) }.orEmpty()
                env.signupPrefill = SignupPrefill(
                    username = link.username.orEmpty().take(RegisterModel.MAX_FIELD),
                    email = link.email.orEmpty().take(RegisterModel.MAX_EMAIL),
                    referral = referral,
                )
                setState { it.copy(navigation = LinkNavigation.Signup(url, referral)) }
            }
            is AuthLink.Purchase -> purchaseLink(link)
        }
    }

    /**
     * RN deepLinkParser's purchase branch: `boost` opens Account boost, `points` Get Points (with the link's product
     * to confirm), `account` the register screen that only sells a paid account. Only well-formed account names and
     * known products are taken; any other type is ignored, as RN.
     */
    private fun purchaseLink(link: AuthLink.Purchase) {
        val username = link.username?.lowercase()?.takeIf { com.ecency.core.secure.KeyVault.isValidUsername(it) }.orEmpty()
        when (link.type) {
            "boost" -> setState { it.copy(navigation = LinkNavigation.Screen(AppScreen.AccountBoost(username))) }
            "points" -> {
                val product = link.productId?.takeIf { it in PurchaseSkus.POINTS }.orEmpty()
                setState { it.copy(navigation = LinkNavigation.Screen(AppScreen.GetPoints(username, product))) }
            }
            "account" -> {
                val referral = link.referral?.lowercase()?.takeIf { com.ecency.core.secure.KeyVault.isValidUsername(it) }.orEmpty()
                env.signupPrefill = SignupPrefill(
                    username = link.username.orEmpty().take(RegisterModel.MAX_FIELD),
                    email = link.email.orEmpty().take(RegisterModel.MAX_EMAIL),
                    referral = referral,
                    purchaseOnly = true,
                )
                setState { it.copy(navigation = LinkNavigation.Signup(SIGNUP_URL, referral)) }
            }
        }
    }

    private suspend fun postingKeyRequest(link: AuthLink.PostingKeyRequest, user: String?, quiet: Boolean) {
        fun error(code: String, message: String) =
            answer(link.callback, link.requestId, listOf("status" to "error", "username" to link.username, "error" to code, "message" to message), quiet)
        val stored = env.accounts.vaultAccount(link.username)
        if (user == null || stored == null) {
            return error("not_logged_in", CallbackText.notLoggedIn(link.username))
        }
        if (stored.shareablePostingKey == null) {
            return error("posting_key_unavailable", CallbackText.postingKeyUnavailable(link.username))
        }
        pending = Pending.PostingKey(link)
        setState { it.copy(prompt = LinkPrompt.PostingKeyRequest(link.username, AuthLinks.requesterLabel(link.callback))) }
    }

    private suspend fun sign(user: String, decoded: HiveUri.Decoded, callback: String?, requestId: String?, pageHost: String?, quiet: Boolean, silent: Boolean) {
        fun cancelled() = callback?.let { answer(it, requestId, listOf("status" to "error", "error" to "user_cancelled"), quiet) }
        val tx = decoded.tx as? JsonObject
        if (decoded.type == "msg" || tx == null || decoded.params.noBroadcast) {
            return refuse(MULTI_OPS_TITLE, L.native.authLink.ecencyCanOnlySign, silent)
        }
        if (decoded.params.signer != null && decoded.params.signer != user) {
            return refuse(INVALID_OP_TITLE, L.native.authLink.mustBeSignedBy(account = decoded.params.signer), silent)
        }
        val account = env.accounts.vaultAccount(user) ?: return goLogin(user)
        // key accounts: only the keys they hold (RN); HiveSigner and HiveAuth sign in their own app
        val available = if (account.authType?.loginMethod() == LoginMethod.KEY) {
            mapOf(
                "posting" to (!account.postingKey.isNullOrEmpty() || !account.accessToken.isNullOrEmpty()),
                "active" to !account.activeKey.isNullOrEmpty(),
                "owner" to false, // never stored
                "memo" to !account.memoKey.isNullOrEmpty(),
            )
        } else {
            null
        }
        when (val formatted = HiveUriTx.format(tx, available)) {
            is FormattedHiveUriTx.Refused -> {
                cancelled()
                val (title, message) = refusalCopy(formatted)
                refuse(title, message, silent)
            }
            is FormattedHiveUriTx.Ready -> {
                // only the signer matters here: the broadcaster builds the transaction (reference block, expiration)
                // itself when the user approves, so the confirmation needs no network
                val resolved = HiveUri.resolveTransaction(
                    formatted.tx, decoded.params, com.ecency.core.api.broadcast.TxReference(0, 0), HiveUri.expirationFrom(env.nowMillis()), user,
                )
                val ops = resolved["operations"] as? JsonArray ?: return refuse(INVALID_OP_TITLE, INVALID_OP_MESSAGE, silent)
                val op = ops.first() as JsonArray
                // key accounts sign on the device: an operation the app cannot serialize yet is refused before the
                // user approves anything (HiveSigner and HiveAuth accounts sign it in their own app)
                if (available != null && !com.ecency.core.api.broadcast.OperationJson.canDecode(op)) {
                    callback?.let { answer(it, requestId, listOf("status" to "error", "error" to "not_supported"), quiet) }
                    return refuse(MULTI_OPS_TITLE, NOT_SUPPORTED, silent)
                }
                val authority = if (formatted.authority == "active") KeyRole.ACTIVE else KeyRole.POSTING
                pending = Pending.Sign(user, ops, authority, callback, requestId)
                setState {
                    it.copy(
                        prompt = LinkPrompt.SignConfirm(
                            username = user,
                            title = formatted.title,
                            operation = (op[0] as JsonPrimitive).content,
                            authority = formatted.authority,
                            fields = fields(op.getOrNull(1) as? JsonObject),
                            requester = callback?.let(AuthLinks::requesterLabel) ?: pageHost?.let { L.native.authLink.requesterPage(host = it) },
                        ),
                    )
                }
            }
        }
    }

    /** The user approved the prompt. */
    fun confirm() {
        val action = pending ?: return
        if (current.isWorking) return
        pending = null
        setState { it.copy(prompt = null, isWorking = true) }
        scope.launch {
            try {
                when (action) {
                    is Pending.Sign -> confirmSign(action)
                    is Pending.PostingKey -> handOverPostingKey(action.link)
                    is Pending.Proof -> answerAuthRequest(action.link)
                    is Pending.Transfer -> {
                        env.session.loginWithAuthTransfer(action.link)
                        setState { it.copy(notice = SUCCESSFUL) }
                    }
                    is Pending.Code -> {
                        env.session.loginWithWebCode(action.link)
                        setState { it.copy(notice = SUCCESSFUL) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = AppError.from(e)
                if (error.kind != ErrorKind.CANCELLED) setState { it.copy(prompt = LinkPrompt.Refused(FAIL_TITLE, error.message)) }
            } finally {
                setState { it.copy(isWorking = false) }
            }
        }
    }

    /** The user declined: the requesting app hears `user_cancelled`. */
    fun decline() {
        val action = pending
        pending = null
        setState { it.copy(prompt = null) }
        when (action) {
            is Pending.Sign -> action.callback?.let { answer(it, action.requestId, listOf("status" to "error", "error" to "user_cancelled"), quiet = false) }
            is Pending.PostingKey -> answer(
                action.link.callback, action.link.requestId,
                listOf(
                    "status" to "error", "username" to action.link.username, "error" to "user_cancelled",
                    "message" to CallbackText.declined(AuthLinks.requesterLabel(action.link.callback)),
                ),
                quiet = false,
            )
            is Pending.Proof -> answer(action.link.callback, action.link.requestId, listOf("status" to "error", "error" to "user_cancelled"), quiet = false)
            else -> Unit
        }
    }

    /** The UI opened [AuthLinkState.callback]; the next queued answer, if any, comes up. */
    fun consumeCallback() = setState { it.copy(callback = queued.removeFirstOrNull()) }

    fun consumeNavigation() = setState { it.copy(navigation = null) }

    fun consumeNotice() = setState { it.copy(notice = null) }

    /** Closes a [LinkPrompt.Refused]. */
    fun dismiss() {
        if (current.prompt is LinkPrompt.Refused) setState { it.copy(prompt = null) }
    }

    /**
     * The link's operations as one action through [WalletSends], as the wallet's own sends: a send that may have landed
     * is followed up ("check your wallet", same transaction, no second signature) and the requesting app hears the
     * final word only: success with the id, `user_cancelled`, `unconfirmed` when the chain cannot tell, else failed.
     */
    private suspend fun confirmSign(action: Pending.Sign) {
        val final = kotlinx.coroutines.CompletableDeferred<WalletSend>()
        val id = action.actionId
        kotlinx.coroutines.withContext(env.dispatchers.main) {
            env.walletSends.send(action.username, action.ops, action.authority, id) { final.complete(it) }
        }
        val checking = scope.launch {
            env.walletSends.state.first { it.sends[id]?.phase is SendPhase.Checking }
            setState { it.copy(notice = com.ecency.core.api.broadcast.BroadcastException.UNDECIDED_MESSAGE) }
        }
        val result = try {
            final.await()
        } finally {
            checking.cancel()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + env.dispatchers.main) { env.walletSends.disown(id) }
        }
        when (val phase = result.phase) {
            is SendPhase.Done -> {
                setState { it.copy(notice = SUCCESSFUL) }
                action.callback?.let { cb -> answer(cb, action.requestId, listOfNotNull("status" to "success", phase.txId?.let { "id" to it }), quiet = false) }
            }
            is SendPhase.Cancelled -> action.callback?.let { cb -> answer(cb, action.requestId, listOf("status" to "error", "error" to "user_cancelled"), quiet = false) }
            is SendPhase.Unconfirmed -> {
                setState { it.copy(notice = phase.message) }
                action.callback?.let { cb -> answer(cb, action.requestId, listOf("status" to "error", "error" to UNCONFIRMED), quiet = false) }
            }
            is SendPhase.Failed -> {
                // a signer elsewhere that may have answered cannot say either: unconfirmed, not failed
                val undecided = phase.error.undecided
                setState { it.copy(notice = if (undecided) phase.error.message else TRANSACTION_FAILED) }
                action.callback?.let { cb -> answer(cb, action.requestId, listOf("status" to "error", "error" to if (undecided) UNCONFIRMED else "qr.transaction_failed"), quiet = false) }
            }
            else -> Unit
        }
    }

    private suspend fun handOverPostingKey(link: AuthLink.PostingKeyRequest) {
        fun error(code: String, message: String) =
            answer(link.callback, link.requestId, listOf("status" to "error", "username" to link.username, "error" to code, "message" to message), quiet = false)
        // RN asks for the PIN before any key leaves the app
        if (!env.appLock.verifyPin(whenOff = true)) return error("pin_required", CallbackText.pinRequired(link.username))
        val stored = env.accounts.vaultAccount(link.username)
        val key = stored?.shareablePostingKey
            ?: return error("posting_key_unavailable", CallbackText.postingKeyUnavailable(link.username))
        answer(link.callback, link.requestId, listOf("status" to "success", "username" to link.username, "posting_key" to key), quiet = false)
    }

    private suspend fun answerAuthRequest(link: AuthLink.AuthRequest) {
        fun refuseWith(code: String) = answer(link.callback, link.requestId, listOf("status" to "error", "error" to code), quiet = false)
        val current = env.session.current
        val username = link.username ?: current.username
        if (username == null || current.accounts.none { it.username == username }) return refuseWith("not_logged_in")
        if (!env.appLock.verifyPin(whenOff = true)) return refuseWith("pin_required")
        val stored = env.accounts.vaultAccount(username) ?: return refuseWith("not_logged_in")
        // the posting key signs the proof (RN); never the active key
        val wif = stored.postingKey?.takeIf { it.isNotEmpty() } ?: return refuseWith("use_hivesigner")
        val key = runCatching { PrivateKey.fromWif(wif) }.getOrNull() ?: return refuseWith("internal_error")
        val proof = HiveSignerMessages.loginProof(username, key, AuthLinks.callbackAudience(link.callback), env.nowMillis())
        answer(link.callback, link.requestId, listOf("status" to "success", "username" to username, "code" to proof), quiet = false)
    }

    /** [callback] passed [AuthLinks.isAcceptableCallback] in the parser; nothing else is ever opened. */
    /** [quiet]: a content link not asked about yet ([handleFromContent]): nothing goes back to its requester. Required: every call site says which it is. */
    private fun answer(callback: String, requestId: String?, payload: List<Pair<String, String>>, quiet: Boolean) {
        if (quiet) return
        val url = AuthLinks.callbackUrl(callback, requestId, payload) ?: return
        // answers queue up: a decline followed by a new link's answer never loses the first
        if (current.callback == null) setState { it.copy(callback = url) } else queued.addLast(url)
    }

    private val queued = ArrayDeque<String>()

    /** The parser-validated callback and request id a link carries, if any. */
    private fun callbackOf(link: AuthLink): Pair<String, String?>? = when (link) {
        is AuthLink.HiveSign -> link.decoded.params.callback?.let { it to null }
        is AuthLink.Transfer -> link.callback?.let { it to link.requestId }
        is AuthLink.AuthRequest -> link.callback to link.requestId
        is AuthLink.PostingKeyRequest -> link.callback to link.requestId
        is AuthLink.Invalid -> link.callback?.let { it to link.requestId }
        else -> null
    }

    private fun refuse(title: String, message: String, silent: Boolean) {
        if (silent) return
        setState { it.copy(prompt = LinkPrompt.Refused(title, message)) }
    }

    private fun goLogin(username: String?) = setState { it.copy(navigation = LinkNavigation.Login(username)) }

    private fun refusalCopy(r: FormattedHiveUriTx.Refused): Pair<String, String> = when (r.errorKey1) {
        "qr.multi_array_ops_alert" -> MULTI_OPS_TITLE to L.qr.multiArrayOpsAlerDesct
        "qr.invalid_key" -> L.qr.invalidKey(key = r.authorityKeyType) to
            L.qr.invalidKeyDesc(key = r.authorityKeyType)
        "qr.invalid_amount" -> INVALID_AMOUNT_TITLE to INVALID_AMOUNT_MESSAGE
        else -> when (r.authorityKeyType) {
            "owner" -> INVALID_OP_TITLE to OWNER_REFUSED
            com.ecency.core.auth.HiveUriTx.MIXED -> INVALID_OP_TITLE to com.ecency.core.auth.OperationAuthority.MIXED_AUTHORITIES
            else -> INVALID_OP_TITLE to INVALID_OP_MESSAGE
        }
    }

    /** Who gets what first, so a long value cannot push them out of view; a run of blanks shows as a marker. */
    private fun fields(body: JsonObject?): List<LinkField> = body.orEmpty().entries
        .sortedBy { (k, _) -> KEY_FIELDS.indexOf(k).let { if (it < 0) KEY_FIELDS.size else it } }
        .map { (k, v) -> LinkField(k, BridgeDisplay.shown(text(v))) }

    private fun text(v: JsonElement): String = when (v) {
        is JsonPrimitive -> v.contentOrNull ?: "null"
        else -> v.toString()
    }

    companion object {
        private val KEY_FIELDS = listOf(
            "account", "to", "to_account", "delegatee", "receiver", "agent", "new_account_name",
            "new_recovery_account", "recovery_account", "owner", "active", "posting",
            "amount", "vesting_shares", "hive_amount", "hbd_amount", "fee",
        )

        /** Callback error when the chain cannot say whether the link's transaction went in (the user checks the wallet). */
        const val UNCONFIRMED = "unconfirmed"

        // RN en-US qr.*, alert.*, login.deep_login_*
        val UNSUPPORTED_TITLE: String get() = L.qr.unsupportedAlertTitle
        val UNSUPPORTED_MESSAGE: String get() = L.qr.unsupportedAlertDesc
        val MULTI_OPS_TITLE: String get() = L.qr.multiArrayOpsAlert
        val INVALID_OP_TITLE: String get() = L.qr.invalidOp
        val INVALID_OP_MESSAGE: String get() = L.qr.invalidOpDesc
        val OWNER_REFUSED: String get() = L.native.authLink.ownerRefused
        val INVALID_AMOUNT_TITLE: String get() = L.qr.invalidAmount
        val INVALID_AMOUNT_MESSAGE: String get() = L.qr.invalidAmountDesc
        val TRANSACTION_FAILED: String get() = L.qr.transactionFailed
        val SUCCESSFUL: String get() = L.alert.successful
        val FAIL_TITLE: String get() = L.alert.fail
        val DEEP_LOGIN_MALFORMED: String get() = L.login.deepLoginMalformedUrl
        val DEEP_LOGIN_EXPIRED: String get() = L.login.deepLoginUrlExpired
        const val SIGNUP_URL = "https://ecency.com/signup"
        val BUSY: String get() = L.native.authLink.busy
        val NOT_SUPPORTED: String get() = L.native.authLink.notSupported

        fun signWarning(authority: String) = L.qr.signWarning(authority = authority)
    }
}

/**
 * The `message` of an error answer sent back to the app that opened an ecency:// link: RN useLinkProcessor's fixed
 * English, word for word, never the app language (the other app reads it).
 */
internal object CallbackText {
    fun notLoggedIn(username: String) = "Username $username is not logged in on Ecency."
    fun postingKeyUnavailable(username: String) = "Username $username doesn't have a posting private key stored on Ecency."
    fun pinRequired(username: String) = "Unable to unlock stored keys for $username."
    fun declined(requester: String?) =
        if (requester.isNullOrEmpty()) "User declined to share the posting private key." else "User declined to share the posting private key with $requester."
}
