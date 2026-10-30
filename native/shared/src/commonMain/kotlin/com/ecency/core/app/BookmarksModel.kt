package com.ecency.core.app

import com.ecency.core.api.ecency.Bookmark
import com.ecency.core.api.ecency.Favorite
import com.ecency.core.api.ecency.FavoriteTag
import com.ecency.core.api.ecency.WrappedPage
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** RN bookmarks screen tabs, in order. */
enum class BookmarksTab(private val labelText: () -> String) {
    BOOKMARKS({ L.sideMenu.bookmarks }),
    FAVORITES({ L.sideMenu.favorites }),
    TAGS({ L.notification.filters.tags }),
    ;

    val label: String get() = labelText()
}

/** One paged list of the bookmarks screen. */
data class PagedList<T>(
    val items: List<T> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loaded: Boolean = false,
    val error: AppError? = null,
    val nextOffset: Int? = null,
    val pages: Int = 0,
) {
    val hasMore: Boolean get() = nextOffset != null
}

data class BookmarksState(
    val selected: Int = 0,
    val bookmarks: PagedList<Bookmark> = PagedList(),
    val favorites: PagedList<Favorite> = PagedList(),
    val tags: PagedList<FavoriteTag> = PagedList(),
) {
    val tab: BookmarksTab get() = BookmarksTab.entries[selected.coerceIn(0, BookmarksTab.entries.size - 1)]

    /** RN shows its placeholder while the bookmarks or favourites load; the tags tab has its own flag. */
    fun isLoading(tab: BookmarksTab): Boolean = when (tab) {
        BookmarksTab.TAGS -> tags.isLoading
        else -> bookmarks.isLoading || favorites.isLoading
    }

    fun error(tab: BookmarksTab): AppError? = when (tab) {
        BookmarksTab.BOOKMARKS -> bookmarks.error
        BookmarksTab.FAVORITES -> favorites.error
        BookmarksTab.TAGS -> tags.error
    }

    /** RN "Nothing here" when a loaded list is empty. */
    fun isEmpty(tab: BookmarksTab): Boolean = when (tab) {
        BookmarksTab.BOOKMARKS -> bookmarks.loaded && bookmarks.items.isEmpty() && bookmarks.error == null
        BookmarksTab.FAVORITES -> favorites.loaded && favorites.items.isEmpty() && favorites.error == null
        BookmarksTab.TAGS -> tags.loaded && tags.items.isEmpty() && tags.error == null
    }
}

/**
 * The signed-in account's bookmarks, favourite accounts and followed tags (RN bookmarksContainer over the
 * SDK's wrapped infinite queries). Bookmarks and favourites are shown newest first across loaded pages (RN
 * sorts the flattened pages by timestamp); a long press removes an entry after the UI confirmed.
 */
