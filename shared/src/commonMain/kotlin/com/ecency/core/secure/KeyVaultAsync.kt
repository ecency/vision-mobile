package com.ecency.core.secure

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * [KeyVault] for UI code: the same calls as suspend functions that run on an I/O dispatcher, so Keystore
 * and Keychain round trips never block the main thread. Swift sees them as async functions.
 */
class KeyVaultAsync(val vault: KeyVault, private val dispatcher: CoroutineDispatcher) {
    constructor(vault: KeyVault) : this(vault, Dispatchers.IO)

    private suspend fun <T> io(block: KeyVault.() -> T): T = withContext(dispatcher) { vault.block() }

    @Throws(SecureStoreException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun accounts(): List<VaultAccount> = io { accounts() }

    @Throws(SecureStoreException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun unreadableUsernames(): List<String> = io { unreadableUsernames() }

    @Throws(SecureStoreException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun account(username: String): VaultAccount? = io { account(username) }

    @Throws(SecureStoreException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun save(account: VaultAccount) = io { save(account) }

    @Throws(SecureStoreException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun remove(username: String) = io { remove(username) }

    @Throws(SecureStoreException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun currentUsername(): String? = io { currentUsername() }

    @Throws(SecureStoreException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun setCurrentUsername(username: String?) = io { setCurrentUsername(username) }

    @Throws(SecureStoreException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun pinLockEnabled(): Boolean = io { pinLockEnabled() }

    @Throws(SecureStoreException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun setPinLockEnabled(enabled: Boolean) = io { setPinLockEnabled(enabled) }

    @Throws(SecureStoreException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun wipe() = io { wipe() }
}
