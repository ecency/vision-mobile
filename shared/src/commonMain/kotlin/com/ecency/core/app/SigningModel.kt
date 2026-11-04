package com.ecency.core.app

import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.auth.AuthException
import com.ecency.core.auth.KeyLogin
import com.ecency.core.auth.AuthUpgradeChoice
import com.ecency.core.auth.HiveAuthSession
import com.ecency.core.auth.HiveSigner
import com.ecency.core.auth.HiveSignerApi
import com.ecency.core.auth.HiveSignerSignResult
import com.ecency.core.auth.OperationAuthority
import com.ecency.core.auth.SignedBroadcast
import com.ecency.core.auth.SigningBackends
import com.ecency.core.auth.SigningRouter
import com.ecency.core.auth.KeyInput
import com.ecency.core.auth.TempActiveKey
import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import com.ecency.core.l10n.L
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/** What a signing step needs from the user right now. */
sealed interface SigningPrompt {
    val username: String

    /**
     * RN authUpgradeSheet: the operation needs [authority] and the account cannot sign it as is. [keyEntry] is
     * true for the active authority (the key goes into [TempActiveKey] for one signature within 60 s).
     */
    data class AuthUpgrade(
        override val username: String,
        val authority: KeyRole,
        val operation: String,
        val keyEntry: Boolean,
        val validating: Boolean = false,
        val error: String? = null,
    ) : SigningPrompt

    /** RN hiveSignerModal: HiveSigner's signing page in the in-app web view. */
    data class HiveSignerHot(override val username: String, val url: String) : SigningPrompt

    /** RN hiveAuthBroadcastSheet: the request waits for the wallet app; [link] brings it up. */
    data class HiveAuthBroadcast(
        override val username: String,
        val step: HiveAuthStep,
        val link: String? = null,
        val expiresAtMillis: Long = 0,
        /** The relay request [link] belongs to; the wallet opens by itself once per request ([walletOpened]). */
        val requestId: String? = null,
        val openWallet: Boolean = false,
    ) : SigningPrompt
}

data class SigningState(val prompt: SigningPrompt? = null)

/**
 * Every broadcast of the app goes through here ([broadcast]): the shared [SigningRouter] decides, this model
 * acts for it ([SigningBackends]). Key accounts sign locally (same transaction on retry), HiveSigner accounts
 * post with their token and sign anything else on HiveSigner's page, HiveAuth accounts sign in the wallet
 * app; the auth upgrade asks for an active key. One prompt shows at a time; [cancel] closes it, which fails
 * the waiting broadcast with [AuthException.Cancelled].
 */
