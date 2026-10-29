package com.ecency.core.app

import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat

/**
 * User-facing copy of the parity gaps wave (recap, charts, payout popover, stats, badges, image viewer, link sheet,
 * reply threads, another user's wallet...), in one place for both apps so the localization wave finds it. Texts
 * come from RN's en-US.json; the key is named in each comment.
 */
object UiCopy {
    // reading bar recap (post.words, post.reading, post.min, ai_assist.*)
    val WORDS: String get() = L.post.words
    val READING: String get() = L.post.reading
    val MIN: String get() = L.post.min
    const val AI_BADGE = "AI"
    val RECAP: String get() = L.aiAssist.actionSummarize // ai_assist.action_summarize
    val TRY_AGAIN: String get() = L.aiAssist.tryAgain // ai_assist.try_again
    val TRANSLATE: String get() = L.postDropdown.translate // post_dropdown.translate
    val HIDE_TRANSLATION: String get() = L.aiAssist.hideTranslation // ai_assist.hide_translation
    val FAIL: String get() = L.alert.fail // alert.fail
    val ERROR_RATE_LIMIT: String get() = L.aiAssist.errorRateLimit // ai_assist.error_rate_limit
    val ERROR_GENERIC: String get() = L.aiAssist.errorGeneric // ai_assist.error_generic
    val ERROR_CONTENT_POLICY: String get() = L.aiAssist.errorContentPolicy // ai_assist.error_content_policy
    val FREE: String get() = L.aiAssist.freeLabel
    val OK: String get() = L.newsletter.ok

    val SOMETHING_WRONG: String get() = L.alert.somethingWrong // alert.something_wrong

    /** Screen reader label of an avatar that is a button on its own (RN #1019; not RN copy). */
    fun profileOf(username: String): String = L.native.ui.profileOf(username = username)

    // wallet balance analytics (wallet.*)
    val OPEN_ANALYTICS: String get() = L.wallet.openAnalytics // wallet.openAnalytics
    val BALANCE_HISTORY: String get() = L.wallet.balanceHistory // wallet.balance_history
    val CURRENT_BALANCE: String get() = L.wallet.currentBalance // wallet.current_balance
    val NO_ACTIVITY: String get() = L.wallet.noActivity // wallet.no_activity
    const val NO_CHANGE = "-"

    /** wallet.granularity_yearly / _monthly / _daily */
    fun granularity(g: String) = when (g) { "monthly" -> L.wallet.granularityMonthly; "daily" -> L.wallet.granularityDaily; else -> L.wallet.granularityYearly }

    /** wallet.yearly_summary / monthly_summary / daily_summary */
    fun summaryTitle(g: String) = when (g) { "monthly" -> L.wallet.monthlySummary; "daily" -> L.wallet.dailySummary; else -> L.wallet.yearlySummary }

    /** wallet.year_change / month_change / day_change */
    fun changeLabel(g: String) = when (g) { "monthly" -> L.wallet.monthChange; "daily" -> L.wallet.dayChange; else -> L.wallet.yearChange }

    /** wallet.year_min / month_min / day_min */
    fun minLabel(g: String) = when (g) { "monthly" -> L.wallet.monthMin; "daily" -> L.wallet.dayMin; else -> L.wallet.yearMin }

    /** wallet.year_max / month_max / day_max */
    fun maxLabel(g: String) = when (g) { "monthly" -> L.wallet.monthMax; "daily" -> L.wallet.dayMax; else -> L.wallet.yearMax }

    /** scroll_top (accessibility only; RN's pill has no text) */
    val SCROLL_TOP: String get() = L.home.scrollTop

    // "Show Images" off (post.tap_to_load_image)
    val TAP_TO_LOAD_IMAGE: String get() = L.post.tapToLoadImage

    // image viewer (post.image_saved, post.image_saved_error, alert.copied)
    val IMAGE_SAVED: String get() = L.post.imageSaved
    val IMAGE_SAVE_ERROR: String get() = L.post.imageSavedError
    val COPIED: String get() = L.alert.copied
    val SAVE_IMAGE: String get() = L.post.saveToLocal // post.save_to_local
    val SHARE: String get() = L.chats.share
    val COPY_LINK: String get() = L.post.copyLink // post.copy_link
    val CLOSE: String get() = L.transfer.close

    /** RN imageViewer's header "Preview (i/n)" (hard coded in RN). */
    fun preview(index: Int, count: Int) = L.native.ui.preview(index = index.toString(), count = count.toString())

    /** "Balance: 1,234.567 HIVE"; [amount] is already formatted for display. */
    fun balance(amount: String) = L.native.ui.balance(amount = amount)

