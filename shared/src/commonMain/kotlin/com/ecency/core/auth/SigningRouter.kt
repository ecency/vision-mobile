package com.ecency.core.auth

import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import com.ecency.core.l10n.L
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException

/** How a broadcast was signed. */
enum class SigningMethod { KEY, HIVESIGNER_TOKEN, HIVESIGNER_HOT, HIVEAUTH }

/** What the user picked in the auth upgrade sheet. KEY means they entered a key into [TempActiveKey]. */
enum class AuthUpgradeChoice { KEY, HIVEAUTH, HIVESIGNER }

data class SignedBroadcast(val method: SigningMethod, val txId: String?, val blockNum: Long? = null)

/**
 * The app side of signing: storage, the network and the UI. The router decides, these act.
 * Every function may throw; [AuthFallback.shouldTrigger] errors lead to the auth upgrade.
 */
interface SigningBackends {
    @Throws(Exception::class)
    suspend fun account(username: String): VaultAccount?

    /** True when the account's on-chain posting account_auths include ecency.app. */
    @Throws(Exception::class)
    suspend fun hasPostingAuthorization(username: String): Boolean

    /** Signs with [key] and broadcasts; returns the transaction id. */
    @Throws(Exception::class)
    suspend fun broadcastWithKey(username: String, ops: JsonArray, key: PrivateKey, authority: KeyRole): String?

    /** HiveSigner API broadcast with the account's access token (posting only). */
    @Throws(Exception::class)
    suspend fun broadcastWithHiveSignerToken(username: String, ops: JsonArray, accessToken: String): String?

    /** Opens [HiveSigner.hotSignUrl] for the user; throws [AuthException.Cancelled] (`mayHaveSigned`) when closed. */
    @Throws(Exception::class)
    suspend fun broadcastWithHiveSignerHot(username: String, ops: JsonArray, authority: KeyRole): HiveSignerSignResult

    /** Signs through the HiveAuth wallet ([HiveAuthClient.broadcast]). */
    @Throws(Exception::class)
    suspend fun broadcastWithHiveAuth(username: String, ops: JsonArray, authority: KeyRole): String?

    /**
     * Asks the user to confirm signing [operationName] with the stored OWNER key. Owner operations never
     * sign silently: false (or an error) cancels.
     */
    @Throws(Exception::class)
    suspend fun confirmOwnerSigning(username: String, operationName: String): Boolean

    /** Shows the auth upgrade sheet; null when dismissed. KEY means the key was stored with [TempActiveKey.set]. */
    @Throws(Exception::class)
    suspend fun showAuthUpgrade(username: String, authority: KeyRole, operationName: String): AuthUpgradeChoice?
}

/**
 * Picks key signing, HiveSigner or HiveAuth per account and operation, a port of @ecency/sdk
 * broadcastWithFallback driven by the RN mobilePlatformAdapter (getLoginType, hasPostingAuthorization,
 * showAuthUpgradeUI):
 *
 * - key accounts sign with the stored key of the required role; the active role reads [TempActiveKey]
 *   first; an account without a posting key but with an access token posts through HiveSigner;
 * - HiveSigner accounts post with their token and sign anything stronger on the HiveSigner page;
 * - HiveAuth accounts sign in the wallet, but post through the token when ecency.app has posting authority;
 * - a missing key or authority shows the auth upgrade (posting and active only), whose HiveSigner choice
 *   always means hot signing; HiveSigner accounts skip the sheet and go straight to hot signing.
 *
 * Deliberate difference: the SDK mobile adapter has no owner key getter, so owner operations fail there;
 * the port signs them with a stored owner key, but only after [SigningBackends.confirmOwnerSigning].
 */
class SigningRouter(private val backends: SigningBackends, val tempActiveKey: TempActiveKey) {
    companion object {
        val OWNER_NEVER: String get() = L.native.signingRouter.ownerNever
    }

    private val upgradeLock = Mutex()
    private val pendingUpgrades = mutableMapOf<Pair<String, KeyRole>, CompletableDeferred<AuthUpgradeChoice?>>()

    /** RN getLoginType. */
    fun loginType(account: VaultAccount, authority: KeyRole): LoginMethod? {
        val mapped = account.authType?.loginMethod() ?: return null
        if (mapped == LoginMethod.KEY) {
            if (authority == KeyRole.ACTIVE && !account.activeKey.isNullOrEmpty()) return LoginMethod.KEY
            if (account.postingKey.isNullOrEmpty() && !account.accessToken.isNullOrEmpty()) return LoginMethod.HIVESIGNER
        }
        if (mapped == LoginMethod.HIVESIGNER && authority == KeyRole.ACTIVE) return null
        return mapped
    }

