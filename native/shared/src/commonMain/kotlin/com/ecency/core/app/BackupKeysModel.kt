package com.ecency.core.app

import com.ecency.core.auth.ChainAuthorities
import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import com.ecency.core.l10n.L
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** RN en-US `settings.backup_keys_modal.*`, `settings.keys_warning`, `alert.*` as the keys screen uses them. */
object BackupKeysCopy {
    /** RN `settings.backup_private_keys`: the screen title and the settings row. */
    val TITLE: String get() = L.settings.backupPrivateKeys
    val WARNING: String get() = L.alert.warning
    val KEYS_WARNING: String get() = L.settings.keysWarning
    val SET_PIN: String get() = L.settings.setPin
    val CANCEL: String get() = L.alert.cancel
    val REVEAL_PUBLIC: String get() = L.settings.backupKeysModal.revealPublic
    val REVEAL_PRIVATE: String get() = L.settings.backupKeysModal.revealPrivate
    val IMPORT_KEY: String get() = L.settings.backupKeysModal.importKey
    val PRIVATE: String get() = L.settings.backupKeysModal.private
    val PUBLIC: String get() = L.settings.backupKeysModal.public
    val KEY_COPIED: String get() = L.settings.backupKeysModal.keyCopied
    val ENTER_PASS: String get() = L.settings.backupKeysModal.enterPass
    val IMPORT_DESC: String get() = L.settings.backupKeysModal.importKeyModalDesc
    val SIGN_PLACEHOLDER: String get() = L.settings.backupKeysModal.signInputPlaceholder
    val SIGN: String get() = L.settings.backupKeysModal.sign
    val IMPORT_FAILED: String get() = L.settings.backupKeysModal.importFailed

    /** Not RN. [roles]: the non-owner roles that use one of the owner authority's keys. */
    fun sharedOwner(roles: List<KeyRole>): String {
        return L.native.backupKeys.sharedOwner(names = CopyLists.and(roles.map(CopyLists::role)))
    }

    /** Not RN, which stores the owner key and offers to import it. */
    val OWNER_NOT_STORED: String get() = L.native.backupKeys.ownerNotStored

    /** RN appends `login.login_failed_body` to the failure. */
    val IMPORT_FAILED_TAIL: String get() = L.login.loginFailedBody

    /** Not in RN, which copies a shown private key without asking. */
    val COPY_WARNING: String get() = L.native.backupKeys.copyWarning
    val COPY: String get() = L.chats.copy

    fun title(role: KeyRole): String = when (role) {
        KeyRole.OWNER -> L.settings.backupKeysModal.ownerKey
        KeyRole.ACTIVE -> L.settings.backupKeysModal.activeKey
        KeyRole.POSTING -> L.settings.backupKeysModal.postingKey
        KeyRole.MEMO -> L.settings.backupKeysModal.memoKey
    }
}

