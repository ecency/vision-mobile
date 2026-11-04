package com.ecency.core.app

import com.ecency.core.api.model.PostKey
import com.ecency.core.l10n.L

/** RN commentView's toggle labels (accessibility only: the pill shows a chevron). */
object CommentThreadCopy {
    val SHOW_REPLIES: String get() = L.post.a11yShowReplies // post.a11y_show_replies
    val HIDE_REPLIES: String get() = L.post.a11yHideReplies // post.a11y_hide_replies
}

/**
 * RN commentsSection / commentView: the replies under a top-level comment are collapsed by default and the chevron
 * pill opens them. A thread holding a reply sent from this device opens by itself (RN `renderOnTop`). The list is
 * the flat discussion of [PostModel] (each top-level comment followed by its nested replies).
 */
object CommentThreads {
    /** The rows to draw: top-level comments, and the replies of the threads that are open. */
    fun visible(comments: List<CommentItem>, expanded: Set<PostKey>): List<CommentItem> {
        val out = ArrayList<CommentItem>(comments.size)
        var open = false
        var i = 0
        while (i < comments.size) {
            val c = comments[i]
            if (c.level <= 1) {
                open = isOpen(comments, i, expanded)
                out += c
            } else if (open) {
                out += c
            }
            i++
        }
        return out
    }

    /** Whether the thread of the top-level comment at [index] is open: toggled open, or holding a local reply. */
    private fun isOpen(comments: List<CommentItem>, index: Int, expanded: Set<PostKey>): Boolean {
        if (comments[index].key in expanded) return true
        var j = index + 1
        while (j < comments.size && comments[j].level > 1) {
            if (comments[j].isLocal) return true
            j++
        }
        return false
    }

    fun isOpen(comments: List<CommentItem>, key: PostKey, expanded: Set<PostKey>): Boolean {
        val i = comments.indexOfFirst { it.key == key && it.level <= 1 }
        return i >= 0 && isOpen(comments, i, expanded)
    }

    /** Replies of [key] loaded in the list (0: the toggle opens the comment's own thread instead, RN openReplyThread). */
    fun loadedReplies(comments: List<CommentItem>, key: PostKey): Int {
        val i = comments.indexOfFirst { it.key == key }
        if (i < 0) return 0
        var j = i + 1
        while (j < comments.size && comments[j].level > comments[i].level) j++
        return j - i - 1
    }

    /** RN: the pill shows on top-level comments with replies. */
    fun hasToggle(item: CommentItem): Boolean = item.level <= 1 && item.post.children > 0

    fun toggle(expanded: Set<PostKey>, key: PostKey): Set<PostKey> = if (key in expanded) expanded - key else expanded + key
}
