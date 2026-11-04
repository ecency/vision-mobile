package com.ecency.core.auth

import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault

/**
 * What a key login stores: the auth type and the private keys (WIF) of the posting, active and memo roles the
 * secret is good for. Never an owner key, and never the master password: a password is only the input the keys
 * are derived from. Never prints a secret.
 */
class KeyLoginResult internal constructor(
    val username: String,
    val authType: AuthType,
    /** WIF per role the app keeps for this account: posting, active, memo; never owner. */
    val keys: Map<KeyRole, String>,
    /** The secret was a master password (nothing of it is kept). */
    val isMasterPassword: Boolean,
    /** Roles of the account the entered secret matched on chain and can sign for alone (owner included, for information). */
    val matchedRoles: Set<KeyRole>,
    /** The role whose key signs the Ecency session code: posting when it matched, else active. */
    val signingRole: KeyRole,
) {
    fun signingKey(): PrivateKey = PrivateKey.fromWif(keys.getValue(signingRole))

    /** A typed private key that is also the account's owner key: kept for its other role, with a warning. */
    val sharesOwnerKey: Boolean get() = !isMasterPassword && KeyRole.OWNER in matchedRoles

    /**
     * For a master password: which of posting and active it did not give, because the account's key for that
     * role is no longer the one the password derives (or cannot sign alone). Empty for a private key.
     */
    val missingSigningRoles: Set<KeyRole>
        get() = if (isMasterPassword) setOf(KeyRole.POSTING, KeyRole.ACTIVE) - keys.keys else emptySet()

    override fun toString(): String =
        "KeyLoginResult(username=$username, authType=$authType, keys=${keys.keys}, secrets=<redacted>)"
}

/**
 * Key login, ported from RN `providers/hive/auth.ts` (login, getPrivateKeys) and the login screen:
 *
 * - the username is trimmed, leading `@` removed and lowercased (login screen);
 * - the secret goes through [KeyInput.secret] (spaces, newlines, NBSP and zero-width characters from a copy);
 * - the secret is a WIF private key when it decodes as one, otherwise a master password from which the
 *   owner, active, posting and memo keys derive as sha256(username + role + password);
 * - each derived public key is checked against the account's on-chain key_auths (memo_key for memo);
 * - the auth type is MASTER_PASSWORD for a master password (it names how the account signed in; no password is
 *   kept), otherwise the role that matched, the last one in RN's order (active, memo, posting).
 *
 * Deliberate differences from RN, all stricter or safer:
 * - a key that matches only the memo key is refused with [AuthException.MemoKeyOnly] (RN signs the Ecency
 *   code with it and fails later at the token exchange);
 * - a key login keeps every role the key matched (RN keeps only the auth type's role);
 * - the owner key is never kept and the master password is never kept (RN stores both). A master password
 *   keeps the posting, active and memo keys it was verified for on chain (RN stores all four derived keys
 *   unverified). A secret that only gives the owner key is refused with [AuthException.OwnerKeyOnly] (RN signs
 *   in with an owner key);
 * - the session code is signed with the posting key when it matched, else the active key: the least powerful
 *   key that works (RN uses the owner key);
 * - a secret that looks like a WIF but fails its checksum is [AuthException.InvalidKey], not "wrong password";
 * - a key counts for a role only when its weight reaches the role's `weight_threshold` by itself; a key that is
 *   on the account but cannot sign alone is refused with [AuthException.KeyBelowThreshold] (RN stores it and
 *   every broadcast then fails on chain).
 */
object KeyLogin {
    private val RN_ROLE_ORDER = listOf(KeyRole.ACTIVE, KeyRole.MEMO, KeyRole.OWNER, KeyRole.POSTING)
    /** The Ecency session code is signed with the least powerful key that works: posting, else active. */
    private val SIGNING_ORDER = listOf(KeyRole.POSTING, KeyRole.ACTIVE)

    /**
     * The one switch for a typed private key that is the account's owner key and also its active or posting key
     * (the account uses one key for several roles). False: it is kept for the non-owner role and the user is
     * warned ([KeyLoginResult.sharesOwnerKey]). True: it is refused with [AuthException.KeySharedWithOwner].
     */
    var refuseKeySharedWithOwner: Boolean = false

    /** The roles whose private key the app may keep. */
    val STORABLE = setOf(KeyRole.POSTING, KeyRole.ACTIVE, KeyRole.MEMO)
    private val WIF_SHAPE = Regex("^5[1-9A-HJ-NP-Za-km-z]{50}$")

    /** The login screen's username normalization; the edges also lose NBSP and zero-width characters ([KeyInput]). */
    fun normalizeUsername(input: String): String = KeyInput.edges(input).replace(Regex("^@+"), "").lowercase()

    /** The login form's button rule (RN: both fields at least 2 characters), counted after normalising. */
    fun formFilled(usernameInput: String, secret: String): Boolean =
        KeyInput.edges(usernameInput).length >= 2 && KeyInput.secret(secret).length >= 2

    /** Shaped like a WIF private key (5 and 50 base58 characters), whether or not its checksum holds. */
    fun looksLikeWif(input: String): Boolean = WIF_SHAPE.matches(KeyInput.secret(input))

    private val PUBLIC_KEY_SHAPE = Regex("^STM[1-9A-HJ-NP-Za-km-z]{45,60}$")

    /**
     * True when [input] is a Hive public key: STM, base58, and a valid RIPEMD-160 checksum, so a master password
     * that happens to start with STM is still read as a password (review L10). The keys screen shows and copies
     * public keys, so users paste them.
     */
    fun isPublicKey(input: String): Boolean =
        KeyInput.secret(input).let { PUBLIC_KEY_SHAPE.matches(it) && runCatching { com.ecency.core.hive.PublicKey.fromString(it) }.isSuccess }