class BookmarksModel internal constructor(private val env: AppEnvironment, initialTab: Int) :
    StateModel<BookmarksState>(BookmarksState(selected = initialTab.coerceIn(0, BookmarksTab.entries.size - 1)), env.dispatchers) {

    private val jobs = mutableMapOf<BookmarksTab, Job>()

    fun select(index: Int) {
        if (index in BookmarksTab.entries.indices) setState { it.copy(selected = index) }
    }

    /** Loads the three lists unless already loaded. */
    fun start() = BookmarksTab.entries.forEach { if (!list(it).loaded && jobs[it]?.isActive != true) load(it, reset = true) }

    fun refresh() = BookmarksTab.entries.forEach { load(it, reset = true) }

    fun loadMore(tab: BookmarksTab) {
        val l = list(tab)
        if (jobs[tab]?.isActive == true || !l.loaded || !l.hasMore || l.error != null) return
        load(tab, reset = false)
    }

    fun retry(tab: BookmarksTab) {
        if (jobs[tab]?.isActive == true) return
        load(tab, reset = !list(tab).loaded || list(tab).items.isEmpty())
    }

    private fun list(tab: BookmarksTab): PagedList<*> = when (tab) {
        BookmarksTab.BOOKMARKS -> current.bookmarks
        BookmarksTab.FAVORITES -> current.favorites
        BookmarksTab.TAGS -> current.tags
    }

    private fun load(tab: BookmarksTab, reset: Boolean) {
        jobs[tab]?.cancel()
        val offset = if (reset) 0 else (list(tab).nextOffset ?: return)
        update(tab) { it.copy(isLoading = reset, isLoadingMore = !reset, error = null) }
        jobs[tab] = scope.launch {
            try {
                env.session.awaitLoaded()
                val user = env.session.current.username ?: throw AppException(AppError(ErrorKind.AUTH, L.native.bookmarks.pleaseSignInFirst, false))
                when (tab) {
                    BookmarksTab.BOOKMARKS -> {
                        val page = env.accountApi.bookmarks(user, offset)
                        setState { s -> s.copy(bookmarks = merge(s.bookmarks, page, reset, ::bookmarkKey) { it.sortedByDescending { b -> b.timestamp } }) }
                    }
                    BookmarksTab.FAVORITES -> {
                        val page = env.accountApi.favorites(user, offset)
                        setState { s -> s.copy(favorites = merge(s.favorites, page, reset, { it.account }) { it.sortedByDescending { f -> f.timestamp } }) }
                    }
                    BookmarksTab.TAGS -> {
                        val page = env.accountApi.favoriteTags(user, offset)
                        setState { s -> s.copy(tags = merge(s.tags, page, reset, { it.tag }) { it }) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(tab) { it.copy(isLoading = false, isLoadingMore = false, loaded = true, error = AppError.from(e)) }
            }
        }
    }

    private fun <T> merge(
        old: PagedList<T>,
        page: WrappedPage<T>,
        reset: Boolean,
        key: (T) -> String,
        order: (List<T>) -> List<T>,
    ): PagedList<T> {
        val known = if (reset) emptySet() else old.items.map(key).toSet()
        val items = (if (reset) emptyList() else old.items) + page.items.filter { key(it) !in known }
        return PagedList(order(items), isLoading = false, isLoadingMore = false, loaded = true, error = null, nextOffset = page.nextOffset, pages = if (reset) 1 else old.pages + 1)
    }

    @Suppress("UNCHECKED_CAST")
    private fun update(tab: BookmarksTab, change: (PagedList<Any?>) -> PagedList<Any?>) = setState { s ->
        when (tab) {
            BookmarksTab.BOOKMARKS -> s.copy(bookmarks = change(s.bookmarks as PagedList<Any?>) as PagedList<Bookmark>)
            BookmarksTab.FAVORITES -> s.copy(favorites = change(s.favorites as PagedList<Any?>) as PagedList<Favorite>)
            BookmarksTab.TAGS -> s.copy(tags = change(s.tags as PagedList<Any?>) as PagedList<FavoriteTag>)
        }
    }

    /** Removes a bookmark (after the UI's "Are you sure you want to remove?"). */
    fun removeBookmark(bookmark: Bookmark) {
        // the API deletes by id: a row without one cannot be removed (and must not take other id-less rows with it)
        if (bookmark.id.isEmpty()) return
        remove(L.alert.success) { user ->
            env.accountApi.deleteBookmark(user, bookmark.id)
            val key = bookmarkKey(bookmark)
            setState { s -> s.copy(bookmarks = s.bookmarks.copy(items = s.bookmarks.items.filter { bookmarkKey(it) != key })) }
        }
    }

    /** One bookmark row: its id, else the post (the same key the paging dedupe uses). */
    private fun bookmarkKey(b: Bookmark): String = b.id.ifEmpty { "${b.author}/${b.permlink}" }

    fun removeFavorite(favorite: Favorite) = remove(L.alert.success) { user ->
        env.accountApi.deleteFavorite(user, favorite.account)
        setState { s -> s.copy(favorites = s.favorites.copy(items = s.favorites.items.filter { it.account != favorite.account })) }
    }

    fun removeTag(tag: FavoriteTag) = remove(L.favoriteTags.removed) { user ->
        env.accountApi.deleteFavoriteTag(user, tag.tag)
        setState { s -> s.copy(tags = s.tags.copy(items = s.tags.items.filter { it.tag != tag.tag })) }
    }

    /** RN mutation hooks: "Success!" (or the tag message) when it went through, "Fail!" otherwise. */
    private fun remove(success: String, block: suspend (String) -> Unit) {
        val user = env.session.current.username ?: return
        scope.launch {
            try {
                block(user)
                env.ui.toast(success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                env.ui.toast(L.alert.fail)
            }
        }
    }
}
