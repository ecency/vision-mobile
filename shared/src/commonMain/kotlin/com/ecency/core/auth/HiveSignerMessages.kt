package com.ecency.core.auth

import com.ecency.core.hive.PrivateKey
import com.ecency.core.hive.PublicKey
import com.ecency.core.hive.Signature
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kotlincrypto.hash.sha2.SHA256
import kotlin.io.encoding.Base64

/**
 * HiveSigner-style signed messages, ported from RN `utils/hive-signer-helper.ts` and the HiveAuth login:
 * JSON `{"signed_message":{...},"authors":[account],"timestamp":<seconds>}`, signed over sha256 of that
 * exact text, then sent with `"signatures":[hex]` appended.
 *
 * The JSON is written by hand so it is byte-identical to `JSON.stringify` in the RN app: the receiver
 * re-serializes the parsed message and verifies the signature against it.
 */
object HiveSignerMessages {
    /** The HiveSigner app every Ecency message is signed for. */
    const val APP = "ecency.app"

    /**
     * A HiveSigner code (type `code`): what Ecency's token exchange turns into an access and refresh token
     * (RN `makeHsCode`). b64u encoded.
     */
    fun code(account: String, key: PrivateKey, nowMillis: Long): String =
        B64u.encode(signed(message("code", null, account, nowMillis), key))

    /**
     * A sign-in proof for another app (type `login`, with an audience), RN `makeHsLoginProof`. It cannot be
     * exchanged for tokens or used to broadcast. b64u encoded.
     */
    fun loginProof(account: String, key: PrivateKey, audience: String, nowMillis: Long): String =
        B64u.encode(signed(message("login", audience, account, nowMillis), key))

    /**
     * The image host's upload token for a posting key account, RN `signImage` (type `posting`, whole seconds as
     * `Math.floor(Date.now() / 1000)`): it goes into the upload url, `/hs/<token>`. b64u encoded.
     */
    fun imageUploadToken(account: String, key: PrivateKey, nowMillis: Long): String =
        B64u.encode(signed(message("posting", null, account, nowMillis / 1000 * 1000), key))

    /**
     * The HiveAuth login challenge (type `refresh`): the wallet signs this text with the posting key
     * (`challenge_data.challenge` in RN useHiveAuth).
     */
    fun hiveAuthChallenge(account: String, nowMillis: Long): String = message("refresh", null, account, nowMillis)

    /** The code a HiveAuth login exchanges: `btoa(JSON)` (standard base64) of the challenge plus its signature. */
    fun hiveAuthCode(challenge: String, signatureHex: String): String =
        Base64.Default.encode(withSignature(challenge, signatureHex).encodeToByteArray())

    /** The unsigned message JSON. */
    internal fun message(type: String, audience: String?, account: String, nowMillis: Long): String = buildString {
        append("{\"signed_message\":{\"type\":").append(JsonPrimitive(type))
        append(",\"app\":").append(JsonPrimitive(APP))
        if (audience != null) append(",\"audience\":").append(JsonPrimitive(audience))
        append("},\"authors\":[").append(JsonPrimitive(account)).append("]")
        append(",\"timestamp\":").append(JsNumber.secondsFromMillis(nowMillis)).append("}")
    }

    /** RN `signer`: hex of the recoverable signature over sha256(UTF-8 message). */
    fun sign(message: String, key: PrivateKey): String = key.sign(SHA256().digest(message.encodeToByteArray())).toHex()

    /** The key that signed [message] with [signatureHex]. */
    fun recoverSigner(message: String, signatureHex: String): PublicKey =
        Signature.fromHex(signatureHex).recover(SHA256().digest(message.encodeToByteArray()))

    private fun signed(message: String, key: PrivateKey) = withSignature(message, sign(message, key))

    private fun withSignature(message: String, signatureHex: String): String {
        require(message.endsWith("}")) { "not a JSON object" }
        return message.dropLast(1) + ",\"signatures\":[" + JsonPrimitive(signatureHex) + "]}"
    }

    /** A decoded message: the unsigned JSON (as signed) and its fields. */
    data class Decoded(
        val type: String?,
        val app: String?,
        val audience: String?,
        val authors: List<String>,
        /** Seconds since the epoch, as sent. */
        val timestamp: Double?,
        val signatures: List<String>,
        /** The message JSON without `signatures`, as the signer signed it (when it was written like ours). */
        val unsignedJson: String,
    )

    /**
     * Reads a b64u code or proof (or, with [standardBase64], a HiveAuth code).
     * @throws AuthException.InvalidLink when it is not one.
     */
    @Throws(AuthException::class)
    fun decode(code: String, standardBase64: Boolean = false): Decoded {
        val text = try {
            if (standardBase64) Base64.Default.decode(code).decodeToString(throwOnInvalidSequence = true) else B64u.decode(code)
        } catch (e: Exception) {
            throw AuthException.InvalidLink("not a HiveSigner message")
        }
        val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw AuthException.InvalidLink("not a HiveSigner message")
        val signed = obj["signed_message"] as? JsonObject
        fun JsonObject?.str(k: String) = runCatching { this?.get(k)?.jsonPrimitive?.contentOrNull }.getOrNull()
        val sigs = (obj["signatures"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val unsigned = text.indexOf(",\"signatures\":").let { if (it < 0) text else text.substring(0, it) + "}" }
        return Decoded(
            type = signed.str("type"),
            app = signed.str("app"),
            audience = signed.str("audience"),
            authors = (obj["authors"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            timestamp = obj.str("timestamp")?.toDoubleOrNull(),
            signatures = sigs,
            unsignedJson = unsigned,
        )
    }
}
