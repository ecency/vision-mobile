package com.ecency.core.api.ecency

import com.ecency.core.api.EcencyException
import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.bool
import com.ecency.core.api.model.double
import com.ecency.core.api.model.int
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.model.stringList
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthException
import com.ecency.core.auth.EcencyPrivateApi
import com.ecency.core.l10n.L
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** A draft on Ecency's servers (`/private-api/drafts`). [metaJson] is its `meta` object as JSON text ("{}" when none). */
data class RemoteDraft(
    val id: String,
    val title: String,
    val body: String,
    /** `tags_arr`, else `tags` split on commas and whitespace (RN _getStorageDraft). */
    val tags: List<String>,
    val metaJson: String,
    val created: String,
    val modified: String,
    val author: String = "",
) {
    private val meta: JsonObject? get() = runCatching { Json.parseToJsonElement(metaJson) }.getOrNull().obj()

    /** RN isTemplateDraft: `meta.postTemplate`. */
    val isTemplate: Boolean get() = meta?.get("postTemplate").let { it is JsonPrimitive && !it.isString && it.content == "true" }

    /** RN templateDisplayName. */
    val templateName: String get() = meta?.str("templateName")?.takeIf { it.isNotEmpty() } ?: title

    val image: String? get() = meta?.get("image").stringList().firstOrNull()
}

/** A scheduled post (`/private-api/schedules`). [status] 1 pending, 2 postponed, 3 published, anything else an error. */
data class ScheduledPost(
    val id: String,
    val title: String,
    val body: String,
    val permlink: String,
    val tags: List<String>,
    val schedule: String,
    val status: Int,
    val message: String? = null,
) {
    /** RN ScheduledPostStatus labels. */
    val statusLabel: String
        get() = when (status) {
            1 -> L.schedules.pending
            2 -> L.schedules.postponed
            3 -> L.schedules.published
            else -> L.alert.error
        }
}

/** An image in the account's gallery (`/private-api/images`). */
data class GalleryImage(val id: String, val url: String, val created: String = "")

/** A snippet (`/private-api/fragments`). */
data class Snippet(val id: String, val title: String, val body: String, val created: String = "", val modified: String = "")

/** RN SupportSettings: the saved "Support Ecency" percents, 0 when off. */
data class SupportSettings(val beneficiaryPercent: Double, val curationPercent: Double)

/**
 * The Ecency private API calls of the editor, as @ecency/sdk makes them for the RN app: drafts, schedules, the
 * image gallery, snippets ("fragments") and the support settings. Every call carries the account's `code`.
 */
class EditorApi(private val private: EcencyPrivateApi) {
    // ---- drafts ----

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun drafts(username: String, offset: Int, limit: Int = PAGE_SIZE): WrappedPage<RemoteDraft> =
        wrapped(call(username, "drafts?format=wrapped&offset=$offset&limit=$limit"), limit, ::draft)

