package com.ecency.core.app

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.community.CommunitySort
import com.ecency.core.api.ecency.GalleryImage
import com.ecency.core.api.ecency.ImageSigning
import com.ecency.core.api.ecency.ImageUploader
import com.ecency.core.api.ecency.RemoteDraft
import com.ecency.core.api.ecency.ScheduledPost
import com.ecency.core.api.ecency.Snippet
import com.ecency.core.api.ecency.UploadFile
import com.ecency.core.auth.AuthException
import com.ecency.core.editor.MediaInsert
import com.ecency.core.editor.MediaInsertStatus
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

// ---- drafts, schedules, templates (RN screens/drafts) ----

data class DraftsState(
    val username: String? = null,
    /** 0 drafts, 1 schedules, 2 templates. */
    val tab: Int = 0,
    val drafts: List<RemoteDraft> = emptyList(),
    val templates: List<RemoteDraft> = emptyList(),
    val schedules: List<ScheduledPost> = emptyList(),
    /** The id-less draft on this device, shown first on the Drafts tab while it has content (RN "Unsynced Draft"). */
    val localDraft: LocalDraft? = null,
    val loadingDrafts: Boolean = false,
    val loadingSchedules: Boolean = false,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val draftsEnd: Boolean = false,
    val schedulesEnd: Boolean = false,
    /** Long-pressed rows: draft and template ids, and schedule ids, kept apart as in RN. */
    val selectedDrafts: Set<String> = emptySet(),
    val selectedSchedules: Set<String> = emptySet(),
    /** Rows with a request running (clone, delete, move). */
    val busy: Set<String> = emptySet(),
    val error: AppError? = null,
) {
    val hasSelection: Boolean get() = if (tab == 1) selectedSchedules.isNotEmpty() else selectedDrafts.isNotEmpty()
}

