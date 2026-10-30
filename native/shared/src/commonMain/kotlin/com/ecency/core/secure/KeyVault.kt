package com.ecency.core.secure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** How an account signs in. [id] is what the vault stores; never rename it. */
enum class AuthType(val id: String) {
    MASTER_PASSWORD("master"),
    POSTING_KEY("posting"),
    ACTIVE_KEY("active"),
    MEMO_KEY("memo"),
    OWNER_KEY("owner"),
    HIVE_SIGNER("hivesigner"),
    HIVE_AUTH("hiveauth"),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * One signed-in account and every credential the app holds for it. Keys are WIF strings. Never prints its
 * secrets.
 *
 * The most the app stores is the active key, with the posting and memo keys. It never stores the owner key
 * and never stores the master password: this type has no field for either, so no code path can write one.
 * A master password is only ever an input: its posting, active and memo keys are derived, checked and kept,
 * the password and the owner key it derives are let go ([com.ecency.core.auth.KeyLogin]). Entries written by
 * earlier builds that hold one are cleaned by [KeyVault.dropRetiredSecrets].
 */
data class VaultAccount(
    val username: String,
    val authType: AuthType?,
    val postingKey: String? = null,
    val activeKey: String? = null,
    val memoKey: String? = null,
    /** HiveSigner access token for HIVE_SIGNER accounts, the Ecency API token for key accounts. */
    val accessToken: String? = null,
    val hiveAuthKey: String? = null,
    val hiveAuthToken: String? = null,
    /** HiveAuth session expiry, epoch milliseconds; 0 when there is no session. */
    val hiveAuthExpiry: Long = 0,
) {
    /** Constructing never throws; [KeyVault.save] rejects an invalid [username] with a typed error. */
    val hasValidUsername: Boolean get() = KeyVault.isValidUsername(username)

    /** True when the account can still sign or authenticate without the user signing in again. */
    val hasCredential: Boolean
        get() = listOf(postingKey, activeKey, memoKey, accessToken, hiveAuthToken)
            .any { !it.isNullOrEmpty() }

    /** Signed in with a key or master password (not HiveSigner, not HiveAuth). */
    private val isKeyAccount: Boolean
        get() = authType != null && authType != AuthType.HIVE_SIGNER && authType != AuthType.HIVE_AUTH

    /**
     * The posting key another app may be handed (`ecency://login`), or null. Read from the keys the account
     * holds, not from its auth type: a key account with a stored posting key that is not also its active key
     * (one WIF can sit in several roles; handing that out would hand out the stronger role).
     */
    val shareablePostingKey: String?
        get() = postingKey?.takeIf { isKeyAccount && it.isNotEmpty() && it != activeKey }

    /**
     * This account with the posting, active and memo keys [masterPassword] derives filled in where it holds
     * none (keys it already holds are kept). Derivation needs no network; whether a derived key is still one of
     * the account's keys is the chain's to say when it is used. The password itself goes nowhere.
     */
    internal fun withKeysDerivedFrom(masterPassword: String?): VaultAccount {
        if (masterPassword.isNullOrEmpty() || !hasValidUsername) return this
        fun derived(role: com.ecency.core.hive.KeyRole) =
            runCatching { com.ecency.core.hive.PrivateKey.fromLogin(username, masterPassword, role).toWif() }.getOrNull()
        return copy(
            postingKey = postingKey?.takeIf { it.isNotEmpty() } ?: derived(com.ecency.core.hive.KeyRole.POSTING),
            activeKey = activeKey?.takeIf { it.isNotEmpty() } ?: derived(com.ecency.core.hive.KeyRole.ACTIVE),
            memoKey = memoKey?.takeIf { it.isNotEmpty() } ?: derived(com.ecency.core.hive.KeyRole.MEMO),
        )
    }

    /** A key account (not HiveSigner, not HiveAuth) that holds neither a posting nor an active key: it can sign nothing. */
    val isKeyAccountWithoutSigningKey: Boolean
        get() = isKeyAccount && postingKey.isNullOrEmpty() && activeKey.isNullOrEmpty()

    /** Uploads are signed with the posting key when a key account holds one; every other account sends its token. */
    val signsWithPostingKey: Boolean get() = isKeyAccount && !postingKey.isNullOrEmpty()

    override fun toString(): String = "VaultAccount(username=$username, authType=$authType, secrets=<redacted>)"
}

/**
 * The app's accounts and their credentials, kept in a [SecureStore]: one entry per account
 * ("account.<username>", JSON) plus "vault.meta" for the current account and migration state.
 *
 * Values are stored as they are used (WIF keys, tokens), protected by the device key of the store.
 * They are not additionally encrypted with the user's PIN; see native/README.md, "Secure storage".
 */
class KeyVault(store: SecureStore) {
    /**
     * Called when the store reports that its device key is gone ([SecureStoreException.KeyInvalidated]), on the
     * thread of the failing call. Set by the app's environment, which then asks the user to sign in again.
     */
    @kotlin.concurrent.Volatile
    internal var onKeyInvalidated: (() -> Unit)? = null

