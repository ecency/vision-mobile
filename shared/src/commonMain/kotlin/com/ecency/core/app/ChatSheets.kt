package com.ecency.core.app

import com.ecency.core.api.chat.ChatApiException
import com.ecency.core.api.chat.ChatUser
import com.ecency.core.api.chat.firstText
import com.ecency.core.l10n.L
import com.ecency.core.render.blocks.SafeTarget
import com.ecency.core.render.js.Js
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** A person offered by the new group search: `@name` and the avatar's account (null when not a valid Hive name). */
data class ChatPerson(val username: String, val account: String?)

data class ChatNewGroupState(
    val term: String = "",
    val results: List<ChatPerson> = emptyList(),
    val searching: Boolean = false,
    val searchFailed: Boolean = false,
    val selected: List<String> = emptyList(),
    val creating: Boolean = false,
    /** The server's refusal, or RN's fallback. */
    val error: String? = null,
    /** The group that was started; the sheet closes with it. */
    val createdChannelId: String? = null,
) {
    val atLimit: Boolean get() = selected.size >= ChatGroups.MAX_OTHERS
    val canCreate: Boolean get() = selected.size >= ChatGroups.MIN_OTHERS && !creating

    /** RN shows the results area once 2 characters are typed (and the group is not full). */
    val showsResults: Boolean get() = Js.trim(term).length >= 2 && !atLimit

    /** RN's text under the field when nothing shows. */
    val emptyText: String get() = if (searchFailed) SEARCH_FAILED else NO_RESULTS

    val placeholder: String get() = if (atLimit) L.native.chatSheets.groupLimit(max = ChatGroups.MAX_OTHERS) else L.native.chatSheets.searchPeopleByUsername

    companion object {
        val TITLE: String get() = L.native.chatSheets.newGroupTitle
        val HINT: String get() = L.native.chatSheets.newGroupHint(min = ChatGroups.MIN_OTHERS.toString(), max = ChatGroups.MAX_OTHERS)
        val NO_RESULTS: String get() = L.native.chatSheets.noResults
        val SEARCH_FAILED: String get() = L.native.chatSheets.searchFailed
        val CREATE: String get() = L.native.chatSheets.create
        val FAILED: String get() = L.native.chatSheets.newGroupFailed
    }
}

/**
 * RN chatNewGroupSheet: picks 2 to 7 people (the viewer and people already picked are not offered) and opens a
 * group with them. The server checks that everyone accepts messages; its refusal text is shown as it came.
 */
class ChatNewGroupModel internal constructor(private val env: AppEnvironment) : StateModel<ChatNewGroupState>(ChatNewGroupState(), env.dispatchers) {
    private val owner = env.session.current.username
    private var searchJob: Job? = null

    private val self: String get() = owner.orEmpty().lowercase()

    fun setTerm(text: String) {
        setState { it.copy(term = text) }
        searchJob?.cancel()
        val q = Js.trim(text)
        if (q.length < 2 || current.atLimit) {
            setState { it.copy(results = emptyList(), searching = false) }
            return
        }
        val u = owner?.takeIf { env.session.current.username == it } ?: return
        setState { it.copy(searching = true, searchFailed = false) }
        searchJob = scope.launch {
            delay(ChatsModel.SEARCH_DEBOUNCE_MILLIS)
            try {
                val users = env.chat.call(u) { env.chat.api.searchUsers(it.token, q) }
                setState { it.copy(results = people(users), searching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setState { it.copy(results = emptyList(), searchFailed = true, searching = false) }
            }
        }
    }

    private fun people(users: List<ChatUser>): List<ChatPerson> =
        users.map { u -> firstText(u.hiveName, u.username).orEmpty().lowercase() }
            .filter { it.isNotEmpty() && it != self }
            .distinct()
            .map { ChatPerson(it, SafeTarget.account(it)) }

    /** The results that can still be picked (RN visibleResults). */
    val visible: List<ChatPerson> get() = current.results.filter { it.username !in current.selected }

    fun add(username: String) {
        val s = current
        if (s.atLimit || username.isEmpty() || username in s.selected) return
        setState { it.copy(selected = it.selected + username, term = "", results = emptyList(), error = null) }
        searchJob?.cancel()
    }

    fun remove(username: String) = setState { it.copy(selected = it.selected - username, error = null) }

    /** RN `_create`: `POST /group`; the server's text on a refusal. */
    fun create() {
        val s = current
        if (!s.canCreate) return
        val u = owner?.takeIf { env.session.current.username == it } ?: return
        setState { it.copy(creating = true, error = null) }
        scope.launch {
            try {
                val id = env.chat.call(u) { env.chat.api.createGroup(it.token, s.selected) }
                setState { it.copy(creating = false, createdChannelId = id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(creating = false, error = (e as? ChatApiException)?.message ?: ChatsModel.message(e, ChatNewGroupState.FAILED)) }
            }
        }
    }
}

data class ChatRenameState(
    val value: String = "",
    val initial: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    /** The saved name (empty: the name was removed); the sheet closes with it. */
    val saved: String? = null,
) {
    val trimmed: String get() = Js.trim(value)
    val unchanged: Boolean get() = trimmed == Js.trim(initial)

    /** Counted as the server counts, so an emoji is one character. */
    val tooLong: Boolean get() = ChatGroups.nameLength(trimmed) > ChatGroups.NAME_MAX_LENGTH
    val canSave: Boolean get() = !unchanged && !tooLong && !saving

    /** RN's line under the field. */
    val message: String? get() = if (tooLong) L.native.chatSheets.nameLimit(max = ChatGroups.NAME_MAX_LENGTH) else error

    companion object {
        val TITLE: String get() = L.native.chatSheets.renameTitle
        val HINT: String get() = L.native.chatSheets.renameHint
        val PLACEHOLDER: String get() = L.native.chatSheets.placeholder
        val NOT_OWNER: String get() = L.native.chatSheets.notOwner
        val FAILED: String get() = L.native.chatSheets.renameFailed
    }
}

/** RN chatRenameGroupSheet: the owner names a group or clears its name; the list and the conversation follow. */
class ChatRenameModel internal constructor(private val env: AppEnvironment, private val channelId: String, currentName: String) :
    StateModel<ChatRenameState>(ChatRenameState(currentName, currentName), env.dispatchers) {
    private val owner = env.session.current.username

    fun setValue(text: String) = setState { it.copy(value = text, error = null) }

    fun save() {
        val s = current
        if (!s.canSave) return
        val u = owner?.takeIf { env.session.current.username == it } ?: return
        setState { it.copy(saving = true, error = null) }
        scope.launch {
            try {
                val name = env.chat.call(u) { env.chat.api.renameGroup(it.token, channelId, s.trimmed) } ?: s.trimmed
                env.chat.rememberRename(channelId, name, u)
                setState { it.copy(saving = false, saved = name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val notOwner = (e as? ChatApiException)?.code == "not_owner"
                setState { it.copy(saving = false, error = if (notOwner) ChatRenameState.NOT_OWNER else ChatRenameState.FAILED) }
            }
        }
    }
}
