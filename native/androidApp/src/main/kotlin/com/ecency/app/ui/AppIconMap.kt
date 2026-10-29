package com.ecency.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ecency.app.R
import com.ecency.core.app.AppIcon
import com.ecency.core.app.EcencyLogoMark
import com.ecency.core.app.WavesMark

/*
 * The one place icons become pictures on Android: every [AppIcon] maps to a Material Symbols (Outlined, weight 400)
 * vector drawable written by scripts/icons/material_symbols.py from scripts/icons/icons.tsv, outlined at rest and
 * filled (FILL 1) for an active, selected or toggled state. Directional drawables carry android:autoMirrored.
 * The Waves mark is the one custom glyph, drawn from the shared [WavesMark] path.
 */

/** The drawable for [this] at rest, or null for the custom Waves mark. */
@DrawableRes
fun AppIcon.drawableRes(): Int? = when (this) {
    AppIcon.WAVES -> null
    AppIcon.HOME -> R.drawable.ic_home
    AppIcon.CHAT -> R.drawable.ic_chat
    AppIcon.COMMENT -> R.drawable.ic_comment
    AppIcon.WALLET -> R.drawable.ic_wallet
    AppIcon.NOTIFICATIONS -> R.drawable.ic_notifications
    AppIcon.NOTIFICATIONS_OFF -> R.drawable.ic_notifications_off
    AppIcon.BACK -> R.drawable.ic_back
    AppIcon.ARROW_FORWARD -> R.drawable.ic_arrow_forward
    AppIcon.CHEVRON_LEFT -> R.drawable.ic_chevron_left
    AppIcon.CHEVRON_RIGHT -> R.drawable.ic_chevron_right
    AppIcon.CHEVRON_DOUBLE_RIGHT -> R.drawable.ic_chevron_double_right
    AppIcon.EXPAND -> R.drawable.ic_expand
    AppIcon.COLLAPSE -> R.drawable.ic_collapse
    AppIcon.ARROW_UP -> R.drawable.ic_arrow_up
    AppIcon.ARROW_DOWN -> R.drawable.ic_arrow_down
    AppIcon.DROPDOWN -> R.drawable.ic_dropdown
    AppIcon.MORE -> R.drawable.ic_more
    AppIcon.CLOSE -> R.drawable.ic_close
    AppIcon.CLEAR -> R.drawable.ic_clear
    AppIcon.ADD -> R.drawable.ic_add
    AppIcon.REMOVE -> R.drawable.ic_remove
    AppIcon.CHECK -> R.drawable.ic_check
    AppIcon.CHECK_CIRCLE -> R.drawable.ic_check_circle
    AppIcon.TASK -> R.drawable.ic_task
    AppIcon.CHECKBOX -> R.drawable.ic_checkbox
    AppIcon.SEARCH -> R.drawable.ic_search
    AppIcon.QR_SCAN -> R.drawable.ic_qr_scan
    AppIcon.BOOST -> R.drawable.ic_boost
    AppIcon.PERSON -> R.drawable.ic_person
    AppIcon.ACCOUNT -> R.drawable.ic_account
    AppIcon.PERSON_ADD -> R.drawable.ic_person_add
    AppIcon.GROUP -> R.drawable.ic_group
    AppIcon.MEMBERS -> R.drawable.ic_members
    AppIcon.GROUP_ADD -> R.drawable.ic_group_add
    AppIcon.BOOKMARK -> R.drawable.ic_bookmark
    AppIcon.DRAFTS -> R.drawable.ic_drafts
    AppIcon.EXPLORE -> R.drawable.ic_explore
    AppIcon.SHARE -> R.drawable.ic_share
    AppIcon.HELP -> R.drawable.ic_help
    AppIcon.SETTINGS -> R.drawable.ic_settings
    AppIcon.POWER -> R.drawable.ic_power
    AppIcon.LOGOUT -> R.drawable.ic_logout
    AppIcon.GIFT -> R.drawable.ic_gift
    AppIcon.TRANSLATE -> R.drawable.ic_translate
    AppIcon.REFRESH -> R.drawable.ic_refresh
    AppIcon.LOCK -> R.drawable.ic_lock
    AppIcon.VERIFIED_USER -> R.drawable.ic_verified_user
    AppIcon.MODERATOR -> R.drawable.ic_moderator
    AppIcon.GLOBE -> R.drawable.ic_globe
    AppIcon.WARNING -> R.drawable.ic_warning
    AppIcon.ERROR -> R.drawable.ic_error
    AppIcon.INFO -> R.drawable.ic_info
    AppIcon.PLAY -> R.drawable.ic_play
    AppIcon.PAUSE -> R.drawable.ic_pause
    AppIcon.PLAY_CIRCLE -> R.drawable.ic_play_circle
    AppIcon.HEADPHONES -> R.drawable.ic_headphones
    AppIcon.CHART_BAR -> R.drawable.ic_chart_bar
    AppIcon.CHART_LINE -> R.drawable.ic_chart_line
    AppIcon.EDIT -> R.drawable.ic_edit
    AppIcon.COMPOSE -> R.drawable.ic_compose
    AppIcon.SWAP -> R.drawable.ic_swap
    AppIcon.SORT -> R.drawable.ic_sort
    AppIcon.DISCARD -> R.drawable.ic_discard
    AppIcon.HEART -> R.drawable.ic_heart
    AppIcon.REBLOG -> R.drawable.ic_reblog
    AppIcon.REPEAT -> R.drawable.ic_repeat
    AppIcon.PIN -> R.drawable.ic_pin
    AppIcon.SEND -> R.drawable.ic_send
    AppIcon.BACKSPACE -> R.drawable.ic_backspace
    AppIcon.KEY -> R.drawable.ic_key
    AppIcon.STAR -> R.drawable.ic_star
    AppIcon.SNOWFLAKE -> R.drawable.ic_snowflake
    AppIcon.BOLT -> R.drawable.ic_bolt
    AppIcon.MEGAPHONE -> R.drawable.ic_megaphone
    AppIcon.ROCKET -> R.drawable.ic_rocket
    AppIcon.CHECKIN -> R.drawable.ic_checkin
    AppIcon.VOTE_UP -> R.drawable.ic_vote_up
    AppIcon.VOTE_DOWN -> R.drawable.ic_vote_down
    AppIcon.TUNE -> R.drawable.ic_tune
    AppIcon.FILTER -> R.drawable.ic_filter
    AppIcon.HISTORY -> R.drawable.ic_history
    AppIcon.PREVIEW -> R.drawable.ic_preview
    AppIcon.AI -> R.drawable.ic_ai
    AppIcon.AI_ASSIST -> R.drawable.ic_ai_assist
    AppIcon.IMAGE -> R.drawable.ic_image
    AppIcon.LINK -> R.drawable.ic_link
    AppIcon.DOWNLOAD -> R.drawable.ic_download
    AppIcon.CLIPBOARD -> R.drawable.ic_clipboard
    AppIcon.VERIFIED -> R.drawable.ic_verified
    AppIcon.CALENDAR -> R.drawable.ic_calendar
    AppIcon.LOCATION -> R.drawable.ic_location
    AppIcon.EMAIL -> R.drawable.ic_email
    AppIcon.MUTED -> R.drawable.ic_muted
    AppIcon.SOUND_ON -> R.drawable.ic_sound_on
    AppIcon.SORT_AZ -> R.drawable.ic_sort_az
    AppIcon.MARK_ALL_READ -> R.drawable.ic_mark_all_read
    AppIcon.CAMERA -> R.drawable.ic_camera
    AppIcon.FOLDER -> R.drawable.ic_folder
    AppIcon.VIDEO -> R.drawable.ic_video
    AppIcon.VIDEO_ATTACHED -> R.drawable.ic_video_attached
    AppIcon.MIC -> R.drawable.ic_mic
    AppIcon.CROSS_POST -> R.drawable.ic_cross_post
    AppIcon.REPLY -> R.drawable.ic_reply
    AppIcon.FULLSCREEN -> R.drawable.ic_fullscreen
    AppIcon.OFFLINE -> R.drawable.ic_offline
    AppIcon.CLOUD_OFF -> R.drawable.ic_cloud_off
    AppIcon.FACE_ID -> R.drawable.ic_face_id
    AppIcon.TOUCH_ID -> R.drawable.ic_touch_id
    AppIcon.TICKET -> R.drawable.ic_ticket
    AppIcon.MONEY -> R.drawable.ic_money
    AppIcon.AUTORENEW -> R.drawable.ic_autorenew
    AppIcon.REORDER -> R.drawable.ic_reorder
    AppIcon.ESCROW -> R.drawable.ic_escrow
    AppIcon.DELEGATION -> R.drawable.ic_delegation
    AppIcon.HOURGLASS_FULL -> R.drawable.ic_hourglass_full
    AppIcon.HOURGLASS_TOP -> R.drawable.ic_hourglass_top
    AppIcon.CREATED -> R.drawable.ic_created
    AppIcon.REFUND -> R.drawable.ic_refund
    AppIcon.TROPHY -> R.drawable.ic_trophy
    AppIcon.TARGET -> R.drawable.ic_target
    AppIcon.SHRINK -> R.drawable.ic_shrink
}

