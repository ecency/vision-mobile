package com.ecency.core.app

import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat

/*
 * Copy of the platform screens that both apps show word for word (l10n-ui): one getter per text, read when shown, so a
 * live language switch reaches it. Keys reuse RN en-US where the English is the same (rn-reuse.json), else `native.ui.*`.
 */

/** The editor and its sheets (poll, snippets, uploads, beneficiaries, AI assist, templates, drafts, schedules). */
object EditorUiCopy {
    val REWARD: String get() = L.editor.settingReward
    val ADD_ACCOUNT: String get() = L.sideMenu.addAccount
    val ADD_CHOICE: String get() = L.postPoll.addChoice
    val ADD_MORE_IMGS: String get() = L.editor.addMoreImgs
    val ADD_POLL_QUESTION: String get() = L.postPoll.questionPlaceholder
    val ADD_SNIPPET: String get() = L.snippets.titleAddSnippet
    val ADD_SNIPPET_BODY_HERE: String get() = L.snippets.placeholderBody
    val ADD_VIDEO: String get() = L.videoUpload.selectSource
    val ADD_YOUR_REPLY: String get() = L.editor.replyPlaceholder
    val AI_ASSIST: String get() = L.native.ui.editor.aiAssist
    val AI_GENERATE_IMAGE: String get() = L.aiAssist.actionGenerateImage
    val AI_GENERATE_IMAGE_DESC: String get() = L.aiAssist.actionGenerateImageDesc
    val AI_TOOLS: String get() = L.native.ui.editor.aiTools
    val AI_WRITING_ASSISTANT: String get() = L.aiAssist.title
    val ALLOW_ECENCY_TO_PUBLISH: String get() = L.native.ui.editor.allowEcencyToPublishForYou
    val ALLOW_VOTE_CHANGE: String get() = L.postPoll.configVoteChange
    val APPLY: String get() = L.composeTranslate.apply
    val APPLY_TO_POST: String get() = L.aiAssist.applyButton
    val ARE_YOU_SURE: String get() = L.editor.alertPubBody
    val ATTACH_POLL: String get() = L.postPoll.attachPoll
    val A_POST_ALREADY_EXISTS: String get() = L.native.ui.editor.aPostAlreadyExistsAtThis
    val A_SCHEDULED_POST_IS: String get() = L.native.ui.editor.aScheduledPostIsPublishedBy
    val BENEFICIARIES: String get() = L.payout.beneficiaries
    val BOLD: String get() = L.native.ui.editor.bold
    val BRIEF_SUMMARY_OF_CONTENT: String get() = L.editor.shortDescPlaceholder
    val CAPTURE_A_PHOTO: String get() = L.editor.capturePhoto
    val CHOICES: String get() = L.postPoll.wizardChoices
    val CHOOSE_FROM_FILES: String get() = L.editor.openFiles
    val CLEAR_CONFIRM: String get() = L.alert.clearAlert
    val CLIPBOARD_NO_IMAGE: String get() = L.editor.clipboardNoImage
    val CLONE: String get() = L.native.ui.editor.clone
    val COPY: String get() = L.aiAssist.copyButton
    val COULD_NOT_STORE_THE: String get() = L.native.ui.editor.couldNotStoreTheDraftOn
    val CREATE_A_POLL: String get() = L.postPoll.createTitle
    val DELETE_SNIPPET: String get() = L.snippets.titleRemoveConfirmation
    val DELETE_SNIPPET_CONFIRM: String get() = L.snippets.messageRemoveConfirmation
    val DELETE_TEMPLATE_CONFIRM: String get() = L.templates.deleteConfirm
    val DICTATE: String get() = L.dictation.title
    val DICTATE_DESC: String get() = L.native.ui.editor.dictateDesc
    val DISCARD: String get() = L.native.ui.editor.discard
    val DISCARD_CHANGES: String get() = L.native.ui.editor.discardChanges
    val DONE_CAPS: String get() = L.editor.done
    val DRAFT: String get() = L.native.ui.editor.draft
    val DRAFTS: String get() = L.sideMenu.drafts
    val EDIT_SNIPPET: String get() = L.snippets.titleEditSnippet
    val END_TIME: String get() = L.postPoll.wizardEndTime
    val ENTER_LABEL_OPTIONAL: String get() = L.editor.enterLabelPlaceholder
    val ENTER_URL: String get() = L.editor.enterUrlPlaceholder
    val GENERATE_AGAIN: String get() = L.aiImageGenerator.generateAgain
    val GRANT: String get() = L.native.ui.editor.grant
    val HIDE_RESULTS_UNTIL_VOTED: String get() = L.postPoll.configHideResults
    val IMMEDIATE: String get() = L.editor.scheduledImmediate
    val INSERT_INTO_POST: String get() = L.aiImageGenerator.insertButton
    val INSERT_LINK: String get() = L.editor.insertLink
    val INTERPRETATION: String get() = L.postPoll.configPollInterpretation
    val ITALIC: String get() = L.native.ui.editor.italic
    val KEEP_DRAFT: String get() = L.editor.publishedDraftKeep
    val KEEP_EDITING: String get() = L.native.ui.editor.keepEditing
    val LATER: String get() = L.alert.later
    val LINK: String get() = L.post.link
    val MOVE: String get() = L.alert.move
    val MOVE_TO_DRAFTS_CONFIRM: String get() = L.alert.moveQuestion
    val MY_BLOG: String get() = L.home.myBlog
    val MY_BLOG_CAPS: String get() = L.native.ui.editor.myBlog
    val MY_COMMUNITIES: String get() = L.native.ui.editor.myCommunities
    val NEW_POST: String get() = L.native.ui.editor.newPost
    val NO_BUTTON: String get() = L.editor.alertBtnNo
    val NOTICE: String get() = L.native.ui.editor.notice
    val NO_SNIPPETS_FOUND: String get() = L.snippets.labelNoSnippets
    val NUMBER_OF_VOTES: String get() = L.postPoll.numberOfVotes
    val OPEN_GALLERY: String get() = L.editor.openGallery
    val PASTE_IMAGE: String get() = L.editor.pasteImage
    val PLAIN: String get() = L.editor.plain
    val PLEASE_INSERT_VALID_URL: String get() = L.editor.invalidUrlError
    val POLL_QUESTION: String get() = L.postPoll.pollQuestion
    val POST_OPTIONS: String get() = L.editor.settingsTitle
    val POST_PUBLISHED: String get() = L.editor.publishedDraftDeleteTitle
    val PREVIEW: String get() = L.editor.preview
    val PUBLISHED_DRAFT_DELETE_BODY: String get() = L.editor.publishedDraftDeleteBody
    val PUBLISHING_EDITS: String get() = L.editor.alertPubEditTitle
    val PUBLISHING_NEW_POST: String get() = L.editor.alertPubNewTitle
    val QUALITY_BOOST: String get() = L.aiImageGenerator.selectPower
    val REBLOG: String get() = L.editor.settingReblog
    val RECORDING: String get() = L.dictation.recording
    val REMOVE_ALL_CONFIRM: String get() = L.alert.removeAllAlert
    val RESET_POLL: String get() = L.postPoll.wizardReset
    val RESTORE: String get() = L.native.ui.editor.restore
    val RESTORE_UNSAVED_CHANGES: String get() = L.native.ui.editor.restoreUnsavedChanges
    val RESULT: String get() = L.aiAssist.resultTitle
    val SAVED: String get() = L.native.ui.editor.saved
    val SAVE_AS_NEW_DRAFT: String get() = L.editor.draftSaveNew
    val SAVE_AS_TEMPLATE: String get() = L.templates.saveAsTemplate
    val SAVING_DRAFT: String get() = L.editor.draftSaveTitle
    val SCHEDULED_FOR: String get() = L.editor.scheduledFor
    val SCHEDULES: String get() = L.schedules.title
    val SCHEDULING_TIME: String get() = L.editor.settingSchedule
    val SELECT_AN_ACTION: String get() = L.aiAssist.actionLabel
    val SELECT_ASPECT_RATIO: String get() = L.aiImageGenerator.selectAspectRatio
    val SELECT_POST_THUMBNAIL: String get() = L.editor.selectThumb
    val SET_DEFAULT: String get() = L.editor.setDefault
    val SHORT_DESCRIPTION: String get() = L.editor.shortDesc
    val SHOW_MORE_CONFIGURATION: String get() = L.postPoll.wizardExpandConfig
    val SHOW_VOTERS: String get() = L.postPoll.configShowVoters
    val SNIPPETS: String get() = L.editor.snippets
    val SNIPPET_INCOMPLETE: String get() = L.snippets.messageIncomplete
    val SNIPPET_TITLE: String get() = L.snippets.placeholderTitle
    val SOURCE_LANGUAGE: String get() = L.composeTranslate.source
    val TABLE: String get() = L.native.ui.editor.table
    val TAGS_SEPARATE_WITH_SPACE: String get() = L.editor.tags
    val TARGET_LANGUAGE: String get() = L.composeTranslate.target
    val TELL_US_SOMETHING: String get() = L.editor.defaultPlaceholder
    val TEMPLATES: String get() = L.templates.title
    val TEMPLATES_EMPTY: String get() = L.templates.emptyList
    val TEMPLATE_NAME: String get() = L.templates.namePlaceholder
    val TEXT_FORMAT: String get() = L.native.ui.editor.textFormat
    val THIS_LINK_IS_ALREADY: String get() = L.native.ui.editor.thisLinkIsAlreadyInUse
    val TITLE: String get() = L.editor.title
    val TOKEN_HIVE_POWER: String get() = L.postPoll.tokens
    val TOP_COMMUNITIES: String get() = L.native.ui.editor.topCommunities
    val TRANSCRIBING: String get() = L.dictation.transcribing
    val TRANSLATE_POST: String get() = L.composeTranslate.title
    val TRY_ANOTHER: String get() = L.aiAssist.tryAnother
    val UNSYNCED_BODY: String get() = L.drafts.unsyncedBody
    val UNSYNCED_DRAFT: String get() = L.drafts.unsyncedTitle
    val UNTITLED: String get() = L.native.ui.editor.untitled
    val UNTITLED_TEMPLATE: String get() = L.templates.untitled
    val UPDATE_CURRENT_DRAFT: String get() = L.editor.draftUpdate
    val UPDATE_IT_OR_PUBLISH: String get() = L.native.ui.editor.updateItOrPublishThisAs
    val UPDATE_POLL: String get() = L.postPoll.updatePoll
    val UPLOADED_IMAGES: String get() = L.editor.uploadedImages
    val USERNAME: String get() = L.beneficiaryModal.username
    val YES_BUTTON: String get() = L.editor.alertBtnYes
    val YOUR_EDITS_WERE_NOT: String get() = L.native.ui.editor.yourEditsWereNotPublished
    val YOUR_POST_WAS_ALREADY: String get() = L.native.ui.editor.yourPostWasAlreadyPublished
    val YOUR_TEXT: String get() = L.aiAssist.textLabel
    val YOU_HAVE_UNSAVED_CHANGES: String get() = L.native.ui.editor.youHaveUnsavedChangesFromAn
    fun choice(number: Int): String = L.postPoll.choicePlaceholder(number = LocaleFormat.integer(number.toLong()))
    fun communityRestriction(title: String): String = L.editor.communityRestriction(title = title)
    fun cost(cost: String): String = L.native.ui.editor.cost(cost = cost)
    fun deleteCount(count: Int): String = L.native.ui.editor.deleteCount(count = LocaleFormat.integer(count.toLong()))
    fun fileSize(name: String, size: String): String = L.native.ui.editor.fileSize(name = name, size = size)
    fun supportEcency(percent: Int): String = L.editor.supportEcency(percent = LocaleFormat.integer(percent.toLong()))
    val THUMBNAIL_PICK: String get() = L.native.ui.editor.thumbnailPick
    val RECORDING_BUSY: String get() = L.native.ui.editor.recordingBusy
    val PHOTO_LOST: String get() = L.native.ui.editor.photoLost
    val ADD_SNIPPET_BUTTON: String get() = L.native.ui.editor.addSnippetButton
    val LABEL: String get() = L.editor.label
    val URL: String get() = L.editor.url
    val POLL_ACCOUNT_AGE: String get() = L.postPoll.configAge
    val POLL_MAX_OPTIONS: String get() = L.postPoll.configMaxOptions
    val AI_TEXT_PLACEHOLDER: String get() = L.aiAssist.textPlaceholder
    val AI_GENERATED_IMAGE: String get() = L.aiImageGenerator.resultTitle
    fun thumbnailNumber(number: Int): String = L.native.ui.editor.thumbnailNumber(number = LocaleFormat.integer(number.toLong()))
    fun tooManyImages(count: Int): String = L.native.plurals.alert.tooManyImages(count = count)
    fun wordsCount(count: Int): String = L.native.ui.editor.wordsCount(count = count)
    val COMMUNITY_SEARCH: String get() = L.native.ui.editor.communitySearch
}

