package com.ecency.core.legacy

import com.ecency.core.auth.LoginMethod
import com.ecency.core.auth.VaultAccountStore
import com.ecency.core.auth.loginMethod
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.SecureStoreException
import com.ecency.core.secure.VaultAccount

/** What one migration run did. Lists usernames only, never secrets. */
class LegacyMigrationReport(
    /** Written to the vault by this run. */
    val imported: List<String>,
    /** Already in the vault; the vault copy wins and is left untouched. */
    val kept: List<String>,
    /** No usable credential survived (or invalid username): the user must sign in again. */
    val skipped: List<String>,
    /** Per account, RN fields that held data but could not be decrypted or validated. */
    val unreadable: Map<String, List<String>>,
    /** True when a previous run had finished, so this one did nothing. */
    val alreadyDone: Boolean,
    /** True when this run deleted RN secrets ([LegacyCleanup]); false when there were none or it must retry. */
    val rnSecretsRemoved: Boolean = false,
    /**
     * True when the RN app had its PIN lock on but its PIN could not be recovered (or was not 4 digits): the
     * lock is left off rather than locking the user out. The app should suggest setting a PIN again.
     */
    val pinLockDropped: Boolean = false,
) {
    internal fun withRemoved(removed: Boolean) =
        LegacyMigrationReport(imported, kept, skipped, unreadable, alreadyDone, removed, pinLockDropped)

    override fun toString(): String =
        "LegacyMigrationReport(imported=$imported, kept=$kept, skipped=$skipped, unreadable=$unreadable, " +
            "alreadyDone=$alreadyDone, rnSecretsRemoved=$rnSecretsRemoved, pinLockDropped=$pinLockDropped)"
}

/**
 * Moves the accounts recovered by [LegacyImporter] into a [KeyVault], once.
 *
 * Idempotent: accounts are keyed by username, an account already in the vault is never overwritten, every
 * migrated account is read back before the vault's "legacy imported" mark is written, and the mark is the
 * last vault write: a run interrupted half way completes on the next launch, a finished run is never
 * repeated (accounts removed later are not resurrected). Only after the mark are the RN secrets deleted
 * ([LegacyCleanup]); if that fails it is retried on the next run. Secrets the vault did not take stay in the RN
 * storage ([mustKeep]), and so does all of it while it cannot be read.
 */
object LegacyMigration {

    /**
     * Reads the RN app's storage, migrates it, then deletes the RN secrets. Call it off the main thread.
     * Only provably absent RN storage counts as done without importing.
     * @throws LegacyImportError.PinUnavailable when PIN_KEY does not match this data,
     *   [LegacyImportError.StorageUnreadable] when RN storage cannot be read now, [LegacyImportError.Malformed]
     *   or [LegacyImportError.NotVerified]: nothing is marked done or deleted, a later run can retry.
     */
    @Throws(LegacyImportError::class, SecureStoreException::class)
    fun run(vault: KeyVault, storage: LegacyStorage, pinKey: String, defaultPin: String?): LegacyMigrationReport =
        run(vault, storage, pinKey, defaultPin, PinLock.DEFAULT_ITERATIONS)

    /** As [run], with the PIN hash cost (tests use a low one). */
    @Throws(LegacyImportError::class, SecureStoreException::class)
    fun run(vault: KeyVault, storage: LegacyStorage, pinKey: String, defaultPin: String?, pinIterations: Int): LegacyMigrationReport {
        val report: LegacyMigrationReport
        // accounts whose RN secrets must stay; null keeps the whole RN storage
        val keep: Set<String>?
        if (vault.legacyImported()) {
            report = done()
            keep = try {
                keptUsernames(LegacyImporter.import(storage::read, pinKey, defaultPin))
            } catch (_: LegacyImportError.NothingToImport) {
                emptySet()
            } catch (_: LegacyImportError) {
                null // cannot tell what the vault lacks: delete nothing
            }
        } else {
            val legacy = try {
                LegacyImporter.import(storage::read, pinKey, defaultPin)
            } catch (_: LegacyImportError.NothingToImport) {
                null
            }
            if (legacy == null) {
                vault.markLegacyImported()
                report = LegacyMigrationReport(emptyList(), emptyList(), emptyList(), emptyMap(), alreadyDone = false)
                keep = emptySet()
            } else {
                report = migrate(legacy, vault, pinIterations)
                keep = keptUsernames(legacy)
            }
        }
        // The mark is set, so the vault holds what it should: the RN copies of the secrets it holds can go.
        val removed = keep != null && try {
            LegacyCleanup.hasSecrets(storage, keep) && LegacyCleanup.scrub(storage, keep)
        } catch (_: LegacyImportError) {
            false // unreadable right now; the next run retries
        }
        return report.withRemoved(removed)
    }

    private fun keptUsernames(legacy: LegacyImport): Set<String> = legacy.accounts.filter { it.mustKeep() }.map { it.username }.toSet()

    /**
     * True when the RN entry holds a secret the vault never gets: a field that did not decrypt or validate, an
     * account with no credential the vault takes (or an invalid name), or an owner key without the master
     * password it comes from (the vault never stores owner keys). Deleting it would delete the only copy.
     */
    private fun LegacyAccount.mustKeep(): Boolean =
        unreadable.isNotEmpty() || toVaultAccount()?.hasCredential != true || (ownerKey != null && masterPassword == null)

