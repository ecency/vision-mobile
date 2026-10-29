package com.ecency.core.legacy

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
 * ([LegacyCleanup]); if that fails it is retried on the next run.
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
        val report = if (vault.legacyImported()) {
            done()
        } else {
            try {
                migrate(LegacyImporter.import(storage::read, pinKey, defaultPin), vault, pinIterations)
            } catch (_: LegacyImportError.NothingToImport) {
                vault.markLegacyImported()
                LegacyMigrationReport(emptyList(), emptyList(), emptyList(), emptyMap(), alreadyDone = false)
            }
        }
        // The mark is set, so the vault holds what it should: the RN copies of the secrets can go.
        val removed = try {
            LegacyCleanup.hasSecrets(storage) && LegacyCleanup.scrub(storage)
        } catch (_: LegacyImportError) {
            false // unreadable right now; the next run retries
        }
        return report.withRemoved(removed)
    }

    /**
     * Gives up on an import that keeps failing the same way (the build's `PIN_KEY` does not open this data, or the
     * data is malformed): marks the import done, so it is never tried again, then deletes the RN secrets, which
     * nothing can use any more (going back to an RN build is not supported; README "In-place upgrade from RN 3.x").
     * A cleanup that fails now is retried by every later [run], as after a normal import.
     * @return true when RN secrets were removed now.
     */
    @Throws(SecureStoreException::class)
    fun abandon(vault: KeyVault, storage: LegacyStorage): Boolean {
        vault.markLegacyImported()
        return try {
            LegacyCleanup.hasSecrets(storage) && LegacyCleanup.scrub(storage)
        } catch (_: LegacyImportError) {
            false
        }
    }

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