/** Reading: post, comments, feeds, waves, listen, votes, notifications, post menu and translation. */
object ReadingUiCopy {
    val ACTIVITIES: String get() = L.native.ui.reading.leaderboardActivities
    val LEADERBOARD_REWARD: String get() = L.native.ui.reading.leaderboardReward
    val ADD_A_TAG: String get() = L.waves.addTagPlaceholder
    val ADD_TAG: String get() = L.native.ui.reading.addTag
    val AGE: String get() = L.native.ui.reading.age
    val APPLY_CAPS: String get() = L.native.ui.reading.apply
    val AUDIO_LANGUAGE: String get() = L.native.ui.reading.audioLanguage
    val AVAILABLE_FILTERS: String get() = L.native.ui.reading.availableFilters
    val CHANGE_LANGUAGE: String get() = L.postTranslate.changeLanguage
    val COMMENTS: String get() = L.comments.title
    val COULD_NOT_UNLOCK_THE: String get() = L.native.ui.reading.couldNotUnlockTheAudioPlease
    val CROSS_POST: String get() = L.postDropdown.crossPost
    val CROSS_POST_DESCRIPTION: String get() = L.crossPost.description
    val CUSTOMISE_FILTERS: String get() = L.selectionList.titleFilters
    val CUSTOM_FEEDS: String get() = L.waves.customFeedsTitle
    val CUSTOM_FEEDS_SUBTITLE: String get() = L.waves.customFeedsSubtitle
    val DOWNVOTE: String get() = L.native.ui.reading.downvote
    val EDIT_HISTORY: String get() = L.postDropdown.editHistory
    val GET_POINTS: String get() = L.boost.title
    val HIDE_STATS: String get() = L.postPoll.hideStats
    val INTERPRET_BY_TOKENS: String get() = L.postPoll.interpretToken
    val INTERPRET_BY_VOTES: String get() = L.postPoll.interpretVote
    val LEADERBOARD: String get() = L.notification.leaderboard
    val LISTEN_TO_THIS_POST: String get() = L.native.ui.reading.listenToThisPost
    val LOAD_FAILED_RETRY: String get() = L.alert.loadFailedRetry
    val LOGIN_TO_SEE: String get() = L.profile.loginToSee
    val LOG_IN: String get() = L.native.ui.reading.logIn
    val MARK_ALL_AS_READ: String get() = L.native.ui.reading.markAllAsRead
    val MARK_AS_READ: String get() = L.native.ui.reading.markAsRead
    val MORE_REPLIES: String get() = L.native.ui.reading.moreReplies
    /** The reel player's sound. */
    val MUTE: String get() = L.native.ui.reading.muteSound
    val NOTHING_HERE_YET: String get() = L.native.ui.reading.nothingHereYet
    val NOTIFICATIONS: String get() = L.notification.notification
    val NOT_ENOUGH_POINTS: String get() = L.native.ui.reading.notEnoughPoints
    val NO_RECENT_ACTIVITY: String get() = L.notification.noactivity
    val OPEN_EDITOR: String get() = L.native.ui.reading.openEditor
    val PAUSE: String get() = L.listen.pause
    val PAUSE_VOICE: String get() = L.native.ui.reading.pauseVoice
    val PLAY: String get() = L.listen.play
    val PLAYBACK_SPEED: String get() = L.native.ui.reading.playbackSpeed
    val PLAY_VOICE: String get() = L.native.ui.reading.playVoice
    val PLEASE_ADD_FROM_AVAILABLE: String get() = L.selectionList.noSelected
    val POST_OPTIONS: String get() = L.post.a11yPostOptions
    val PROMOTED: String get() = L.post.promoted
    val PUBLISH: String get() = L.quickReply.publish
    val PUBLISHING_IN_WAVES: String get() = L.native.ui.reading.publishingInWaves
    val PUSH_NOTIFICATION: String get() = L.settings.pushNotification
    val QUESTS_DONE: String get() = L.native.ui.reading.questsDone
    val READ: String get() = L.native.ui.reading.read
    val READ_NEXT: String get() = L.similarEntries.title
    val REMOVE_VOTE: String get() = L.native.ui.reading.removeVote
    val REPLY: String get() = L.native.ui.reading.reply
    val SEEK: String get() = L.native.ui.reading.seek
    val SELECTED_FILTERS: String get() = L.native.ui.reading.selectedFilters
    val SELECT_COMMUNITY: String get() = L.native.ui.reading.selectCommunity
    val SHARE_YOUR_THOUGHTS_HERE: String get() = L.crossPost.messagePlaceholder
    val SHOW_ORIGINAL: String get() = L.postTranslate.showOriginal
    val SOURCES: String get() = L.waves.sources
    val THE_AUDIO_COULD_NOT: String get() = L.native.ui.reading.theAudioCouldNotBeLoaded
    val THIS_POST_DOES_NOT: String get() = L.native.ui.reading.thisPostDoesNotExistOr
    val TOP: String get() = L.home.top
    val TOP_CAPS: String get() = L.commentFilter.top
    val TOP_USERS: String get() = L.notification.leaderboardTitle
    val TRENDING_TAGS: String get() = L.waves.trendingTags
    val UNMUTE: String get() = L.native.ui.reading.unmuteSound
    val UNREAD: String get() = L.native.ui.reading.unread
    val USER_IS_NOT_FOUND: String get() = L.voters.noUser
    val VIDEO_ATTACHED: String get() = L.uploadsModal.videoAttached
    val VIEW_STATS: String get() = L.postPoll.viewStats
    val VOICE_POSITION: String get() = L.native.ui.reading.voicePosition
    /** A poll's vote button. */
    val VOTE: String get() = L.native.ui.reading.pollVote
    /** The comment sort by votes. */
    val VOTES: String get() = L.native.ui.reading.sortVotes
    val VOTE_WEIGHT: String get() = L.native.ui.reading.voteWeight
    val WHATS_HAPPENING: String get() = L.quickReply.placeholderWave
    val YOUR_FEEDS: String get() = L.waves.yourFeeds
    fun downvotePercent(percent: Int): String = L.native.ui.reading.downvotePercent(percent = LocaleFormat.integer(percent.toLong()))
    fun rebloggedBy(username: String): String = L.native.ui.reading.rebloggedBy(username = username)
    fun shortContentHint(n: Int): String = L.native.plurals.editor.shortContentHint(n = n)
    val NO_MORE_POSTS: String get() = L.native.ui.reading.noMorePosts
    val FAVOURITE: String get() = L.native.ui.reading.favourite
    val NO_VOTES_YET: String get() = L.native.ui.reading.noVotesYet
    val NO_COMMENTS_YET: String get() = L.native.ui.reading.noCommentsYet
    val SENDING_TO_CHAIN: String get() = L.native.ui.reading.sendingToChain
    val COMPARE: String get() = L.native.ui.reading.compare
    val YOUR_VOTE: String get() = L.native.ui.reading.yourVote
    val WHAT_DO_YOU_THINK: String get() = L.native.ui.reading.whatDoYouThink
    val CLEAR_TAG_FILTER: String get() = L.native.ui.reading.clearTagFilter
    val ADD_TAG_FEED: String get() = L.native.ui.reading.addTagFeed
    val ATTACHED_IMAGE_REMOVE: String get() = L.native.ui.reading.attachedImageRemove
    val CAMERA: String get() = L.native.ui.reading.camera
    val FILES: String get() = L.native.ui.reading.files
    val PAUSED: String get() = L.native.ui.reading.paused
    val POST: String get() = L.wallet.post
    fun commentsCount(count: Int): String = L.native.ui.reading.commentsCount(count = LocaleFormat.integer(count.toLong()))
    fun replyTo(username: String): String = L.native.ui.reading.replyTo(username = username)
    fun tabSelected(title: String): String = L.native.ui.reading.tabSelected(title = title)
    fun feedPinned(label: String): String = L.native.ui.reading.feedPinned(label = label)
    fun inTopic(topic: String): String = L.native.ui.reading.inTopic(topic = topic)
}