    internal val store: SecureStore = WatchedSecureStore(store) { onKeyInvalidated?.invoke() }

    @Throws(SecureStoreException::class)
    fun usernames(): List<String> =
        store.names().filter { it.startsWith(ACCOUNT_PREFIX) }.map { it.removePrefix(ACCOUNT_PREFIX) }
            .filter(::isValidUsername).sorted()

    /**
     * The stored account, or null when there is none or [username] is not a valid Hive name.
     * @throws SecureStoreException.Corrupted when the stored entry is not a readable account.
     */
    @Throws(SecureStoreException::class)
    fun account(username: String): VaultAccount? {
        if (!isValidUsername(username)) return null
        val name = ACCOUNT_PREFIX + username
        val raw = store.getString(name) ?: return null
        val account = runCatching { decode(json.parseToJsonElement(raw).jsonObject) }
            .getOrElse { throw SecureStoreException.Corrupted(name, it) }
        if (account.username != username) throw SecureStoreException.Corrupted(name)
        return account
    }

    /** Every readable account. Unreadable entries are skipped here and listed by [unreadableUsernames]. */
    @Throws(SecureStoreException::class)
    fun accounts(): List<VaultAccount> = usernames().mapNotNull { readable(it).getOrNull() }

    /** Accounts whose entry exists but cannot be read; the app should ask them to sign in again. */
    @Throws(SecureStoreException::class)
    fun unreadableUsernames(): List<String> = usernames().filter { readable(it).isFailure }

    /** Corrupted entries become failures; anything else (lost device key, platform error) propagates. */
    private fun readable(username: String): Result<VaultAccount?> = try {
        Result.success(account(username))
    } catch (e: SecureStoreException.Corrupted) {
        Result.failure(e)
    }

    /**
     * Rewrites every account entry an earlier build wrote with an owner key or a master password, without them.
     * Posting, active and memo keys, tokens and the auth type stay exactly as they are. An entry that holds a
     * master password and lacks one of those three keys gets the missing ones derived from it first (offline;
     * see [VaultAccount.withKeysDerivedFrom]), in the same single write that drops the password, so there is no
     * moment in which the password is gone and the keys are not there. An entry that cannot be read or written
     * is left alone and does not stop the pass; only a lost device key does ([SecureStoreException.KeyInvalidated]).
     * Safe to run at every start: a clean vault is not written to. Returns the accounts it rewrote.
     */
    @Throws(SecureStoreException::class)
    fun dropRetiredSecrets(): List<String> = store.withLock {
        store.names().filter { it.startsWith(ACCOUNT_PREFIX) }.mapNotNull { name ->
            // One entry that cannot be read or written (corrupted, or the store failing for that call) is left for
            // the next start and never keeps the entries after it from being cleaned. A lost device key is not
            // an entry's problem: it ends the pass, and the app's start handles it as everywhere else.
            try {
                val raw = store.getString(name) ?: return@mapNotNull null
                val o = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@mapNotNull null
                if (RETIRED_FIELDS.none { it in o }) return@mapNotNull null
                // decode already fills in what a stored master password derives
                val account = runCatching { decode(o) }.getOrNull() ?: return@mapNotNull null
                store.putString(name, encode(account).toString())
                account.username
            } catch (e: SecureStoreException.KeyInvalidated) {
                throw e
            } catch (e: SecureStoreException) {
                null
            }
        }
    }