/** The filled drawable for [this], or null when it has no filled form (the outline is drawn instead). */
@DrawableRes
fun AppIcon.filledDrawableRes(): Int? = when (this) {
    AppIcon.HOME -> R.drawable.ic_home_fill
    AppIcon.CHAT -> R.drawable.ic_chat_fill
    AppIcon.COMMENT -> R.drawable.ic_comment_fill
    AppIcon.WALLET -> R.drawable.ic_wallet_fill
    AppIcon.NOTIFICATIONS -> R.drawable.ic_notifications_fill
    AppIcon.NOTIFICATIONS_OFF -> R.drawable.ic_notifications_off_fill
    AppIcon.CLEAR -> R.drawable.ic_clear_fill
    AppIcon.REMOVE -> R.drawable.ic_remove_fill
    AppIcon.CHECK_CIRCLE -> R.drawable.ic_check_circle_fill
    AppIcon.TASK -> R.drawable.ic_task_fill
    AppIcon.CHECKBOX -> R.drawable.ic_checkbox_fill
    AppIcon.BOOST -> R.drawable.ic_boost_fill
    AppIcon.PERSON -> R.drawable.ic_person_fill
    AppIcon.ACCOUNT -> R.drawable.ic_account_fill
    AppIcon.PERSON_ADD -> R.drawable.ic_person_add_fill
    AppIcon.GROUP -> R.drawable.ic_group_fill
    AppIcon.MEMBERS -> R.drawable.ic_members_fill
    AppIcon.GROUP_ADD -> R.drawable.ic_group_add_fill
    AppIcon.BOOKMARK -> R.drawable.ic_bookmark_fill
    AppIcon.DRAFTS -> R.drawable.ic_drafts_fill
    AppIcon.EXPLORE -> R.drawable.ic_explore_fill
    AppIcon.SHARE -> R.drawable.ic_share_fill
    AppIcon.HELP -> R.drawable.ic_help_fill
    AppIcon.SETTINGS -> R.drawable.ic_settings_fill
    AppIcon.GIFT -> R.drawable.ic_gift_fill
    AppIcon.LOCK -> R.drawable.ic_lock_fill
    AppIcon.VERIFIED_USER -> R.drawable.ic_verified_user_fill
    AppIcon.MODERATOR -> R.drawable.ic_moderator_fill
    AppIcon.WARNING -> R.drawable.ic_warning_fill
    AppIcon.ERROR -> R.drawable.ic_error_fill
    AppIcon.INFO -> R.drawable.ic_info_fill
    AppIcon.PLAY -> R.drawable.ic_play_fill
    AppIcon.PAUSE -> R.drawable.ic_pause_fill
    AppIcon.PLAY_CIRCLE -> R.drawable.ic_play_circle_fill
    AppIcon.HEADPHONES -> R.drawable.ic_headphones_fill
    AppIcon.EDIT -> R.drawable.ic_edit_fill
    AppIcon.COMPOSE -> R.drawable.ic_compose_fill
    AppIcon.DISCARD -> R.drawable.ic_discard_fill
    AppIcon.HEART -> R.drawable.ic_heart_fill
    AppIcon.PIN -> R.drawable.ic_pin_fill
    AppIcon.SEND -> R.drawable.ic_send_fill
    AppIcon.BACKSPACE -> R.drawable.ic_backspace_fill
    AppIcon.KEY -> R.drawable.ic_key_fill
    AppIcon.STAR -> R.drawable.ic_star_fill
    AppIcon.BOLT -> R.drawable.ic_bolt_fill
    AppIcon.MEGAPHONE -> R.drawable.ic_megaphone_fill
    AppIcon.ROCKET -> R.drawable.ic_rocket_fill
    AppIcon.VOTE_UP -> R.drawable.ic_vote_up_fill
    AppIcon.VOTE_DOWN -> R.drawable.ic_vote_down_fill
    AppIcon.PREVIEW -> R.drawable.ic_preview_fill
    AppIcon.AI -> R.drawable.ic_ai_fill
    AppIcon.AI_ASSIST -> R.drawable.ic_ai_assist_fill
    AppIcon.IMAGE -> R.drawable.ic_image_fill
    AppIcon.CLIPBOARD -> R.drawable.ic_clipboard_fill
    AppIcon.VERIFIED -> R.drawable.ic_verified_fill
    AppIcon.CALENDAR -> R.drawable.ic_calendar_fill
    AppIcon.LOCATION -> R.drawable.ic_location_fill
    AppIcon.EMAIL -> R.drawable.ic_email_fill
    AppIcon.MUTED -> R.drawable.ic_muted_fill
    AppIcon.SOUND_ON -> R.drawable.ic_sound_on_fill
    AppIcon.CAMERA -> R.drawable.ic_camera_fill
    AppIcon.FOLDER -> R.drawable.ic_folder_fill
    AppIcon.VIDEO -> R.drawable.ic_video_fill
    AppIcon.VIDEO_ATTACHED -> R.drawable.ic_video_attached_fill
    AppIcon.MIC -> R.drawable.ic_mic_fill
    AppIcon.CLOUD_OFF -> R.drawable.ic_cloud_off_fill
    AppIcon.FACE_ID -> R.drawable.ic_face_id_fill
    AppIcon.TICKET -> R.drawable.ic_ticket_fill
    AppIcon.ESCROW -> R.drawable.ic_escrow_fill
    AppIcon.DELEGATION -> R.drawable.ic_delegation_fill
    AppIcon.HOURGLASS_FULL -> R.drawable.ic_hourglass_full_fill
    AppIcon.HOURGLASS_TOP -> R.drawable.ic_hourglass_top_fill
    AppIcon.CREATED -> R.drawable.ic_created_fill
    AppIcon.TROPHY -> R.drawable.ic_trophy_fill
    else -> null
}

