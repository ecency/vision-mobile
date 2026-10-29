package com.ecency.core.legacy

import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64

/** How an account signed in on the RN app (src/constants/authType.ts). */
enum class LegacyAuthType(val value: String) {
    MASTER_KEY("masterKey"),
    POSTING_KEY("postingKey"),
    ACTIVE_KEY("activeKey"),
    MEMO_KEY("memoKey"),
    OWNER_KEY("ownerKey"),
    HIVE_SIGNER("steemConnect"),
    HIVE_AUTH("hiveAuth"),
    ;

    companion object {
        fun from(value: String) = entries.firstOrNull { it.value == value }
    }
}

/** One account recovered from the RN app's storage. Never prints its secrets. */
class LegacyAccount(
    val username: String,
    val authType: LegacyAuthType?,
    val masterPassword: String?,
    val postingKey: String?,
    val activeKey: String?,
    val memoKey: String?,
    val ownerKey: String?,
    val accessToken: String?,
    val hiveAuthKey: String?,
    val hiveAuthToken: String?,
    val hiveAuthExpiry: Long,
    /** Fields that held data but could not be decrypted or validated. */
    val unreadable: List<String>,
) {
    override fun toString(): String =
        "LegacyAccount(username=$username, authType=$authType, unreadable=$unreadable, secrets=<redacted>)"
}

/** The RN app's unlock PIN, carried over so users keep it. Never prints it. */
class LegacyPin internal constructor(internal val value: String) {
    override fun toString(): String = "LegacyPin(<redacted>)"
}

class LegacyImport(
    val accounts: List<LegacyAccount>,
    val currentUsername: String?,
    val pinLockEnabled: Boolean,
    /**
     * The PIN the RN lock screen checked (pinCodeContainer `_verifyPinCode`): `application.encUnlockPin` when
     * set, else `application.pin`, both decrypted with PIN_KEY. Null when neither decrypts.
     */
    val unlockPin: LegacyPin? = null,
    /** RN `application.isBiometricEnabled` (settings "Use biometrics"; the lock screen offered it). */
    val biometricEnabled: Boolean = false,
)

sealed class LegacyImportError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NothingToImport : LegacyImportError("no RN app data found")

    /** The stored app PIN does not decrypt with this build's PIN_KEY, or there is no PIN to try. */
    class PinUnavailable : LegacyImportError("stored PIN could not be decrypted")

    /** RN storage exists but cannot be read now; retry later, never treat it as "nothing to import". */
    class StorageUnreadable(cause: Throwable? = null) : LegacyImportError("RN app storage is unreadable", cause)

    /** RN storage was read but is not in the expected shape. */
    class Malformed(cause: Throwable? = null) : LegacyImportError("RN app storage is malformed", cause)

    /** Migrated accounts did not read back from the vault; nothing is marked done or deleted. */
    class NotVerified(val usernames: List<String>) : LegacyImportError("migrated accounts did not read back: $usernames")
}

/**
 * Reads what the RN app left in AsyncStorage: "persist:root" (redux-persist) for the encrypted PIN
 * and current account, and "user" for each account's PIN-encrypted secrets (src/utils/crypto.ts).
 *
 * Secrets are not all under the same PIN in the RN app: the redux app PIN (`application.pin`), DEFAULT_PIN
 * (fresh logins, and accounts re-encrypted by migrateUserEncryption) and the user's own PIN kept in
 * `application.encUnlockPin` (accounts not re-encrypted yet; updatePinCode skips ownerKey accounts). Each
 * field is tried with each of them, in that order.
 */
object LegacyImporter {
    private const val STAMP = "995a06d5-ee54-407f-bb8e-e4af2ab2fe01"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param read returns the AsyncStorage value for a key, null when absent; throws when unreadable.
     * @param pinKey the RN build's PIN_KEY. @param defaultPin the RN build's DEFAULT_PIN, when known.
     * @throws LegacyImportError only: any read failure becomes StorageUnreadable, bad data Malformed.
     */
    @Throws(LegacyImportError::class)
    fun import(read: (String) -> String?, pinKey: String, defaultPin: String? = null): LegacyImport {
        fun get(key: String): String? = try {
            read(key)
        } catch (e: LegacyImportError) {
            throw e
        } catch (e: Exception) {
            throw LegacyImportError.StorageUnreadable(e)
        }
        val usersJson = get("user") ?: throw LegacyImportError.NothingToImport()
        val rootJson = get("persist:root")
        return try {
            parse(usersJson, rootJson, pinKey, defaultPin)
        } catch (e: LegacyImportError) {
            throw e
        } catch (e: Exception) {
            throw LegacyImportError.Malformed(e)
        }
    }

