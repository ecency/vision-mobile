package com.ecency.core.app

import com.ecency.core.l10n.L
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class TagFollowState(
    /** RN hides the row for guests and for names that are not followable tags. */
    val visible: Boolean = false,
    val following: Boolean = false,
    val busy: Boolean = false,
) {
    val hint: String
        get() = if (following) L.favoriteTags.followingHint else L.favoriteTags.hint
    val buttonLabel: String get() = if (following) L.waves.following else L.user.follow
}

/** RN FollowTagButton on the tag screen: Ecency's followed tags (`favorite-tags`). */
class TagFollowModel internal constructor(private val env: AppEnvironment, rawTag: String) :
    StateModel<TagFollowState>(TagFollowState(), env.dispatchers) {
    val tag: String? = normalize(rawTag)

    fun start() {
        val user = env.session.current.username
        val t = tag
        if (user == null || t == null) {
            setState { TagFollowState() }
            return
        }
        setState { it.copy(visible = true, busy = true) }
        scope.launch {
            val followed = try {
                env.accountApi.isFavoriteTag(user, t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            setState { it.copy(following = followed, busy = false) }
        }
    }

    fun toggle() {
        val user = env.session.current.username ?: return
        val t = tag ?: return
        val s = current
        if (s.busy || !s.visible) return
        val follow = !s.following
        setState { it.copy(busy = true, following = follow) }
        scope.launch {
            try {
                if (follow) env.accountApi.addFavoriteTag(user, t) else env.accountApi.deleteFavoriteTag(user, t)
                setState { it.copy(busy = false) }
                env.ui.toast(if (follow) L.favoriteTags.added else L.favoriteTags.removed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(busy = false, following = !follow) }
                env.ui.toast(PostActionsModel.FAIL)
            }
        }
    }

    companion object {
        private val TAG = Regex("^[a-z0-9-]{1,32}$")
        private val COMMUNITY = Regex("^hive-\\d+$")

        /** RN normalizeTag. */
        fun normalize(raw: String): String? {
            val t = raw.trim().lowercase().removePrefix("#")
            return t.takeIf { TAG.matches(it) && !COMMUNITY.matches(it) }
        }
    }
}
