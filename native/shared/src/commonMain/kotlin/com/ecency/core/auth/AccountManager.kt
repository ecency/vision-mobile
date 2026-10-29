package com.ecency.core.auth

import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.coroutines.cancellation.CancellationException

/** How an account signs. */
enum class LoginMethod { KEY, HIVESIGNER, HIVEAUTH }

/** RN `mapAuthTypeToLoginType`: master/posting/active/memo/owner sign with keys. */
fun AuthType.loginMethod(): LoginMethod = when (this) {
    AuthType.HIVE_SIGNER -> LoginMethod.HIVESIGNER
    AuthType.HIVE_AUTH -> LoginMethod.HIVEAUTH
    else -> LoginMethod.KEY
}

/** What the UI shows for one account. No secrets. */
data class AccountSummary(
    val username: String,
    val authType: AuthType?,
    val method: LoginMethod?,
    /** Roles the app holds a private key for: posting, active, memo. Never owner. */
    val storedKeys: Set<KeyRole>,
    val hasAccessToken: Boolean,
    val isCurrent: Boolean,
) {
    companion object {
        fun of(a: VaultAccount, current: String?) = AccountSummary(
            username = a.username,
            authType = a.authType,
            method = a.authType?.loginMethod(),
            storedKeys = buildSet {
                if (!a.activeKey.isNullOrEmpty()) add(KeyRole.ACTIVE)
                if (!a.postingKey.isNullOrEmpty()) add(KeyRole.POSTING)
                if (!a.memoKey.isNullOrEmpty()) add(KeyRole.MEMO)
            },
            hasAccessToken = !a.accessToken.isNullOrEmpty(),
            isCurrent = a.username == current,
        )
    }
}

/** A finished HiveAuth login (see [HiveAuthClient.authenticate]) to store. */
class HiveAuthLogin(
    val username: String,
    /** The code built from the signed challenge ([HiveSignerMessages.hiveAuthCode]). */
    val code: String,
    val authKey: String,
    val token: String?,
    /** Epoch milliseconds the HiveAuth session expires. */
    val expiresAtMillis: Long,
) {
    override fun toString(): String = "HiveAuthLogin(username=$username, secrets=<redacted>)"
}

/** A key sign-in that went through, and what its secret did not give or should not be. No secrets. */
class KeyLoginOutcome(
    val summary: AccountSummary,
    /** Of posting and active: the roles a master password no longer matches on chain (nothing is stored for them). */
    val missingSigningRoles: Set<KeyRole>,
    /** The typed key is also the account's owner key. */
    val sharesOwnerKey: Boolean,
)

/**
 * Multi-account sign-in, switching and sign-out over an [AccountStore], ported from RN
 * `providers/hive/auth.ts` (login, loginWithSC2, loginWithAuthTransfer, loginWithHiveAuth, refreshSCToken,
 * switchAccount) and the account reducer.
 *
 * Every login fetches the account on chain, obtains an Ecency session (key logins sign a HiveSigner code
 * locally, HiveSigner and HiveAuth logins exchange the code they received), stores the account and makes it
 * current. The app lock must stay on while [hasAnyAccount] is true, not only while one is current.
 */
