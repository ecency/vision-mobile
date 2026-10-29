package com.ecency.core.auth

import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.SecureStore
import com.ecency.core.secure.SecureStoreException
import com.ecency.core.secure.VaultAccount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Where accounts and their sessions live. Blocking; [AccountManager] calls it on its I/O dispatcher.
 * The production implementation is [VaultAccountStore]; tests may use it over an in-memory SecureStore.
 */
interface AccountStore {
    /** Every stored username, readable or not. */
    @Throws(SecureStoreException::class) fun usernames(): List<String>
    @Throws(SecureStoreException::class) fun accounts(): List<VaultAccount>
    @Throws(SecureStoreException::class) fun account(username: String): VaultAccount?
    @Throws(SecureStoreException::class) fun save(account: VaultAccount)
    /** Removes the account and its session. */
    @Throws(SecureStoreException::class) fun remove(username: String)
    @Throws(SecureStoreException::class) fun currentUsername(): String?
    @Throws(SecureStoreException::class) fun setCurrentUsername(username: String?)
    @Throws(SecureStoreException::class) fun session(username: String): TokenSession?
    @Throws(SecureStoreException::class) fun saveSession(session: TokenSession)
}

/**
 * [AccountStore] over the existing [KeyVault] (accounts, current account) plus one extra SecureStore entry
 * per account, `auth.session.<username>`, for the refresh token and expiry the vault has no field for.
 * The access token itself stays in [VaultAccount.accessToken], where the RN migration puts it.
 */
class VaultAccountStore(private val vault: KeyVault, private val store: SecureStore) : AccountStore {
    constructor(store: SecureStore) : this(KeyVault(store), store)

    override fun usernames(): List<String> = vault.usernames()
    override fun accounts(): List<VaultAccount> = vault.accounts()
    override fun account(username: String): VaultAccount? = vault.account(username)
    override fun save(account: VaultAccount) = vault.save(account)

    override fun remove(username: String) = store.withLock {
        vault.remove(username)
        if (KeyVault.isValidUsername(username)) store.delete(SESSION_PREFIX + username)
    }

    override fun currentUsername(): String? = vault.currentUsername()
    override fun setCurrentUsername(username: String?) = vault.setCurrentUsername(username)

    override fun session(username: String): TokenSession? {
        if (!KeyVault.isValidUsername(username)) return null
        val account = vault.account(username) ?: return null
        val token = account.accessToken?.takeIf { it.isNotEmpty() } ?: return null
        val raw = try {
            store.getString(SESSION_PREFIX + username)
        } catch (_: SecureStoreException.Corrupted) {
            null
        }
        val o = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        return TokenSession(
            username = username,
            accessToken = token,
            refreshToken = runCatching { o?.get("refreshToken")?.jsonPrimitive?.contentOrNull }.getOrNull(),
            expiresAtMillis = runCatching { o?.get("expiresAt")?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0,
        )
    }

    override fun saveSession(session: TokenSession) = store.withLock {
        if (!KeyVault.isValidUsername(session.username)) throw SecureStoreException.InvalidArgument("invalid Hive username")
        val account = vault.account(session.username) ?: throw SecureStoreException.InvalidArgument("unknown account")
        vault.save(account.copy(accessToken = session.accessToken))
        store.putString(
            SESSION_PREFIX + session.username,
            buildJsonObject {
                put("v", 1)
                put("refreshToken", session.refreshToken)
                put("expiresAt", session.expiresAtMillis)
            }.toString(),
        )
    }

    companion object {
        const val SESSION_PREFIX = "auth.session."
    }
}
