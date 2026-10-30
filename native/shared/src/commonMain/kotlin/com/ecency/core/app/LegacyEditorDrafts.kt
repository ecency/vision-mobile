package com.ecency.core.app

import com.ecency.core.api.model.arr
import com.ecency.core.api.model.long
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.model.stringList
import com.ecency.core.editor.RewardType
import com.ecency.core.legacy.LegacyImportError
import com.ecency.core.legacy.LegacyStorage
import com.ecency.core.secure.KeyVault
import kotlinx.serialization.json.JsonPrimitive

/**
 * The RN editor's unsaved drafts (README "Importing accounts from the RN app"). RN autosaves the editor into the
 * persisted redux `cache.draftsCollection` (in `persist:root`): the new post under `DEFAULT_USER_DRAFT_ID_<username>`,
 * an opened server draft under its id, each `{author, title, body, tags: "a,b", meta: {description, image,
 * rewardType, poll...}, created, updated, expiresAt}` and kept 7 days. RN's old `draft` key (storage.ts
 * `setDraftPost`) has no writer left in RN 3.x and is not read. Reply autosaves (`replyCache`) and the
 * meta parts the editor would have to re-validate (poll, beneficiaries) are not carried over.
 */
object LegacyEditorDrafts {
    const val RN_NEW_POST_PREFIX = "DEFAULT_USER_DRAFT_ID_"

    data class Entry(val account: String, val slot: String, val draft: LocalDraft)

    /**
     * The drafts that are still current at [nowMillis]; empty when there are none or the data is not RN's.
     * @throws LegacyImportError.StorageUnreadable when the storage exists but cannot be read now.
     */
    @Throws(LegacyImportError::class)
    fun read(storage: LegacyStorage, nowMillis: Long): List<Entry> {
        val root = storage.read("persist:root") ?: return emptyList()
        val drafts = runCatching { JsonPrimitive(root).obj()?.get("cache").obj()?.get("draftsCollection").obj() }.getOrNull()
            ?: return emptyList()
        return drafts.mapNotNull { (id, value) ->
            val d = value.obj() ?: return@mapNotNull null
            val author = d.str("author")?.takeIf { KeyVault.isValidUsername(it) } ?: return@mapNotNull null
            val expires = d.long("expiresAt")
            if (expires != null && expires <= nowMillis) return@mapNotNull null
            val slot = when {
                id == RN_NEW_POST_PREFIX + author -> LocalDrafts.NEW_POST
                // ids with a path ("author/permlink", ".../ecency.waves") are wave and reply autosaves of RN builds
                // from before its migration 14 moved them out: no editor slot to put them in
                id.startsWith(RN_NEW_POST_PREFIX) || id.isBlank() || '/' in id -> return@mapNotNull null
                else -> LocalDrafts.draft(id)
            }
            val meta = d["meta"].obj()
            val draft = LocalDraft(
                title = d.str("title").orEmpty(),
                body = d.str("body").orEmpty(),
                tags = d["tags"].stringList(),
                updated = d.long("updated") ?: 0,
                reward = meta?.str("rewardType")?.let { key -> RewardType.entries.firstOrNull { it.key == key } },
                description = meta?.str("description").orEmpty(),
                thumbUrl = meta?.get("image").arr()?.firstOrNull()?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty(),
            )
            if (draft.hasContent) Entry(author, slot, draft) else null
        }
    }

    /** Saves [entries] into slots this app has nothing in yet; a draft written since the update always wins. */
    suspend fun apply(entries: List<Entry>, drafts: LocalDrafts) {
        for (e in entries) {
            if (drafts.loadRaw(e.account, e.slot) == null) drafts.save(e.account, e.slot, e.draft)
        }
    }
}
