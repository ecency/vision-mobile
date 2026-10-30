package com.ecency.core.app

import com.ecency.core.api.broadcast.WalletOps
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * RN asks for the PIN before a wallet operation when the app lock is on: the PINCODE screen opens before the transfer
 * (every TransferTypes kind, Points and engine tokens), trade and redeem screens, and before a hive:// signing
 * (assetDetailsScreen, walletContainer, pointsContainer, postOptionsModal, useLinkProcessor). Native asks once the
 * user confirmed, before signing, from [WalletSends], which every wallet send goes through. Reward claims and
 * proposal votes were never asked in RN and are not here. The pad is the app lock's own (biometrics when on).
 */
internal object WalletPin {
    private val UNASKED = setOf("claim_reward_balance", "update_proposal_votes")

    /** True when [ops] (condenser `[name, payload]` pairs) hold anything RN asked the PIN for. */
    fun asks(ops: JsonArray): Boolean = ops.any { op ->
        val pair = op as? JsonArray
        val name = (pair?.getOrNull(0) as? JsonPrimitive)?.contentOrNull
        val id = ((pair?.getOrNull(1) as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
        !(name in UNASKED || (name == "custom_json" && id == WalletOps.ENGINE_CLAIM_ID))
    }

    /** May [ops] be signed now: nothing to ask, the lock is off, or the PIN (or biometrics) was just confirmed. */
    suspend fun confirmed(env: AppEnvironment, ops: JsonArray): Boolean =
        !asks(ops) || withContext(env.dispatchers.main) { env.appLock.verifyPin(whenOff = true) }
}