    /** Every draft, unpaged (RN getDraftsQueryOptions: opening a draft by id, the "DRAFT" button). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun allDrafts(username: String): List<RemoteDraft> = list(call(username, "drafts")).mapNotNull(::draft)

    /** `drafts-add`; [tags] is RN's space-joined string. Returns the new draft when the answer carries it. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun addDraft(username: String, title: String, body: String, tags: String, meta: JsonElement): RemoteDraft? {
        val result = call(username, "drafts-add", buildJsonObject { put("title", title); put("body", body); put("tags", tags); put("meta", meta) })
        // RN: response.drafts[0], response[0] or the object itself
        val first = result.obj()?.get("drafts").arr()?.firstOrNull() ?: result.arr()?.firstOrNull() ?: result
        return first.obj()?.let(::draft)
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun updateDraft(username: String, id: String, title: String, body: String, tags: String, meta: JsonElement) {
        call(username, "drafts-update", buildJsonObject { put("id", id); put("title", title); put("body", body); put("tags", tags); put("meta", meta) })
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun deleteDraft(username: String, id: String) {
        call(username, "drafts-delete", buildJsonObject { put("id", id) })
    }

    // ---- schedules ----

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun schedules(username: String, offset: Int, limit: Int = PAGE_SIZE): WrappedPage<ScheduledPost> =
        wrapped(call(username, "schedules?format=wrapped&offset=$offset&limit=$limit"), limit) { o ->
            val id = o.str("_id") ?: return@wrapped null
            ScheduledPost(
                id, o.str("title").orEmpty(), o.str("body").orEmpty(), o.str("permlink").orEmpty(),
                o["tags_arr"].stringList().ifEmpty { o["tags"].stringList() }, o.str("schedule").orEmpty(), o.int("status") ?: 0, o.str("message"),
            )
        }

    /** `schedules-add` as RN _setScheduledPost: [options] is the `comment_options` body, `reblog` always false. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun addSchedule(username: String, permlink: String, title: String, body: String, meta: JsonElement, options: JsonElement, schedule: String) {
        call(
            username, "schedules-add",
            buildJsonObject {
                put("permlink", permlink); put("title", title); put("body", body); put("meta", meta)
                put("schedule", schedule); put("reblog", false); put("options", options)
            },
        )
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun deleteSchedule(username: String, id: String) {
        call(username, "schedules-delete", buildJsonObject { put("id", id) })
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun moveScheduleToDrafts(username: String, id: String) {
        call(username, "schedules-move", buildJsonObject { put("id", id) })
    }

    // ---- image gallery ----

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun images(username: String, offset: Int, limit: Int = PAGE_SIZE): WrappedPage<GalleryImage> =
        wrapped(call(username, "images?format=wrapped&offset=$offset&limit=$limit"), limit) { o ->
            val url = o.str("url") ?: return@wrapped null
            GalleryImage(o.str("_id").orEmpty(), url, o.str("created").orEmpty())
        }

    /** `images-add`. A 409 means the image is there already (RN refreshes the list instead of failing). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun addImage(username: String, url: String) {
        call(username, "images-add", buildJsonObject { put("url", url) })
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun deleteImage(username: String, id: String) {
        call(username, "images-delete", buildJsonObject { put("id", id) })
    }

    // ---- snippets ----

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun snippets(username: String, offset: Int, limit: Int = PAGE_SIZE): WrappedPage<Snippet> =
        wrapped(call(username, "fragments?format=wrapped&offset=$offset&limit=$limit"), limit) { o ->
            val id = o.str("id") ?: o.str("_id") ?: return@wrapped null
            Snippet(id, o.str("title").orEmpty(), o.str("body").orEmpty(), o.str("created").orEmpty(), o.str("modified").orEmpty())
        }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun addSnippet(username: String, title: String, body: String) {
        call(username, "fragments-add", buildJsonObject { put("title", title); put("body", body) })
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun updateSnippet(username: String, id: String, title: String, body: String) {
        call(username, "fragments-update", buildJsonObject { put("id", id); put("title", title); put("body", body) })
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun deleteSnippet(username: String, id: String) {
        call(username, "fragments-delete", buildJsonObject { put("id", id) })
    }

    // ---- support settings ----

    /** `support-settings`; null when the answer is not RN's isValidSupportSettings shape. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun supportSettings(username: String): SupportSettings? = support(call(username, "support-settings"))

    private fun support(answer: JsonElement?): SupportSettings? {
        val o = answer.obj() ?: return null
        val b = o.double("beneficiary_percent")?.takeIf { it.isFinite() } ?: return null
        val c = o.double("curation_percent")?.takeIf { it.isFinite() } ?: return null
        return SupportSettings(b, c)
    }

    /**
     * `support-settings-update`: both percents always travel together (RN _persistSupportPreference). Returns the
     * saved values the server answered with, null when the answer is not RN's isValidSupportSettings shape.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun updateSupportSettings(username: String, beneficiaryPercent: Double, curationPercent: Double): SupportSettings? =
        support(call(username, "support-settings-update", buildJsonObject { put("beneficiary_percent", beneficiaryPercent); put("curation_percent", curationPercent) }))

    // ---- plumbing ----

    private fun draft(o: JsonObject): RemoteDraft? {
        val id = o.str("_id") ?: return null
        val tags = (o["tags_arr"] as? JsonArray)?.let { o["tags_arr"].stringList() }
            ?: o.str("tags").orEmpty().split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
        return RemoteDraft(
            id, o.str("title").orEmpty(), o.str("body").orEmpty(), tags, o["meta"].obj()?.toString() ?: "{}",
            o.str("created").orEmpty(), o.str("modified").orEmpty(), o.str("author").orEmpty(),
        )
    }

    private fun list(result: JsonElement?): List<JsonObject> =
        (result.arr() ?: result.obj()?.get("data").arr()).orEmpty().mapNotNull { it.obj() }

    private suspend fun call(username: String, path: String, fields: JsonObject = JsonObject(emptyMap())): JsonElement? {
        val response: HttpResult = try {
            private.post(path, username, fields)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            throw e
        } catch (e: EcencyException) {
            throw e
        } catch (e: Exception) {
            throw EcencyHttpException.ofSendFailure(e)
        }
        if (response.status !in 200..299) throw EcencyHttpException(response.status)
        return runCatching { Json.parseToJsonElement(response.body) }.getOrNull()
    }

    /** SDK normalizeToWrappedResponse: a wrapped page, or an old-style plain array (then there is no next page). */
    private fun <T> wrapped(result: JsonElement?, limit: Int, item: (JsonObject) -> T?): WrappedPage<T> {
        val o = result as? JsonObject
        val data = o?.get("data")
        if (o != null && data is JsonArray && o.containsKey("pagination")) {
            val p = o["pagination"].obj()
            val next = if (p?.bool("has_next") == true) (p.int("offset") ?: 0) + (p.int("limit") ?: limit) else null
            return WrappedPage(data.mapNotNull { it.obj()?.let(item) }, next)
        }
        return WrappedPage(result.arr().orEmpty().mapNotNull { it.obj()?.let(item) }, null)
    }

    companion object {
        /** RN's page size for drafts, schedules, images and snippets. */
        const val PAGE_SIZE = 20
    }
}
