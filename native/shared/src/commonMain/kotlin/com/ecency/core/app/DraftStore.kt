package com.ecency.core.app

import com.ecency.core.api.model.arr
import com.ecency.core.api.model.bool
import com.ecency.core.api.model.int
import com.ecency.core.api.model.long
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.editor.BeneficiaryRoute
import com.ecency.core.editor.PollDraft
import com.ecency.core.editor.RewardType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Where local drafts live on the device (RN: the persisted redux `cache.draftsCollection` and `replyCache`).
 * Small text values by key; calls block on file I/O, so models call them on [AppDispatchers.io]. Android and iOS
 * back it with one private file per key; tests and fresh environments use [InMemoryDraftStorage].
 */
interface DraftStorage {
    fun read(account: String, slot: String): String?

    /** Null removes the entry. */
    fun write(account: String, slot: String, value: String?)

    /** Removes everything kept for [account]: its drafts, reply drafts, edit recovery copies and capture temp files. */
    fun clear(account: String)

    /** Removes every account's entries (the wipe after a third wrong PIN, "forgot PIN", logging every account out). */
    fun clearAll()
}

class InMemoryDraftStorage : DraftStorage {
    /** By [LocalDrafts.key]. */
    val values = mutableMapOf<String, String>()

    override fun read(account: String, slot: String): String? = values[LocalDrafts.key(account, slot)]

    override fun write(account: String, slot: String, value: String?) {
        val key = LocalDrafts.key(account, slot)
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun clear(account: String) {
        values.keys.filter { it.startsWith("draft/$account/") }.forEach { values.remove(it) }
    }

    override fun clearAll() = values.clear()
}

/**
 * A publish that was sent and may be on chain: its [permlink], the [hashes] of exactly what was sent under it (see
 * [PendingPublish.hashOf]) and when. A retry uses it to tell "already published" from "something else".
 */
data class PendingPublish(val permlink: String, val hashes: List<String>, val atMillis: Long) {
    /** The same permlink sent again with other content: both versions may be what the chain holds. */
    fun alsoSent(hash: String, nowMillis: Long) = if (hash in hashes) this else copy(hashes = hashes + hash, atMillis = nowMillis)

    /** A clock that stepped backwards (negative age) never makes a pending publish look forgotten: it stays pending. */
    fun isFresh(nowMillis: Long): Boolean = nowMillis - atMillis <= MAX_AGE_MILLIS

    /** Past this the transaction of that attempt has expired on every node: it can no longer land. */
    fun canStillLand(nowMillis: Long): Boolean = nowMillis - atMillis <= LANDING_WINDOW_MILLIS

