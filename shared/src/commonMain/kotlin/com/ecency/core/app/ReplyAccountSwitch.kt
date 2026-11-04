package com.ecency.core.app

import com.ecency.core.l10n.L

object ReplyAccountCopy {
    /** What screen readers hear for the reply editor's account tile (RN draws avatar, name and an arrow). */
    val SWITCH: String get() = L.native.replyAccount.switch
}

data class ReplyAccountState(
    /** The accounts sheet is asked for (the platform opens it once and calls [ReplyAccountHandoff.sheetShown]). */
    val askAccounts: Boolean = false,
)

/**
 * RN reply editor's account tile (markdownEditorView `changeUser`: the accounts sheet). In RN the open editor
 * simply carries on as the newly chosen account. Here an editor belongs to the account that opened it and every
 * screen that needs an account goes on an account change, so the tile hands the reply over instead: the editor's
 * text is saved for its account ([EditorModel.flush]), and when the switch that follows lands on another account
 * the same reply editor opens again for it ([reopen]), starting from the text that was in the tile's editor
 * ([takeBody]) unless that account has its own draft of this reply. Only a switch within [WINDOW_MILLIS] of the
 * tap counts; anything else (the sheet closed, a sign-out) hands nothing over. One per app.
 */
class ReplyAccountHandoff internal constructor(private val env: AppEnvironment) :
    StateModel<ReplyAccountState>(ReplyAccountState(), env.dispatchers) {

    private class Pending(val from: String, val args: EditorArgs, val body: String, val at: Long)

    private var pending: Pending? = null
    private var carried: Triple<EditorArgs, String, Long>? = null

    /** The tile: only a reply editor of the signed-in account hands over. */
    fun begin(editor: EditorModel) {
        val s = editor.current
        val owner = s.owner ?: return
        if (editor.args.kind != EditorKind.REPLY || s.isSending || env.session.current.username != owner) return
        editor.flush()
        pending = Pending(owner, editor.args, s.body, env.nowMillis())
        setState { ReplyAccountState(askAccounts = true) }
    }

    /** The platform opened the accounts sheet. */
    fun sheetShown() = setState { ReplyAccountState() }

    /**
     * The navigation after an account change to [username]: [afterChange] (what any change leaves), plus the reply
     * editor again when this change is the switch the tile asked for.
     */
    fun reopen(afterChange: NavState, username: String?): NavState {
        val p = pending ?: return afterChange
        pending = null
        if (username == null || username == p.from || env.nowMillis() - p.at !in 0..WINDOW_MILLIS) return afterChange
        carried = Triple(p.args, p.body, env.nowMillis())
        return afterChange.push(AppScreen.Editor(p.args))
    }

    /**
     * The text the tile's editor held, for the reply editor of [args] that [reopen] opened; once. The first reply
     * editor to load after the switch ends the hand-over whatever it is, and so does a failed load.
     */
    internal fun takeBody(args: EditorArgs): String? {
        val c = carried ?: return null
        // anything else asking (another editor) or asking late ends the hand-over: the text never drifts elsewhere
        carried = null
        if (c.first != args || env.nowMillis() - c.third !in 0..WINDOW_MILLIS) return null
        return c.second
    }

    /** The app's one model: screens do not own it. */
    override fun close() {}

    companion object {
        /** How long after the tap a switch still counts as the tile's. */
        const val WINDOW_MILLIS = 5 * 60 * 1000L
    }
}
