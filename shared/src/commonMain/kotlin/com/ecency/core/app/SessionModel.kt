package com.ecency.core.app

import com.ecency.core.api.EcencyException
import com.ecency.core.auth.AccountSummary
import com.ecency.core.auth.AuthException
import com.ecency.core.auth.AuthLink
import com.ecency.core.auth.HiveAuthChallenge
import com.ecency.core.auth.HiveAuthLogin
import com.ecency.core.auth.HiveSignerMessages
import com.ecency.core.auth.KeyLogin
import com.ecency.core.auth.LoginMethod
import com.ecency.core.secure.KeyVault
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Where a HiveAuth sign-in is (RN hiveAuthModal states). */
enum class HiveAuthStep {
    /** Asking the relay for a request (RN "Initiating..."). */
    INITIATING,

    /** The request waits for the wallet app: show the QR and the "open wallet" link (RN "Authenticating..."). */
    WAITING,

    /** The wallet approved; exchanging the signed challenge for the Ecency session (RN "Logging In..."). */
    LOGGING_IN,
    SUCCESS,
    FAILED,
}

/** A HiveAuth sign-in in progress. [link] opens the wallet (`has://auth_req/...`); the UI also shows it as a QR. */
data class HiveAuthLoginState(
    val username: String,
    val step: HiveAuthStep,
    val link: String? = null,
    /** Epoch milliseconds the relay drops the request. */
    val expiresAtMillis: Long = 0,
    val error: AppError? = null,
    /**
     * The relay request [link] belongs to. The UI opens the wallet by itself once per request: while
     * [openWallet] is true, then calls [SessionModel.walletOpened] (a remount, rotation or relock never reopens it).
     */
    val requestId: String? = null,
    val openWallet: Boolean = false,
)

data class SessionState(
    /** The signed-in (current) account, or null. */
    val username: String? = null,
    /** Every account signed in on this device, the current one marked. */
    val accounts: List<AccountSummary> = emptyList(),
    /** True until the vault was read once. */
    val isLoading: Boolean = true,
    /** A key, HiveSigner or link sign-in is running. */
    val isSigningIn: Boolean = false,
    /** The account being switched to (RN "Switching..."). */
    val switchingTo: String? = null,
    val error: AppError? = null,
    val hiveAuth: HiveAuthLoginState? = null,
    /**
     * The account whose session expired and could not be refreshed (RN `_checkHiveAuthExpiry` and the refresh
     * failure): the UI asks to verify the login (RN alert.warning / alert.auth_expired, Cancel / Verify).
     */
    val expiredSession: String? = null,
    /** Prefill for the next login screen (Verify after an expired session). */
    val loginPrefill: String? = null,
) {
    val isLoggedIn: Boolean get() = username != null
    val currentAccount: AccountSummary? get() = accounts.firstOrNull { it.isCurrent }
}

/** Runs after every sign-in (RN usePostLogin): prefetches what the account needs. Failures are ignored. */
fun interface PostLoginHook {
    @Throws(Exception::class)
    suspend fun afterLogin(username: String)
}

/**
 * Sign-in, accounts and the Ecency session for both UIs, over the shared [com.ecency.core.auth.AccountManager]:
 * key (master password or WIF), HiveSigner code, HiveAuth, the ecency.com `/auth` code and auth-transfer
 * links, switching, per-account logout and logout of every account.
 *
 * A sign-in that changes the current account clears the previous account's votes and drafts, refreshes the
 * app lock and emits [AppEvent.SessionChanged] (feeds and posts reload in place). Signing out of the last
 * account turns the PIN lock off, as RN's logout does; while any account remains, the lock stays.
 */