class SigningModel internal constructor(private val env: AppEnvironment) :
    StateModel<SigningState>(SigningState(), env.dispatchers), SigningBackends {

    val tempActiveKey = TempActiveKey(env.nowMillis)
    val router = SigningRouter(this, tempActiveKey)

    private val promptLock = Mutex()
    private var cancelSignal: CompletableDeferred<Unit>? = null
    private var hotResult: CompletableDeferred<HiveSignerSignResult>? = null
    private var upgradeChoice: CompletableDeferred<AuthUpgradeChoice?>? = null

    /** Signs and broadcasts [ops] for [username] with [authority] (default: the operations' own). */
    @Throws(Exception::class)
    suspend fun broadcast(username: String, ops: JsonArray, authority: KeyRole = OperationAuthority.required(ops)): SignedBroadcast =
        withContext(generationOf(username)) { router.broadcast(username, ops, authority) }

    /**
     * As [broadcast], for one user action named by [actionId] (made once when the user confirms, e.g. with
     * [AppEnvironment.idempotencyKey]): retrying that action resumes its transaction, and nothing else does. A
     * later action with the same operations (a second, deliberate transfer) gets its own id and its own transaction.
     */
    @Throws(Exception::class)
    suspend fun broadcast(username: String, ops: JsonArray, authority: KeyRole, actionId: String): SignedBroadcast =
        withContext(SigningAction(actionId) + generationOf(username)) { router.broadcast(username, ops, authority) }

    /**
     * Asks again about a key account's send that ended undecided ([com.ecency.core.api.broadcast.BroadcastException.Network]
     * with a pending whose transaction may have landed): the same signed transaction is re-sent while it can still be
     * accepted, otherwise the chain is asked, and a new transaction is signed only after the chain said the earlier
     * one did not land ([com.ecency.core.api.broadcast.PendingBroadcast.send]). No key or prompt is needed: the
     * pending holds its own. The journal follows it as for [broadcast].
     */
    @Throws(Exception::class)
    suspend fun resume(username: String, actionId: String, operation: String, pending: com.ecency.core.api.broadcast.PendingBroadcast): SignedBroadcast =
        SignedBroadcast(com.ecency.core.auth.SigningMethod.KEY, journaled(username, actionId, operation) { pending.send() }.txId)

    private suspend fun journaled(
        username: String,
        actionId: String,
        operation: String,
        send: suspend () -> com.ecency.core.api.broadcast.BroadcastResult,
    ): com.ecency.core.api.broadcast.BroadcastResult {
        try {
            return send().also { env.sendJournal.settle(username, actionId) }
        } catch (e: com.ecency.core.api.broadcast.BroadcastException.Network) {
            val pending = e.pending
            val tx = pending?.signed
            if (pending != null && tx != null && pending.mayHaveLanded) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    env.sendJournal.record(username, actionId, operation, tx.id, tx.transaction.expiration)
                }
            }
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // decided in this run (refused, or closed as unconfirmed, which the screen shows): nothing to resolve later
            withContext(kotlinx.coroutines.NonCancellable) { env.sendJournal.settle(username, actionId) }
            throw e
        }
    }

    /**
     * Drops everything held for every account (the PIN wipe): the active key, the prompt on screen and every
     * prompt still waiting its turn; each waiting broadcast fails as cancelled. Main thread.
     */
    internal fun forgetAll() {
        tempActiveKey.clear()
        generation++
        cancel()
    }

    /** Moved by [forgetAll] (on main): a broadcast started before it never shows a prompt after it. */
    @kotlin.concurrent.Volatile
    private var generation = 0

    /** Per account, moved by [forget] (on main): a broadcast of a signed-out account never prompts after it. */
    @kotlin.concurrent.Volatile
    private var accountGenerations: Map<String, Int> = emptyMap()

    private fun generationOf(username: String) = SigningGeneration(generation, username, accountGenerations[username] ?: 0)

    /** Drops anything held for [username] (sign-out), also its sends waiting their turn for a prompt. Main thread. */
    internal fun forget(username: String) {
        tempActiveKey.clear()
        accountGenerations = accountGenerations + (username to (accountGenerations[username] ?: 0) + 1)
        val p = current.prompt
        if (p != null && p.username == username) cancel()
    }

    // ---- what the UI calls ----

    /** The UI brought the wallet up for the waiting HiveAuth request. */
    fun walletOpened() = setState { s ->
        val p = s.prompt
        if (p is SigningPrompt.HiveAuthBroadcast) s.copy(prompt = p.copy(openWallet = false)) else s
    }

    /** Closes the current prompt; the broadcast waiting on it fails as cancelled. */
    fun cancel() {
        upgradeChoice?.complete(null)
        cancelSignal?.complete(Unit)
    }

    /**
     * A navigation inside the HiveSigner page. True when it reported the outcome (the web view stops loading
     * it); only the redirect URI or HiveSigner's own success page count ([HiveSigner.parseSignResult]).
     */
    fun hotSignNavigation(url: String): Boolean {
        if (current.prompt !is SigningPrompt.HiveSignerHot) return false
        val result = HiveSigner.parseSignResult(url, env.auth.hiveSigner) ?: return false
        hotResult?.complete(result)
        return true
    }

    /** Upgrade sheet: sign with HiveSigner or HiveAuth instead. */
    fun chooseUpgrade(choice: AuthUpgradeChoice) {
        if (choice == AuthUpgradeChoice.KEY) return
        upgradeChoice?.complete(choice)
    }

    /**
     * Upgrade sheet "Sign": [input] is the active private key or the master password. It must derive the key
     * of the prompt's authority that is on the account on chain (RN invalid_key / key_mismatch); then it is
     * held for that account for one signature within 60 s.
     */
    fun submitUpgradeKey(input: String) {
        val prompt = current.prompt as? SigningPrompt.AuthUpgrade ?: return
        if (!prompt.keyEntry || prompt.validating) return
        val deferred = upgradeChoice ?: return
        setPrompt(prompt.copy(validating = true, error = null))
        scope.launch {
            val error = try {
                // the one normaliser for every key entry (RN #3600): chat app spaces and zero-width characters
                val cleaned = KeyInput.secret(input)
                val wif = runCatching { PrivateKey.fromWif(cleaned) }.getOrNull()
                // anything else is a master password, as at sign-in, also one shaped like a WIF that fails its checksum
                val notAPassword = cleaned.isEmpty() || KeyLogin.isPublicKey(cleaned)
                val key = wif ?: if (notAPassword) null else PrivateKey.fromLogin(prompt.username, cleaned, prompt.authority)
                if (KeyLogin.isPublicKey(cleaned)) {
                    AppError.PUBLIC_KEY_GIVEN
                } else if (key == null) {
                    INVALID_KEY
                } else {
                    val chain = env.chain.authorities(prompt.username)
                    val public = key.publicKey().toString()
                    if (chain == null || public !in chain.keysFor(prompt.authority)) {
                        // a private key of another role of this account: say which one (RN #3600)
                        val other = if (wif != null && chain != null) KeyLogin.rolesOf(public, chain).firstOrNull() else null
                        when {
                            other != null -> wrongRole(other, prompt.authority)
                            // shaped like a private key and deriving nothing of the account: a mistyped key
                            wif == null && KeyLogin.looksLikeWif(cleaned) -> INVALID_KEY
                            else -> KEY_MISMATCH
                        }
                    } else if (!KeyLogin.keyMatchesRole(key.toWif(), prompt.authority, chain)) {
                        // on the account, but its weight is below the threshold: the chain would refuse the signature
                        AppError.KEY_BELOW_THRESHOLD
                    } else {
                        tempActiveKey.set(prompt.username, key.toWif())
                        null
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppError.from(e).message
            }
            if (error == null) {
                deferred.complete(AuthUpgradeChoice.KEY)
            } else {
                setState { s ->
                    val p = s.prompt
                    if (p is SigningPrompt.AuthUpgrade && p.username == prompt.username) s.copy(prompt = p.copy(validating = false, error = error)) else s
                }
            }
        }
    }

    // ---- SigningBackends ----

    override suspend fun account(username: String): VaultAccount? = env.accounts.vaultAccount(username)

    override suspend fun hasPostingAuthorization(username: String): Boolean =
        env.chain.authorities(username)?.grantsPostingTo() == true

    override suspend fun broadcastWithKey(username: String, ops: JsonArray, key: PrivateKey, authority: KeyRole): String? {
        val operations = withContext(env.dispatchers.io) { ops.map { OperationJson.decodeOwn(it as? JsonArray ?: JsonArray(emptyList())) } }
        val action = kotlinx.coroutines.currentCoroutineContext()[SigningAction]?.id
        val broadcaster = env.api.broadcaster
        return when {
            // one user action: only its retry resumes it; the user is part of the key. An outcome the chain has not
            // told yet goes into the restart journal until it settles.
            action != null -> journaled(username, action, operationName(ops)) { broadcaster.broadcastAction(actionKey(username, action, ops), operations, key) }
            // repeating these changes nothing (a vote, a follow, a post edit): the operations' JSON is the key
            com.ecency.core.api.broadcast.RepeatSafeOps.all(operations) -> broadcaster.broadcastIdempotent("ops/$username/$ops", operations, key)
            // anything that moves value must name its action, or a deliberate second transfer could read as a retry
            else -> throw com.ecency.core.api.broadcast.BroadcastException.InvalidOperation(ACTION_ID_REQUIRED)
        }.txId
    }

    override suspend fun broadcastWithHiveSignerToken(username: String, ops: JsonArray, accessToken: String): String? {
        val result = HiveSignerApi(env.auth.http).broadcast(accessToken, ops)
        return runCatching { result?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull }.getOrNull()
    }

    override suspend fun broadcastWithHiveSignerHot(username: String, ops: JsonArray, authority: KeyRole): HiveSignerSignResult {
        val deferred = CompletableDeferred<HiveSignerSignResult>()
        return try {
            prompted(SigningPrompt.HiveSignerHot(username, HiveSigner.hotSignUrl(ops, env.auth.hiveSigner)), onStart = { hotResult = deferred }) {
                deferred.await()
            }
        } catch (e: AuthException.Cancelled) {
            // the page reports its outcome only by a redirect; closed without one, HiveSigner may still have broadcast
            throw AuthException.Cancelled(HOT_PAGE_CLOSED, mayHaveSigned = true)
        } finally {
            hotResult = null
        }
    }

    override suspend fun broadcastWithHiveAuth(username: String, ops: JsonArray, authority: KeyRole): String? {
        val account = env.accounts.vaultAccount(username) ?: throw AuthException.NotSignedIn()
        val key = account.hiveAuthKey?.takeIf { it.isNotEmpty() }
            ?: throw AuthException.HiveAuth(AuthException.HiveAuth.Reason.SESSION_EXPIRED)
        val session = HiveAuthSession(username, key, account.hiveAuthToken, account.hiveAuthExpiry)
        // never weaker than the authority the router resolved; HiveAuth has no owner key type
        val keyType = OperationAuthority.hiveAuthKeyType(ops).let { if (it == KeyRole.POSTING && authority == KeyRole.ACTIVE) KeyRole.ACTIVE else it }
        if (keyType == KeyRole.OWNER || authority == KeyRole.OWNER) throw AuthException.UnsupportedOperation(L.native.dappBridgeOps.ownerRefused)
        return prompted(SigningPrompt.HiveAuthBroadcast(username, HiveAuthStep.INITIATING)) {
            env.hiveAuth.broadcast(session, keyType, ops) { wait ->
                setState { s ->
                    val p = s.prompt
                    if (p is SigningPrompt.HiveAuthBroadcast) {
                        s.copy(
                            prompt = p.copy(
                                step = HiveAuthStep.WAITING, link = wait.link, expiresAtMillis = wait.expiresAtMillis,
                                requestId = wait.uuid, openWallet = p.requestId != wait.uuid || p.openWallet,
                            ),
                        )
                    } else {
                        s
                    }
                }
            }.txId
        }
    }

    /** In-app owner signing is not offered yet: always declined. */
    override suspend fun confirmOwnerSigning(username: String, operationName: String): Boolean = false

    override suspend fun showAuthUpgrade(username: String, authority: KeyRole, operationName: String): AuthUpgradeChoice? {
        val deferred = CompletableDeferred<AuthUpgradeChoice?>()
        val prompt = SigningPrompt.AuthUpgrade(username, authority, operationName, keyEntry = authority == KeyRole.ACTIVE)
        return try {
            prompted(prompt, onStart = { upgradeChoice = deferred }) { deferred.await() }
        } catch (e: AuthException.Cancelled) {
            null
        } finally {
            upgradeChoice = null
        }
    }

    /**
     * Shows [prompt] while [block] runs, one prompt at a time. [cancel] ends it with [AuthException.Cancelled].
     */
    private suspend fun <T> prompted(prompt: SigningPrompt, onStart: () -> Unit = {}, block: suspend () -> T): T {
        // the broadcast's own start; a prompt asked for outside one counts from now
        val started = kotlinx.coroutines.currentCoroutineContext()[SigningGeneration]
        val asked = started?.value ?: generation
        return promptLock.withLock {
            val cancelled = CompletableDeferred<Unit>()
            withContext(env.dispatchers.main) {
                // a wipe since this broadcast started (while it waited for the prompt before it, or a fallback
                // after the wipe closed its first prompt): it never shows
                if (generation != asked) throw AuthException.Cancelled()
                // the account signed out since this send started (it waited behind another prompt): it never shows
                if (started != null && (accountGenerations[started.username] ?: 0) != started.account) throw AuthException.Cancelled()
                cancelSignal = cancelled
                onStart()
                setPrompt(prompt)
            }
            try {
                coroutineScope {
                    val work = async { block() }
                    select {
                        work.onAwait { it }
                        cancelled.onAwait {
                            work.cancel()
                            throw AuthException.Cancelled()
                        }
                    }
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + env.dispatchers.main) {
                    if (cancelSignal === cancelled) cancelSignal = null
                    setState { s -> if (s.prompt?.let { it::class == prompt::class && it.username == prompt.username } == true) s.copy(prompt = null) else s }
                }
            }
        }
    }

    private fun setPrompt(prompt: SigningPrompt) = setState { it.copy(prompt = prompt) }

    companion object {
        val HOT_PAGE_CLOSED: String get() = L.native.signing.hotPageClosed

        // RN en-US auth_upgrade.invalid_key, auth_upgrade.key_mismatch
        val INVALID_KEY: String get() = L.authUpgrade.invalidKey
        val KEY_MISMATCH: String get() = L.authUpgrade.keyMismatch

        /** A valid private key of [given]'s role pasted where [needed]'s is required. */
        fun wrongRole(given: KeyRole, needed: KeyRole): String =
            L.native.signing.wrongRole(given = CopyLists.role(given), needed = CopyLists.role(needed))

        /** The name of the first operation, for prompts. */
        fun operationName(ops: JsonArray): String =
            ((ops.firstOrNull() as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull ?: "unknown"
    }
}

/** The retry key of one user action: account, action id and the operations (a retried action may not change them). */
internal fun actionKey(username: String, actionId: String, ops: JsonArray) = "act/$username/$actionId/$ops"

/** Raised for a value-moving broadcast without an action id ([SigningModel.broadcast] with `actionId`). */
val ACTION_ID_REQUIRED: String get() = L.native.signing.actionIdRequired

/** The user action a broadcast belongs to ([SigningModel.broadcast] with an action id). */
/** The [SigningModel] generation a broadcast started in (see [SigningModel.forgetAll]). */
internal class SigningGeneration(val value: Int, val username: String, val account: Int) : kotlin.coroutines.AbstractCoroutineContextElement(SigningGeneration) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<SigningGeneration>
}

internal class SigningAction(val id: String) : kotlin.coroutines.AbstractCoroutineContextElement(SigningAction) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<SigningAction>
}