    /** @throws SecureStoreException.InvalidArgument when the username is not a valid Hive name. */
    @Throws(SecureStoreException::class)
    fun save(account: VaultAccount) {
        if (!account.hasValidUsername) throw SecureStoreException.InvalidArgument("invalid Hive username")
        store.putString(ACCOUNT_PREFIX + account.username, encode(account).toString())
    }

    /**
     * Removes the account. When it was the current one, [currentUsername] is cleared first, so a failure can
     * only leave an account that is no longer current, never a deleted account reported as a failure.
     * An invalid or unknown username is a no-op.
     */
    @Throws(SecureStoreException::class)
    fun remove(username: String) {
        if (!isValidUsername(username)) return
        store.withLock {
            val meta = meta()
            if (meta.current == username) writeMeta(meta.copy(current = null))
            store.delete(ACCOUNT_PREFIX + username)
        }
    }

    /** The account the app acts as, or null. */
    @Throws(SecureStoreException::class)
    fun currentUsername(): String? = meta().current

    /** @throws SecureStoreException.InvalidArgument for an account the vault does not hold. */
    @Throws(SecureStoreException::class)
    fun setCurrentUsername(username: String?) = store.withLock {
        if (username != null && account(username) == null) throw SecureStoreException.InvalidArgument("unknown account")
        writeMeta(meta().copy(current = username))
    }

    @Throws(SecureStoreException::class)
    fun pinLockEnabled(): Boolean = meta().pinLock

    @Throws(SecureStoreException::class)
    fun setPinLockEnabled(enabled: Boolean) = store.withLock { writeMeta(meta().copy(pinLock = enabled)) }

    /** Whether biometric unlock is on; it only matters while the PIN lock is on ([PinLock]). */
    @Throws(SecureStoreException::class)
    fun biometricUnlockEnabled(): Boolean = meta().biometric

    @Throws(SecureStoreException::class)
    internal fun setBiometricUnlockEnabled(enabled: Boolean) = store.withLock { writeMeta(meta().copy(biometric = enabled)) }

    // PIN lock entries, used only by PinLock. Separate from the meta so a counter write never rewrites it.
    internal fun <T> withLock(block: () -> T): T = store.withLock(block)

    /**
     * The encoded verifier; null only when there is none. An entry that exists but cannot be read comes back
     * as [UNREADABLE_VERIFIER] (no valid verifier), so the lock stays on (fails closed, [PinLock]).
     */
    internal fun pinVerifier(): String? = try {
        store.getString(PIN_VERIFIER)
    } catch (_: SecureStoreException.Corrupted) {
        UNREADABLE_VERIFIER
    }

    internal fun setPinVerifier(encoded: String?) =
        if (encoded == null) store.delete(PIN_VERIFIER) else store.putString(PIN_VERIFIER, encoded)

    /** The stored failure count; null when absent or unreadable (PinLock decides what that means). */
    internal fun pinFailures(): Int? = try {
        store.getString(PIN_FAILURES)?.toIntOrNull()?.takeIf { it >= 0 }
    } catch (_: SecureStoreException.Corrupted) {
        null
    }

    /** The biometric binding token ([BiometricBinder]); null when absent or unreadable. */
    internal fun biometricBinding(): String? = try {
        store.getString(PIN_BIOMETRIC)
    } catch (_: SecureStoreException.Corrupted) {
        null
    }

    internal fun setBiometricBinding(binding: String?) =
        if (binding == null) store.delete(PIN_BIOMETRIC) else store.putString(PIN_BIOMETRIC, binding)

    internal fun setPinFailures(count: Int?) =
        if (count == null) store.delete(PIN_FAILURES) else store.putString(PIN_FAILURES, count.toString())

    /** Set once the RN app's accounts have been migrated (or there were none), so it never runs twice. */
    @Throws(SecureStoreException::class)
    fun legacyImported(): Boolean = meta().legacyImported

    @Throws(SecureStoreException::class)
    fun markLegacyImported() = store.withLock { writeMeta(meta().copy(legacyImported = true)) }

    /** Removes every account and all vault state. Only on the user's explicit choice. */
    @Throws(SecureStoreException::class)
    fun wipe() = store.wipe()

