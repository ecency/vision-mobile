package com.ecency.core.app

import com.ecency.core.l10n.L
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class TransferFavoritesState(
    val accounts: List<String> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = false,
    val error: AppError? = null,
    /** RN isAdding: the add row is open. */
    val adding: Boolean = false,
    /** The name typed in the add row. */
    val addName: String = "",
    /** RN addFavoriteMutation.isPending. */
    val isSaving: Boolean = false,
) {
    /** RN favourites sheet search: the typed text in the name (RN normalizeUsername: no "@", spaces or capitals). */
    val visible: List<String> get() = TransferFavoritesModel.normalize(query).let { q -> if (q.isEmpty()) accounts else accounts.filter { it.contains(q) } }
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

    /** RN: the header button opens or closes the add row; closing clears what was typed. */
    fun toggleAdd() = setState { it.copy(adding = !it.adding, addName = "") }

    fun setAddName(text: String) = setState { it.copy(addName = text) }

    /**
     * RN _handleAddFavorite: `favorites-add` for the typed name, then the list is read again and searched for it. A
     * refusal says "Could not add favorite" and keeps the row as it is.
     */
    fun add() {
        val s = current
        val name = normalize(s.addName)
        if (name.isEmpty() || s.isSaving) return
        setState { it.copy(isSaving = true) }
        scope.launch {
            try {
                val user = env.session.current.username ?: return@launch setState { it.copy(isSaving = false) }
                env.accountApi.addFavorite(user, name)
                setState { it.copy(isSaving = false, adding = false, addName = "", query = name) }
                start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isSaving = false) }
                env.ui.toast(TransferFavoritesCopy.ADD_ERROR)
            }
        }
    }

    companion object {
        const val LIMIT = 50

        /** RN normalizeUsername: trimmed, without a leading "@", lower case. */
        fun normalize(text: String): String = text.trim().removePrefix("@").lowercase()
    }
}

/** RN transferFavoritesSheet copy (the RN keys, recorded in rn-reuse.json). */
object TransferFavoritesCopy {
    val TITLE: String get() = L.favorites.title
    val ADD: String get() = L.favorites.add
    val CANCEL_ADD: String get() = L.favorites.cancelAdd
    val CONFIRM_ADD: String get() = L.favorites.confirmAdd
    val ADD_ERROR: String get() = L.favorites.addError
    val SEARCH: String get() = L.favorites.search
    val EMPTY: String get() = L.favorites.emptyList
    val USERNAME: String get() = L.transfer.favoriteUsernamePlaceholder
}
