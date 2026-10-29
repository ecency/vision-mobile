package com.ecency.core.app

import com.ecency.core.api.AccountPostsSort
import com.ecency.core.api.RankedSort
import com.ecency.core.api.community.Community
import com.ecency.core.api.community.CommunitySort
import com.ecency.core.api.model.Post
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.search.PostUrlParser
import com.ecency.core.api.search.SearchApiException
import com.ecency.core.api.search.SearchFilters
import com.ecency.core.api.search.SearchHit
import com.ecency.core.api.search.SearchQueries
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** RN searchResult tabs, in order (the topics tab is commented out in RN). "Communities" as on the web (RN says "Groups", #3599). */
enum class SearchTab(private val labelText: () -> String) {
    POSTS({ L.searchResult.best.title }),
    PEOPLE({ L.searchResult.people.title }),
    COMMUNITIES({ L.home.communities }),
    ;

    val label: String get() = labelText()
}

/** A row of the posts tab (RN postsResults item). */
data class SearchPostRow(
    val author: String,
    val permlink: String,
    val title: String,
    /** The search API's highlighted body without the <mark> tags; empty when there is none. */
    val snippet: String,
    val created: String,
    val createdEpochMillis: Long,
    val reputation: Int,
    /** RN "$ <payout>", null when the result has no payout. */
    val payoutText: String?,
    val votes: Long,
    val comments: Long,
    val avatarUrl: String,
) {
    val key: String get() = "$author/$permlink"

    companion object {
        internal fun of(hit: SearchHit, proxy: String) = SearchPostRow(
            hit.author, hit.permlink, hit.title, hit.snippet, hit.created, PostCard.epochMillis(hit.created), hit.reputation,
            hit.payout?.let { "$ " + jsNumber(it) }, hit.votes, hit.children, PostCard.avatarUrl(hit.author, proxy),
        )

        internal fun of(post: Post, proxy: String) = SearchPostRow(
            post.author, post.permlink, post.title, "", post.created, PostCard.epochMillis(post.created), post.authorReputation,
            "$ " + jsNumber(post.payout), post.stats.totalVotes.toLong(), post.children.toLong(), PostCard.avatarUrl(post.author, proxy),
        )

        /** How JS prints a payout number (1 not 1.0). */
        internal fun jsNumber(v: Double): String =
            if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    }
}

/** What the posts tab shows: a pasted post, the search, or the initial list (nothing typed). */
enum class SearchPostsMode { INITIAL, POST_URL, SEARCH }

data class SearchPostsState(
    val mode: SearchPostsMode = SearchPostsMode.INITIAL,
    val items: List<SearchPostRow> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    /** A failure with nothing to show (RN "Search could not be completed. Please try again"). */
    val error: String? = null,
    /** A failed next page under results already shown. */
    val moreError: String? = null,
    /** The query was not sent: RN's validation message (needs criteria, too many tags, too long). */
    val validationError: String? = null,
    val loaded: Boolean = false,
) {
    /** RN EmptyScreen: the validation message first, then the error, then "Nothing here". */
    val emptyText: String? get() = when {
        validationError != null -> validationError
        error != null -> error
        loaded && !loading && items.isEmpty() -> SearchCopy.NOTHING_HERE
        else -> null
    }
}

data class SearchPeopleState(
    val items: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** RN shows "Nothing here" until a lookup found someone (also before any search). */
    val emptyText: String? get() = when {
        loading || items.isNotEmpty() -> null
        error != null -> error
        else -> SearchCopy.NOTHING_HERE
    }
}

data class SearchCommunitiesState(
    val rows: List<CommunityRow> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
) {
    val emptyText: String? get() = when {
        error != null -> error
        loaded && !loading && rows.isEmpty() -> SearchCopy.NOTHING_HERE
        else -> null
    }
}

data class SearchState(
    /** The search bar text, updated on every keystroke. */
    val input: String = "",
    /** The text searched for: [input] after the 500 ms debounce. */
    val query: String = "",
    val filters: SearchFilters = SearchFilters.EMPTY,
    val tab: SearchTab = SearchTab.POSTS,
    val posts: SearchPostsState = SearchPostsState(),
    val people: SearchPeopleState = SearchPeopleState(),
    val communities: SearchCommunitiesState = SearchCommunitiesState(),
    val loggedIn: Boolean = false,
) {
    /** RN colours the filter button when any filter differs from the defaults. */
    val activeFilterCount: Int get() = filters.activeCount

    /** What RN sends: the query without a leading @ or #, trimmed and lowercased. */
    val clipped: String get() = SearchQueries.clip(query)
}

/** RN en-US copy for search. */
object SearchCopy {
    val PLACEHOLDER: String get() = L.basicHeader.search
    val ERROR: String get() = L.searchResult.error
    val NOTHING_HERE: String get() = L.emptyScreen.nothingHere
    val FILTERS: String get() = L.searchResult.filters.open
    val AUTHOR: String get() = L.searchResult.filters.author
    val AUTHOR_PLACEHOLDER: String get() = L.searchResult.filters.authorPlaceholder
    val CATEGORY: String get() = L.searchResult.filters.category
    val CATEGORY_PLACEHOLDER: String get() = L.searchResult.filters.categoryPlaceholder
    val TAGS: String get() = L.notification.filters.tags
    val TAGS_PLACEHOLDER: String get() = L.searchResult.filters.tagsPlaceholder
    val TYPE: String get() = L.searchResult.filters.type
    val DATE: String get() = L.searchResult.filters.date
    val SORT: String get() = L.searchResult.filters.sort
    val APPLY: String get() = L.composeTranslate.apply

    fun typeLabel(type: com.ecency.core.api.search.SearchType) = when (type) {
        com.ecency.core.api.search.SearchType.ALL -> L.notification.activities
        com.ecency.core.api.search.SearchType.POST -> L.home.posts
        com.ecency.core.api.search.SearchType.COMMENT -> L.search.comments
    }
}

/**
 * RN searchResultScreen: one search bar (500 ms debounce) over three tabs. Only the tab on screen loads; the
 * others load when shown (RN's TabView ran all three at every keystroke pause).
 *
 * - Posts: a pasted post link or `@author/permlink` opens that post's row; otherwise the search API with the
 *   filters (RN postsResultsContainer), paged by scroll id and deduped; nothing typed shows @ecency's latest 7.
 * - People: `lookup_accounts` for the text (or the author of a pasted link).
 * - Communities: `list_communities` by rank matching the text (100), or 20 shuffled when empty.
 */
class SearchModel(private val env: AppEnvironment, private val debounceMillis: Long = DEBOUNCE_MILLIS) :
    StateModel<SearchState>(SearchState(), env.dispatchers) {
    private var debounce: Job? = null
    private val jobs = mutableMapOf<SearchTab, Job>()
    private val stale = mutableSetOf(SearchTab.POSTS, SearchTab.PEOPLE, SearchTab.COMMUNITIES)
    private var scrollId: String? = null
    private var communities: List<Community> = emptyList()

    init {
        scope.launch { env.communities.state.collect { rebuildCommunities() } }
        scope.launch {
            env.events.collect {
                if (it is AppEvent.SessionChanged) {
                    // fetched for the previous observer: its join state must not show for this account
                    communities = communities.map { c -> c.copy(observerRole = null, observerSubscribed = null) }
                    rebuildCommunities()
                    invalidate()
                }
            }
        }
    }

    fun start() {
        setState { it.copy(loggedIn = env.session.current.isLoggedIn) }
        loadIfStale(current.tab)
    }

    fun setInput(text: String) {
        setState { it.copy(input = text) }
        debounce?.cancel()
        debounce = scope.launch {
            delay(debounceMillis)
            if (text != current.query) {
                setState { it.copy(query = text) }
                invalidate()
            }
        }
    }

    /** Searches [text] at once (keyboard search button). */
    fun submit(text: String = current.input) {
        debounce?.cancel()
        setState { it.copy(input = text, query = text) }
        invalidate()
    }

    fun selectTab(tab: SearchTab) {
        setState { it.copy(tab = tab) }
        loadIfStale(tab)
    }

    /** RN search filters sheet: applied only when valid for the current text ([validateFilters]). */
    fun applyFilters(filters: SearchFilters) {
        if (filters == current.filters) return
        setState { it.copy(filters = filters) }
        stale += SearchTab.POSTS
        loadIfStale(current.tab)
    }

    /** RN filters sheet apply check, against the live input (not the debounced one). Null when valid. */
    fun validateFilters(filters: SearchFilters): String? =
        SearchQueries.validate(SearchQueries.build(SearchQueries.clip(current.input), filters).q)?.message

    fun loadMorePosts() {
        val p = current.posts
        if (p.mode != SearchPostsMode.SEARCH || jobs[SearchTab.POSTS]?.isActive == true || p.endReached || scrollId == null || p.moreError != null) return
        loadPosts(more = true)
    }

    fun retry() {
        when (current.tab) {
            SearchTab.POSTS -> if (current.posts.moreError != null) {
                setState { it.copy(posts = it.posts.copy(moreError = null)) }
                loadPosts(more = true)
            } else {
                loadPosts(more = false)
            }
            SearchTab.PEOPLE -> loadPeople()
            SearchTab.COMMUNITIES -> loadCommunities()
        }
    }

    /** Join or leave from the communities tab; false when signed out. */
    fun toggle(row: CommunityRow): Boolean = env.communities.toggleSubscription(row.name, row.title, row.subscribed)

    private fun invalidate() {
        stale += SearchTab.entries
        setState { it.copy(loggedIn = env.session.current.isLoggedIn) }
        loadIfStale(current.tab)
    }

    private fun loadIfStale(tab: SearchTab) {
        if (tab !in stale) return
        stale -= tab
        when (tab) {
            SearchTab.POSTS -> loadPosts(more = false)
            SearchTab.PEOPLE -> loadPeople()
            SearchTab.COMMUNITIES -> loadCommunities()
        }
    }

    private fun loadPosts(more: Boolean) {
        jobs[SearchTab.POSTS]?.cancel()
        val value = current.clipped
        val filters = current.filters
        val user = env.session.current.username
        val url = PostUrlParser.parse(value)
        val postUrl = url?.author?.takeIf { it.isNotEmpty() }?.let { a -> url.permlink?.takeIf { it.isNotEmpty() }?.let { a to it } }
        val mode = when {
            postUrl != null -> SearchPostsMode.POST_URL
            value.isNotEmpty() || filters.hasActive -> SearchPostsMode.SEARCH
            else -> SearchPostsMode.INITIAL
        }
        val q = SearchQueries.build(value, filters).q
        val validation = if (mode == SearchPostsMode.SEARCH) SearchQueries.validate(q) else null
        if (validation != null) {
            scrollId = null
            setState { it.copy(posts = SearchPostsState(mode = mode, validationError = validation.message, loaded = true)) }
            return
        }
        if (!more) scrollId = null
        setState { s ->
            s.copy(posts = if (more) s.posts.copy(loadingMore = true, moreError = null) else SearchPostsState(mode = mode, loading = true))
        }
        jobs[SearchTab.POSTS] = scope.launch {
            try {
                val rows: List<SearchPostRow>
                var end = true
                when (mode) {
                    SearchPostsMode.POST_URL -> {
                        val post = env.api.posts.getPost(postUrl!!.first, postUrl.second, user)
                        rows = listOfNotNull(post?.let { SearchPostRow.of(it, env.proxyBase) })
                    }
                    SearchPostsMode.INITIAL -> {
                        val page = env.api.feeds.page(FeedSource.Account(INITIAL_AUTHOR, AccountPostsSort.BLOG), null, user, INITIAL_LIMIT)
                        rows = page.posts.map { SearchPostRow.of(it, env.proxyBase) }
                    }
                    SearchPostsMode.SEARCH -> {
                        val since = SearchQueries.since(filters.date, env.nowMillis())
                        val page = env.searchApi.search(q, filters.sort, HIDE_LOW, since, if (more) scrollId else null)
                        rows = page.hits.map { SearchPostRow.of(it, env.proxyBase) }
                        scrollId = page.scrollId
                        end = page.hits.isEmpty() || page.scrollId == null
                    }
                }
                setState { s ->
                    val items = if (more) s.posts.items + rows else rows
                    s.copy(posts = s.posts.copy(items = items.distinctBy { it.key }, loading = false, loadingMore = false, endReached = end, loaded = true))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = message(e)
                setState { s ->
                    if (more) s.copy(posts = s.posts.copy(loadingMore = false, moreError = message))
                    else s.copy(posts = s.posts.copy(loading = false, error = message, loaded = true))
                }
            }
        }
    }

    private fun loadPeople() {
        jobs[SearchTab.PEOPLE]?.cancel()
        val value = current.clipped
        if (value.isEmpty()) {
            setState { it.copy(people = SearchPeopleState()) }
            return
        }
        val name = PostUrlParser.parse(value)?.author?.takeIf { it.isNotEmpty() } ?: value
        setState { it.copy(people = SearchPeopleState(loading = true)) }
        jobs[SearchTab.PEOPLE] = scope.launch {
            try {
                val names = env.communityApi.lookupAccounts(name, PEOPLE_LIMIT)
                setState { it.copy(people = SearchPeopleState(items = names)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(people = SearchPeopleState(error = SearchCopy.ERROR)) }
            }
        }
    }

    private fun loadCommunities() {
        jobs[SearchTab.COMMUNITIES]?.cancel()
        val value = current.clipped
        setState { it.copy(communities = SearchCommunitiesState(loading = true)) }
        jobs[SearchTab.COMMUNITIES] = scope.launch {
            try {
                val found = env.communityApi.communities(
                    CommunitySort.RANK, value.ifEmpty { null }, if (value.isEmpty()) 20 else 100, env.session.current.username,
                )
                communities = if (value.isEmpty()) found.shuffled() else found
                setState { it.copy(communities = it.communities.copy(loading = false, loaded = true)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                communities = emptyList()
                setState { it.copy(communities = SearchCommunitiesState(error = SearchCopy.ERROR, loaded = true)) }
            }
            rebuildCommunities()
        }
    }

    private fun rebuildCommunities() {
        val subs = env.communities.current
        setState { it.copy(communities = it.communities.copy(rows = communities.map { c -> CommunitiesModel.row(c, subs) })) }
    }

    private fun message(e: Exception): String = when (e) {
        is SearchApiException -> SearchCopy.ERROR
        else -> AppError.from(e).let { if (it.kind == ErrorKind.NETWORK) it.message else SearchCopy.ERROR }
    }

    companion object {
        const val DEBOUNCE_MILLIS = 500L

        /** RN never hid low-payout results in the app. */
        const val HIDE_LOW = false

        /** RN shows @ecency's latest posts before anything is typed. */
        const val INITIAL_AUTHOR = "ecency"
        const val INITIAL_LIMIT = 7

        /** SDK lookupAccountsQueryOptions default. */
        const val PEOPLE_LIMIT = 50

        /** Case-insensitive ranges of [word] in [text] (RN Highlighter on the snippet). */
        fun highlights(text: String, word: String): List<IntRange> {
            if (word.isBlank()) return emptyList()
            val out = mutableListOf<IntRange>()
            var from = 0
            while (true) {
                val i = text.indexOf(word, from, ignoreCase = true)
                if (i < 0) break
                out += i until i + word.length
                from = i + word.length
            }
            return out
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Account autocomplete (RN communityRoleEditSheet lookup; reusable by the editor's @ mentions)

data class AccountLookupState(
    /** The normalized name typed (lowercase, no leading @). */
    val input: String = "",
    val suggestions: List<String> = emptyList(),
    /** [input] is an existing account: `lookup_accounts` answered it exactly (it returns neighbours for anything). */
    val known: Boolean = false,
    val searching: Boolean = false,
)

/**
 * An account name field with suggestions: `lookup_accounts` after a pause in typing, the first [maxSuggestions]
 * names, and whether the text is a real account (exact match only, as RN's role sheet and transfer screen).
 */
class AccountLookupModel(
    private val env: AppEnvironment,
    private val maxSuggestions: Int = 6,
    private val debounceMillis: Long = 300,
    private val limit: Int = 20,
) : StateModel<AccountLookupState>(AccountLookupState(), env.dispatchers) {
    private var job: Job? = null

    /** A name already known to exist (opened for a given member). */
    fun seed(name: String) {
        job?.cancel()
        setState { AccountLookupState(input = name, known = name.isNotEmpty()) }
    }

    fun setInput(text: String) {
        val name = normalize(text)
        job?.cancel()
        setState { AccountLookupState(input = name, suggestions = if (name.isEmpty()) emptyList() else it.suggestions, searching = name.isNotEmpty()) }
        if (name.isEmpty()) return
        job = scope.launch {
            delay(debounceMillis)
            try {
                val results = env.communityApi.lookupAccounts(name, limit)
                setState { it.copy(suggestions = results.take(maxSuggestions), known = name in results, searching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(suggestions = emptyList(), known = false, searching = false) }
            }
        }
    }

    fun pick(name: String) {
        job?.cancel()
        setState { AccountLookupState(input = name, known = true) }
    }

    companion object {
        /** RN _normalize: trimmed, lowercased, one leading @ dropped. */
        fun normalize(text: String): String = com.ecency.core.render.js.Js.toLowerCase(com.ecency.core.render.js.Js.trim(text)).removePrefix("@")
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Tag result (RN screens/tagResult)

data class TagResultState(val input: String, val tag: String, val tab: TagTab)

/** RN GLOBAL_POST_FILTERS_VALUE tabs of a tag. */
enum class TagTab(val sort: RankedSort, val filter: String, private val labelText: () -> String) {
    TRENDING(RankedSort.TRENDING, "trending", { L.home.trending }),
    HOT(RankedSort.HOT, "hot", { L.home.hot }),
    NEW(RankedSort.CREATED, "created", { L.home.new }),
    ;

    val label: String get() = labelText()

    companion object {
        fun of(filter: String?): TagTab = entries.firstOrNull { it.filter == filter } ?: TRENDING
    }
}

/** RN tagResultScreen: a `#` search field (1 s debounce) over the tag's trending / hot / new feeds. */
class TagResultModel(private val env: AppEnvironment, tag: String, filter: String = "", private val debounceMillis: Long = 1000) :
    StateModel<TagResultState>(TagResultState(tag.trim(), tag.trim(), TagTab.of(filter)), env.dispatchers) {
    private var job: Job? = null

    fun setInput(text: String) {
        setState { it.copy(input = text) }
        job?.cancel()
        job = scope.launch {
            delay(debounceMillis)
            setState { it.copy(tag = tagOf(text)) }
        }
    }

    fun selectTab(tab: TagTab) = setState { it.copy(tab = tab) }

    fun feedSource(tab: TagTab = current.tab, tag: String = current.tag): FeedSource = FeedSource.Ranked(tab.sort, tag)

    /** The field shows RN's "#" prefix; a typed "#" is not part of the tag. */
    private fun tagOf(text: String) = text.trim().removePrefix("#")
}

// ---------------------------------------------------------------------------------------------------------------
// Explore (RN dappBrowser home): the dApp directory and the URL field

data class Dapp(val name: String, val url: String, val icon: String, val color: String, val initial: String, val category: String)

object Dapps {
    val TITLE: String get() = L.dappBrowser.title
    val SUBTITLE: String get() = L.dappBrowser.subtitle
    val SEARCH_PLACEHOLDER: String get() = L.dappBrowser.searchPlaceholder
    val POPULAR: String get() = L.dappBrowser.popularDapps
    /** RN dapp_browser.signing_info without its dash (native wording, so its own key). */
    val SIGNING_INFO: String get() = L.native.dapps.signingInfo

    private fun favicon(domain: String) = "https://www.google.com/s2/favicons?domain=$domain&sz=128"

    /** RN DAPP_DIRECTORY, in order (categories in the current language). */
    val DIRECTORY: List<Dapp> get() = listOf(
        Dapp("Ecency", "https://ecency.com", favicon("ecency.com"), "#357CE6", "E", L.native.dapps.categorySocial),
        Dapp("Raidstead", "https://ecency.com/raidstead", "https://ecency.com/assets/raidstead-icon.png", "#7A4BB4", "R", L.native.dapps.categoryGaming),
        Dapp("PeakD", "https://peakd.com", favicon("peakd.com"), "#E31337", "P", L.native.dapps.categorySocial),
        Dapp("Hive.blog", "https://hive.blog", favicon("hive.blog"), "#E31337", "H", L.native.dapps.categorySocial),
        Dapp("Splinterlands", "https://splinterlands.com", favicon("splinterlands.com"), "#F5A623", "S", L.native.dapps.categoryGaming),
        Dapp("Rising Star", "https://www.risingstargame.com", favicon("risingstargame.com"), "#E91E63", "R", L.native.dapps.categoryGaming),
        Dapp("Tribaldex", "https://tribaldex.com", favicon("tribaldex.com"), "#00B4D8", "T", L.native.dapps.categoryDefi),
        Dapp("LeoDex", "https://leodex.io", favicon("leodex.io"), "#F5A623", "L", L.native.dapps.categoryDefi),
        Dapp("HiveEngine", "https://hive-engine.com", favicon("hive-engine.com"), "#E31337", "HE", L.native.dapps.categoryDefi),
        Dapp("3Speak", "https://3speak.tv", favicon("3speak.tv"), "#E74C3C", "3S", L.editor.video),
        Dapp("Liketu", "https://liketu.com", favicon("liketu.com"), "#FF69B4", "Li", L.native.dapps.categorySocial),
        Dapp("InLeo", "https://inleo.io", favicon("inleo.io"), "#F5A623", "IL", L.native.dapps.categorySocial),
        Dapp("Altera", "https://altera.magi.eco", favicon("altera.magi.eco"), "#6C5CE7", "A", L.native.dapps.categoryDefi),
    )

    private val HOST_LIKE = Regex("^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?\\.[a-zA-Z]{2,}(/.*)?$")

    /** RN _normalizeUrl: http(s) kept, a host gets https://, anything else becomes a Google search. Null when blank. */
    fun normalizeUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
        if (HOST_LIKE.matches(t)) return "https://$t"
        return "https://www.google.com/search?q=" + encodeComponent(t)
    }

    /** JS encodeURIComponent (lone surrogates, which JS refuses, are dropped). */
    internal fun encodeComponent(s: String): String {
        val js = com.ecency.core.render.js.Js
        val encoded = try {
            js.encodeUri(s)
        } catch (e: Exception) {
            js.encodeUri(s.filterNot { it.isSurrogate() })
        }
        val sb = StringBuilder(encoded.length)
        for (c in encoded) {
            if (c in ";/?:@&=+$,#") sb.append('%').append(c.code.toString(16).uppercase().padStart(2, '0')) else sb.append(c)
        }
        return sb.toString()
    }

    /** The dApp browser opened on [url] (RN DAPP_BROWSER with `url`). */
    fun screen(url: String): AppScreen = AppScreen.DappBrowser(url)
}
