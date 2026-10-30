package com.ecency.app.ui

import androidx.compose.foundation.layout.heightIn

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.semantics


import androidx.compose.ui.semantics.Role

import androidx.compose.ui.text.style.TextDirection
import com.ecency.core.app.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ecency.core.api.search.SearchDate
import com.ecency.core.api.search.SearchFilters
import com.ecency.core.api.search.SearchSort
import com.ecency.core.api.search.SearchType
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppScreen
import com.ecency.core.app.Dapps
import com.ecency.core.app.PostCard
import com.ecency.core.app.SearchCopy
import com.ecency.core.app.SearchModel
import com.ecency.core.app.SearchPostRow
import com.ecency.core.app.SearchPostsMode
import com.ecency.core.app.SearchTab
import com.ecency.core.app.TagResultModel
import com.ecency.core.app.TagTab
import com.ecency.core.app.TimeAgo
import com.ecency.core.app.UiCopy

// ---------------------------------------------------------------------------------------------------------------
// Search (RN searchResult)

/** RN SearchInput: rounded field with back, an optional prefix and a clear button. */
@Composable
fun SearchBar(
    value: String,
    onValue: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    placeholder: String = SearchCopy.PLACEHOLDER,
    onSubmit: (() -> Unit)? = null,
) {
    val colors = LocalEcencyColors.current
    Row(modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onBack, role = Role.Button).padding(10.dp).testTag("back")) {
            Glyph(AppIcon.BACK, colors.darkGray, 24.dp, description = UiCopy.BACK)
        }
        Row(
            // a stable 48 dp host: the clear button appearing never moves the layout; a large font grows it
            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).background(colors.lightBackground).testTag("searchBar").padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(AppIcon.SEARCH, colors.icon, 18.dp)
            if (prefix != null) Text(prefix, color = colors.text, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
            BasicTextField(
                value = value, onValueChange = onValue, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = colors.text, fontSize = 15.sp, textDirection = TextDirection.Content),
                cursorBrush = SolidColor(colors.blueText),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }),
                modifier = Modifier.weight(1f).padding(start = if (prefix == null) 8.dp else 2.dp).testTag("searchInput"),
                // the placeholder inside the field, so TalkBack reads it as the field's hint; it wraps when long
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, color = colors.darkGray, fontSize = 15.sp)
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                Box(Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onValue("") }.padding(2.dp).testTag("clearSearch")) {
                    Glyph(AppIcon.CLEAR, colors.icon, 18.dp, description = UiCopy.CLEAR, filled = true)
                }
            }
        }
    }
}