@OptIn(ExperimentalAtomicApi::class)
class AccountManager(
    private val store: AccountStore,
    private val chain: ChainAccounts,
    private val tokens: EcencyTokenApi,
    private val nowMillis: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EcencyCodeProvider {
    private val mutex = Mutex()

    /**
     * Removal marks (RN `storage/authWriteGuard`): how many removals of all accounts, and of each account,
     * have finished. A session renewal takes its mark before its first suspension and writes only when the
     * mark has not moved, under [mutex], so a renewal that began before or during a logout, a logout-all or
     * a wipe never writes afterwards: not even over the session of the same account signed in again in the
     * meantime. A removal runs entirely under [mutex], so no write can land in the middle of one.
     */
    private val removals = AtomicReference(RemovalMarks(0, emptyMap()))

    private data class RemovalMarks(val all: Long, val byAccount: Map<String, Long>)

    internal data class RemovalMark(val all: Long, val account: Long)

    internal fun removalMark(username: String): RemovalMark = removals.load().let { RemovalMark(it.all, it.byAccount[username] ?: 0) }

    /** Called after the removal is done (inside [mutex]), so a renewal that read the store during it is stale too. */
    private fun markRemoved(username: String?) = removals.update { m ->
        if (username == null) m.copy(all = m.all + 1) else m.copy(byAccount = m.byAccount + (username to (m.byAccount[username] ?: 0) + 1))
    }

    /**
     * Every account left the device by a route outside this class (the PIN wipe, [com.ecency.core.secure.PinLock.wipeAll]):
     * renewals still in flight must not write. Call it right after such a wipe.
     */
    fun accountsWiped() = markRemoved(null)

    private suspend fun <T> db(block: AccountStore.() -> T): T = withContext(io) { store.block() }

    // ---- reading ----

    @Throws(Exception::class)
    suspend fun accounts(): List<AccountSummary> = db {
        val current = currentUsername()
        accounts().map { AccountSummary.of(it, current) }
    }

    /** True while any account entry is stored, readable or not: the app lock must apply. */
    @Throws(Exception::class)
    suspend fun hasAnyAccount(): Boolean = db { usernames().isNotEmpty() }

    /**
     * The stored account [username], if any. The UI asks for explicit confirmation before a link
     * (auth-transfer, web code) signs in an account that is already stored.
     */
    @Throws(Exception::class)
    suspend fun storedAccount(username: String): AccountSummary? = db {
        account(username)?.let { AccountSummary.of(it, currentUsername()) }
    }

    @Throws(Exception::class)
    suspend fun current(): AccountSummary? = db {
        val name = currentUsername() ?: return@db null
        account(name)?.let { AccountSummary.of(it, name) }
    }

    /** When [username]'s Ecency session expires (epoch ms); null without a session, 0 when unknown. */
    @Throws(Exception::class)
    suspend fun sessionExpiry(username: String): Long? = db { session(username)?.expiresAtMillis }

    /** The stored account (with its secrets), for signing. */
    @Throws(Exception::class)
    suspend fun vaultAccount(username: String): VaultAccount? = db { account(username) }

    // ---- logins ----

    /**
     * Username plus master password or WIF key. Fetches the account, classifies the secret
     * ([KeyLogin.classify]), exchanges a locally signed code for the Ecency session, then stores and
     * selects the account.
     */
    @Throws(Exception::class)
    suspend fun loginWithKey(usernameInput: String, secret: String): AccountSummary = loginWithKeyOutcome(usernameInput, secret).summary

    /** [loginWithKey], with what the UI should tell the user about the secret afterwards. */
    @Throws(Exception::class)
    suspend fun loginWithKeyOutcome(usernameInput: String, secret: String): KeyLoginOutcome {
        val username = KeyLogin.normalizeUsername(usernameInput)
        if (!KeyVault.isValidUsername(username)) throw AuthException.InvalidUsername()
        val authorities = chain.authorities(username) ?: throw AuthException.UnknownAccount(username)
        val result = KeyLogin.classify(username, secret, authorities)
        val now = nowMillis()
        val session = tokens.exchange(HiveSignerMessages.code(username, result.signingKey(), now), now)
        val summary = store(
            VaultAccount(
                username = username,
                authType = result.authType,
                // posting, active and memo only: the result holds no owner key and no password
                postingKey = result.keys[KeyRole.POSTING],
                activeKey = result.keys[KeyRole.ACTIVE],
                memoKey = result.keys[KeyRole.MEMO],
            ),
            session.copy(username = username),
        )
        return KeyLoginOutcome(summary, result.missingSigningRoles, result.sharesOwnerKey)
    }

    /**
     * RN loginWithSC2: a HiveSigner OAuth `code`. With [expectedUsername] (a web `/auth` link), the code must
     * belong to that account. A stored key account keeps its keys and only takes the new token.
     */
    @Throws(Exception::class)
    suspend fun loginWithHiveSignerCode(code: String, expectedUsername: String? = null): AccountSummary {
        val session = tokens.exchange(code, nowMillis())
        val username = session.username
        if (expectedUsername != null && username != expectedUsername) throw AuthException.AccountMismatch(expectedUsername, username)
        if (!KeyVault.isValidUsername(username) || chain.authorities(username) == null) {
            throw AuthException.UnknownAccount(username)
        }
        return storeHiveSigner(session)
    }

    /**
     * The ecency.com `/auth?username&code` link (RN login screen `_confirmCodeLogin` then loginWithSC2): the
     * code must be readable, at most 7 days old, not from the future, signed for the link's account, and
     * exchange to that account.
     */
    @Throws(Exception::class)
    suspend fun loginWithWebCode(link: AuthLink.CodeLogin): AccountSummary {
        val username = link.username?.let(KeyLogin::normalizeUsername)?.takeIf(KeyVault::isValidUsername)
            ?: throw AuthException.InvalidUsername()
        val code = link.code?.takeIf { it.isNotEmpty() } ?: throw AuthException.InvalidLink("missing code")
        AuthLinks.checkLoginCode(code, username, nowMillis())?.let { throw AuthException.InvalidLink("login code ${it.name.lowercase()}") }
        return loginWithHiveSignerCode(code, username)
    }

    /**
     * RN loginWithAuthTransfer (`ecency://auth-transfer`): tokens handed over from the web app. RN stores
     * them as given; the port first redeems the refresh token at Ecency's token endpoint, which proves the
     * tokens are real and whose account they are, and stores the fresh session only when that account is
     * the one the link names. A stored key account keeps its keys and only takes the new token; use
     * [storedAccount] to ask the user before replacing anything.
     */
    @Throws(Exception::class)
    suspend fun loginWithAuthTransfer(link: AuthLink.AuthTransfer): AccountSummary {
        val session = tokens.exchange(link.refreshToken, nowMillis())
        if (session.username != link.username) throw AuthException.AccountMismatch(link.username, session.username)
        if (chain.authorities(link.username) == null) throw AuthException.UnknownAccount(link.username)
        return storeHiveSigner(session)
    }

    /** A HiveSigner session: merged into a stored key account, else stored as a HiveSigner account. */
    private suspend fun storeHiveSigner(session: TokenSession): AccountSummary {
        val existing = db { account(session.username) }
        // A key account keeps its keys and only takes the new token. One that holds neither a posting nor an
        // active key has nothing to keep signing with: the HiveSigner sign-in makes it a HiveSigner account
        // (its memo key stays), otherwise signing in again would leave it as unable to sign as before.
        val account = when {
            existing?.authType?.loginMethod() != LoginMethod.KEY -> VaultAccount(session.username, AuthType.HIVE_SIGNER)
            existing.isKeyAccountWithoutSigningKey -> VaultAccount(session.username, AuthType.HIVE_SIGNER, memoKey = existing.memoKey)
            else -> existing
        }
        return store(account, session)
    }

    /** RN loginWithHiveAuth: exchanges the signed challenge, stores the HiveAuth key, token and expiry. */
    @Throws(Exception::class)
    suspend fun loginWithHiveAuth(login: HiveAuthLogin): AccountSummary {
        val session = tokens.exchange(login.code, nowMillis())
        if (session.username != login.username) throw AuthException.UnknownAccount(session.username)
        if (chain.authorities(login.username) == null) throw AuthException.UnknownAccount(login.username)
        return store(
            VaultAccount(
                username = login.username,
                authType = AuthType.HIVE_AUTH,
                hiveAuthKey = login.authKey,
                hiveAuthToken = login.token,
                hiveAuthExpiry = login.expiresAtMillis,
            ),
            session,
        )
    }

    private suspend fun store(account: VaultAccount, session: TokenSession): AccountSummary = mutex.withLock {
        db {
            save(account.copy(accessToken = session.accessToken))
            saveSession(session.copy(username = account.username))
            setCurrentUsername(account.username)
            AccountSummary.of(account(account.username)!!, account.username)
        }
    }

    /** Persists a refreshed HiveAuth session (RN updateHiveAuthSession). */
    @Throws(Exception::class)
    suspend fun updateHiveAuthSession(username: String, token: String?, expiresAtMillis: Long?) = mutex.withLock {
        db {
            val a = account(username) ?: throw AuthException.UnknownStoredAccount(username)
            save(a.copy(hiveAuthToken = token ?: a.hiveAuthToken, hiveAuthExpiry = expiresAtMillis ?: a.hiveAuthExpiry))
        }
    }

    // ---- key import (RN importPrivateKeyModal, getUpdatedUserKeys) ----

    /**
     * Adds private keys to the stored account [username] from a master password or one private key, checked
     * against the account's authorities on chain. A master password adds the posting, active and memo keys it
     * derives and that passed the check; the password itself and the owner key are never stored. A private key
     * is stored for every one of those three roles it matches; an owner key is refused. Keys already stored
     * for other roles stay.
     * The account's auth type says how it signed in and does not change with an imported key: a posting-key
     * account that adds its active key is still a posting-key account that now also holds an active key (RN
     * switches the type to the imported key's, which changes what the account may do elsewhere). Only a master
     * password that gave both the posting and the active key makes a key account a master-password account,
     * since it then holds everything that sign-in holds. A HiveSigner or HiveAuth account keeps its sign-in method and its tokens. What the account can do
     * is read from the keys it holds ([VaultAccount.shareablePostingKey], [VaultAccount.signsWithPostingKey]).
     * Nothing changes unless [username] is still the current account when the write happens.
     * @throws AuthException WrongPassword, KeyNotOnAccount or InvalidKey when no role matches (RN
     *   `auth.invalid_credentials`), KeyBelowThreshold for a key that cannot sign alone, UnknownAccount,
     *   NotSignedIn or AccountMismatch.
     */
    @Throws(Exception::class)
    suspend fun importKeys(username: String, secret: String): VaultAccount {
        val authorities = chain.authorities(username) ?: throw AuthException.UnknownAccount(username)
        val imported = KeyLogin.matchForImport(username, secret, authorities)
        return mutex.withLock {
            db {
                val current = currentUsername() ?: throw AuthException.NotSignedIn()
                if (current != username) throw AuthException.AccountMismatch(username, current)
                val stored = account(username) ?: throw AuthException.UnknownStoredAccount(username)
                val keepsMethod = stored.authType == AuthType.HIVE_SIGNER || stored.authType == AuthType.HIVE_AUTH
                // a master password that gave both signing keys: the account now holds what a master sign-in holds
                val bringsFullSignIn = imported.isMasterPassword && KeyRole.POSTING in imported.keys && KeyRole.ACTIVE in imported.keys
                val next = stored.copy(
                    authType = if (keepsMethod || !bringsFullSignIn) stored.authType else AuthType.MASTER_PASSWORD,
                    postingKey = imported.keys[KeyRole.POSTING] ?: stored.postingKey,
                    activeKey = imported.keys[KeyRole.ACTIVE] ?: stored.activeKey,
                    memoKey = imported.keys[KeyRole.MEMO] ?: stored.memoKey,
                )
                save(next)
                next
            }
        }
    }

    // ---- switching and sign-out ----

    @Throws(Exception::class)
    suspend fun switchTo(username: String): AccountSummary = mutex.withLock {
        db {
            val a = account(username) ?: throw AuthException.UnknownStoredAccount(username)
            setCurrentUsername(username)
            AccountSummary.of(a, username)
        }
    }

    /**
     * Removes [username] and its keys and session. When it was current, the next remaining account (by
     * name) becomes current, as the RN app moves to another signed-in account. Returns the current account.
     */
    @Throws(Exception::class)
    suspend fun remove(username: String): AccountSummary? = mutex.withLock {
        try {
            db {
                val wasCurrent = currentUsername() == username
                remove(username)
                if (wasCurrent) setCurrentUsername(accounts().firstOrNull()?.username)
                val current = currentUsername()
                current?.let { account(it) }?.let { AccountSummary.of(it, current) }
            }
        } finally {
            // also after a failure part way: the account may already be gone
            markRemoved(username)
        }
    }

    /** Signs out of the current account; see [remove]. */
    @Throws(Exception::class)
    suspend fun logout(): AccountSummary? {
        val name = db { currentUsername() } ?: return null
        return remove(name)
    }

    /** Removes every account entry (unreadable ones too) and its session. */
    @Throws(Exception::class)
    suspend fun logoutAll() = mutex.withLock {
        try {
            db {
                usernames().forEach { remove(it) }
                setCurrentUsername(null)
            }
        } finally {
            markRemoved(null)
        }
    }

    // ---- Ecency session ----

    /** The account's Ecency `code` (access token) as stored, or null. */
    @Throws(Exception::class)
    override suspend fun code(username: String): String? = db { account(username)?.accessToken?.takeIf { it.isNotEmpty() } }

    /** The account's stored refresh token, or null (the chat bootstrap sends it, as RN does). */
    @Throws(Exception::class)
    suspend fun refreshToken(username: String): String? = db { session(username)?.refreshToken?.takeIf { it.isNotEmpty() } }

    /**
     * RN refreshSCToken, run at app start for the current account: without a refresh token the stored token
     * is kept; otherwise the refresh token is exchanged and the new session stored. When the refresh fails,
     * the old token is kept while it has not expired, else [AuthException.SessionExpired] is thrown (RN then
     * offers to sign out). Null, with nothing stored, when the account was removed while the renewal ran
     * (signed out, or signed out and in again): see [removals].
     */
    @Throws(Exception::class)
    suspend fun refreshSession(username: String): String? {
        // before the first suspension: a removal that starts at any point after this voids the write below
        val mark = removalMark(username)
        val existing = db { session(username) } ?: return null
        val refresh = existing.refreshToken ?: return existing.accessToken
        val now = nowMillis()
        val renewed = try {
            tokens.exchange(refresh, now).also {
                if (it.username != username) throw AuthException.AccountMismatch(username, it.username)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            if (e !is AuthException.TokenExchangeFailed && e !is AuthException.AccountMismatch) throw e
            // signed out meanwhile: no "session expired" for an account that is gone
            if (removalMark(username) != mark) return null
            if (existing.isExpired(now)) throw AuthException.SessionExpired(e)
            return existing.accessToken
        }
        val stored = mutex.withLock {
            removalMark(username) == mark && db { (account(username) != null).also { if (it) saveSession(renewed.copy(username = username)) } }
        }
        // signed out (or out and in again) meanwhile: this token was never stored and is not handed out
        return if (stored) renewed.accessToken else null
    }

    /**
     * RN migrateToMasterKeyWithAccessToken: a key account without an access token (older RN data) signs a
     * code with the least powerful stored key that works (posting, else active; never memo, unlike RN; no owner key is stored) and stores the
     * session. No-op for other accounts and for accounts with no usable key.
     */
    @Throws(Exception::class)
    suspend fun ensureKeySession(username: String): String? {
        val mark = removalMark(username)
        val a = db { account(username) } ?: throw AuthException.UnknownStoredAccount(username)
        a.accessToken?.takeIf { it.isNotEmpty() }?.let { return it }
        if (a.authType?.loginMethod() != LoginMethod.KEY) return null
        val key = listOf(a.postingKey, a.activeKey)
            .firstNotNullOfOrNull { wif -> wif?.let { runCatching { com.ecency.core.hive.PrivateKey.fromWif(it) }.getOrNull() } }
            ?: return null
        val now = nowMillis()
        val session = tokens.exchange(HiveSignerMessages.code(username, key, now), now)
        if (session.username != username) throw AuthException.AccountMismatch(username, session.username)
        val stored = mutex.withLock {
            removalMark(username) == mark && db { (account(username) != null).also { if (it) saveSession(session.copy(username = username)) } }
        }
        return if (stored) session.accessToken else null
    }
}