class DraftsModel internal constructor(private val env: AppEnvironment, tab: Int) :
    StateModel<DraftsState>(DraftsState(tab = tab.coerceIn(0, 2)), env.dispatchers) {

    private var all: List<RemoteDraft> = emptyList()
    private var draftsNext: Int? = 0
    private var schedulesNext: Int? = 0
    private var draftsJob: Job? = null
    private var schedulesJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.session.awaitLoaded()
            reload()
        }
        scope.launch {
            env.events.collect { e ->
                when (e) {
                    is AppEvent.SessionChanged -> reload()
                    is AppEvent.DraftsChanged -> if (e.username == current.username) reload(keep = true)
                    else -> {}
                }
            }
        }
    }

    fun setTab(tab: Int) = setState { it.copy(tab = tab.coerceIn(0, 2)) }

    /** Pull to refresh: both lists again. */
    fun refresh() {
        setState { it.copy(refreshing = true) }
        reload(keep = true)
    }

    private fun reload(keep: Boolean = false) {
        val user = env.session.current.username
        draftsJob?.cancel()
        schedulesJob?.cancel()
        all = if (keep && user == current.username) all else emptyList()
        draftsNext = 0
        schedulesNext = 0
        // another account's rows never stay on screen
        setState { s -> if (keep && user == s.username) s.copy(error = null) else DraftsState(username = user, tab = s.tab) }
        if (user == null) return
        loadDrafts(user, reset = true)
        loadSchedules(user, reset = true)
        scope.launch {
            val local = env.localDrafts.load(user, LocalDrafts.NEW_POST)?.takeIf { it.updated > 0 && it.hasContent }
            setState { if (it.username == user) it.copy(localDraft = local) else it }
        }
    }

    /** The list's end came into view. */
    fun loadMore() {
        val user = current.username ?: return
        if (current.tab == 1) loadSchedules(user, reset = false) else loadDrafts(user, reset = false)
    }

    private fun publishDrafts(s: DraftsState) = s.copy(drafts = all.filterNot { it.isTemplate }, templates = all.filter { it.isTemplate })

    private fun loadDrafts(user: String, reset: Boolean) {
        if (draftsJob?.isActive == true) return
        val offset = draftsNext ?: return
        setState { it.copy(loadingDrafts = true) }
        draftsJob = scope.launch {
            try {
                var next: Int? = offset
                var pages = 0
                var fresh = if (reset) emptyList() else all
                // RN: a tab that is still empty (only templates so far, or none) keeps loading, 5 pages at most
                do {
                    val page = env.editorApi.drafts(user, next ?: break)
                    fresh = (fresh + page.items).distinctBy { it.id }
                    next = page.nextOffset
                    pages++
                    val shown = if (current.tab == 2) fresh.count { it.isTemplate } else fresh.count { !it.isTemplate }
                } while (next != null && shown == 0 && pages < AUTO_FETCH_PAGE_BUDGET)
                all = fresh
                draftsNext = next
                setState { publishDrafts(it).copy(loadingDrafts = false, loaded = true, refreshing = false, draftsEnd = next == null, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(loadingDrafts = false, loaded = true, refreshing = false, error = AppError.from(e)) }
            }
        }
    }

    private fun loadSchedules(user: String, reset: Boolean) {
        if (schedulesJob?.isActive == true) return
        val offset = schedulesNext ?: return
        setState { it.copy(loadingSchedules = true) }
        schedulesJob = scope.launch {
            try {
                val page = env.editorApi.schedules(user, offset)
                schedulesNext = page.nextOffset
                setState { s ->
                    val list = ((if (reset) emptyList() else s.schedules) + page.items).distinctBy { it.id }
                    s.copy(schedules = list, loadingSchedules = false, schedulesEnd = page.nextOffset == null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(loadingSchedules = false, error = AppError.from(e)) }
            }
        }
    }

    /** Long press, or a tap while a selection is open. */
    fun toggleSelected(id: String) = setState { s ->
        if (s.tab == 1) s.copy(selectedSchedules = s.selectedSchedules.toggle(id)) else s.copy(selectedDrafts = s.selectedDrafts.toggle(id))
    }

    private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

    fun clearSelection() = setState { it.copy(selectedDrafts = emptySet(), selectedSchedules = emptySet()) }

    private fun act(id: String, done: () -> Unit = {}, block: suspend (user: String) -> Unit) {
        val user = current.username ?: return
        if (id in current.busy) return
        setState { it.copy(busy = it.busy + id) }
        scope.launch {
            try {
                block(user)
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                env.ui.toast(L.alert.fail)
            } finally {
                setState { it.copy(busy = it.busy - id) }
            }
        }
    }

    /** After the UI's confirm ("Are you sure you want to remove?"). */
    fun deleteDraft(id: String) = act(id) { user ->
        env.editorApi.deleteDraft(user, id)
        all = all.filter { it.id != id }
        setState { publishDrafts(it).copy(selectedDrafts = it.selectedDrafts - id) }
    }

    /** RN clone: a new draft titled "Copy of <title>". */
    fun cloneDraft(id: String) {
        val draft = all.firstOrNull { it.id == id } ?: return
        act(id) { user ->
            val meta = runCatching { Json.parseToJsonElement(draft.metaJson) }.getOrNull() ?: kotlinx.serialization.json.JsonObject(emptyMap())
            env.editorApi.addDraft(user, L.native.editorSide.copyOf(title = draft.title), draft.body, draft.tags.joinToString(" "), meta)
            reload(keep = true)
        }
    }

    fun deleteSchedule(id: String) = act(id) { user ->
        env.editorApi.deleteSchedule(user, id)
        setState { it.copy(schedules = it.schedules.filter { s -> s.id != id }, selectedSchedules = it.selectedSchedules - id) }
    }

    /** After the UI's "Are you sure to move to drafts?". */
    fun moveScheduleToDrafts(id: String) = act(id, done = { env.ui.toast(L.alert.successMoved) }) { user ->
        env.editorApi.moveScheduleToDrafts(user, id)
        reload(keep = true)
    }

    /** After the UI's "Are you sure you want to remove all selected items?": RN's toast for all, none or some failing. */
    fun deleteSelected() {
        val user = current.username ?: return
        val schedules = current.tab == 1
        val ids = (if (schedules) current.selectedSchedules else current.selectedDrafts).toList()
        if (ids.isEmpty()) return
        setState { it.copy(busy = it.busy + ids) }
        scope.launch {
            var failed = 0
            for (id in ids) {
                try {
                    if (schedules) env.editorApi.deleteSchedule(user, id) else env.editorApi.deleteDraft(user, id)
                    if (!schedules) all = all.filter { it.id != id }
                    setState { s -> if (schedules) s.copy(schedules = s.schedules.filter { it.id != id }) else publishDrafts(s) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
            env.ui.toast(
                when (failed) {
                    0 -> L.alert.success
                    ids.size -> L.alert.fail
                    else -> L.native.editorSide.deletionsFailed(failed = failed.toString(), total = ids.size)
                },
            )
            setState { it.copy(busy = it.busy - ids.toSet(), selectedDrafts = emptySet(), selectedSchedules = emptySet()) }
        }
    }

    /** The unsynced local draft was opened and saved or cleared elsewhere. */
    fun refreshLocalDraft() {
        val user = current.username ?: return
        scope.launch {
            val local = env.localDrafts.load(user, LocalDrafts.NEW_POST)?.takeIf { it.updated > 0 && it.hasContent }
            setState { if (it.username == user) it.copy(localDraft = local) else it }
        }
    }

    companion object {
        /** RN AUTO_FETCH_PAGE_BUDGET. */
        const val AUTO_FETCH_PAGE_BUDGET = 5
    }
}

// ---- image gallery and uploads (RN uploadsGalleryModal) ----

/**
 * The one signed image upload of the app (the editor's uploads, a wave's image): [file] goes to the image host for
 * [user], signed with that account's key or token whoever is signed in by the time it runs. The size guard, the
 * retries and the stall timeout are [ImageUploader]'s; the address the host answers with is accepted only when it
 * is a clean https url on the image host, and the image's ratio is remembered for `image_ratios`.
 */
internal object ImageUploads {
    suspend fun upload(env: AppEnvironment, user: String, file: PickedImage, onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> }): String {
        val account = env.accounts.vaultAccount(user) ?: throw AuthException.NotSignedIn()
        // signing is crypto: never on the main thread
        val token = kotlinx.coroutines.withContext(env.dispatchers.io) { ImageSigning.token(account, env.nowMillis()) }
        val url = ImageUploader(env.imageTransport, env.imageHost, env.uploadRetryDelay).upload(UploadFile(file.bytes, file.name, file.mime), token, onProgress)
        // what the host answered goes into a post body: only a clean https url on the image host is accepted
        if (!UploadNames.isSafeUrl(url, env.imageHost)) throw EcencyHttpException(200, "The image host returned an unexpected address.")
        if (file.width > 0 && file.height > 0) env.knownImageRatios[url] = file.width.toDouble() / file.height
        return url
    }
}

object UploadsCopy {
    /** RN uploads gallery "+" pill: images go into the gallery, not into the body. */
    val ADD_TO_GALLERY: String get() = L.native.uploads.addToGallery
}

/** An image the platform picked and prepared (HEIC converted, resized to 1920 px, off the main thread). */
class PickedImage(val bytes: ByteArray, val name: String, val mime: String, val width: Int = 0, val height: Int = 0)

data class UploadsState(
    val username: String? = null,
    val images: List<GalleryImage> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val endReached: Boolean = false,
    /** Uploads running now. */
    val uploading: Int = 0,
    /** 0..1 over the running uploads, null when none run. */
    val progress: Float? = null,
    /** Gallery rows picked for delete (RN's delete mode). */
    val selected: Set<String> = emptySet(),
    /** RN's "Fail!" alert body after uploads failed; the UI shows it once and calls [UploadsModel.clearAlert]. */
    val alert: String? = null,
    val error: AppError? = null,
)

/**
 * Uploads for one editor and the account's image gallery. An upload belongs to the account that owns the
 * editor: it is signed with that account's key or token even when another account is signed in by the time it
 * runs. Nothing here touches the main thread with image bytes; the platform hands over prepared files.
 */
class UploadsModel internal constructor(private val env: AppEnvironment, private val editor: EditorModel?) :
    StateModel<UploadsState>(UploadsState(), env.dispatchers) {

    private var next: Int? = 0
    private var listJob: Job? = null
    private val uploadJobs = mutableListOf<Job>()

    /** Filenames whose "Uploading..." placeholder is in the body and not resolved yet (RN prepareInsertDispatch). */
    private val inFlight = mutableSetOf<String>()
    private val sent = mutableMapOf<String, Long>()
    private val totals = mutableMapOf<String, Long>()

    private fun owner(): String? = editor?.current?.owner ?: env.session.current.username

    fun start() {
        if (current.loaded || current.loading) return
        refresh()
    }

    fun refresh() {
        val user = owner() ?: return
        listJob?.cancel()
        next = 0
        setState { it.copy(username = user, loading = false) }
        load(reset = true)
    }

    fun loadMore() = load(reset = false)

    private fun load(reset: Boolean) {
        val user = owner() ?: return
        if (listJob?.isActive == true) return
        val offset = next ?: return
        setState { it.copy(loading = true) }
        listJob = scope.launch {
            try {
                val page = env.editorApi.images(user, offset)
                next = page.nextOffset
                setState { s ->
                    s.copy(images = ((if (reset) emptyList() else s.images) + page.items).distinctBy { it.id.ifEmpty { it.url } }, loading = false, loaded = true, endReached = page.nextOffset == null, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(loading = false, loaded = true, error = AppError.from(e)) }
            }
        }
    }

    fun clearAlert() = setState { it.copy(alert = null) }

    /** Work that belongs to these uploads and must not stop when the screen leaves composition (preparing picked files). */
    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private fun dispatch(items: List<MediaInsert>) {
        val model = editor ?: return
        // RN prepareInsertDispatch: uploads of other batches that still own a placeholder must not be touched
        val names = items.mapNotNull { it.filename?.takeIf { n -> n.isNotEmpty() } }.toSet()
        val others = inFlight.filter { it !in names }
        items.forEach { item ->
            val name = item.filename?.takeIf { it.isNotEmpty() } ?: return@forEach
            if (item.status == MediaInsertStatus.UPLOADING) inFlight += name else inFlight -= name
        }
        model.insertMedia(items, others)
    }

    /** Gallery taps: the chosen images go in at the caret. */
    fun insert(urls: List<String>) = dispatch(urls.map { MediaInsert(url = it, status = MediaInsertStatus.READY) })

    /**
     * Uploads [files]. With [insert] each gets an "Uploading..." placeholder at the caret that becomes the image
     * (or disappears when its upload fails); without, the images are only added to the gallery (RN addToUploads).
     */
    fun upload(files: List<PickedImage>, insert: Boolean) {
        val user = owner() ?: return
        val sized = files.filter { it.bytes.size <= ImageUploader.MAX_BYTES }
        if (sized.size < files.size) setState { it.copy(alert = TOO_LARGE) }
        // the name goes into the body as "(Uploading... NAME)": only harmless characters, and unique among running uploads
        val taken = inFlight.toMutableSet()
        val accepted = sized.map { file ->
            var name = UploadNames.safe(file.name)
            var n = 2
            while (name in taken) name = UploadNames.numbered(UploadNames.safe(file.name), n++)
            taken += name
            if (name == file.name) file else PickedImage(file.bytes, name, file.mime, file.width, file.height)
        }
        if (accepted.isEmpty()) return
        if (insert) dispatch(accepted.map { MediaInsert(filename = it.name, status = MediaInsertStatus.UPLOADING) })
        setState { it.copy(uploading = it.uploading + accepted.size, progress = it.progress ?: 0f) }
        editor?.uploadsChanged(accepted.size)
        val job = scope.launch {
            val failures = mutableListOf<Throwable>()
            try {
                coroutineScope {
                    accepted.mapIndexed { index, file ->
                        val id = "${file.name}#$index#${env.nowMillis()}"
                        async {
                            try {
                                val url = uploadOne(user, file, id)
                                if (insert) dispatch(listOf(MediaInsert(url = url, filename = file.name, status = MediaInsertStatus.READY))) else addToGallery(user, url)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failures += e
                                if (insert) dispatch(listOf(MediaInsert(filename = file.name, status = MediaInsertStatus.FAILED)))
                            } finally {
                                sent.remove(id)
                                totals.remove(id)
                                setState { s -> (s.uploading - 1).coerceAtLeast(0).let { left -> s.copy(uploading = left, progress = if (left == 0) null else s.progress) } }
                                editor?.uploadsChanged(-1)
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: CancellationException) {
                // cancelled by the user: nothing of these uploads stays in the body
                if (insert) dispatch(accepted.filter { it.name in inFlight }.map { MediaInsert(filename = it.name, status = MediaInsertStatus.FAILED) })
                throw e
            }
            if (failures.isNotEmpty()) {
                val messages = failures.map { ImageUploader.message(it) }.distinct()
                val text = if (failures.size > 1) L.native.editorSide.uploadsFailed(count = failures.size, messages = messages.joinToString("\n")) else messages.first()
                setState { it.copy(alert = text) }
            }
            if (!insert) refresh()
        }
        uploadJobs += job
        job.invokeOnCompletion { uploadJobs -= job }
    }

    private suspend fun uploadOne(user: String, file: PickedImage, id: String): String =
        ImageUploads.upload(env, user, file) { done, total ->
            // called from the transport's thread: hop to main before touching state
            scope.launch {
                sent[id] = done
                totals[id] = total
                val all = totals.values.sum()
                if (all > 0) setState { it.copy(progress = if (it.uploading == 0) null else (sent.values.sum().toFloat() / all).coerceIn(0f, 1f)) }
            }
        }

    private suspend fun addToGallery(user: String, url: String) {
        try {
            env.editorApi.addImage(user, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EcencyHttpException) {
            // 409: the image is in the gallery already (RN refreshes instead of failing)
            if (e.status != 409) env.ui.toast(L.alert.fail)
        }
    }

    /** Stops every running upload; their placeholders leave the body. */
    fun cancelUploads() {
        uploadJobs.toList().forEach { it.cancel() }
    }

    fun toggleSelected(id: String) = setState { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }

    fun clearSelection() = setState { it.copy(selected = emptySet()) }

    /** RN useMediaDeleteMutation: the selected gallery images, one request each. */
    fun deleteSelected() {
        val user = owner() ?: return
        val ids = current.selected.toList()
        if (ids.isEmpty()) return
        scope.launch {
            try {
                for (id in ids) {
                    env.editorApi.deleteImage(user, id)
                    setState { it.copy(images = it.images.filter { img -> img.id != id }, selected = it.selected - id) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                env.ui.toast(L.native.editorSide.failedToDelete)
                refresh()
            }
        }
    }

    override fun close() {
        // uploads for an editor keep running while its screen is covered; they stop with the editor itself
        super.close()
    }

    companion object {
        val TOO_LARGE: String get() = L.alert.payloadTooLarge
    }
}

// ---- snippets (RN snippetsModal) ----

data class SnippetsState(
    val snippets: List<Snippet> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val endReached: Boolean = false,
    val error: AppError? = null,
)

class SnippetsModel internal constructor(private val env: AppEnvironment) : StateModel<SnippetsState>(SnippetsState(), env.dispatchers) {
    private var next: Int? = 0
    private var job: Job? = null

    fun start() {
        if (!current.loaded && !current.loading) refresh()
    }

    fun refresh() {
        job?.cancel()
        next = 0
        load(reset = true)
    }

    fun loadMore() = load(reset = false)

    private fun load(reset: Boolean) {
        val user = env.session.current.username ?: return
        if (job?.isActive == true) return
        val offset = next ?: return
        setState { it.copy(loading = true) }
        job = scope.launch {
            try {
                val page = env.editorApi.snippets(user, offset)
                next = page.nextOffset
                setState { s -> s.copy(snippets = ((if (reset) emptyList() else s.snippets) + page.items).distinctBy { it.id }, loading = false, loaded = true, endReached = page.nextOffset == null, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(loading = false, loaded = true, error = AppError.from(e)) }
            }
        }
    }

    /** Add ([id] null) or edit. False when the title or body is empty ("Please add title and body for snippet"). */
    fun save(id: String?, title: String, body: String): Boolean {
        val user = env.session.current.username ?: return false
        if (title.isEmpty() || body.isEmpty()) return false
        // shown at once, as RN's optimistic update
        setState { s ->
            val item = Snippet(id ?: "", title, body)
            s.copy(snippets = if (id != null) s.snippets.map { if (it.id == id) item else it } else listOf(item) + s.snippets)
        }
        scope.launch {
            try {
                if (id != null) env.editorApi.updateSnippet(user, id, title, body) else env.editorApi.addSnippet(user, title, body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                env.ui.toast(L.snippets.messageFailed)
            }
            refresh()
        }
        return true
    }

    /** After the UI's "Are you sure you want to delete this snippet?". */
    fun delete(id: String) {
        val user = env.session.current.username ?: return
        scope.launch {
            try {
                env.editorApi.deleteSnippet(user, id)
                setState { it.copy(snippets = it.snippets.filter { s -> s.id != id }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                env.ui.toast(L.alert.fail)
            }
        }
    }
}

// ---- community picker (RN selectCommunityModal) ----

data class CommunityPickerState(
    val subscribed: List<EditorCommunity> = emptyList(),
    val top: List<EditorCommunity> = emptyList(),
    val query: String = "",
    /** Null while the sections show (fewer than 3 characters typed). */
    val results: List<EditorCommunity>? = null,
    val loading: Boolean = false,
)

class CommunityPickerModel internal constructor(private val env: AppEnvironment) :
    StateModel<CommunityPickerState>(CommunityPickerState(), env.dispatchers) {
    private var searchJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        val user = env.session.current.username ?: return
        setState { it.copy(loading = true) }
        scope.launch {
            val subs = runCatchingCancellable { env.communityApi.subscriptions(user) }.orEmpty().sortedBy { it.title.lowercase() }
            setState { it.copy(subscribed = subs.map { s -> EditorCommunity(s.community, s.title, role = s.role) }) }
        }
        scope.launch {
            val top = runCatchingCancellable { env.communityApi.communities(CommunitySort.RANK, null, 15, user) }.orEmpty()
            setState { it.copy(top = top.map { c -> EditorCommunity(c.name, c.title, c.typeId, c.observerRole ?: "guest") }, loading = false) }
        }
    }

    private suspend fun <T> runCatchingCancellable(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** RN: search from 3 characters, 500 ms after the last keystroke; fewer shows the sections again. */
    fun setQuery(text: String) {
        searchJob?.cancel()
        setState { it.copy(query = text, results = if (text.length >= 3) it.results else null) }
        if (text.length < 3) return
        val user = env.session.current.username
        searchJob = scope.launch {
            delay(500)
            val found = runCatchingCancellable { env.communityApi.communities(CommunitySort.RANK, text, 15, user) }.orEmpty()
            setState { it.copy(results = found.map { c -> EditorCommunity(c.name, c.title, c.typeId, c.observerRole ?: "guest") }) }
        }
    }

    /** The full community for a tapped row (its type and the account's role decide whether posting is allowed). */
    suspend fun resolve(choice: EditorCommunity): EditorCommunity {
        val user = env.session.current.username
        val c = runCatchingCancellable { env.communityApi.community(choice.name, user) } ?: return choice
        return EditorCommunity(c.name, c.title, c.typeId, c.observerRole ?: choice.role)
    }
}

/** What may go into a post body from an upload: the placeholder's file name and the url the host answered with. */
object UploadNames {
    private val HOSTS = setOf("images.ecency.com", "i.ecency.com", "img.ecency.com")

    /** Letters, digits, dot, dash and underscore; anything else (spaces, parentheses, brackets, line breaks) becomes "_". 80 characters at most. */
    fun safe(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }.joinToString("").takeLast(80)
        return cleaned.ifEmpty { "image.jpg" }
    }

    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name-$n" else name.substring(0, dot) + "-$n" + name.substring(dot)
    }

    /** https, on Ecency's image hosts or [imageHost], no whitespace, parentheses, brackets, quotes or control characters. */
    fun isSafeUrl(url: String, imageHost: String): Boolean {
        if (!url.startsWith("https://") || url.length > 2048) return false
        if (url.any { it <= ' ' || it == '(' || it == ')' || it == '[' || it == ']' || it == '<' || it == '>' || it == '"' || it == '\\' || it.code == 0x7f }) return false
        val host = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.contains('@') || host.contains(':')) return false
        val configured = imageHost.removePrefix("https://").substringBefore('/')
        return host.lowercase() in HOSTS || (imageHost.startsWith("https://") && host.equals(configured, ignoreCase = true))
    }
}