class SessionModel internal constructor(private val env: AppEnvironment) :
    StateModel<SessionState>(SessionState(), env.dispatchers) {

    private var hiveAuthJob: Job? = null

    init {
        // RN auth.ts: the account's ignore list comes with the account (feeds filter by it)
        scope.launch {
            var last: String? = null
            state.collect { st ->
                val user = st.username
                if (user != last) {
                    last = user
                    if (user != null) launch { env.loadMutes(user) }
                }
            }
        }
    }

    /** Reads the accounts, then refreshes the current account's Ecency session (RN `_refreshAccessToken`). */
    fun start() {
        // one app start is one session for the rating rule, however often the platforms call this
        if (!sessionCounted) {
            sessionCounted = true
            runCatching { env.rating.recordSession() }
        }
        // never two reads of the vault at once: a call while one runs asks for one more read after it (the app
        // lock calls again after a migration changed the vault), so the last state always comes from the last read
        if (startJob?.isActive == true) {
            startAgain = true
            return
        }
        startJob = scope.launch {
            do {
                startAgain = false
                // only the read asked for by [startAfterImport], or the first one after a failed read, may announce
                val announcing = announceNext
                announceNext = false
                try {
                    reload()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // reload() armed the next good read to announce what it finds
                    setState { it.copy(isLoading = false, error = AppError.from(e)) }
                    continue
                }
                val user = current.username
                if (announcing) {
                    // the RN import changed the current account, or an earlier read failed: told only now, once the
                    // state holds it, and only when the screens had been told another one (before any read, they
                    // wait for this one)
                    val base = toldBase
                    toldBase = NOT_TOLD
                    if (base !== NOT_TOLD && base != user) env.emit(AppEvent.SessionChanged(user))
                } else if (announceNext && toldBase === NOT_TOLD) {
                    // a read from before the import finished after it was asked for: that is what the screens saw
                    toldBase = user
                }
                user?.let { refreshSession(it) }
            } while (startAgain)
        }
    }

    /**
     * The RN import (the app lock's first start) changed the vault: reads it again, and once that read is in the
     * state, emits [AppEvent.SessionChanged] when it found another current account than the screens were told. A
     * plain [start] (the platforms call it again on every activity recreation) never emits, unless the read before
     * it failed: logout, switching and sign-in announce their own changes, after their cleanup.
     */
    internal fun startAfterImport() {
        announceNext = true
        toldBase = if (current.isLoading) NOT_TOLD else current.username
        start()
    }

    /**
     * The next successful read announces a change: a [startAfterImport] waits for its read, or a read failed (the
     * screens went by a state without the stored account). Reads after a successful one never announce.
     */
    private var announceNext = false

    /** The account the screens were told before that read ([NOT_TOLD]: none read yet). */
    private var toldBase: Any? = NOT_TOLD

    private object NOT_TOLD

    /** Suspends until no load started by [start] is running (the state then is what the last read found). */
    @Throws(CancellationException::class)
    suspend fun awaitStartFinished() {
        startJob?.join()
    }

    private var startJob: kotlinx.coroutines.Job? = null
    private var startAgain = false
    private var sessionCounted = false

    /** Back in front: refreshes a session that expires within a day, so it never lapses while the app is used. */
    fun onForeground() {
        val user = current.username ?: return
        scope.launch {
            val expiry = try {
                env.accounts.sessionExpiry(user)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (expiry in 1..(env.nowMillis() + REFRESH_MARGIN_MILLIS)) refreshSession(user)
        }
    }

    // ---- sign-in ----

    /**
     * Username plus master password or private key (RN login screen). Returns the username; throws [AppException]
     * with RN's copy (invalid username, invalid credentials) or a network error.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun loginWithKey(username: String, secret: String): String {
        var outcome: com.ecency.core.auth.KeyLoginOutcome? = null
        val name = signIn { env.accounts.loginWithKeyOutcome(username, secret).also { outcome = it }.summary }
        // what the secret did not give, or should not be: said once, after the sign-in went through
        outcome?.let { env.accountNotices.signedIn(it) }
        // RN loginContainer: a key login is a points activity (20)
        env.pointActivities.record(name, PointActivity.LOGIN)
        return name
    }

    /** The `code` HiveSigner's login page handed to the redirect URI (RN loginWithSC2). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun loginWithHiveSignerCode(code: String): String = signIn { env.accounts.loginWithHiveSignerCode(code) }

    /** An ecency.com `/auth?username&code` link the user confirmed. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun loginWithWebCode(link: AuthLink.CodeLogin): String = signIn { env.accounts.loginWithWebCode(link) }

    /** An `ecency://auth-transfer` link the user confirmed. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun loginWithAuthTransfer(link: AuthLink.AuthTransfer): String = signIn { env.accounts.loginWithAuthTransfer(link) }

    /**
     * HiveAuth sign-in (RN hiveAuthModal): asks the relay, shows the wallet link ([SessionState.hiveAuth]), waits
     * for the wallet to sign the posting challenge, then exchanges it for the Ecency session.
     */
    fun startHiveAuth(usernameInput: String) {
        val username = KeyLogin.normalizeUsername(usernameInput)
        hiveAuthJob?.cancel()
        if (!KeyVault.isValidUsername(username)) {
            setState { it.copy(hiveAuth = HiveAuthLoginState(username, HiveAuthStep.FAILED, error = AppError.from(AuthException.InvalidUsername()))) }
            return
        }
        setState { it.copy(hiveAuth = HiveAuthLoginState(username, HiveAuthStep.INITIATING), error = null) }
        hiveAuthJob = scope.launch {
            try {
                val challenge = HiveSignerMessages.hiveAuthChallenge(username, env.nowMillis())
                val result = env.hiveAuth.authenticate(username, HiveAuthChallenge("posting", challenge)) { wait ->
                    setState { s -> s.copy(
                            hiveAuth = s.hiveAuth?.copy(
                                step = HiveAuthStep.WAITING, link = wait.link, expiresAtMillis = wait.expiresAtMillis,
                                requestId = wait.uuid, openWallet = s.hiveAuth.requestId != wait.uuid || s.hiveAuth.openWallet,
                            ),
                        ) }
                }
                setState { s -> s.copy(hiveAuth = s.hiveAuth?.copy(step = HiveAuthStep.LOGGING_IN, link = null)) }
                val signature = result.challengeSignature
                    ?: throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.INVALID_RESPONSE, "missing challenge signature")
                val login = HiveAuthLogin(
                    username = username,
                    code = HiveSignerMessages.hiveAuthCode(challenge, signature),
                    authKey = result.session.key,
                    token = result.session.token,
                    expiresAtMillis = result.session.expiresAtMillis,
                )
                val previous = current.username
                completed(env.accounts.loginWithHiveAuth(login), previous)
                setState { s -> s.copy(hiveAuth = s.hiveAuth?.copy(step = HiveAuthStep.SUCCESS, link = null)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { s -> s.copy(hiveAuth = s.hiveAuth?.copy(step = HiveAuthStep.FAILED, link = null, error = AppError.from(e))) }
            }
        }
    }

    /** The UI brought the wallet up for the current request (see [HiveAuthLoginState.openWallet]). */
    fun walletOpened() = setState { s -> s.copy(hiveAuth = s.hiveAuth?.copy(openWallet = false)) }

    /** Closes the HiveAuth sheet; a request still waiting is abandoned (RN reset()). */
    fun cancelHiveAuth() {
        val job = hiveAuthJob
        hiveAuthJob = null
        setState { it.copy(hiveAuth = null) }
        if (job?.isActive == true) {
            job.cancel()
            scope.launch { runCatching { env.hiveAuth.close() } }
        }
    }

    private suspend fun signIn(block: suspend () -> AccountSummary): String {
        setState { it.copy(isSigningIn = true, error = null) }
        // the account before the sign-in wrote the vault: a re-read landing meanwhile must not hide the change
        val previous = current.username
        try {
            val summary = block()
            completed(summary, previous)
            return summary.username
        } catch (e: CancellationException) {
            setState { it.copy(isSigningIn = false) }
            throw e
        } catch (e: Exception) {
            val error = AppError.from(e)
            setState { it.copy(isSigningIn = false, error = error) }
            throw e as? AppException ?: AppException(error, e)
        }
    }

    /** After a sign-in: the account is stored and current. [previous] is the current account before it. */
    private suspend fun completed(summary: AccountSummary, previous: String?) {
        // signed in (again): its drafts are kept from here on
        env.localDrafts.returned(summary.username)
        if (previous != summary.username) env.forgetSessionState()
        reload()
        setState {
            it.copy(
                isSigningIn = false, error = null, loginPrefill = null,
                expiredSession = it.expiredSession?.takeIf { u -> u != summary.username },
            )
        }
        env.appLock.refresh()
        if (previous != summary.username) {
            env.emit(AppEvent.SessionChanged(summary.username))
            // only an emit told the screens: without one, an armed announcement still waits for its read
            announced()
        }
        val hooks = env.postLoginHooks.toList()
        scope.launch {
            hooks.forEach { hook ->
                try {
                    hook.afterLogin(summary.username)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // a prefetch never fails the sign-in
                }
            }
        }
    }

    // ---- accounts ----

    /** RN _switchAccount: makes [username] current, then refreshes its session. No-op for the current account. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun switchTo(username: String) {
        if (username == current.username) return
        setState { it.copy(switchingTo = username, error = null) }
        try {
            env.accounts.switchTo(username)
        } catch (e: CancellationException) {
            setState { it.copy(switchingTo = null) }
            throw e
        } catch (e: Exception) {
            val error = AppError.from(e)
            setState { it.copy(switchingTo = null, error = error) }
            throw AppException(error, e)
        }
        env.forgetSessionState()
        reload()
        setState { it.copy(switchingTo = null) }
        env.appLock.refresh()
        env.emit(AppEvent.SessionChanged(username))
        announced()
        scope.launch { refreshSession(username) }
    }

    /**
     * Signs [username] out: its keys, tokens and session leave the device, and an active key held for it is
     * dropped. When it was current, the next account becomes current (RN switches to the first remaining one);
     * after the last account the PIN lock goes off.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun logout(username: String) {
        val wasCurrent = username == current.username
        // push: the access token is read before the account is gone, its row disabled afterwards (never awaited)
        val leaving = env.push.leaving(listOf(username))
        var next: AccountSummary? = null
        var failure: Exception? = null
        try {
            guarded {
                env.signing.forget(username)
                next = env.accounts.remove(username)
                turnLockOffIfEmpty()
            }
        } catch (e: Exception) {
            if (username !in gone(listOf(username))) {
                env.push.staying(leaving)
                throw e
            }
            // the account left the vault before the failure: everything below still runs, the error is thrown at the end
            failure = e
        }
        // a signed-out account leaves no text behind on the device (RN keeps its drafts; here logout clears them)
        env.accountsLeft(drafts = listOf(username), forget = wasCurrent) { env.push.release(leaving) }
        reload()
        setState { it.copy(expiredSession = it.expiredSession?.takeIf { u -> u != username }) }
        env.appLock.refresh()
        if (wasCurrent) {
            env.emit(AppEvent.SessionChanged(next?.username))
            announced()
            next?.username?.let { scope.launch { refreshSession(it) } }
        }
        failure?.let { throw it }
    }

    /** Signs out of the current account; see [logout]. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun signOut() {
        logout(current.username ?: return)
    }

    /** Signs every account out and turns the PIN lock off. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun logoutAll() {
        val hadCurrent = current.username != null
        val names = current.accounts.map { it.username }
        var leaving = env.push.leaving(names)
        // null: every account
        var left: Set<String>? = null
        var failure: Exception? = null
        try {
            guarded {
                names.forEach { env.signing.forget(it) }
                env.accounts.logoutAll()
                turnLockOffIfEmpty()
            }
        } catch (e: Exception) {
            val removed = gone(names)
            if (removed.isEmpty()) {
                env.push.staying(leaving)
                throw e
            }
            // it failed part way: the accounts that did leave the vault are cleaned up, the others stay as they were
            failure = e
            if (removed.size < names.size) {
                env.push.staying(leaving.filter { it.username !in removed })
                leaving = leaving.filter { it.username in removed }
                left = removed
            }
        }
        val only = left
        env.accountsLeft(drafts = only?.toList(), forget = only == null || current.username in only) { env.push.release(leaving) }
        reload()
        setState { it.copy(expiredSession = null) }
        env.appLock.refresh()
        if (hadCurrent && current.username == null) {
            env.emit(AppEvent.SessionChanged(null))
            announced()
        }
        failure?.let { throw it }
    }

    /**
     * Which of [names] are no longer in the vault, asked after a logout failed: the vault removes an account
     * before the steps that can still fail (picking the next account, the lock). An entry that cannot be read
     * counts as still there.
     */
    private suspend fun gone(names: List<String>): Set<String> = withContext(NonCancellable + env.dispatchers.io) {
        names.filter { runCatching { env.vault.vault.account(it) == null }.getOrDefault(false) }.toSet()
    }

    private suspend fun turnLockOffIfEmpty() = withContext(env.dispatchers.io) {
        if (env.vault.vault.usernames().isEmpty()) env.pinLock.clearWithoutPin()
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val error = AppError.from(e)
        setState { it.copy(error = error) }
        throw AppException(error, e)
    }

    /** Opens the login screen for [username] next (RN "Verify" after an expired session). */
    /** Suspends until the stored session was read at start (immediately afterwards). */
    @Throws(CancellationException::class)
    suspend fun awaitLoaded() {
        state.first { !it.isLoading }
    }

    fun requestLogin(username: String?) = setState { it.copy(loginPrefill = username, expiredSession = null) }

    fun consumeLoginPrefill() = setState { it.copy(loginPrefill = null) }

    fun dismissExpiredSession() = setState { it.copy(expiredSession = null) }

    fun clearError() = setState { it.copy(error = null) }

    /** The vault was wiped: signed out, nothing left to read. */
    internal fun onWiped() {
        cancelHiveAuth()
        setState { SessionState(isLoading = false) }
        // the wipe's own SessionChanged(null) follows at once
        announced()
    }

    private suspend fun reload() {
        val accounts = try {
            env.accounts.accounts().sortedBy { it.username }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // any failed read (start, sign-in, switch, logout): the screens still go by the state as it is, so the
            // next read that succeeds (a recreation's start, a retry) tells them when it finds another account
            toldBase = current.username
            announceNext = true
            throw e
        }
        setState { s -> s.copy(username = accounts.firstOrNull { it.isCurrent }?.username, accounts = accounts, isLoading = false) }
    }

    /** A sign-in, switch, logout or wipe told the screens the state as it is now: nothing waits to be announced. */
    private fun announced() {
        announceNext = false
        toldBase = NOT_TOLD
    }

    /**
     * RN `_refreshAccessToken` plus `_checkHiveAuthExpiry`: exchanges the refresh token (a key account without a
     * token signs a fresh code) and flags the account when its session or HiveAuth key expired. Network failures
     * keep the old token silently.
     */
    private suspend fun refreshSession(username: String) {
        try {
            // one renewal per account at a time, shared with screens that renew on a 401 (Listen)
            if (env.sessionRenewals.exchange(username) == null) env.accounts.ensureKeySession(username)
            val account = env.accounts.vaultAccount(username)
            if (account?.authType?.let { it == com.ecency.core.secure.AuthType.HIVE_AUTH } == true &&
                account.hiveAuthExpiry in 1..env.nowMillis()
            ) {
                setState { it.copy(expiredSession = username) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException.SessionExpired) {
            setState { it.copy(expiredSession = username) }
        } catch (_: Exception) {
            // offline or the endpoint is down: the stored token stays until the next start
        }
    }

    companion object {
        /** Refresh on foreground when the session ends within this window. */
        const val REFRESH_MARGIN_MILLIS = 24 * 60 * 60_000L
    }
}