    private data class Meta(
        val current: String? = null,
        val pinLock: Boolean = false,
        val legacyImported: Boolean = false,
        val biometric: Boolean = false,
    )

    /**
     * Unreadable meta reads as defaults (no current account, flags off, not migrated) instead of blocking the
     * vault forever; the next write replaces it. The app lock does not depend on it: [PinLock] is on whenever
     * a verifier is stored. A repeated RN migration after that is harmless: accounts
     * already in the vault win, and the RN secrets are deleted after the first successful run.
     */
    private fun meta(): Meta {
        val raw = try {
            store.getString(META)
        } catch (_: SecureStoreException.Corrupted) {
            null
        } ?: return Meta()
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return Meta()
        return Meta(
            current = obj.string("current")?.takeIf(::isValidUsername),
            pinLock = obj.boolean("pinLock"),
            legacyImported = obj.boolean("legacyImported"),
            biometric = obj.boolean("biometric"),
        )
    }

    private fun writeMeta(next: Meta) = store.putString(
        META,
        buildJsonObject {
            put("v", FORMAT)
            put("current", next.current)
            put("pinLock", next.pinLock)
            put("legacyImported", next.legacyImported)
            put("biometric", next.biometric)
        }.toString(),
    )

    companion object {
        private const val FORMAT = 1
        private const val ACCOUNT_PREFIX = "account."
        private const val META = "vault.meta"
        private const val PIN_VERIFIER = "pin.verifier"

        /** Stands for a verifier entry that exists but is unreadable; never a valid encoding. */
        internal const val UNREADABLE_VERIFIER = "unreadable"
        private const val PIN_FAILURES = "pin.failures"
        private const val PIN_BIOMETRIC = "pin.biometric"
        private val USERNAME = Regex("^[a-z][a-z0-9.-]{2,15}$")
        private val json = Json { ignoreUnknownKeys = true }

        fun isValidUsername(username: String) = USERNAME.matches(username)

        /** Fields earlier builds wrote and this one never does. */
        internal const val RETIRED_MASTER_PASSWORD = "masterPassword"
        internal const val RETIRED_OWNER_KEY = "ownerKey"
        internal val RETIRED_FIELDS = listOf(RETIRED_MASTER_PASSWORD, RETIRED_OWNER_KEY)

        private fun encode(a: VaultAccount) = buildJsonObject {
            put("v", FORMAT)
            put("username", a.username)
            put("authType", a.authType?.id)
            // no owner key and no master password: there is nothing to write them from (see VaultAccount)
            put("postingKey", a.postingKey)
            put("activeKey", a.activeKey)
            put("memoKey", a.memoKey)
            put("accessToken", a.accessToken)
            put("hiveAuthKey", a.hiveAuthKey)
            put("hiveAuthToken", a.hiveAuthToken)
            put("hiveAuthExpiry", a.hiveAuthExpiry)
        }

        private fun decode(o: JsonObject) = VaultAccount(
            username = o.string("username").orEmpty(),
            authType = AuthType.fromId(o.string("authType")),
            postingKey = o.string("postingKey"),
            activeKey = o.string("activeKey"),
            memoKey = o.string("memoKey"),
            accessToken = o.string("accessToken"),
            hiveAuthKey = o.string("hiveAuthKey"),
            hiveAuthToken = o.string("hiveAuthToken"),
            hiveAuthExpiry = o["hiveAuthExpiry"]?.jsonPrimitive?.longOrNull ?: 0,
        ).also { require(it.hasValidUsername) }
            // An entry of an earlier build may hold a master password ("masterPassword") and an owner key
            // ("ownerKey"). Neither is ever handed out: the owner key is ignored, and the password is only
            // used here, to fill in the keys it derives when the entry lacks them, so whoever saves this
            // account next keeps the account usable although the password is gone.
            .withKeysDerivedFrom(o.string(RETIRED_MASTER_PASSWORD))

        private fun JsonObject.string(field: String): String? =
            runCatching { get(field)?.jsonPrimitive?.contentOrNull }.getOrNull()

        private fun JsonObject.boolean(field: String): Boolean =
            runCatching { get(field)?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
    }
}