    companion object {
        /** A pending publish older than a day is forgotten: whatever is typed then is a new post. */
        const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

        /** Transactions expire 60 s after signing; two minutes covers clock skew. */
        const val LANDING_WINDOW_MILLIS = 2L * 60 * 1000

/** SHA-256 over the parts that make a post or reply what it is: parent, title, body and the published tag list. */
        fun hashOf(parentAuthor: String, parentPermlink: String, title: String, body: String, tags: List<String> = emptyList()): String =
            org.kotlincrypto.hash.sha2.SHA256().digest((listOf(parentAuthor, parentPermlink, title, body) + tags).joinToString("\u0000").encodeToByteArray())
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

/** What the editor keeps on the device for one draft slot of one account. */
data class LocalDraft(
    val title: String = "",
    val body: String = "",
    val tags: List<String> = emptyList(),
    /** Epoch milliseconds of the last change (RN `updated`). */
    val updated: Long = 0,
    val caret: Int? = null,
    val reward: RewardType? = null,
    val description: String = "",
    val thumbUrl: String = "",
    /** Null: the author never touched the list (RN: no key in beneficiariesMap). */
    val beneficiaries: List<BeneficiaryRoute>? = null,
    val poll: PollDraft? = null,
    val scheduledFor: String? = null,
    val reblog: Boolean = false,
    /** The server draft this became after a save (new post slot only). */
    val draftId: String? = null,
    /** A publish that was already sent from this slot. */
    val pending: PendingPublish? = null,
    /** An edit's recovery copy: the `updated` time of the post it was made from. */
    val baseUpdated: String? = null,
    /** RN fields.aiTools: the AI disclosures this text earned ("writing_edit", "media_generation"). */
    val aiTools: List<String> = emptyList(),
    /** RN videoThumbs: uploaded videos' thumbnails by embed address. */
    val videoThumbs: List<com.ecency.core.editor.VideoThumb> = emptyList(),
) {
    /** RN: an id-less draft counts when it has a title, tags or a body. */
    val hasContent: Boolean get() = title.isNotEmpty() || body.isNotEmpty() || tags.isNotEmpty()

    fun encode(): String = buildJsonObject {
        put("title", title)
        put("body", body)
        putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
        put("updated", updated)
        caret?.let { put("caret", it) }
        reward?.let { put("reward", it.key) }
        put("description", description)
        put("thumb", thumbUrl)
        beneficiaries?.let { list ->
            put("beneficiaries", buildJsonArray { list.forEach { add(buildJsonObject { put("account", it.account); put("weight", it.weight) }) } })
        }
        poll?.let { put("poll", pollJson(it)) }
        scheduledFor?.let { put("schedule", it) }
        put("reblog", reblog)
        draftId?.let { put("draftId", it) }
        pending?.let {
            put("pending", it.permlink)
            put("pendingHash", it.hashes.joinToString(","))
            put("pendingAt", it.atMillis)
        }
        baseUpdated?.let { put("baseUpdated", it) }
        if (aiTools.isNotEmpty()) putJsonArray("aiTools") { aiTools.forEach { add(JsonPrimitive(it)) } }
        if (videoThumbs.isNotEmpty()) putJsonArray("videoThumbs") { videoThumbs.forEach { add(buildJsonArray { add(JsonPrimitive(it.embedUrl)); add(JsonPrimitive(it.thumbUrl)) }) } }
    }.toString()

    companion object {
        fun decode(text: String?): LocalDraft? {
            val o = runCatching { Json.parseToJsonElement(text ?: return null) }.getOrNull().obj() ?: return null
            return LocalDraft(
                title = o.str("title").orEmpty(),
                body = o.str("body").orEmpty(),
                tags = o["tags"].arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                updated = o.long("updated") ?: 0,
                caret = o.int("caret"),
                reward = o.str("reward")?.let(RewardType::ofKey),
                description = o.str("description").orEmpty(),
                thumbUrl = o.str("thumb").orEmpty(),
                beneficiaries = o["beneficiaries"].arr()?.let(::routes),
                poll = o["poll"].obj()?.let(::pollOf),
                scheduledFor = o.str("schedule"),
                reblog = o.bool("reblog") == true,
                draftId = o.str("draftId"),
                pending = o.str("pending")?.let { permlink ->
                    // a pending permlink without its hash (an older build) cannot be verified, so it is not used
                    val hash = o.str("pendingHash") ?: return@let null
                    PendingPublish(permlink, hash.split(',').filter { it.isNotEmpty() }, o.long("pendingAt") ?: 0)
                },
                baseUpdated = o.str("baseUpdated"),
                videoThumbs = o["videoThumbs"].arr().orEmpty().mapNotNull { e ->
                    val pair = e.arr()?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: return@mapNotNull null
                    if (pair.size == 2) com.ecency.core.editor.VideoThumb(pair[0], pair[1]) else null
                },
                aiTools = o["aiTools"].arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.filter { it in AI_TOOLS },
            )
        }

        /** The disclosure flags RN's cleanAiTools knows. */
        val AI_TOOLS = setOf("writing_edit", "media_generation")

        fun routes(array: kotlinx.serialization.json.JsonArray): List<BeneficiaryRoute> = array.mapNotNull { e ->
            val b = e.obj() ?: return@mapNotNull null
            BeneficiaryRoute(b.str("account") ?: return@mapNotNull null, b.int("weight") ?: return@mapNotNull null)
        }

        /** A poll draft in RN's shape (`meta.poll` of a saved draft). */
        fun pollJson(p: PollDraft): JsonObject = buildJsonObject {
            put("title", p.title)
            putJsonArray("choices") { p.choices.forEach { add(JsonPrimitive(it)) } }
            put("endTime", p.endTime)
            put("voteChange", p.voteChange)
            put("hideVotes", p.hideVotes)
            put("hideResults", p.hideResults)
            put("interpretation", p.interpretation)
            put("maxChoicesVoted", p.maxChoicesVoted)
            p.token?.let { put("token", it) }
            putJsonArray("communityMembership") { p.communityMembership.forEach { add(JsonPrimitive(it)) } }
            put("filters", buildJsonObject { put("accountAge", p.accountAge) })
        }

        fun pollOf(o: JsonObject): PollDraft = PollDraft(
            title = o.str("title").orEmpty(),
            choices = o["choices"].arr().orEmpty().map { (it as? JsonPrimitive)?.content.orEmpty() }.ifEmpty { listOf("", "") },
            endTime = o.str("endTime").orEmpty(),
            voteChange = o.bool("voteChange") == true,
            hideVotes = o.bool("hideVotes") == true,
            hideResults = o.bool("hideResults") != false,
            interpretation = o.str("interpretation") ?: PollDraft.NUMBER_OF_VOTES,
            maxChoicesVoted = o.int("maxChoicesVoted") ?: 1,
            token = o.str("token"),
            communityMembership = o["communityMembership"].arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            accountAge = o["filters"].obj()?.int("accountAge") ?: 100,
        )
    }
}

/**
 * Local drafts by account and slot. The account is part of every key, so one account's draft can never show
 * up for another (RN keys its id-less draft `DEFAULT_USER_DRAFT_ID_<username>` for the same reason).
 */
class LocalDrafts(private val storage: DraftStorage, private val dispatchers: AppDispatchers) {
    suspend fun load(account: String, slot: String): LocalDraft? = withContext(dispatchers.io) {
        runCatching { LocalDraft.decode(storage.read(account, slot)) }.getOrNull()
    }

    /**
     * False when the device refused the write (disk full), and for an account that left the device ([departed]):
     * the caller keeps the text in memory and says so. A screen that closes after a logout or a wipe flushes its
     * text on the way out; that late write must not put back what the teardown just deleted.
     */
    suspend fun save(account: String, slot: String, draft: LocalDraft): Boolean = withContext(dispatchers.io) {
        // one writer or clear at a time: a write that got in before a clear is deleted by it, one after is refused
        gate.withLock { !hasLeft(account) && runCatching { storage.write(account, slot, draft.encode()) }.isSuccess }
    }

    /** A value that is not a draft (an unfinished paid request), under the same rules as [load]. */
    suspend fun loadRaw(account: String, slot: String): String? = withContext(dispatchers.io) {
        runCatching { storage.read(account, slot) }.getOrNull()
    }

    /** As [save]: refused for an account that left the device. */
    suspend fun saveRaw(account: String, slot: String, value: String): Boolean = withContext(dispatchers.io) {
        gate.withLock { !hasLeft(account) && runCatching { storage.write(account, slot, value) }.isSuccess }
    }

    suspend fun delete(account: String, slot: String) {
        withContext(dispatchers.io) { gate.withLock { runCatching { storage.write(account, slot, null) } } }
    }

    /** [account] signed out: nothing it wrote stays on the device. False when it failed or did not finish in time. */
    suspend fun clear(account: String): Boolean {
        departed(listOf(account))
        return bounded { storage.clear(account) }
    }

    /** The wipe: every account's drafts go. False when it failed or did not finish in time. */
    suspend fun clearAll(): Boolean {
        departed(null)
        return bounded { storage.clearAll() }
    }

    private val gate = Mutex()

    @Volatile private var gone: Set<String> = emptySet()

    /** After a wipe every account counts as gone, except the ones that signed in since ([back]). */
    @Volatile private var allGone = false

    @Volatile private var back: Set<String> = emptySet()

    /**
     * [accounts] left the device (null: all of them, a wipe or logout-all). From now on nothing is written for
     * them, whoever still holds their text in memory, until [returned]. Called before anything is deleted.
     */
    fun departed(accounts: List<String>?) {
        if (accounts == null) {
            allGone = true
            back = emptySet()
        } else {
            gone = gone + accounts
            back = back - accounts.toSet()
        }
    }

    /** [account] signed in (again): its drafts are kept from here on. */
    fun returned(account: String) {
        gone = gone - account
        if (allGone) back = back + account
    }

    internal fun hasLeft(account: String): Boolean = account in gone || (allGone && account !in back)

    private val clears = CoroutineScope(SupervisorJob() + dispatchers.io)

    /**
     * Runs [block] on the IO dispatcher and waits for it at most [CLEAR_BOUND_MILLIS]: a logout or a wipe never
     * hangs on a stuck disk. The delete itself is not abandoned, it finishes whenever the disk answers.
     */
    private suspend fun bounded(block: () -> Unit): Boolean {
        val work = clears.async { gate.withLock { runCatching(block).isSuccess } }
        return withTimeoutOrNull(CLEAR_BOUND_MILLIS) { work.await() } ?: false
    }

    companion object {
        /** The longest a logout or a wipe waits for the drafts to be deleted. */
        const val CLEAR_BOUND_MILLIS = 5_000L

        /** The id-less new post (RN DEFAULT_USER_DRAFT_ID + username). */
        const val NEW_POST = "new"

        fun draft(id: String) = "id:$id"

        fun reply(parentAuthor: String, parentPermlink: String) = "reply:$parentAuthor/$parentPermlink"

        fun edit(author: String, permlink: String) = "edit:$author/$permlink"

        fun key(account: String, slot: String) = "draft/$account/$slot"
    }
}

/** What a retry may do about a publish that was already sent ([PendingPublish]). */
internal enum class PendingVerdict {
    /** Nothing usable is pending: publish under a new permlink. */
    FRESH,

    /** The chain holds exactly what the editor holds: it is published, send nothing. */
    PUBLISHED,

    /** The chain holds what was sent, and the editor now holds something else: the user decides. */
    CONFLICT,

    /**
     * The chain holds something under the pending permlink that was not sent from here (the post was edited
     * elsewhere since): the user decides too, with wording that says so.
     */
    CONFLICT_CHANGED_ELSEWHERE,

    /** Not on chain: send under the same permlink (a late landing of the first attempt can then never be a second post). */
    RESEND,
}

internal object PendingCheck {
    /**
     * [hashNow] is the hash of what the editor holds, [chainHash] the same hash of a post read from the chain. A
     * post found under the pending permlink is never edited or duplicated silently: when its content is not what
     * the editor holds, the user is asked, whether it is something that was sent from here or not.
     */
    suspend fun verdict(env: AppEnvironment, owner: String, pending: PendingPublish?, hashNow: String, chainHash: (com.ecency.core.api.ChainContent) -> String): PendingVerdict {
        if (pending == null || !pending.isFresh(env.nowMillis())) return PendingVerdict.FRESH
        // the chain itself, not hivemind: a post that landed a second ago is already there
        val existing = env.api.hive.content(owner, pending.permlink)
        if (existing != null) {
            val onChain = chainHash(existing)
            return when {
                onChain == hashNow -> PendingVerdict.PUBLISHED
                onChain !in pending.hashes -> PendingVerdict.CONFLICT_CHANGED_ELSEWHERE
                else -> PendingVerdict.CONFLICT
            }
        }
        return if (hashNow in pending.hashes || pending.canStillLand(env.nowMillis())) PendingVerdict.RESEND else PendingVerdict.FRESH
    }
}