/** Chat screens and sheets. */
object ChatUiCopy {
    val CANCEL_EDIT: String get() = L.native.ui.chat.cancelEdit
    val CANCEL_REPLY: String get() = L.native.ui.chat.cancelReply
    val CHANNELS: String get() = L.chats.channels
    val CHANNEL_MEMBERS: String get() = L.chats.channelMembers
    val CHANNEL_OPTIONS: String get() = L.chats.channelOptions
    val COPIED_TO_CLIPBOARD: String get() = L.native.ui.chat.copiedToClipboard
    val DELETE_MESSAGE: String get() = L.chats.remove
    val FAVORITE: String get() = L.chats.favorite
    val GROUP_OPTIONS: String get() = L.native.ui.chat.groupOptions
    val JOIN: String get() = L.chats.join
    val MEMBERS: String get() = L.native.ui.chat.members
    val USERS: String get() = L.chats.users
    val MESSAGE_OPTIONS: String get() = L.chats.messageActions
    val MORE_MENU: String get() = L.native.ui.chat.more
    val NO_EMOJI_FOUND: String get() = L.chats.noEmojiFound
    val NO_MEMBERS_FOUND: String get() = L.chats.noMembers
    val PINNED_MESSAGES: String get() = L.native.ui.chat.pinnedMessages
    val SEARCH_EMOJIS: String get() = L.chats.searchEmojis
    val SELECT_EMOJI: String get() = L.chats.selectEmoji
    val SORT_BY_NAME: String get() = L.native.ui.chat.sortByName
    val UNPIN: String get() = L.chats.unpin
    fun editedAt(time: String): String = L.native.ui.chat.editedAt(time = time)
    fun removeUser(username: String): String = L.native.ui.chat.removeUser(username = username)
    fun reactionLabel(emoji: String, count: Int): String = L.native.ui.chat.reactionLabel(emoji = emoji, count = LocaleFormat.integer(count.toLong()))
}

