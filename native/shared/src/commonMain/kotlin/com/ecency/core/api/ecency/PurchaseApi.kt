package com.ecency.core.api.ecency

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.EcencyTimeoutException
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyCodeProvider
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
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
 * The server only queues the order: 201 means it was stored and will be verified with the store and delivered by
 * Ecency's worker; 409 means an order with the same receipt was stored before (or the insert failed), see
 * [com.ecency.core.app.PurchaseService]. Not idempotent per call, so it is bounded by [TIMEOUT_MILLIS] (RN
 * `PURCHASE_ORDER_TIMEOUT_MS`), much longer than other private calls: a slow success must not read as no answer.
 */
class PurchaseApi(private val http: AuthHttp, private val codes: EcencyCodeProvider, private val host: String) {
    /**
     * Sends [order] once. [codeOf] names the signed-in account whose `code` goes along (null: none).
     * @throws EcencyHttpException with the refused status; [EcencyTimeoutException] after [TIMEOUT_MILLIS].
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
        if (response.status !in 200..299) throw EcencyHttpException(response.status)
        return response.status
    }

    companion object {
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