@Composable
fun SearchScreen(model: SearchModel, env: AppEnvironment, entry: StackEntry, now: Long, nav: ScreenNav) {
    val state by model.state.collectAsState()
    val colors = LocalEcencyColors.current
    LaunchedEffect(model) { model.start() }
    var filtersOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SearchBar(state.input, model::setInput, nav.pop, Modifier.weight(1f), onSubmit = { model.submit() })
        Box(Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { filtersOpen = true }.padding(10.dp).testTag("searchFilters")) {
            // tune has no outline variant: an active filter is shown by colour, as RN
            Glyph(AppIcon.TUNE, if (state.activeFilterCount > 0) colors.blueText else colors.icon, 22.dp, description = SearchCopy.FILTERS)
        }
    }
    UnderlineTabs(SearchTab.entries.map { it.label }, SearchTab.entries.map { it.name }, state.tab.ordinal, { model.selectTab(SearchTab.entries[it]) }, tagPrefix = "searchTab")
    Box(Modifier.fillMaxSize()) {
        when (state.tab) {
            SearchTab.POSTS -> SearchPosts(model, state.posts, state.clipped, env.proxyBase, now, entry.get("posts") { LazyListState() }, nav)
            SearchTab.PEOPLE -> LazyColumn(Modifier.fillMaxSize().testTag("peopleResults"), state = entry.get("people") { LazyListState() }) {
                if (state.people.loading) item { LoadingBlock(Modifier.padding(top = 48.dp)) }
                state.people.emptyText?.let { item { EsieMessageBlock(com.ecency.core.esie.EsiePlacement.EMPTY, it, Modifier.padding(top = 48.dp)) } }
                itemsIndexed(state.people.items, key = { _, n -> n }) { index, name ->
                    Row(
                        Modifier.fillMaxWidth().background(if (index % 2 != 0) colors.lightBackground else colors.background)
                            .clickable(role = Role.Button) { nav.push(AppScreen.Profile(name)) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("person:$name"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(PostCard.avatarUrl(name, env.proxyBase), 36.dp)
                        Text("@$name", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            SearchTab.COMMUNITIES -> LazyColumn(Modifier.fillMaxSize().testTag("communityResults"), state = entry.get("communities") { LazyListState() }) {
                val c = state.communities
                if (c.loading && c.rows.isEmpty()) item { LoadingBlock(Modifier.padding(top = 48.dp)) }
                c.emptyText?.let { item { EsieMessageBlock(if (c.error != null) com.ecency.core.esie.EsiePlacement.ERROR else com.ecency.core.esie.EsiePlacement.EMPTY, it, Modifier.padding(top = 48.dp), UiCopy.RETRY.takeIf { _ -> c.error != null }) { model.retry() } } }
                itemsIndexed(c.rows, key = { _, r -> r.name }) { index, row ->
                    CommunityListRow(row, index, PostCard.avatarUrl(row.name, env.proxyBase), { nav.push(AppScreen.Community(row.name)) }, {
                        if (!model.toggle(row)) nav.login()
                    }, showJoin = state.loggedIn)
                }
            }
        }
    }
    if (filtersOpen) {
        SearchFiltersSheet(state.filters, model::validateFilters, onApply = { filtersOpen = false; model.applyFilters(it) }, onDismiss = { filtersOpen = false })
    }
}

@Composable
private fun SearchPosts(model: SearchModel, posts: com.ecency.core.app.SearchPostsState, query: String, proxy: String, now: Long, listState: LazyListState, nav: ScreenNav) {
    LoadMoreEffect(listState, posts.items.size) { model.loadMorePosts() }
    LazyColumn(Modifier.fillMaxSize().testTag("postResults"), state = listState) {
        if (posts.loading) item { LoadingBlock(Modifier.padding(top = 48.dp)) }
        posts.emptyText?.let { text -> item { EsieMessageBlock(if (posts.error != null) com.ecency.core.esie.EsiePlacement.ERROR else com.ecency.core.esie.EsiePlacement.EMPTY, text, Modifier.padding(top = 48.dp), UiCopy.RETRY.takeIf { posts.error != null }) { model.retry() } } }
        itemsIndexed(posts.items, key = { _, r -> r.key }) { index, row ->
            SearchPostItem(row, index, if (posts.mode == SearchPostsMode.SEARCH) query else "", now) { nav.push(AppScreen.Post(row.author, row.permlink)) }
        }
        if (posts.loadingMore) item { LoadingBlock() }
        posts.moreError?.let { item { MessageBlock(it, actionLabel = UiCopy.RETRY) { model.retry() } } }
    }
}

/** RN postsResults item: author header, title, highlighted snippet, payout, votes and comments. */
@Composable
private fun SearchPostItem(row: SearchPostRow, index: Int, highlight: String, now: Long, onOpen: () -> Unit) {
    val colors = LocalEcencyColors.current
    Column(
        Modifier.fillMaxWidth().background(if (index % 2 != 0) colors.lightBackground else colors.background)
            .clickable(onClick = onOpen, role = Role.Button).padding(16.dp).testTag("result:${row.key}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(row.avatarUrl, 36.dp)
            Text(row.author, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
            if (row.createdEpochMillis > 0) Text(" • ${TimeAgo.short(row.createdEpochMillis, now)}", color = colors.darkGray, fontSize = 13.sp)
        }
        Text(row.title, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        if (row.snippet.isNotEmpty()) {
            val text = remember(row.snippet, highlight, colors) {
                buildAnnotatedString {
                    var at = 0
                    SearchModel.highlights(row.snippet, highlight).forEach { r ->
                        append(row.snippet.substring(at, r.first))
                        withStyle(SpanStyle(background = colors.darkGrayBackground, color = Color.White)) { append(row.snippet.substring(r.first, r.last + 1)) }
                        at = r.last + 1
                    }
                    append(row.snippet.substring(at))
                }
            }
            Text(text, color = colors.darkGray, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            row.payoutText?.let { Text(it, color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp)) }
            Glyph(AppIcon.HEART, colors.darkGray, 16.dp)
            Text("${row.votes}", color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, end = 12.dp))
            Glyph(AppIcon.COMMENT, colors.darkGray, 16.dp)
            Text("${row.comments}", color = colors.darkGray, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** RN searchFiltersSheet: author, category, tags, then type, date and sort chips; validated before it closes. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchFiltersSheet(initial: SearchFilters, validate: (SearchFilters) -> String?, onApply: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalEcencyColors.current
    var f by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp).testTag("filtersSheet")) {
            Text(SearchCopy.FILTERS, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() }.fillMaxWidth().padding(bottom = 12.dp))
            @Composable
            fun field(label: String, placeholder: String, value: String, tag: String, set: (String) -> Unit) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                OutlinedTextField(value, { error = null; set(it) }, placeholder = { Text(placeholder) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("filter:$tag"))
            }
            field(SearchCopy.AUTHOR, SearchCopy.AUTHOR_PLACEHOLDER, f.author, "author") { f = f.copy(author = it) }
            field(SearchCopy.CATEGORY, SearchCopy.CATEGORY_PLACEHOLDER, f.category, "category") { f = f.copy(category = it) }
            field(SearchCopy.TAGS, SearchCopy.TAGS_PLACEHOLDER, f.tags, "tags") { f = f.copy(tags = it) }
            @Composable
            fun <T> chips(label: String, options: List<T>, selected: T, name: (T) -> String, set: (T) -> Unit) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.text, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { o ->
                        val on = o == selected
                        Text(
                            name(o), fontSize = 13.sp, color = if (on) Color.White else colors.text,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) colors.blue else colors.lightBackground)
                                .clickable(role = Role.Button) { error = null; set(o) }.padding(horizontal = 14.dp, vertical = 6.dp).testTag("chip:${name(o)}"),
                        )
                    }
                }
            }
            chips(SearchCopy.TYPE, listOf(SearchType.ALL, SearchType.POST, SearchType.COMMENT), f.type, SearchCopy::typeLabel) { f = f.copy(type = it) }
            chips(SearchCopy.DATE, listOf(SearchDate.ALL, SearchDate.WEEK, SearchDate.MONTH, SearchDate.YEAR), f.date, { it.label }) { f = f.copy(date = it) }
            chips(SearchCopy.SORT, SearchSort.entries, f.sort, { it.label }) { f = f.copy(sort = it) }
            error?.let { Text(it, color = colors.redText, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(top = 12.dp).testTag("filterError")) }
            Button(
                onClick = { val e = validate(f); if (e != null) error = e else onApply(f) },
                colors = ButtonDefaults.buttonColors(contentColor = androidx.compose.ui.graphics.Color.White, containerColor = colors.blue),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("applyFilters"),
            ) { Text(SearchCopy.APPLY, color = Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Tag result (RN tagResult)

@Composable
fun TagResultScreen(model: TagResultModel, env: AppEnvironment, entry: StackEntry, now: Long, feedActions: FeedActions, nav: ScreenNav) {
    val state by model.state.collectAsState()
    SearchBar(state.input, model::setInput, nav.pop, Modifier.fillMaxWidth(), prefix = "#", placeholder = "")
    PillTabs(TagTab.entries.map { it.label }, TagTab.entries.map { it.name }, state.tab.ordinal, { model.selectTab(TagTab.entries[it]) })
    val tab = state.tab
    val tag = state.tag
    key(tab, tag) {
        Guarded({ entry.get("feed:${tab.name}:$tag") { env.feedModel(model.feedSource(tab, tag)) } }) { feed ->
            FeedList(feed, entry.get("list:${tab.name}:$tag") { LazyListState() }, env.votes.state, now, feedActions, scrollTopModifier = Modifier.navigationBarsPadding())
        }
    }
}

