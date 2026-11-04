package com.ecency.core.api.ecency

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.EcencyTimeoutException
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyCodeProvider
import kotlinx.coroutines.withTimeoutOrNull
import com.ecency.core.api.json.SafeJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** RN `PurchaseRequestData.meta`: who the paid account is for. */
data class AccountOrderMeta(val username: String, val email: String, val referral: String) {
    override fun toString(): String = "AccountOrderMeta(username=$username, email=<redacted>, referral=$referral)"
}

/** RN `PurchaseRequestData` (ecency.types.ts). [receipt] is never printed. */
data class PurchaseOrder(
    val platform: String,
    val product: String,
    val receipt: String,
    val user: String,
    val meta: AccountOrderMeta? = null,
) {
    override fun toString(): String = "PurchaseOrder(platform=$platform, product=$product, user=$user, meta=$meta, receipt=<redacted>)"
}

/**
 * RN `purchaseOrder` (providers/ecency/ecency.ts): `POST /private-api/purchase-order` with
 * `{platform, product, receipt, user, meta?}`, plus the signed-in account's `code` as RN's axios interceptor adds it
 * (Ecency's purchase worker does not read it; signed out, the body has none).
 *
 * The server only queues the order; Ecency's worker verifies it with the store (an App Store JWS against Apple's
 * roots, unverified on the device or not) and delivers it. The answers and what [com.ecency.core.app.PurchaseService]
 * does with them:
 * - 201 stored: finish the store purchase.
 * - 409 already stored (delivered once, or it will be): finish, as delivered.
 * - 422 `{"reason":"receipt_held","order":<id>}` held for review ([ReceiptHeldException]): keep unfinished, the user
 *   contacts support with the order number; once released a resend gets 409.
 * - 400, any other 422, 503, no answer: keep unfinished, resent later.
 * Not idempotent per call, so it is bounded by [TIMEOUT_MILLIS] (RN
 * `PURCHASE_ORDER_TIMEOUT_MS`), much longer than other private calls: a slow success must not read as no answer.
 */
class PurchaseApi(private val http: AuthHttp, private val codes: EcencyCodeProvider, private val host: String) {
    /**
     * Sends [order] once. [codeOf] names the signed-in account whose `code` goes along (null: none).
     * @throws EcencyHttpException with the refused status ([ReceiptHeldException] for a held receipt);
     *   [EcencyTimeoutException] after [TIMEOUT_MILLIS].
     */
    @Throws(Exception::class)
    suspend fun purchaseOrder(order: PurchaseOrder, codeOf: String?): Int {
        val code = codeOf?.let { runCatching { codes.code(it) }.getOrNull() }
        val response = withTimeoutOrNull(TIMEOUT_MILLIS) {
            http.send(
                AuthHttpRequest(
                    "POST", "$host/private-api/purchase-order", mapOf("Content-Type" to "application/json"), body(order, code),
                    timeoutMillis = TIMEOUT_MILLIS,
                ),
            )
        } ?: throw EcencyTimeoutException()
        if (response.status == 422) heldOrder(response.body)?.let { throw ReceiptHeldException(it) }
        if (response.status !in 200..299) throw EcencyHttpException(response.status)
        return response.status
    }

    companion object {
        /** The order number of a 422 that is exactly `{"reason":"receipt_held","order":<id>}`; null otherwise. */
        fun heldOrder(body: String): String? {
            val o = SafeJson.parseOrNull(body, 2) as? JsonObject ?: return null
            if ((o["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "receipt_held") return null
            val order = o["order"] as? JsonPrimitive ?: return null
            return order.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it > 0 }?.toString()
        }

        /** RN `PURCHASE_ORDER_TIMEOUT_MS`. */
        const val TIMEOUT_MILLIS = 60_000L

        /** The JSON body, in RN's field order. */
        fun body(order: PurchaseOrder, code: String?): String {
            val o: JsonObject = buildJsonObject {
                put("platform", order.platform)
                put("product", order.product)
                put("receipt", order.receipt)
                put("user", order.user)
                order.meta?.let { m ->
                    put("meta", buildJsonObject { put("username", m.username); put("email", m.email); put("referral", m.referral) })
                }
                code?.takeIf { it.isNotEmpty() }?.let { put("code", it) }
            }
            return o.toString()
        }
    }
}

/** Ecency holds this receipt for review (422 `receipt_held`): [order] is the number support needs. */
class ReceiptHeldException(val order: String) : EcencyHttpException(422, "Purchase held for review")
