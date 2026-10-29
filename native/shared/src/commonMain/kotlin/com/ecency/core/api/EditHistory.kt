package com.ecency.core.api

import com.ecency.core.api.model.str
import com.ecency.core.text.DiffMatchPatch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One run of a diff: [kind] -1 deleted, 0 unchanged, 1 inserted. */
data class DiffSpan(val kind: Int, val text: String)

/** A version and its changes against the version before it (version 1 against itself, as RN). */
data class HistoryDiff(val title: List<DiffSpan>, val tags: List<DiffSpan>, val body: List<DiffSpan>)

/** RN editHistoryScreen's historyBuilder and diff view. */
object EditHistory {
    /** Most patch text one history may carry; past it the history is refused (patches are untrusted CPU work). */
    const val MAX_PATCH_CHARS = 300_000

    /** The summed length of the patch bodies ("@@ ...") in [items]. */
    fun patchChars(items: List<JsonObject>): Long =
        items.sumOf { o -> (o.str("body") ?: "").let { if (it.startsWith("@@")) it.length.toLong() else 0L } }

    /**
     * Rebuilds every version in order: a body starting with "@@" is a diff-match-patch patch applied to the
     * previous body, anything else is the full body. Null when the patches exceed [MAX_PATCH_CHARS].
     * Call off the main thread.
     */
    fun rebuildCapped(items: List<JsonObject>): List<HistoryVersion>? =
        if (patchChars(items) > MAX_PATCH_CHARS) null else rebuild(items)

    fun rebuild(items: List<JsonObject>): List<HistoryVersion> {
        val dmp = DiffMatchPatch()
        var body = ""
        return items.mapIndexed { index, o ->
            val raw = o.str("body") ?: ""
            body = if (raw.startsWith("@@")) {
                runCatching { dmp.patchApply(dmp.patchFromText(raw), body).first }.getOrDefault(body)
            } else {
                raw
            }
            HistoryVersion(
                version = (o["v"] as? JsonPrimitive)?.intOrNull ?: (index + 1),
                title = o.str("title") ?: "",
                body = body,
                tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                timestamp = o.str("timestamp") ?: "",
            )
        }
    }

    /** Tags as RN shows them: comma separated. */
    fun tagsText(v: HistoryVersion): String = v.tags.joinToString(", ")

    /** diff_main + diff_cleanupSemantic of [index] against the version before it. */
    fun diff(versions: List<HistoryVersion>, index: Int): HistoryDiff {
        val cur = versions[index]
        val prev = versions[(index - 1).coerceAtLeast(0)]
        return HistoryDiff(spans(prev.title, cur.title), spans(tagsText(prev), tagsText(cur)), spans(prev.body, cur.body))
    }

    fun spans(before: String, after: String): List<DiffSpan> {
        val dmp = DiffMatchPatch()
        val diffs = dmp.diffMain(before, after)
        dmp.diffCleanupSemantic(diffs)
        return diffs.map { DiffSpan(it.operation.sign, it.text) }
    }
}
