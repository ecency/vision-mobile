package com.ecency.core.app

import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class TransferFavoritesState(
    val accounts: List<String> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = false,
    val error: AppError? = null,
) {
    /** RN favourites sheet search: the typed text in the name. */
    val visible: List<String> get() = if (query.isBlank()) accounts else accounts.filter { it.contains(query.trim().lowercase()) }
}

/**
 * RN TRANSFER_FAVORITES sheet: the signed-in account's favourite accounts (`/private-api/favorites`, limit 50) to
 * pick a recipient from. The pick goes to the transfer screen's recipient field (and its existence check).
 */
class TransferFavoritesModel internal constructor(private val env: AppEnvironment) :
    StateModel<TransferFavoritesState>(TransferFavoritesState(), env.dispatchers) {
    fun start() {
        setState { it.copy(isLoading = true, error = null) }
        scope.launch {
            try {
                env.session.awaitLoaded()
                val user = env.session.current.username ?: return@launch setState { it.copy(isLoading = false) }
                val page = env.accountApi.favorites(user, 0, LIMIT)
                setState { it.copy(accounts = page.items.map { f -> f.account }.distinct(), isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, error = AppError.from(e)) }
            }
        }
    }

    fun setQuery(q: String) = setState { it.copy(query = q) }

    companion object {
        const val LIMIT = 50
    }
}
