package com.ecency.core.app

import com.ecency.core.render.blocks.LinkCard
import com.ecency.core.render.blocks.LinksMeta
import com.ecency.core.render.blocks.SafeTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** The post link cards read so far, by "author/permlink". */
data class PostLinkCardsState(val cards: Map<String, LinkCard> = emptyMap())

/**
 * Post link cards (#3705): the linked post is read (`bridge.get_post`) when its card is first shown, and its title,
 * summary and thumbnail kept for the session; they replace the linking post's `links_meta` text, which only shows
 * until then (RN `linkPreview.tsx` read the post whenever `links_meta` had no full entry; the website reads it always). The summary
 * and thumbnail are the feed card's (`PostBodyRenderer.summary`, `cardImage`); an NSFW post shows no thumbnail unless
 * the NSFW setting is "Always show" when it is read. At
 * most [MAX_READS] reads run at once. A missing or muted post, or one without a title, keeps the plain card and is not
 * read again; a failed read is read again the next time its card shows.
 */
class PostLinkCards internal constructor(private val env: AppEnvironment) :
    StateModel<PostLinkCardsState>(PostLinkCardsState(), env.dispatchers) {
    private val reads = Semaphore(MAX_READS)

    /** Read, being read, or answered without a card. */
    private val asked = mutableSetOf<String>()

    fun card(author: String, permlink: String): LinkCard? = current.cards[LinksMeta.key(author, permlink)]

    /** The card of [author]/[permlink] is on screen: its post is read once. */
    fun shown(author: String, permlink: String) {
        val post = SafeTarget.post(author, permlink) ?: return
        val key = LinksMeta.key(post.author, post.permlink)
        if (!asked.add(key)) return
        // the NSFW setting as feed cards follow it: only "Always show" shows an NSFW post's image
        val showsNsfw = NsfwMode.of(env.prefs) == NsfwMode.SHOW
        scope.launch {
            val card = try {
                reads.withPermit { env.api.posts.getPost(post.author, post.permlink, observer = null) }
                    ?.takeIf { !it.isMuted && it.title.isNotBlank() }
                    ?.let { p ->
                        withContext(env.dispatchers.render) {
                            val renderer = env.renderer
                            LinkCard(p.title.trim(), renderer.summary(p), renderer.cardImage(p).takeUnless { NsfwMode.isNsfw(p) && !showsNsfw })
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
            if (card != null) setState { it.copy(cards = it.cards + (key to card)) }
        }
    }

    companion object {
        const val MAX_READS = 3
    }
}
