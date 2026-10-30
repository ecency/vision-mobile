package com.ecency.core.auth

import com.ecency.core.api.rpc.HiveRpcClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.ecency.core.hive.KeyRole

/** An account's public keys per role and its posting account auths, from `condenser_api.get_accounts`. */
data class ChainAuthorities(
    val username: String,
    val owner: List<String>,
    val active: List<String>,
    val posting: List<String>,
    val memo: String?,
    /** Apps with posting authority over the account (e.g. "ecency.app"). */
    val postingAccountAuths: List<String> = emptyList(),
    /** `weight_threshold` per role; a role that is not listed counts as 1. */
    val thresholds: Map<KeyRole, Int> = emptyMap(),
    /** Each key's weight per role; a key that is not listed counts as 1. */
    val weights: Map<KeyRole, Map<String, Int>> = emptyMap(),
) {
    /**
     * Whether [publicKey] alone satisfies [role]'s authority: it is one of the role's keys and its weight
     * reaches the role's `weight_threshold`. A key of a multi-signature authority (weight below the threshold)
     * is on the account and still cannot sign by itself. The memo key has no weights.
     */
    fun canSignAlone(role: KeyRole, publicKey: String): Boolean {
        if (publicKey !in keysFor(role)) return false
        if (role == KeyRole.MEMO) return true
        val weight = weights[role]?.get(publicKey) ?: 1
        val threshold = thresholds[role] ?: 1
        // a threshold of 0 or less is no authority the chain accepts, and a weight of 0 or less signs nothing
        return threshold > 0 && weight > 0 && weight >= threshold
    }

    fun keysFor(role: KeyRole): List<String> = when (role) {
        KeyRole.OWNER -> owner
        KeyRole.ACTIVE -> active
        KeyRole.POSTING -> posting
        KeyRole.MEMO -> listOfNotNull(memo?.takeIf { it.isNotEmpty() })
    }

    /** RN mobilePlatformAdapter.hasPostingAuthorization: ecency.app may post for the account. */
    fun grantsPostingTo(app: String = HiveSignerMessages.APP): Boolean = app in postingAccountAuths

    companion object {
        fun fromJson(element: JsonElement?): ChainAuthorities? {
            val o = element as? JsonObject ?: return null
            val name = o.text("name")?.takeIf { it.isNotBlank() } ?: return null
            fun auths(role: String, field: String) = ((o[role] as? JsonObject)?.get(field) as? JsonArray).orEmpty()
                .mapNotNull { ((it as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull }
            val roles = listOf(KeyRole.OWNER to "owner", KeyRole.ACTIVE to "active", KeyRole.POSTING to "posting")
            fun weightsOf(role: String): Map<String, Int> = ((o[role] as? JsonObject)?.get("key_auths") as? JsonArray).orEmpty().mapNotNull { pair ->
                val key = ((pair as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                // a weight that cannot be read counts as 0: such a key never passes as able to sign alone
                key to (((pair as? JsonArray)?.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0)
            }.toMap()
            return ChainAuthorities(
                username = name,
                owner = auths("owner", "key_auths"),
                active = auths("active", "key_auths"),
                posting = auths("posting", "key_auths"),
                memo = o.text("memo_key"),
                postingAccountAuths = auths("posting", "account_auths"),
                // an authority without a readable threshold is not one a single key can be trusted to satisfy
                thresholds = roles.associate { (role, field) ->
                    role to (((o[field] as? JsonObject)?.get("weight_threshold") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: Int.MAX_VALUE)
                },
                weights = roles.associate { (role, field) -> role to weightsOf(field) },
            )
        }

        private fun JsonObject.text(field: String): String? =
            runCatching { get(field)?.jsonPrimitive?.contentOrNull }.getOrNull()
    }
}

/** Looks up an account's authorities on chain (read-only). Null when the account does not exist. */
fun interface ChainAccounts {
    @Throws(Exception::class)
    suspend fun authorities(username: String): ChainAuthorities?
}

/** [ChainAccounts] over the app's node pool: `condenser_api.get_accounts`. */
class RpcChainAccounts(private val client: HiveRpcClient) : ChainAccounts {
    @Throws(Exception::class)
    override suspend fun authorities(username: String): ChainAuthorities? {
        val result = client.call(
            "condenser_api.get_accounts",
            buildJsonArray { add(buildJsonArray { add(JsonPrimitive(username)) }) },
            validate = HiveRpcClient.IS_ARRAY,
        )
        return (result as? JsonArray).orEmpty().mapNotNull(ChainAuthorities::fromJson).firstOrNull { it.username == username }
    }
}