/** Profile screens. */
object ProfileUiCopy {
    /** The profile header's menu button. */
    val MENU: String get() = L.native.ui.profile.menu
    /** Title of a list of accounts. */
    val USERS: String get() = L.native.ui.profile.users
    val AN_ERROR_OCCURRED: String get() = L.alert.unknowError
    val BOOKMARKS: String get() = L.sideMenu.bookmarks
    val CONFIRM_REPORT: String get() = L.report.confirmReportTitle
    val COVER_IMAGE: String get() = L.profile.edit.coverImg
    val ECENCY_PRO_MEMBER: String get() = L.pro.badgeA11y
    val EDIT_PROFILE: String get() = L.profile.edit.editProfile
    val EMAIL_DIGEST: String get() = L.newsletter.profileOption
    val OPEN_WALLET: String get() = L.native.ui.profile.openWallet
    val REPORT_CONFIRM: String get() = L.report.confirmReportBody
    val WALLET: String get() = L.profile.wallet
    val YOUR_WALLET_IS_ON: String get() = L.native.ui.profile.yourWalletIsOnTheWallet
    fun userHasNoComments(username: String): String = L.native.ui.profile.userHasNoComments(username = username)
    /** A post card's line under the title: payout, votes and replies, counts in the app language. */
    fun cardStats(payout: String, votes: Int, replies: Int): String =
        listOf(payout, L.native.ui.profile.votesCount(count = votes), L.native.ui.profile.repliesCount(count = replies)).joinToString("  •  ")
}