/** One key of the screen (RN `_renderKey`). The private key is in [value] only while [revealed]. */
class KeyRow internal constructor(
    val role: KeyRole,
    /** The account's first key of this role on chain (RN `key_auths[0][0]`, `memo_key`); empty when unknown. */
    val publicKey: String,
    /** A private key for this role is stored on this device. */
    val hasPrivate: Boolean,
    val revealed: Boolean,
    private val privateKey: String?,
) {
    val title: String get() = BackupKeysCopy.title(role)

    /** RN: "Owner Key (Public)" or "Owner Key (Private)". */
    val label: String get() = "$title (${if (revealed) BackupKeysCopy.PRIVATE else BackupKeysCopy.PUBLIC})"

    /** What the box shows and the copy button copies. */
    val value: String get() = if (revealed) privateKey.orEmpty() else publicKey

    /** The app may hold this role's private key: posting, active, memo. The owner key is never stored. */
    val importable: Boolean get() = role != KeyRole.OWNER

    /** What the row says in place of a button when the role cannot be imported. */
    val note: String? get() = if (importable) null else BackupKeysCopy.OWNER_NOT_STORED

    /**
     * RN `_renderRevealBtn`: import when nothing is stored, else the reveal toggle. Empty for the owner row:
     * RN offers "Import Private" there too, this app does not (see [note]).
     */
    val button: String
        get() = when {
            !importable -> ""
            !hasPrivate -> BackupKeysCopy.IMPORT_KEY
            revealed -> BackupKeysCopy.REVEAL_PUBLIC
            else -> BackupKeysCopy.REVEAL_PRIVATE
        }

    /** The value on screen is a private key: copying it needs the warning and the expiring clipboard. */
    val isSensitive: Boolean get() = revealed && !privateKey.isNullOrEmpty()

    override fun equals(other: Any?): Boolean =
        other is KeyRow && other.role == role && other.publicKey == publicKey && other.hasPrivate == hasPrivate &&
            other.revealed == revealed && other.privateKey == privateKey

    override fun hashCode(): Int = role.hashCode() * 31 + publicKey.hashCode() + (if (revealed) 7 else 0) + (if (hasPrivate) 3 else 0)

    /** Never prints a key. */
    override fun toString(): String = "KeyRow(role=$role, hasPrivate=$hasPrivate, revealed=$revealed)"
}

enum class KeysPhase {
    /** Reading the lock state or waiting for the PIN: nothing of the account shows. */
    CHECKING,

    /** The lock is off: RN's keys_warning with Cancel and Set PIN. */
    NEEDS_PIN,

    /** The PIN (or biometrics) was confirmed: the keys show. */
    SHOWN,

    /** Over (cancelled, locked, backgrounded, another account): the UI leaves the screen. */
    CLOSED,
}

/** What the platform puts on the clipboard: [sensitive] goes through the expiring key clipboard. */
class KeyCopy internal constructor(val text: String, val sensitive: Boolean) {
    override fun toString(): String = "KeyCopy(sensitive=$sensitive)"
}

data class BackupKeysState(
    val phase: KeysPhase = KeysPhase.CHECKING,
    /** The account whose keys these are: the one signed in when the screen opened. */
    val account: String? = null,
    val rows: List<KeyRow> = emptyList(),
    /** RN's import modal is open. */
    val importOpen: Boolean = false,
    val isImporting: Boolean = false,
    /** RN's "Keys Import Failed" alert body. */
    val importError: String? = null,
    /** The role whose private key waits for the copy warning. */
    val copyAsks: KeyRole? = null,
    /** The account uses its owner key for another role too ([BackupKeysCopy.sharedOwner]); null otherwise. */
    val sharedOwnerWarning: String? = null,
) {
    val isShown: Boolean get() = phase == KeysPhase.SHOWN
}

/**
 * RN backupKeysScreen ("Manage Authorities") with its settings gate and import modal.
 *
 * Gate (RN settingsContainer BACKUP_PRIVATE_KEYS): with the lock on, the PIN pad first (biometrics when turned
 * on, as RN's PINCODE screen); with the lock off, RN's warning with Set PIN, and the keys right after the new
 * PIN was set. Rows as RN: the chain's first public key of each role, "Reveal Private" for a stored private key,
 * "Import Private" for a role without one.
 *
 * The native app's own rules: the keys belong to the account signed in when the screen opened and every action
 * checks that it still is; the screen closes ([KeysPhase.CLOSED], private keys dropped from memory) when the
 * app locks, leaves the foreground ([AppEvent.Backgrounded]), the account changes or the data is wiped. Private
 * keys never enter [toString], logs, toasts or saved state.
 */