    /** Migrates without touching the RN storage; [run] is the normal entry point. */
    @Throws(LegacyImportError::class, SecureStoreException::class)
    fun migrate(legacy: LegacyImport, vault: KeyVault): LegacyMigrationReport =
        migrate(legacy, vault, PinLock.DEFAULT_ITERATIONS)

    @Throws(LegacyImportError::class, SecureStoreException::class)
    fun migrate(legacy: LegacyImport, vault: KeyVault, pinIterations: Int): LegacyMigrationReport {
        if (vault.legacyImported()) return done()
        val imported = mutableListOf<String>()
        val kept = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val written = mutableMapOf<String, VaultAccount>()

        for (account in legacy.accounts) {
            val converted = account.toVaultAccount()
            if (converted == null || !converted.hasCredential) {
                skipped += account.username
                continue
            }
            val existing = try {
                vault.account(converted.username)
            } catch (_: SecureStoreException.Corrupted) {
                null // an unreadable vault entry is worth less than the RN copy
            }
            if (existing != null || converted.username in imported) {
                kept += converted.username
            } else {
                // the RN session first: the account entry is what marks this account done. A key account without a
                // refresh token is marked expired (1), so its first renewal signs it a new session.
                if (!converted.accessToken.isNullOrEmpty()) {
                    val keyAccount = converted.authType?.loginMethod() == LoginMethod.KEY
                    when {
                        account.refreshToken != null ->
                            VaultAccountStore.putSession(vault.store, converted.username, account.refreshToken, account.tokenExpiresAt)
                        keyAccount -> VaultAccountStore.putSession(vault.store, converted.username, null, 1)
                    }
                }
                vault.save(converted)
                imported += converted.username
                written[converted.username] = converted
            }
        }

        if (vault.currentUsername() == null) {
            val current = legacy.currentUsername?.takeIf { it in imported || it in kept } ?: imported.firstOrNull()
            if (current != null) vault.setCurrentUsername(current)
        }
        // The RN lock carries over with the user's own PIN, as a verifier (never the PIN itself). Without a
        // usable PIN the lock stays off: a lock nobody can open would only end in a wipe.
        val pinLock = PinLock(vault, pinIterations)
        var pinLockDropped = false
        if (legacy.pinLockEnabled && !pinLock.hasPin()) {
            val pin = legacy.unlockPin?.value?.takeIf(PinLock::isValidPin)
            if (pin != null) {
                pinLock.setPin(pin)
                // RN's biometric unlock carries over unbound: offered only after the first correct PIN binds it
                if (legacy.biometricEnabled) vault.setBiometricUnlockEnabled(true)
            } else {
                pinLockDropped = true
            }
        }

        val missing = (imported + kept).filter { username ->
            val stored = try {
                vault.account(username)
            } catch (_: SecureStoreException.Corrupted) {
                null
            }
            stored == null || (username in written && stored != written[username])
        }
        if (missing.isNotEmpty()) throw LegacyImportError.NotVerified(missing)
        vault.markLegacyImported()

        return LegacyMigrationReport(
            imported = imported,
            kept = kept.distinct(),
            skipped = skipped,
            unreadable = legacy.accounts.filter { it.unreadable.isNotEmpty() }.associate { it.username to it.unreadable },
            alreadyDone = false,
            pinLockDropped = pinLockDropped,
        )
    }

    private fun done() = LegacyMigrationReport(emptyList(), emptyList(), emptyList(), emptyMap(), alreadyDone = true)

    private fun LegacyAccount.toVaultAccount(): VaultAccount? {
        if (!KeyVault.isValidUsername(username)) return null
        // RN stored the master password and the owner key; neither comes along. An RN entry that has the
        // password but lacks one of the three keys it derives gets that key derived here, so the account works.
        return VaultAccount(
            username = username,
            authType = authType?.toAuthType() ?: inferAuthType(),
            postingKey = postingKey,
            activeKey = activeKey,
            memoKey = memoKey,
            accessToken = accessToken,
            hiveAuthKey = hiveAuthKey,
            hiveAuthToken = hiveAuthToken,
            hiveAuthExpiry = hiveAuthExpiry,
        ).withKeysDerivedFrom(masterPassword)
    }

    private fun LegacyAuthType.toAuthType() = when (this) {
        LegacyAuthType.MASTER_KEY -> AuthType.MASTER_PASSWORD
        LegacyAuthType.POSTING_KEY -> AuthType.POSTING_KEY
        LegacyAuthType.ACTIVE_KEY -> AuthType.ACTIVE_KEY
        LegacyAuthType.MEMO_KEY -> AuthType.MEMO_KEY
        LegacyAuthType.OWNER_KEY -> AuthType.OWNER_KEY
        LegacyAuthType.HIVE_SIGNER -> AuthType.HIVE_SIGNER
        LegacyAuthType.HIVE_AUTH -> AuthType.HIVE_AUTH
    }

    /** RN data without a known auth type: name it after the strongest credential that survived. */
    private fun LegacyAccount.inferAuthType(): AuthType? = when {
        masterPassword != null -> AuthType.MASTER_PASSWORD
        ownerKey != null -> AuthType.OWNER_KEY
        activeKey != null -> AuthType.ACTIVE_KEY
        postingKey != null -> AuthType.POSTING_KEY
        hiveAuthToken != null -> AuthType.HIVE_AUTH
        accessToken != null -> AuthType.HIVE_SIGNER
        memoKey != null -> AuthType.MEMO_KEY
        else -> null
    }
}