/** Wallet, transfer and store screens. */
object WalletUiCopy {
    val PRODUCT_NOT_AVAILABLE: String get() = L.native.ui.wallet.productNotAvailable
    val REFRESH: String get() = L.wallet.refresh
    val REPEAT: String get() = L.native.ui.wallet.repeat
    val SCAN_QR_RECIPIENT: String get() = L.transfer.scanQrRecipient
    val SWAP: String get() = L.wallet.swap
}

/** Sign-in, lock and key screens beyond [LoginCopy], [HiveAuthCopy] and [AccountCopy]. */
object AuthUiCopy {
    val APPROVE_THE_REQUEST_IN: String get() = L.native.ui.auth.approveTheRequestInYourWallet
    val AUTHENTICATION_FAILED: String get() = L.hiveauth.authFail
    val CLEAR_USER_DATA_CONFIRM: String get() = L.alert.clearUserAlert
    val CURRENT_ACCOUNT: String get() = L.native.ui.auth.currentAccount
    val OH_I_FORGOT_IT: String get() = L.pincode.forgotText
    val QR_CODE: String get() = L.native.ui.auth.qrCode
    val UNLOCK_WITH_BIOMETRICS: String get() = L.native.appLock.offerTitle
    val VERIFY_DIRECT_LOGIN: String get() = L.login.deepLoginAlertBody
    fun accountLabel(username: String): String = L.native.ui.auth.accountLabel(username = username)
    fun easyLogin(username: String): String = L.login.deepLoginAlertTitle(username = username)
    fun keyCopyLabel(title: String): String = L.native.ui.auth.copyKey(title = title)
    fun pinDigits(filled: Int, total: Int): String = L.native.ui.auth.pinDigits(filled = LocaleFormat.integer(filled.toLong()), total = total)
    fun privateKeyShown(title: String): String = L.native.ui.auth.privateKeyShown(title = title)
    fun requestedBy(requester: String): String = L.native.ui.auth.requestedBy(requester = requester)
}