class BackupKeysModel internal constructor(private val env: AppEnvironment) :
    StateModel<BackupKeysState>(BackupKeysState(), env.dispatchers) {

    private var owner: String? = null
    private var privateKeys: Map<KeyRole, String> = emptyMap()
    private var publicKeys: Map<KeyRole, String> = emptyMap()
    private var revealed: Set<KeyRole> = emptySet()
    private var gate: Job? = null
    private var started = false

    /** Counted in [AppEnvironment.keysScreens] (from [start] until [shut]). Main thread only. */
    private var onScreen = false

    /** Runs the gate once. */
    fun start() {
        if (started) return
        started = true
        onScreen = true
        env.keysScreens.value += 1
        scope.launch {
            env.events.collect { event ->
                when (event) {
                    is AppEvent.SessionChanged -> if (event.username != owner) shut()
                    AppEvent.DataWiped, AppEvent.Backgrounded -> shut()
                    else -> {}
                }
            }
        }
        scope.launch { env.appLock.state.collect { if (it.hidesContent && it.loaded) shut() } }
        gate = scope.launch {
            try {
                env.session.awaitLoaded()
                val user = env.session.current.username ?: return@launch shut()
                owner = user
                setState { it.copy(account = user) }
                val lock = env.appLock.state.first { it.loaded }
                if (lock.hidesContent) return@launch shut()
                if (!lock.enabled) {
                    setState { it.copy(phase = KeysPhase.NEEDS_PIN) }
                } else if (env.appLock.verifyPin(whenOff = false)) {
                    show(user)
                } else {
                    shut()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // an unreadable vault shows nothing
                shut()
            }
        }
    }

    /**
     * RN "Set PIN" on the warning: the new PIN pad (after the biometric offer when [biometricAvailable]); the keys
     * show once the PIN is set, the warning stays otherwise.
     */
    fun setPin(biometricAvailable: Boolean) {
        if (current.phase != KeysPhase.NEEDS_PIN || gate?.isActive == true) return
        val user = owner ?: return
        setState { it.copy(phase = KeysPhase.CHECKING) }
        env.appLock.startEnable(biometricAvailable)
        gate = scope.launch {
            try {
                // the biometric offer, then the PIN pad, are one flow: it ends when neither is up
                env.appLock.state.first { it.biometricOffer || it.prompt?.purpose == PinPurpose.ENABLE }
                val after = env.appLock.state.first { !it.biometricOffer && it.prompt?.purpose != PinPurpose.ENABLE }
                if (after.enabled && !after.hidesContent) show(user) else setState { it.copy(phase = KeysPhase.NEEDS_PIN) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                shut()
            }
        }
    }

    /** Cancel on the warning, the close button, back. */
    fun dismiss() = shut()

    /** RN `_handleRevealKey`: private and public in turn; a role without a stored key opens the import. */
    fun toggle(role: KeyRole) {
        if (!usable()) return
        // the owner row has no button: there is nothing to reveal and nothing to import
        if (role == KeyRole.OWNER) return
        if (role !in privateKeys) {
            setState { it.copy(importOpen = true, importError = null) }
            return
        }
        revealed = if (role in revealed) revealed - role else revealed + role
        publish()
    }

    /**
     * The copy button of [role]. A public key is returned at once; a revealed private key first asks
     * ([BackupKeysState.copyAsks]) and comes back from [confirmCopy].
     */
    fun keyToCopy(role: KeyRole): KeyCopy? {
        if (!usable()) return null
        val row = current.rows.firstOrNull { it.role == role } ?: return null
        if (row.isSensitive) {
            setState { it.copy(copyAsks = role) }
            return null
        }
        if (row.value.isEmpty()) return null
        env.ui.toast(BackupKeysCopy.KEY_COPIED)
        return KeyCopy(row.value, sensitive = false)
    }

    /** The user accepted the warning: the private key for the expiring clipboard, or null when it no longer applies. */
    fun confirmCopy(): KeyCopy? {
        val role = current.copyAsks ?: return null
        setState { it.copy(copyAsks = null) }
        if (!usable() || role !in revealed) return null
        val key = privateKeys[role] ?: return null
        env.ui.toast(BackupKeysCopy.KEY_COPIED)
        return KeyCopy(key, sensitive = true)
    }

    fun cancelCopy() = setState { it.copy(copyAsks = null) }

    /** Every revealed private key goes back to its public key (the platform saw a screenshot or a recording start). */
    fun hideAll() {
        if (current.phase != KeysPhase.SHOWN) return
        revealed = emptySet()
        publish { it.copy(copyAsks = null) }
    }

    fun closeImport() {
        if (current.isImporting) return
        setState { it.copy(importOpen = false, importError = null) }
    }

    fun clearImportError() = setState { it.copy(importError = null) }

    /** RN `_handleSignKey`: a master password or a private key of this account. [secret] is never kept. */
    fun importKey(secret: String) {
        val user = owner ?: return
        if (!usable() || !current.importOpen || current.isImporting) return
        setState { it.copy(isImporting = true, importError = null) }
        scope.launch {
            try {
                val account = env.accounts.importKeys(user, secret)
                if (!usable()) return@launch
                privateKeys = stored(account)
                publish { it.copy(importOpen = false, isImporting = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = AppError.from(e).message
                setState { it.copy(isImporting = false, importError = "$reason\n${BackupKeysCopy.IMPORT_FAILED_TAIL}") }
            }
        }
    }

    private suspend fun show(user: String) {
        val account = env.accounts.vaultAccount(user) ?: return shut()
        val chain: ChainAuthorities? = try {
            env.chain.authorities(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // the account may have changed, or the app locked, while the vault and the node were read
        if (env.session.current.username != user || env.appLock.current.hidesContent || current.phase == KeysPhase.CLOSED) return shut()
        privateKeys = stored(account)
        // RN shows the account's first key per role; without the chain, what the stored key derives to
        publicKeys = KeyRole.entries.associateWith { role ->
            chain?.keysFor(role)?.firstOrNull()
                ?: privateKeys[role]?.let { wif -> runCatching { PrivateKey.fromWif(wif).publicKey().toString() }.getOrNull() }
                ?: ""
        }
        revealed = emptySet()
        // one of the owner authority's keys is also an active or posting key: say so, every time the keys show
        val shared = chain?.let { c -> listOf(KeyRole.ACTIVE, KeyRole.POSTING).filter { role -> c.keysFor(role).any { it in c.owner } } }.orEmpty()
        publish { it.copy(phase = KeysPhase.SHOWN, sharedOwnerWarning = shared.takeIf { r -> r.isNotEmpty() }?.let { r -> BackupKeysCopy.sharedOwner(r) }) }
    }

    private fun stored(account: VaultAccount): Map<KeyRole, String> = buildMap {
        account.activeKey?.takeIf { it.isNotEmpty() }?.let { put(KeyRole.ACTIVE, it) }
        account.postingKey?.takeIf { it.isNotEmpty() }?.let { put(KeyRole.POSTING, it) }
        account.memoKey?.takeIf { it.isNotEmpty() }?.let { put(KeyRole.MEMO, it) }
    }

    /** Shown, and still the owner's, unlocked session. */
    private fun usable(): Boolean =
        current.phase == KeysPhase.SHOWN && owner != null && env.session.current.username == owner && !env.appLock.current.hidesContent

    private fun publish(also: (BackupKeysState) -> BackupKeysState = { it }) = setState { s ->
        also(s).copy(
            rows = ROW_ORDER.map { role ->
                val isRevealed = role in revealed && role in privateKeys
                KeyRow(role, publicKeys[role].orEmpty(), role in privateKeys, isRevealed, privateKeys[role].takeIf { isRevealed })
            },
        )
    }

    /** Ends the screen: the keys leave memory and the state before anything else. */
    private fun shut() {
        privateKeys = emptyMap()
        revealed = emptySet()
        publicKeys = emptyMap()
        gate?.cancel()
        if (onScreen) {
            onScreen = false
            env.keysScreens.value -= 1
        }
        if (current.phase == KeysPhase.CLOSED) return
        setState { BackupKeysState(phase = KeysPhase.CLOSED, account = it.account) }
    }

    override fun close() {
        shut()
        super.close()
    }

    companion object {
        /** RN's order on the screen. */
        val ROW_ORDER = listOf(KeyRole.OWNER, KeyRole.ACTIVE, KeyRole.POSTING, KeyRole.MEMO)
    }
}
