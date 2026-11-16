package com.ecency.core.app

import com.ecency.core.render.ImageProxy
import com.ecency.core.render.blocks.LinkCard
import com.ecency.core.render.blocks.LinksMeta
import com.ecency.core.render.blocks.SafeTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The post link cards read so far, by "author/permlink"; [plain]: reads that ended without a card. [mutes] changes with
 * the reader's mutes, so cards drawn from [PostLinkCards.card] redraw at once.
 */
data class PostLinkCardsState(val cards: Map<String, LinkCard> = emptyMap(), val plain: Set<String> = emptySet(), val mutes: Int = 0)

/**
 * Post link cards (#3705): the linked post is read (`bridge.get_post`) when its card is first shown, and its title,
 * summary and thumbnail kept for the session; they replace the linking post's `links_meta` text, which only shows,
 * unverified, until then (RN `linkPreview.tsx` read the post whenever `links_meta` had no full entry; the website
 * reads it always). The summary is the feed card's (`PostBodyRenderer.summary`), the thumbnail the feed card's image
 * at [THUMB_PX]. At most [MAX_READS] reads run at once.
 *
 * A missing or muted post, or one without a title, ends [isPlain]: the plain card (author and permlink), never the
 * linking post's text, and it is not read again; a failed read is read again the next time its card shows. What the
 * reader decides later applies at once: an author they mute gets the plain card, and an NSFW post's thumbnail shows only
 * while the NSFW setting is "Always show".
 */
class PostLinkCards internal constructor(private val env: AppEnvironment) :
    StateModel<PostLinkCardsState>(PostLinkCardsState(), env.dispatchers) {
    private val reads = Semaphore(MAX_READS)

    /** Read, being read, or answered without a card. */
    private val asked = mutableSetOf<String>()

    /** Cards of NSFW posts: their thumbnail follows the NSFW setting when served. */
    private val nsfw = mutableSetOf<String>()

    init {
        // a mute applies to cards already on screen: their views redraw from the changed state
        scope.launch {
            env.events.collect { e -> if (e is AppEvent.AuthorMuted || e is AppEvent.MutesLoaded) setState { it.copy(mutes = it.mutes + 1) } }
        }
    }

    /** The card of [author]/[permlink] once read; null while it is read, and for [isPlain]. */
    fun card(author: String, permlink: String): LinkCard? {
        if (author in env.mutedAuthors) return null
        val key = LinksMeta.key(author, permlink)
        val card = current.cards[key] ?: return null
        return if (key in nsfw && NsfwMode.of(env.prefs) != NsfwMode.SHOW) card.copy(image = null) else card
    }

    /** The read ended without a card, or the reader muted the author: the plain card. */
    fun isPlain(author: String, permlink: String): Boolean =
        author in env.mutedAuthors || LinksMeta.key(author, permlink) in current.plain

    /** The card of [author]/[permlink] is on screen: its post is read once. */
    fun shown(author: String, permlink: String) {
        val post = SafeTarget.post(author, permlink) ?: return
        val key = LinksMeta.key(post.author, post.permlink)
        if (!asked.add(key)) return
        scope.launch {
            val read = try {
                reads.withPermit { env.api.posts.getPost(post.author, post.permlink, observer = null) }
                    ?.takeIf { !it.isMuted && it.title.isNotBlank() }
                    ?.let { p ->
                        withContext(env.dispatchers.render) {
                            val renderer = env.renderer
                            val image = renderer.cardImage(p)?.let { ImageProxy(env.proxyBase).proxify(it, THUMB_PX, THUMB_PX) }
                                ?.takeIf { it.startsWith("${env.proxyBase}/") }
                            LinkCard(p.title.trim(), renderer.summary(p), image) to NsfwMode.isNsfw(p)
                        }
                    }
            } catch (e: CancellationException) {
                asked.remove(key)
                throw e
            } catch (e: Exception) {
                asked.remove(key)
                ErrorReports.quiet(e, "postLinkCards.read")
                return@launch
            }
            if (read == null) {
                setState { it.copy(plain = it.plain + key) }
            } else {
                if (read.second) nsfw += key
                setState { it.copy(cards = it.cards + (key to read.first)) }
            }
        }
    }

    companion object {
        const val MAX_READS = 3

        /** The thumbnail's side in pixels: the cards draw it at 64 points, 3x on most phones. */
        const val THUMB_PX = 192
    }
}
