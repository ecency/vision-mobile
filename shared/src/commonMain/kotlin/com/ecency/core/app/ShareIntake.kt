package com.ecency.core.app

import com.ecency.core.api.ecency.ImageUploader
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Content another app shared into this one (RN react-native-receive-sharing-intent). [images] are platform handles
 * the app reads only when the user picked where the content goes: Android content URIs, iOS file URLs inside the
 * share extension's App Group folder. Nothing here is a secret.
 */
data class SharedContent(val texts: List<String>, val images: List<String>) {
    val isEmpty: Boolean get() = texts.isEmpty() && images.isEmpty()

    /** RN quickPostModalContent: text and web links joined by new lines. */
    val text: String get() = texts.joinToString("\n")
}

/** RN ShareIntentSheet's two answers. */
enum class ShareTarget { BLOG, WAVE }

/** What the share leads to once a target was picked. */
sealed interface ShareNavigation {
    /** The login screen: the share waits for the sign-in. */
    data object Login : ShareNavigation

    /** A new post in the editor, which takes [ShareModel.takeForEditor] when it opens. */
    data class Editor(val screen: AppScreen) : ShareNavigation

    /** The Waves tab, whose composer opens with the text ([WavesService.requestCompose]) and the first image. */
    data object Wave : ShareNavigation
}

data class ShareState(
    /** RN "Share to..." sheet. */
    val choosing: Boolean = false,
    val navigation: ShareNavigation? = null,
    /** How many images are waiting (the sheet can say so). */
    val images: Int = 0,
)

/** RN en-US share_intent.* */
object ShareCopy {
    val TITLE: String get() = L.shareIntent.title
    val BLOG: String get() = L.shareIntent.blogPost
    val BLOG_DESC: String get() = L.shareIntent.blogPostDesc
    val WAVE: String get() = L.shareIntent.wave
    val WAVE_DESC: String get() = L.shareIntent.waveDesc
    val INVALID_IMAGE: String get() = L.alert.invalidImage
}

/**
 * RN applicationContainer getReceivedFiles + ShareIntentSheet: a share opens "Share to..." (Blog Post or Wave; closing
 * the sheet picks Blog Post, so the content is not lost), then the editor or the wave composer with it.
 *
 * - Nothing opens past the app lock, nor before the accounts were read: the share waits (as links do).
 * - Signed out the login screen opens and the share waits for that sign-in (RN opens the editor signed out).
 * - A share belongs to the account that picked its target, and to nobody else: an account change, a logout or a
 *   wipe drops it, and it expires after [TTL_MILLIS] unclaimed. A newer share replaces an older one.
 * - The editor takes the text as a snippet at the caret and uploads the images through its own upload (prepared:
 *   EXIF and other metadata stripped). The wave composer takes the text and the first image (it holds one).
 * - Text is capped at [MAX_TEXT] characters and images at [ImageUploader.MAX_IMAGES_PER_PICK] (RN's picker cap).
 */
class ShareModel internal constructor(private val env: AppEnvironment) : StateModel<ShareState>(ShareState(), env.dispatchers) {
    private var content: SharedContent? = null
    private var receivedAt = 0L
    private var waiting: Job? = null

    /** The picked target's content, for [owner]. */
    private var claimed: Pair<String, SharedContent>? = null
    private var claimedAt = 0L

    init {
        scope.launch {
            env.events.collect { e ->
                if (e is AppEvent.DataWiped) clear()
                if (e is AppEvent.SessionChanged) {
                    // a share picked for one account never reaches another
                    if (claimed?.first != e.username) claimed = null
                    // signed in after the login it sent the user to: the sheet comes up now
                    if (e.username != null && content != null && waiting == null) present()
                }
            }
        }
    }

    /** A share arrived (Android SEND / SEND_MULTIPLE, the iOS share extension). */
    fun receive(texts: List<String>, images: List<String>) {
        val cleaned = SharedContent(
            texts = texts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").take(MAX_TEXT).let { if (it.isEmpty()) emptyList() else listOf(it) },
            images = images.filter { it.isNotEmpty() }.distinct().take(ImageUploader.MAX_IMAGES_PER_PICK),
        )
        if (cleaned.isEmpty) return
        content = cleaned
        receivedAt = env.nowMillis()
        claimed = null
        setState { ShareState(images = cleaned.images.size) }
        present()
    }

    private fun present() {
        waiting?.cancel()
        waiting = scope.launch {
            env.session.state.first { !it.isLoading }
            if (env.appLock.hasStarted) env.appLock.state.first { !it.hidesContent } else env.appLock.state.first { !it.isLocked }
            waiting = null
            if (expired()) return@launch clear()
            if (env.session.current.username == null) {
                setState { it.copy(choosing = false, navigation = ShareNavigation.Login) }
            } else {
                setState { it.copy(choosing = true) }
            }
        }
    }

    /** RN sheet answer; null (the sheet was closed) is Blog Post. */
    fun choose(target: ShareTarget?) {
        val c = content ?: return setState { it.copy(choosing = false) }
        val user = env.session.current.username ?: return setState { it.copy(choosing = false, navigation = ShareNavigation.Login) }
        content = null
        claimed = user to c
        claimedAt = env.nowMillis()
        when (target ?: ShareTarget.BLOG) {
            ShareTarget.BLOG -> setState {
                ShareState(navigation = ShareNavigation.Editor(AppScreen.Editor(EditorArgs(EditorKind.NEW_POST))))
            }
            ShareTarget.WAVE -> {
                env.waves.requestCompose(c.text)
                setState { ShareState(navigation = ShareNavigation.Wave) }
            }
        }
    }

    /** The UI followed [ShareState.navigation]. */
    fun consumeNavigation() = setState { it.copy(navigation = null) }

    /** The user backed out of the login the share waited for: it goes. */
    fun loginDismissed() {
        if (env.session.current.username == null) clear()
    }

    /** The new post editor of [owner] opened: the shared content, once (null when there is none for it). */
    fun takeForEditor(owner: String?): SharedContent? = take(owner)

    /** The wave composer of [owner] opened: the shared images (the text went through [WavesService.requestCompose]). */
    fun takeImagesForWave(owner: String?): List<String> = take(owner)?.images.orEmpty()

    private fun take(owner: String?): SharedContent? {
        val (user, c) = claimed ?: return null
        if (owner == null || user != owner || env.nowMillis() - claimedAt !in 0..TTL_MILLIS) return null
        claimed = null
        return c
    }

    private fun expired() = env.nowMillis() - receivedAt !in 0..TTL_MILLIS

    private fun clear() {
        waiting?.cancel()
        waiting = null
        content = null
        claimed = null
        setState { ShareState() }
    }

    companion object {
        /** A share not taken within 10 minutes is gone (as a waves link's text). */
        const val TTL_MILLIS = 10 * 60_000L
        const val MAX_TEXT = 60_000
    }
}