/** RN's wavy dash from the shared path: one round-capped stroke on a 36 unit grid. */
private val WavesVector: ImageVector by lazy {
    val path = PathBuilder().apply {
        moveTo(WavesMark.START[0], WavesMark.START[1])
        WavesMark.CURVES.forEach { c -> curveTo(c[0], c[1], c[2], c[3], c[4], c[5]) }
    }.nodes
    ImageVector.Builder("waves", 24.dp, 24.dp, WavesMark.GRID, WavesMark.GRID)
        .addPath(path, stroke = SolidColor(Color.Black), strokeLineWidth = WavesMark.STROKE, strokeLineCap = StrokeCap.Round)
        .build()
}

/** The Ecency logo mark (RN EcencySourceBadge) from the shared path, filled black for the caller to tint. */
val EcencyLogoMarkVector: ImageVector by lazy {
    val path = PathBuilder().apply {
        EcencyLogoMark.CONTOURS.forEach { c ->
            moveTo(c[0], c[1])
            for (i in 2 until c.size step 6) curveTo(c[i], c[i + 1], c[i + 2], c[i + 3], c[i + 4], c[i + 5])
            close()
        }
    }.nodes
    val box = EcencyLogoMark.VIEW_SIZE
    ImageVector.Builder("ecencyLogoMark", 12.dp, 12.dp, box, box)
        .addGroup(translationX = -EcencyLogoMark.VIEW_X, translationY = -EcencyLogoMark.VIEW_Y)
        .addPath(path, fill = SolidColor(Color.Black))
        .clearGroup()
        .build()
}

/** [this] as a vector: the filled form when [filled] and there is one, else the outline. */
@Composable
fun AppIcon.vector(filled: Boolean = false): ImageVector {
    val res = (if (filled) filledDrawableRes() else null) ?: drawableRes() ?: return WavesVector
    return ImageVector.vectorResource(res)
}

/**
 * An icon, tinted, [size] square. [description] is what the icon says when it stands alone (an icon-only control
 * takes its label from its action instead); null keeps it out of accessibility.
 */
@Composable
fun Glyph(
    icon: AppIcon,
    tint: Color,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
    description: String? = null,
    filled: Boolean = false,
) = Icon(icon.vector(filled), contentDescription = description, tint = tint, modifier = modifier.size(size))