    @Throws(Exception::class)
    suspend fun broadcast(username: String, ops: JsonArray, authority: KeyRole = OperationAuthority.required(ops)): SignedBroadcast {
        OperationAuthority.unsupported(ops)?.let { throw AuthException.UnsupportedOperation(it) }
        // owner operations are never signed in the app, whatever the login: not with a key, not on HiveSigner's
        // page, not in a HiveAuth wallet (review L5)
        if (authority == KeyRole.OWNER || OperationAuthority.required(ops) == KeyRole.OWNER) throw AuthException.UnsupportedOperation(OWNER_NEVER)
        val account = backends.account(username) ?: throw AuthException.NotSignedIn()
        val opName = (ops.firstOrNull() as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "unknown"
        val type = loginType(account, authority)

        if (type != null) {
            if (authority == KeyRole.POSTING && type == LoginMethod.HIVEAUTH && backends.hasPostingAuthorization(username)) {
                try {
                    return method(LoginMethod.HIVESIGNER, account, ops, authority)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!AuthFallback.shouldTrigger(e)) throw e
                }
            }
            return try {
                method(type, account, ops, authority)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (AuthFallback.shouldTrigger(e) && (authority == KeyRole.POSTING || authority == KeyRole.ACTIVE)) {
                    upgraded(account, ops, authority, opName)
                } else {
                    throw e
                }
            }
        }

        return when (authority) {
            KeyRole.POSTING -> try {
                method(LoginMethod.HIVESIGNER, account, ops, authority)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (AuthFallback.shouldTrigger(e)) upgraded(account, ops, authority, opName) else throw e
            }
            KeyRole.ACTIVE -> upgraded(account, ops, authority, opName)
            else -> throw AuthException.NotSignedIn()
        }
    }

    private suspend fun upgraded(account: VaultAccount, ops: JsonArray, authority: KeyRole, opName: String): SignedBroadcast {
        val choice = upgradeChoice(account, authority, opName)
            ?: throw AuthException.Cancelled("Operation requires ${authority.value} authority. User declined alternate auth.")
        return when (choice) {
            AuthUpgradeChoice.KEY -> method(LoginMethod.KEY, account, ops, authority)
            AuthUpgradeChoice.HIVEAUTH -> method(LoginMethod.HIVEAUTH, account, ops, authority)
            AuthUpgradeChoice.HIVESIGNER -> hot(account, ops, authority)
        }
    }

    /**
     * RN showAuthUpgradeUI: one sheet per account and authority at a time (concurrent callers for the same
     * pair share its answer); HiveSigner users skip it.
     */
    private suspend fun upgradeChoice(account: VaultAccount, authority: KeyRole, opName: String): AuthUpgradeChoice? {
        if (account.authType?.loginMethod() == LoginMethod.HIVESIGNER) return AuthUpgradeChoice.HIVESIGNER
        val slot = account.username to authority
        val (deferred, owner) = upgradeLock.withLock {
            pendingUpgrades[slot]?.let { it to false }
                ?: CompletableDeferred<AuthUpgradeChoice?>().also { pendingUpgrades[slot] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        try {
            val choice = try {
                backends.showAuthUpgrade(account.username, authority, opName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            deferred.complete(choice)
            return choice
        } catch (e: Throwable) {
            deferred.complete(null)
            throw e
        } finally {
            upgradeLock.withLock { if (pendingUpgrades[slot] === deferred) pendingUpgrades.remove(slot) }
        }
    }

    private suspend fun method(type: LoginMethod, account: VaultAccount, ops: JsonArray, authority: KeyRole): SignedBroadcast =
        when (type) {
            LoginMethod.KEY -> {
                // The app never stores an owner key and never signs with one: an owner operation ends here, before
                // any key is looked at (SigningBackends.confirmOwnerSigning is no longer asked).
                if (authority == KeyRole.OWNER) throw AuthException.Cancelled("Owner operations are never signed in the app.")
                val wif = when (authority) {
                    KeyRole.ACTIVE -> tempActiveKey.take(account.username) ?: account.activeKey
                    KeyRole.POSTING -> account.postingKey
                    // no owner key is ever stored: an owner operation has nothing to sign with
                    KeyRole.OWNER -> null
                    KeyRole.MEMO -> account.memoKey
                }?.takeIf { it.isNotEmpty() } ?: throw AuthException.MissingKey(authority)
                val key = runCatching { PrivateKey.fromWif(wif) }.getOrElse { throw AuthException.MissingKey(authority) }
                SignedBroadcast(SigningMethod.KEY, backends.broadcastWithKey(account.username, ops, key, authority))
            }
            LoginMethod.HIVEAUTH -> SignedBroadcast(SigningMethod.HIVEAUTH, backends.broadcastWithHiveAuth(account.username, ops, authority))
            LoginMethod.HIVESIGNER -> {
                if (authority != KeyRole.POSTING) {
                    hot(account, ops, authority)
                } else {
                    val token = account.accessToken?.takeIf { it.isNotEmpty() }
                    if (token == null) {
                        hot(account, ops, authority)
                    } else {
                        try {
                            SignedBroadcast(SigningMethod.HIVESIGNER_TOKEN, backends.broadcastWithHiveSignerToken(account.username, ops, token))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (AuthFallback.shouldTrigger(e)) hot(account, ops, authority) else throw e
                        }
                    }
                }
            }
        }

    private suspend fun hot(account: VaultAccount, ops: JsonArray, authority: KeyRole): SignedBroadcast {
        val r = backends.broadcastWithHiveSignerHot(account.username, ops, authority)
        return SignedBroadcast(SigningMethod.HIVESIGNER_HOT, r.id, r.blockNum)
    }
}
