package com.ecency.core.app

import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.l10n.L
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.cancellation.CancellationException

/** The posting authority grant, for every surface that asks for it (scheduling, the editor's prompt, the quick post paths). */
internal object PostingAuthorityFlow {
    /**
     * RN _handleSchedulePress: the account_update that adds ecency.app to the posting authority, built from an
     * account row read right now ([com.ecency.core.api.broadcast.PostingGrant], which refuses anything that does
     * not look complete). Signed with the active authority through [SigningModel] (auth upgrade when needed).
     */
    suspend fun grant(env: AppEnvironment, owner: String, known: com.ecency.core.auth.ChainAuthorities) {
        suspend fun row(): kotlinx.serialization.json.JsonElement? = try {
            (env.api.client.call("condenser_api.get_accounts", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(owner)) }) }) as? JsonArray)?.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // a key account's own posting key must be in the authority that is about to be rewritten
        val ownKey = withContext(env.dispatchers.io) {
            env.accounts.vaultAccount(owner)?.postingKey?.takeIf { it.isNotEmpty() }?.let { wif -> runCatching { com.ecency.core.hive.PrivateKey.fromWif(wif).publicKey().toString() }.getOrNull() }
        }
        fun build(r: kotlinx.serialization.json.JsonElement?) =
            com.ecency.core.api.broadcast.PostingGrant.build(owner, r, ownPostingKey = ownKey, expectedKeys = known.posting, expectedAccounts = known.postingAccountAuths)
        // Two reads made now must agree on everything the operation writes back (every key and account with its
        // weight, the threshold, the memo key), not only on the names; the operation is built from the newer one.
        suspend fun twice(): com.ecency.core.hive.AccountUpdatePosting? {
            val older = row()
            val newer = row()
            val grant = com.ecency.core.api.broadcast.PostingGrant
            if (grant.fingerprint(older) != grant.fingerprint(newer)) throw com.ecency.core.api.broadcast.PostingGrantRefused(grant.READS_DISAGREE)
            return build(newer)
        }
        val op = try {
            try {
                twice()
            } catch (e: com.ecency.core.api.broadcast.PostingGrantRefused) {
                // once more (another node may answer); a second refusal stands
                twice()
            }
        } catch (e: com.ecency.core.api.broadcast.PostingGrantRefused) {
            throw AppException(AppError(ErrorKind.AUTH, e.message.orEmpty(), true))
        } ?: return
        env.signing.broadcast(owner, JsonArray(listOf(OperationJson.encode(op))))
    }
}

/**
 * RN `usePostSubmitter`: a publish from the quick post paths (a wave, a quick reply) that fails because
 * `ecency.app` may not broadcast for the account (`unauthorized_client`) opens the posting authority prompt
 * instead of showing the raw error. "Grant Permission" makes the guarded grant with the active authority and
 * sends once more; "Skip for Now" or closing the prompt ends the publish quietly and keeps the draft. The one
 * retry never asks again: when it fails the same way, the error shows. One per composer.
 */
internal class AuthorityRecovery(private val env: AppEnvironment) {
    private var retry = false

    /** At the start of a send: true when this send is the one retry after a grant. */
    fun takeRetry(): Boolean = retry.also { retry = false }

    /** Whether the failure [error] of a send opens the prompt ([wasRetry]: that send was the retry). */
    fun asks(error: Throwable, wasRetry: Boolean): Boolean = !wasRetry && PostingAuthorityPrompt.isMissingAuthority(error)

    /**
     * "Grant Permission" for [owner]: the authorities are read now, the grant is made unless `ecency.app` is
     * there already, and the next send counts as the retry. Throws what the grant threw (a closed key prompt
     * included): the caller then does not send.
     */
    suspend fun grant(owner: String) {
        val known = env.chain.authorities(owner) ?: throw AppException(AppError(ErrorKind.AUTH, CHECK_FAILED, true))
        if (!known.grantsPostingTo()) {
            PostingAuthorityFlow.grant(env, owner, known)
            env.ui.toast(PostingAuthorityPrompt.GRANTED)
        }
        retry = true
    }

    companion object {
        val CHECK_FAILED: String get() = L.native.postingAuthorityFlow.checkFailed
    }
}