    /** The roles of [chain] whose authority lists [publicKey], owner, active, posting, memo order. */
    fun rolesOf(publicKey: String, chain: ChainAuthorities): List<KeyRole> =
        listOf(KeyRole.OWNER, KeyRole.ACTIVE, KeyRole.POSTING, KeyRole.MEMO).filter { publicKey in chain.keysFor(it) }

    /** True when [secret] decodes as a WIF private key with a valid checksum. */
    fun isWif(secret: String): Boolean = runCatching { PrivateKey.fromWif(KeyInput.secret(secret)) }.isSuccess

    /** The four role keys of a master password (or the same key four times for a WIF), RN getPrivateKeys. */
    internal fun privateKeys(username: String, secret: String): Pair<Map<KeyRole, PrivateKey>, Boolean> {
        val wif = runCatching { PrivateKey.fromWif(secret) }.getOrNull()
        return if (wif != null) {
            KeyRole.entries.associateWith { wif } to false
        } else {
            KeyRole.entries.associateWith { PrivateKey.fromLogin(username, secret, it) } to true
        }
    }

    /**
     * Classifies [secret] against [chain] (the account's current authorities).
     * @throws AuthException InvalidUsername, InvalidKey, WrongPassword, KeyNotOnAccount or MemoKeyOnly.
     */
    @Throws(AuthException::class)
    fun classify(usernameInput: String, secret: String, chain: ChainAuthorities): KeyLoginResult {
        val username = normalizeUsername(usernameInput)
        if (!KeyVault.isValidUsername(username)) throw AuthException.InvalidUsername()
        val cleaned = KeyInput.secret(secret)
        if (cleaned.isEmpty()) throw AuthException.WrongPassword()
        if (isPublicKey(cleaned)) throw AuthException.PublicKeyGiven()
        val (keys, isMaster) = privateKeys(username, cleaned)

        val onAccount = RN_ROLE_ORDER.filter { role -> keys.getValue(role).publicKey().toString() in chain.keysFor(role) }
        // a role counts only when the key satisfies its authority alone (weight at or above the threshold)
        val matched = onAccount.filter { role -> chain.canSignAlone(role, keys.getValue(role).publicKey().toString()) }
        if (matched.isEmpty()) {
            throw when {
                onAccount.isNotEmpty() -> AuthException.KeyBelowThreshold()
                isMaster && WIF_SHAPE.matches(cleaned) -> AuthException.InvalidKey()
                isMaster -> AuthException.WrongPassword()
                else -> AuthException.KeyNotOnAccount()
            }
        }
        // The owner key is never kept, whether it was typed or derived. What is left to keep are the posting,
        // active and memo roles the secret matched; a master password stores exactly those it was verified
        // for, nothing it merely derives.
        val storable = matched.filter { it in STORABLE }
        if (storable.isEmpty()) throw AuthException.OwnerKeyOnly(fromMasterPassword = isMaster)
        // a typed key that is also the owner key (a master password derives a different key per role)
        if (!isMaster && KeyRole.OWNER in matched && refuseKeySharedWithOwner) throw AuthException.KeySharedWithOwner()
        val signingRole = SIGNING_ORDER.firstOrNull { it in storable } ?: throw AuthException.MemoKeyOnly()

        val authType = if (isMaster) AuthType.MASTER_PASSWORD else when (storable.last()) {
            KeyRole.ACTIVE -> AuthType.ACTIVE_KEY
            KeyRole.MEMO -> AuthType.MEMO_KEY
            else -> AuthType.POSTING_KEY
        }
        return KeyLoginResult(
            username = username,
            authType = authType,
            keys = storable.associateWith { keys.getValue(it).toWif() },
            isMasterPassword = isMaster,
            matchedRoles = matched.toSet(),
            signingRole = signingRole,
        )
    }

    /**
     * RN getUpdatedUserKeys (the keys screen's "Import Private"): [secret] against [chain], without a session to
     * sign for, so a key that only matches the memo key is accepted here. The stored roles follow [classify]:
     * posting, active and memo, never owner. [KeyLoginResult.signingRole] is not used by the import.
     * @throws AuthException InvalidUsername, InvalidKey, WrongPassword, KeyNotOnAccount, KeyBelowThreshold or
     *   OwnerKeyOnly.
     */
    @Throws(AuthException::class)
    fun matchForImport(usernameInput: String, secret: String, chain: ChainAuthorities): KeyLoginResult = try {
        classify(usernameInput, secret, chain)
    } catch (_: AuthException.MemoKeyOnly) {
        // only the memo key matched (of the roles the app keeps): it is kept for that role
        val username = normalizeUsername(usernameInput)
        val cleaned = KeyInput.secret(secret)
        val (keys, isMaster) = privateKeys(username, cleaned)
        KeyLoginResult(
            username = username,
            authType = AuthType.MEMO_KEY,
            keys = mapOf(KeyRole.MEMO to keys.getValue(KeyRole.MEMO).toWif()),
            isMasterPassword = isMaster,
            matchedRoles = setOf(KeyRole.MEMO),
            signingRole = KeyRole.MEMO,
        )
    }

    /**
     * Whether [wif] is one of [role]'s keys on chain: the check behind the auth upgrade's key entry.
     */
    fun keyMatchesRole(wif: String, role: KeyRole, chain: ChainAuthorities): Boolean {
        val key = runCatching { PrivateKey.fromWif(KeyInput.secret(wif)) }.getOrNull() ?: return false
        return chain.canSignAlone(role, key.publicKey().toString())
    }
}