    private fun parse(usersJson: String, rootJson: String?, pinKey: String, defaultPin: String?): LegacyImport {
        // the accounts first: RN data without any account has nothing a PIN could open, whatever the PIN_KEY
        val users = json.parseToJsonElement(usersJson).jsonArray
        if (users.isEmpty()) throw LegacyImportError.NothingToImport()
        val root = rootJson?.let { json.parseToJsonElement(it).jsonObject }
        val application = root.reducer("application")
        val encPin = application?.string("pin")?.ifEmpty { null }
        val appPin = encPin?.let { decryptStamped(it, pinKey) ?: throw LegacyImportError.PinUnavailable() }
        val encUnlock = application?.string("encUnlockPin")?.ifEmpty { null }
        val unlockPin = encUnlock?.let { decryptStamped(it, pinKey) }
        val pins = listOfNotNull(appPin, defaultPin, unlockPin).filter { it.isNotEmpty() }.distinct()
        if (pins.isEmpty()) throw LegacyImportError.PinUnavailable()

        val accounts = users.map { readAccount(it.jsonObject, pins) }
        return LegacyImport(
            accounts = accounts,
            currentUsername = root.reducer("account")?.get("currentAccount")?.jsonObject?.string("name"),
            pinLockEnabled = application?.get("isPinCodeOpen")?.jsonPrimitive?.contentOrNull == "true",
            // RN checks encUnlockPin when it is set (even if it no longer decrypts), else the app PIN
            unlockPin = (if (encUnlock != null) unlockPin else appPin)?.ifEmpty { null }?.let(::LegacyPin),
            biometricEnabled = application?.get("isBiometricEnabled")?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    private fun readAccount(user: JsonObject, pins: List<String>): LegacyAccount {
        val unreadable = mutableListOf<String>()
        fun secret(field: String): String? {
            val value = user.string(field)
            if (value.isNullOrEmpty()) return null
            val plain = pins.firstNotNullOfOrNull { decryptStamped(value, it) }
            return plain.also { if (it == null) unreadable += field }?.ifEmpty { null }
        }
        fun key(field: String): String? = secret(field)?.takeIf { wif ->
            runCatching { PrivateKey.fromWif(wif) }.isSuccess.also { if (!it) unreadable += field }
        }

        val username = user.string("username").orEmpty()
        val password = secret("masterKey")
        val posting = key("postingKey")
        // A master-password account must derive the posting key it stored; anything else is corrupt data.
        if (password != null && posting != null &&
            PrivateKey.fromLogin(username, password, KeyRole.POSTING).toWif() != posting
        ) {
            unreadable += "masterKey"
        }
        return LegacyAccount(
            username = username,
            authType = user.string("authType")?.let(LegacyAuthType::from),
            masterPassword = password.takeUnless { "masterKey" in unreadable },
            postingKey = posting,
            activeKey = key("activeKey"),
            memoKey = key("memoKey"),
            ownerKey = key("ownerKey"),
            accessToken = secret("accessToken"),
            hiveAuthKey = secret("hiveAuthKey"),
            hiveAuthToken = secret("hiveAuthToken"),
            hiveAuthExpiry = user["hiveAuthExpiry"]?.jsonPrimitive?.longOrNull ?: 0,
            unreadable = unreadable,
        )
    }

    /** encryptKey() output: base64(crypto-js ciphertext of {"data","stamp"}). Null when it does not decrypt. */
    internal fun decryptStamped(value: String, passphrase: String): String? = runCatching {
        val inner = Base64.decode(value).decodeToString(throwOnInvalidSequence = true)
        val stamped = json.parseToJsonElement(CryptoJsAes.decrypt(inner, passphrase)).jsonObject
        require(stamped.string("stamp") == STAMP)
        stamped.string("data")
    }.getOrNull()

    private fun JsonObject.string(field: String): String? = get(field)?.jsonPrimitive?.contentOrNull

    /** redux-persist v6 stores each reducer as a JSON string. */
    private fun JsonObject?.reducer(name: String): JsonObject? =
        this?.string(name)?.let { json.parseToJsonElement(it).jsonObject }
}