/** The shell: tabs, header, drawer, dApp browser chrome, system prompts. */
object ShellUiCopy {
    val ACCOUNT_SIGNED_OUT: String get() = L.native.ui.shell.accountSignedOut
    val CLOSE_TAB: String get() = L.native.ui.shell.closeTab
    val COMING_SOON_TO_THE: String get() = L.native.ui.shell.comingSoonToTheNativeApp
    val CREATE: String get() = L.native.ui.shell.create
    /** Accessibility state of a collapsible header (the profile summary, the community Details card). */
    val EXPANDED: String get() = L.native.ios.a11yShell.expanded
    val COLLAPSED: String get() = L.native.ios.a11yShell.collapsed
    /** The action a collapsed header offers (TalkBack "double tap to expand"). */
    val EXPAND: String get() = L.native.ui.shell.expand
    val FORWARD: String get() = L.native.ui.shell.forward
    val HOME: String get() = L.native.dappBrowser.homeTitle
    val LOADING: String get() = L.native.ui.shell.loading
    val LOGIN_TO_CUSTOMIZE_FEED: String get() = L.header.title
    val NEW_TAB: String get() = L.native.ui.shell.newTab
    val NO_CONNECTION: String get() = L.alert.noInternet
    val PERKS: String get() = L.perks.header
    val QR_SCAN: String get() = L.sideMenu.qr
    val RELOAD: String get() = L.native.ui.shell.reload
    val SENT: String get() = L.native.ui.shell.sent
    val SOMETHING_WRONG_ALT: String get() = L.alert.somethingWrongAlt
    fun accountOf(username: String): String = L.native.ui.shell.accountOf(username = username)
    fun tabUnread(title: String, count: String): String = L.native.ui.shell.tabUnread(title = title, count = count)
    val NO_APP_FOR_LINK: String get() = L.native.ui.shell.noAppForLink
}
