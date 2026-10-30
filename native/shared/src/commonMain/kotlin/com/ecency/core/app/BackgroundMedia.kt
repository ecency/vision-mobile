package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.l10n.L

/**
 * What the lock screen, Control Center and the media notification show for Listen audio (README "Background media").
 * [artworkUrl] is an https image or null (the system then shows the app icon).
 */
data class NowPlayingCard(val title: String, val artist: String, val album: String, val artworkUrl: String?)

/**
 * The now playing card and the remote skip commands, the same on both platforms.
 *
 * Privacy: the card shows the post's title and its author, which are public on chain. The artwork is the post's
 * image (its author's avatar for a post without one or an NSFW post), and none at all while the PIN lock is on: a
 * locked phone shows only the title and the author, nothing else from the post.
 */
object NowPlaying {
    /** Lock screen, Control Center and notification skip back and forward. */
    const val SKIP_SECONDS = 15.0
    const val ALBUM = "Ecency"
    const val TITLE_MAX = 120

    fun card(post: Post?, fallbackAuthor: String, lockEnabled: Boolean, image: String?, proxyBase: String): NowPlayingCard {
        val author = post?.author ?: fallbackAuthor
        val title = clean(post?.title.orEmpty()).ifEmpty { L.native.backgroundMedia.postBy(author = author) }
        val artwork = when {
            lockEnabled -> null
            post != null && !NsfwMode.isNsfw(post) && image != null && https(image) -> image
            else -> PostCard.avatarUrl(author, proxyBase, "medium").takeIf { it.isNotEmpty() && https(it) }
        }
        return NowPlayingCard(title, "@$author", ALBUM, artwork)
    }

    /** Where a skip of [delta] seconds from [position] lands: never before the start, never past the end. */
    fun skip(position: Double, delta: Double, duration: Double?): Double {
        val from = if (position.isFinite()) position else 0.0
        val to = (from + delta).coerceAtLeast(0.0)
        return if (duration != null && duration.isFinite() && duration > 0) to.coerceAtMost(duration) else to
    }

    /** One line without control or bidi override characters, capped. */
    internal fun clean(text: String): String {
        val flat = buildString {
            for (c in text) {
                when {
                    c == '\n' || c == '\r' || c == '\t' -> append(' ')
                    c.isISOControl() || c in '‪'..'‮' || c in '⁦'..'⁩' -> {}
                    else -> append(c)
                }
            }
        }.trim().replace(Regex(" {2,}"), " ")
        return if (flat.length > TITLE_MAX) flat.take(TITLE_MAX - 1).trimEnd() + "…" else flat
    }

    private fun https(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) && url.length <= 2048 && url.none { it.isWhitespace() || it.isISOControl() }
}

/**
 * Everything that plays or records while the app is in the background (Listen streams, a dictation): each registers
 * how it stops, and [stopAll] runs them all when accounts leave the device (logout, logout-all, the PIN wipe), so
 * no media session, lock screen card or notification outlives its account. Main thread only.
 */
class BackgroundSessions {
    private val stops = mutableListOf<Entry>()

    /** One registered session; [close] it when the session ends by itself. */
    inner class Entry internal constructor(internal val stop: () -> Unit) {
        fun close() {
            stops.remove(this)
        }
    }

    /** Registers [stop], run by [stopAll]. */
    fun register(stop: () -> Unit): Entry = Entry(stop).also { stops += it }

    val count: Int get() = stops.size

    /** Stops every session; one that throws never keeps the others going. */
    fun stopAll() {
        val all = stops.toList()
        stops.clear()
        all.forEach { runCatching { it.stop() } }
    }
}