    /** The HiveAuth wait: "Expires in 4:05" in the app language's digits, or that it expired. */
    fun expiresIn(secondsLeft: Long): String {
        if (secondsLeft <= 0) return L.native.ui.expired
        val s = (secondsLeft % 60).toString().padStart(2, '0')
        return L.native.ui.expiresIn(time = LocaleFormat.integer(secondsLeft / 60) + ":" + LocaleFormat.posix(s, grouping = false))
    }

    /** ai_assist.error_insufficient_points */
    fun insufficientPoints(required: String, available: String) = L.aiAssist.errorInsufficientPoints(required = required, available = available)

    // Copy of the platform screens that both apps show word for word (l10n-ui).
    val ACCOUNTS: String get() = L.sideMenu.accounts
    val ATTACH_IMAGE: String get() = L.native.ui.attachImage
    val AUTO: String get() = L.native.ui.auto
    val BACK: String get() = L.native.ui.back
    val CANCEL: String get() = L.alert.cancel
    val CLEAR: String get() = L.alert.clear
    val CLOSE_CAPS: String get() = L.alert.close
    val CONFIRM: String get() = L.alert.confirm
    val DELETE: String get() = L.alert.delete
    val DISMISS: String get() = L.wavesOnboarding.dismiss
    val DONE: String get() = L.alert.done
    val DOWNVOTED: String get() = L.post.downvoted
    val EDIT: String get() = L.profile.editLabel
    val FOLLOW: String get() = L.user.follow
    val FOLLOWER: String get() = L.profile.follower
    val FOLLOWING: String get() = L.profile.following
    val HOT: String get() = L.native.ui.hot
    val IMAGE: String get() = L.post.image
    val INVALID_IMAGE: String get() = L.alert.invalidImage
    val LOGIN: String get() = L.login.signin
    val LOGOUT: String get() = L.sideMenu.logout
    val MESSAGE: String get() = L.profile.message
    /** "More" that loads the next page of a list. */
    val LOAD_MORE: String get() = L.native.ui.loadMore
    val MUTED: String get() = L.community.muted
    val NEW: String get() = L.native.ui.new
    val NEW_REPLY: String get() = L.native.ui.newReply
    val NOTHING_HERE: String get() = L.emptyScreen.nothingHere
    val NSFW_TAP_TO_REVEAL: String get() = L.native.ui.nsfwTapToReveal
    val POLL: String get() = L.native.navigation.poll
    val POSTS: String get() = L.home.posts
    val REMOVE: String get() = L.native.ui.remove
    val REMOVE_CONFIRM: String get() = L.alert.removeAlert
    val REPUTATION: String get() = L.profile.reputation
    val RESOURCE_CREDITS: String get() = L.profile.resourceCredits
    val RETRY: String get() = L.dappBrowser.retry
    val SAVE: String get() = L.beneficiaryModal.save
    val SAVE_CAPS: String get() = L.transfer.save
    val SEARCH: String get() = L.basicHeader.search
    val SEND: String get() = L.chats.send
    val SEND_TIP: String get() = L.post.a11yTip
    val TRY_AGAIN_QUESTION: String get() = L.alert.somethingWrongReload
    val UNLOCK_ECENCY: String get() = L.native.ui.unlockEcency
    val UPDATE_IT: String get() = L.native.ui.updateIt
    val UPDATE_IT_OR_POST: String get() = L.native.ui.updateItOrPostThisAs
    val UPVOTE: String get() = L.post.upvote
    val UPVOTED: String get() = L.post.upvoted
    val USE_PIN: String get() = L.native.ui.usePin
    val VIDEO: String get() = L.editor.video
    val VIEW_FULL_PROFILE: String get() = L.profile.viewFull
    val YOUR_REPLY_WAS_ALREADY: String get() = L.native.ui.yourReplyWasAlreadyPosted
    fun errorTryAgain(message: String): String = L.native.ui.errorTryAgain(message = message)
    fun imageOf(index: Int, count: Int): String = L.native.ui.imageOf(index = LocaleFormat.integer(index.toLong()), count = LocaleFormat.integer(count.toLong()))
    fun userHasNoPosts(username: String): String = L.native.ui.userHasNoPosts(username = username)
    val COPIED_PLAIN: String get() = L.native.ui.copied
    val TRENDING: String get() = L.native.ui.trendingCaps
    val MOVE_UP: String get() = L.native.ui.moveUp
    val MOVE_DOWN: String get() = L.native.ui.moveDown
    val SCROLL_TO_TOP: String get() = L.native.ui.scrollToTop
}
