package com.ecency.core.legacy

import com.ecency.core.auth.LoginMethod
import com.ecency.core.auth.VaultAccountStore
import com.ecency.core.auth.loginMethod
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.SecureStoreException
import com.ecency.core.secure.VaultAccount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

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
    /** True when a previous run had finished: this one only settled kept RN entries ([imported] lists any it wrote). */
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
        // accounts whose RN secrets must stay
        val keep: Set<String>
        // accounts the user removed in this app (forget): never imported again
        val removed = names(vault, REMOVED)
        val firstRun = !vault.legacyImported()
        if (!firstRun) {
            // without both lists a kept or removed entry cannot be told from the rest: import and delete nothing
            val keptBefore = names(vault, KEPT)
            if (keptBefore == null || removed == null) return done()
            val legacy = try {
                LegacyImporter.import(storage::read, pinKey, defaultPin)
            } catch (_: LegacyImportError.NothingToImport) {
                null
            } catch (_: LegacyImportError) {
                return done() // cannot tell what the vault lacks: delete nothing
            }
            if (legacy == null) {
                report = done()
                keep = emptySet()
            } else {
                // entries kept by an earlier run that read now go into the vault before their RN copy may go;
                // other RN accounts were settled before (or came after the import: README "Rollback")
                val settled = settle(legacy, vault, only = keptBefore, removed)
                report = done(settled.imported)
                keep = settled.keep
            }
        } else {
            // unknown removals could bring a removed account back: retry on the next launch
            if (removed == null) throw LegacyImportError.StorageUnreadable()
            val legacy = try {
                LegacyImporter.import(storage::read, pinKey, defaultPin)
            } catch (_: LegacyImportError.NothingToImport) {
                null
            }
            if (legacy == null) {
                report = LegacyMigrationReport(emptyList(), emptyList(), emptyList(), emptyMap(), alreadyDone = false)
                keep = emptySet()
            } else {
                report = importAccounts(legacy, vault, pinIterations, removed)
                keep = settle(legacy, vault, only = null, removed).keep
            }
        }
        // the next run must know which entries are kept, or it could not tell them from accounts removed since;
        // the first run writes it before the mark, so a run killed in between repeats the import instead of guessing
        if (!recordKept(vault, keep)) return report
        if (firstRun) vault.markLegacyImported()
        // The mark is set, so the vault holds what it should: the RN copies of the secrets it holds can go.
        val scrubbed = try {
            LegacyCleanup.hasSecrets(storage, keep) && LegacyCleanup.scrub(storage, keep)
        } catch (_: LegacyImportError) {
            false // unreadable right now; the next run retries
        }
        return report.withRemoved(scrubbed)
    }

    private class Settled(val imported: List<String>, val keep: Set<String>)

    /**
     * Which RN entries must stay: an entry leaves only once the vault holds its keys, read back. An account the
     * vault lacks is written first (with its RN session), and so are keys a vault key account lacks; a key the
     * vault holds with another value is the vault's, and the RN entry then stays. A failed write keeps the entry.
     */
    private fun settle(legacy: LegacyImport, vault: KeyVault, only: Set<String>?, removed: Set<String>): Settled {
        val imported = mutableListOf<String>()
        val keep = mutableSetOf<String>()
        for (account in legacy.accounts) {
            // removed by the user: neither imported nor kept, so its RN entry goes
            if (account.username in removed) continue
            val converted = account.toVaultAccount()
            if (converted == null || account.mustKeep()) {
                keep += account.username
                continue
            }
            if (only != null && account.username !in only) continue
            val safe = try {
                val existing = try {
                    vault.account(converted.username)
                } catch (_: SecureStoreException.Corrupted) {
                    null
                }
                val target = if (existing == null) converted else existing.withKeysOf(converted)
                if (target != null && target != existing) {
                    if (existing == null) saveSession(vault, account, converted)
                    vault.save(target)
                }
                val held = vault.account(converted.username)?.holdsKeysOf(converted) == true
                if (held && existing == null) imported += converted.username
                held
            } catch (_: Exception) {
                false // the vault did not take it: the RN copy stays for the next run
            }
            if (!safe) keep += account.username
        }
        return Settled(imported, keep)
    }

    /**
     * The user removed [names] in this app (sign-out): their RN entries are never imported again and are deleted.
     * Call it before the vault accounts go. The names are recorded in the vault (`legacy.removed`, which every
     * later run honours) and the RN entries are deleted now; either one is enough, so it throws only when both
     * failed, and the caller then removes nothing.
     */
    @Throws(SecureStoreException::class, LegacyImportError::class)
    fun forget(vault: KeyVault, storage: LegacyStorage?, names: Collection<String>) {
        val recorded = runCatching {
            vault.store.withLock {
                val before = names(vault, REMOVED) ?: throw SecureStoreException.Corrupted(REMOVED)
                if (!before.containsAll(names)) writeNames(vault, REMOVED, before + names)
            }
        }
        val scrubbed = runCatching { storage?.let { LegacyCleanup.forget(it, names.toSet()) } }
        if (scrubbed.isFailure) recorded.exceptionOrNull()?.let { throw it }
    }

    private const val KEPT = "legacy.kept"
    private const val REMOVED = "legacy.removed"

    /** A list of usernames in the vault: empty when absent, null when it cannot be read (then nothing may be deleted). */
    private fun names(vault: KeyVault, entry: String): Set<String>? = try {
        vault.store.getString(entry)?.let { raw ->
            (Json.parseToJsonElement(raw) as JsonArray).map { e -> (e as JsonPrimitive).also { check(it.isString) }.content }.toSet()
        } ?: emptySet()
    } catch (_: Exception) {
        null
    }

    private fun writeNames(vault: KeyVault, entry: String, names: Set<String>) =
        vault.store.putString(entry, JsonArray(names.sorted().map(::JsonPrimitive)).toString())

    /** False when it could not be written: nothing is deleted then. */
    private fun recordKept(vault: KeyVault, keep: Set<String>): Boolean = try {
        if (keep != names(vault, KEPT)) writeNames(vault, KEPT, keep)
        true
    } catch (_: Exception) {
        false
    }

    private fun VaultAccount.keyPairs(rn: VaultAccount) = listOf(postingKey to rn.postingKey, activeKey to rn.activeKey, memoKey to rn.memoKey)

    /** Every key [rn] holds is in this account with the same value. */
    private fun VaultAccount.holdsKeysOf(rn: VaultAccount) = keyPairs(rn).all { (mine, theirs) -> theirs.isNullOrEmpty() || mine == theirs }

    /** This key account with the keys it lacks taken from [rn]; null when a key differs or either is not a key account. */
    private fun VaultAccount.withKeysOf(rn: VaultAccount): VaultAccount? {
        if (holdsKeysOf(rn)) return this
        if (authType?.loginMethod() != LoginMethod.KEY || rn.authType?.loginMethod() != LoginMethod.KEY) return null
        if (keyPairs(rn).any { (mine, theirs) -> !mine.isNullOrEmpty() && !theirs.isNullOrEmpty() && mine != theirs }) return null
        return copy(
            postingKey = postingKey?.ifEmpty { null } ?: rn.postingKey,
            activeKey = activeKey?.ifEmpty { null } ?: rn.activeKey,
            memoKey = memoKey?.ifEmpty { null } ?: rn.memoKey,
        )
    }

    /**
     * The RN session, written before the account entry (that entry is what marks the account done). A key account
     * without a refresh token is marked expired (1), so its first renewal signs it a new session.
     */
    private fun saveSession(vault: KeyVault, account: LegacyAccount, converted: VaultAccount) {
        if (converted.accessToken.isNullOrEmpty()) return
        when {
            account.refreshToken != null ->
                VaultAccountStore.putSession(vault.store, converted.username, account.refreshToken, account.tokenExpiresAt)
            converted.authType?.loginMethod() == LoginMethod.KEY -> VaultAccountStore.putSession(vault.store, converted.username, null, 1)
        }
    }

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
        val removed = names(vault, REMOVED) ?: throw LegacyImportError.StorageUnreadable()
        return importAccounts(legacy, vault, pinIterations, removed).also { vault.markLegacyImported() }
    }

    /** [migrate] without the mark; [removed] accounts are left out. */
    private fun importAccounts(legacy: LegacyImport, vault: KeyVault, pinIterations: Int, removed: Set<String>): LegacyMigrationReport {
        val imported = mutableListOf<String>()
        val kept = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val written = mutableMapOf<String, VaultAccount>()

        for (account in legacy.accounts) {
            if (account.username in removed) continue
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
                saveSession(vault, account, converted)
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

        return LegacyMigrationReport(
            imported = imported,
            kept = kept.distinct(),
            skipped = skipped,
            unreadable = legacy.accounts.filter { it.unreadable.isNotEmpty() }.associate { it.username to it.unreadable },
            alreadyDone = false,
            pinLockDropped = pinLockDropped,
        )
    }

    private fun done(imported: List<String> = emptyList()) =
        LegacyMigrationReport(imported, emptyList(), emptyList(), emptyMap(), alreadyDone = true)

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
