package com.ecency.core.app

import com.ecency.core.api.chat.ChatChannel
import com.ecency.core.api.chat.ChatPost
import com.ecency.core.api.chat.ChatReaction
import com.ecency.core.api.chat.ChatUser
import com.ecency.core.api.chat.firstText
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import com.ecency.core.render.js.Js
import com.ecency.core.render.markdown.ChatInline
import com.ecency.core.render.markdown.InlineRun
import com.ecency.core.render.methods.JsRegex
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/*
 * The pure parts of RN's chat screens (src/providers/chat/mattermost.ts badge math, src/screens/chats/utils:
 * groupUtils, messageFormatters, chatBanNotice, chatLinkify, emoji, and the small pieces of the children that compute
 * text). Ported line by line; the golden test (ChatGoldens, generated from the RN modules themselves) pins them. The
 * inline markdown follows web instead (#3468, [ChatInlineMarkdown], pinned by ChatInlineFixtures).
 */

/** RN getChannelUnreadMeta's result. */
data class ChatUnreadMeta(val unreadMentions: Long, val unreadMessages: Long, val unreadCount: Long, val totalUnread: Long) {
    companion object {
        val ZERO = ChatUnreadMeta(0, 0, 0, 0)
    }
}

object ChatUnread {
    /**
     * RN isChannelUnreadEligible: the server's `unread_eligible: false` wins; muted never counts; a channel that is
     * not a direct message or group and was never viewed (`last_viewed_at ?? last_view_at` null or 0) never counts.
     */
    fun eligible(c: ChatChannel): Boolean {
        if (c.unreadEligible == false) return false
        if (c.isMuted) return false
        val viewed = c.lastViewedAt ?: c.lastViewAt
        if (!c.isConversation && (viewed == null || viewed == 0L)) return false
        return true
    }

    /** RN getChannelUnreadMeta: mentions are a subset of the messages, so the total is the larger of the two. */
    fun meta(c: ChatChannel): ChatUnreadMeta {
        if (!eligible(c)) return ChatUnreadMeta.ZERO
        val mentions = if (c.mentionCounters.isEmpty()) 0.0 else max(0.0, c.mentionCounters.max())
        val messages = c.messageCounter ?: 0.0
        val count = if (mentions != 0.0) mentions else messages
        return ChatUnreadMeta(mentions.toLong(), messages.toLong(), count.toLong(), max(mentions, messages).toLong())
    }

    fun total(c: ChatChannel): Long = meta(c).totalUnread

    /** RN calculateGlobalUnreadTotal over a channel list. */
    fun globalTotal(channels: List<ChatChannel>): Long = channels.sumOf { total(it) }
}

/** RN src/screens/chats/utils/groupUtils.ts. */
object ChatGroups {
    /** Mattermost allows 3 to 8 members in a group, the creator included. */
    const val MIN_OTHERS = 2
    const val MAX_OTHERS = 7

    /** Characters (code points) a group name may have, as the server counts them. */
    const val NAME_MAX_LENGTH = 64

    /** The length of a group name as the server counts it (code points of the trimmed name), so an emoji is one. */
    fun nameLength(name: String): Int {
        val t = Js.trim(name)
        var n = 0
        var i = 0
        while (i < t.length) {
            i += if (t[i].isHighSurrogate() && i + 1 < t.length && t[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }

    /** RN userName: the Hive name, else the username. */
    fun userName(user: ChatUser): String = firstText(user.hiveName, user.username).orEmpty()

    fun memberNames(channel: ChatChannel): List<String> = channel.groupUsers.map(::userName).filter { it.isNotEmpty() }

    /** A group's name, trimmed; null when empty or when it reads as an official account (web safeChatName). */
    fun safeGroupName(name: String?): String? = OfficialAccounts.safeName(name?.let { Js.trim(it) }, null)

    /** "alice, bob, carol +2"; [fallback] without names. */
    fun membersTitle(users: List<ChatUser>, fallback: String, maxNames: Int = 3): String {
        val names = users.map(::userName).filter { it.isNotEmpty() }
        if (names.isEmpty()) return fallback
        if (names.size <= maxNames) return names.joinToString(", ")
        return names.take(maxNames).joinToString(", ") + " +${names.size - maxNames}"
    }

    /** The name its owner gave the group (unless it poses as an official account), else its members. */
    fun title(channel: ChatChannel, fallback: String? = null): String =
        safeGroupName(channel.groupName)
            ?: membersTitle(channel.groupUsers, firstText(fallback, channel.displayName, channel.name).orEmpty())

    /** One entry per emoji in the order each emoji was first used; ids once each (RN groupReactions, the reactors sheet). */
    fun reactions(reactions: List<ChatReaction>, currentUserId: String?): List<GroupedReaction> {
        val byEmoji = LinkedHashMap<String, GroupedReaction>()
        for (r in reactions) {
            if (r.emojiName.isEmpty()) continue
            val entry = byEmoji[r.emojiName] ?: GroupedReaction(r.emojiName, emptyList(), false)
            val ids = if (r.userId.isNotEmpty() && r.userId !in entry.userIds) entry.userIds + r.userId else entry.userIds
            byEmoji[r.emojiName] = entry.copy(userIds = ids, reacted = entry.reacted || (currentUserId != null && r.userId == currentUserId))
        }
        return byEmoji.values.toList()
    }

    /** Reactor ids missing from [known], once each, sorted (one batched request). */
    fun missingUserIds(ids: List<String>, known: Set<String>): List<String> = ids.filter { it.isNotEmpty() && it !in known }.distinct().sorted()

    /** The new name of a group rename notice (`system_header_change`, `props.new_header`). */
    fun renamedName(post: ChatPost): String = post.newHeader.orEmpty()
}

data class GroupedReaction(val emojiName: String, val userIds: List<String>, val reacted: Boolean)

/** A reaction pill under a message (RN MessageReactions). */
data class ReactionPill(val emojiName: String, val emoji: String, val count: Int, val reacted: Boolean) {
    /** RN shows the count only above one. */
    val showsCount: Boolean get() = count > 1
}

object ChatReactions {
    /**
     * RN MessageReactions: one pill per emoji, counting every reaction of that emoji. The order is the JS object's
     * key order: integer-like names ("100", "1234") first in numeric order, then the others in first-use order.
     */
    fun pills(reactions: List<ChatReaction>, currentUserId: String?): List<ReactionPill> {
        val grouped = LinkedHashMap<String, MutableList<ChatReaction>>()
        for (r in reactions) grouped.getOrPut(r.emojiName) { mutableListOf() }.add(r)
        val (indexed, named) = grouped.entries.partition { isArrayIndex(it.key) }
        return (indexed.sortedBy { it.key.toLong() } + named).map { (name, list) ->
            ReactionPill(name, ChatEmoji.display(name), list.size, currentUserId != null && list.any { it.userId == currentUserId })
        }
    }

    /** A canonical array index as JS orders object keys: digits, no leading zero (except "0"), below 2^32 - 1. */
    internal fun isArrayIndex(key: String): Boolean {
        if (key.isEmpty() || key.length > 10 || key.any { it !in '0'..'9' }) return false
        if (key.length > 1 && key[0] == '0') return false
        return key.toLong() < 4294967295L
    }
}

/** One styled piece of a chat message (RN inlineMarkdown InlineSpan). */
data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
) {
    val plain: Boolean get() = !bold && !italic && !strike && !code
}

/**
 * Inline markdown in chat: bold (`**`, `__`), italic (`*`, `_`), strikethrough (`~~`) and code spans, read as web's
 * `simpleMarkdownToHTML` reads them ([ChatInline], remarkable's own rules). Block constructs, HTML and entities are
 * left as typed (README "Chats").
 */
object ChatInlineMarkdown {
    fun parse(text: String): List<InlineSpan> =
        ChatInline.runs(text).map { InlineSpan(it.text, it.bold, it.italic, it.strike, it.code) }
}

/** A piece of a rendered chat message: styled text, a mention (bold) or a link with its shown text. */
data class ChatSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    /** The link's target (http, https or hive://); [text] is what is shown for it (RN setLinkText). */
    val url: String? = null,
)

/** The text and the images of one message (RN parseMessageContent). */
data class ChatMessageContent(val text: String, val images: List<String>)

/** RN src/screens/chats/utils/messageFormatters.tsx and chatLinkify.ts. */
object ChatText {
    private val IMAGE_REGEX = Regex("""https?://.*\.(?:png|jpg|jpeg|gif|heic|webp)""", RegexOption.IGNORE_CASE)
    private const val URL_BODY = "+&@#/%?=~_|!:,.;-"
    private const val URL_END = "-+&@#/%=~_|"
    private val PROFILE_LINK = Regex("""/@([a-zA-Z0-9\-.]+)""")
    private val MENTION_WORD = Regex("""^@([a-zA-Z0-9\-.]+)""")
    private val ECENCY_PROFILE = Regex("""^https://ecency\.com/@([a-zA-Z0-9\-.]+)/?$""")

    /**
     * RN extractUrls (utils/editor.ts): `/(\b(https?|ftp):\/\/[A-Z0-9+&@#/%?=~_|!:,.;-]*[-A-Z0-9+&@#/%=~_|])/gim`, as
     * a scanner (an ASCII word boundary before the scheme, the longest run that ends on an end character).
     */
    fun extractUrls(body: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < body.length) {
            val scheme = listOf("https://", "http://", "ftp://").firstOrNull { Js.startsWithCi(body, it, i) }
            if (scheme == null || (i > 0 && Js.isWordChar(body[i - 1]))) {
                i++
                continue
            }
            var j = i + scheme.length
            var lastEnd = -1
            while (j < body.length && (body[j].isAsciiAlnum() || body[j] in URL_BODY)) {
                if (body[j].isAsciiAlnum() || body[j] in URL_END) lastEnd = j
                j++
            }
            if (lastEnd >= 0) {
                out += body.substring(i, lastEnd + 1)
                i = lastEnd + 1
            } else {
                i++
            }
        }
        return out
    }

    private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** RN extractImageUrls: the urls with an image extension anywhere after the scheme. */
    fun extractImageUrls(body: String): List<String> = extractUrls(body).filter { IMAGE_REGEX.containsMatchIn(it) }

    /**
     * RN parseMessageContent: images leave the text (markdown image, markdown link and bare forms; the url is used as
     * a pattern, as RN does), `@name` words become profile links unless the text has profile links already, then the
     * text is trimmed and `:emoji:` names are replaced.
     */
    fun content(raw: String, parseMentionUrl: Boolean = true): ChatMessageContent {
        val images = extractImageUrls(raw)
        var text = raw
        for (url in images) text = removeImage(text, url)
        if (parseMentionUrl && !PROFILE_LINK.containsMatchIn(text)) {
            text = text.split(' ').joinToString(" ") { word ->
                if (word.startsWith("http://") || word.startsWith("https://")) word
                else MENTION_WORD.find(word)?.let { "https://ecency.com/@${it.groupValues[1]}" + word.substring(it.range.last + 1) } ?: word
            }
        }
        return ChatMessageContent(ChatEmoji.emojify(Js.trim(text)), images)
    }

    private fun removeImage(text: String, url: String): String {
        val patterns = listOf("!\\[.*?\\]\\($url\\)", "\\[.*?\\]\\($url\\)", url)
        var out = text
        for (p in patterns) {
            // RN builds these from the url without escaping it; where JS would throw (an unbalanced bracket), RN's
            // render crashes: here the url is removed literally instead
            val regex = runCatching { JsRegex.compile(p) }.getOrNull()
            out = regex?.let { runCatching { it.replace(out, "") }.getOrNull() } ?: if (p == url) out.replace(url, "") else out
        }
        return out
    }

    /** RN setLinkText: an Ecency profile url shows as "@name". */
    fun linkText(url: String): String = ECENCY_PROFILE.find(url)?.let { "@${it.groupValues[1]}" } ?: url

    /** A link found in a message: [start] until [end] in the text, [url] its target. */
    data class Link(val start: Int, val end: Int, val url: String)

    /**
     * The links linkify-it finds with RN's settings (no fuzzy links, plus the `hive:` scheme): `http://`, `https://`
     * and `ftp://` urls up to white space, without trailing punctuation or an unbalanced closing bracket, and
     * `hive://` links up to white space (RN chatLinkify). E-mail addresses stay text (RN makes them mailto links).
     */
    fun links(text: String): List<Link> {
        val out = mutableListOf<Link>()
        var i = 0
        while (i < text.length) {
            val hive = startsWithCi(text, i, "hive://")
            val scheme = when {
                hive -> "hive://"
                startsWithCi(text, i, "https://") -> "https://"
                startsWithCi(text, i, "http://") -> "http://"
                startsWithCi(text, i, "ftp://") -> "ftp://"
                else -> null
            }
            // linkify-it wants a scheme at a word start
            if (scheme == null || (i > 0 && isSchemeChar(text[i - 1]))) {
                i++
                continue
            }
            var end = i + scheme.length
            if (hive) {
                while (end < text.length && !Js.isSpace(text[end]) && text[end] !in "<>\"") end++
                if (end > i + scheme.length) out += Link(i, end, text.substring(i, end))
                i = maxOf(end, i + 1)
                continue
            }
            // the host (and port, user info) first: a character it cannot hold ends the link there
            while (end < text.length && (text[end].isLetterOrDigit() || text[end] in "-._~:@%")) end++
            if (end < text.length && text[end] in "/?#") {
                while (end < text.length && !Js.isSpace(text[end]) && text[end] !in "<>\"") end++
            }
            end = trimUrlEnd(text, i + scheme.length, end)
            val host = text.substring(i + scheme.length, end).takeWhile { it !in "/?#" }
            if (host.isNotEmpty() && host.any { it.isLetterOrDigit() } && !host.startsWith(".") && !host.startsWith("-")) {
                out += Link(i, end, text.substring(i, end))
                i = end
            } else {
                i += scheme.length
            }
        }
        return out
    }

    private fun isSchemeChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun startsWithCi(s: String, at: Int, lit: String) = Js.startsWithCi(s, lit, at)

    /** linkify-it's tail rules: trailing `.,:;!?'"` go, and a closing bracket only stays when it closes one in the url. */
    private fun trimUrlEnd(text: String, from: Int, to: Int): Int {
        var end = to
        while (end > from) {
            val c = text[end - 1]
            val drop = when (c) {
                '.', ',', ':', ';', '!', '?', '\'', '"' -> true
                ')' -> count(text, from, end, '(') < count(text, from, end, ')')
                ']' -> count(text, from, end, '[') < count(text, from, end, ']')
                '}' -> count(text, from, end, '{') < count(text, from, end, '}')
                else -> false
            }
            if (!drop) break
            end--
        }
        return end
    }

    private fun count(s: String, from: Int, to: Int, c: Char): Int {
        var n = 0
        for (k in from until to) if (s[k] == c) n++
        return n
    }

    private const val ATOM = '\uFFFC'
    private const val MARKERS = "*_~`"

    /** The longest message whose inline marks are read; longer ones show as typed (links and mentions still work). */
    const val MAX_MARKDOWN = 8_000

    /**
     * Web reads the marks before it finds links, so `**see https://x.com**` ends its link before the `**`. Here links
     * come first: the longest run of marker characters ending a link leaves it only when it closes a mark opened
     * before the link (the text so far with the link as one unit, read as markdown, consumes it); otherwise it is part
     * of the URL, as web keeps `a_` in `a _b_ c https://x.com/a_`.
     */
    private fun trimClosingMarkers(text: String, link: Link): Link {
        var end = link.end
        while (end > link.start && text[end - 1] in MARKERS) end--
        if (end == link.end || end <= link.start + link.url.indexOf("://") + 3) return link
        val before = buildString { for (k in 0 until link.start) append(if (text[k] == ATOM) '\uFFFD' else text[k]) }
        for (cut in end until link.end) {
            val closer = text.substring(cut, link.end)
            val read = ChatInline.runs(before + ATOM + closer).joinToString("") { it.text }
            if (read.endsWith(ATOM)) return Link(link.start, cut, text.substring(link.start, cut))
        }
        return link
    }

    /** A link or a mention: one unit the markdown cannot split, styled as a whole. */
    private class Atom(val start: Int, val end: Int, val link: Link?)

    /**
     * RN renderTextWithBoldMentions with web's inline markdown: `@name` mentions (after white space or at the start,
     * not inside a link) in bold, links as links (shown as [linkText]), and the marks around them as web reads them
     * (`**hi @bob**` is bold through the mention). Markers inside a link or a mention never count, a code span shows
     * a link or mention inside it as its raw text.
     */
    fun spans(text: String): List<ChatSpan> {
        // the rebuild runs on the main thread: a message longer than this shows its marks as typed (Mattermost allows
        // 16383 characters; real chat lines are far shorter)
        val markdown = text.length <= MAX_MARKDOWN
        val links = links(text).map { if (markdown) trimClosingMarkers(text, it) else it }
        val atoms = mutableListOf<Atom>()
        links.forEach { atoms += Atom(it.start, it.end, it) }
        var i = 0
        while (i < text.length) {
            // `(^|\s)@name`: the start, or a white space character followed by "@"
            val atIndex = if (i == 0 && text[0] == '@') 0 else if (Js.isSpace(text[i])) i + 1 else -1
            if (atIndex >= 0 && atIndex < text.length && text[atIndex] == '@') {
                var e = atIndex + 1
                while (e < text.length && (text[e].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '.' })) e++
                if (e > atIndex + 1) {
                    if (links.none { atIndex >= it.start && e <= it.end }) atoms += Atom(atIndex, e, null)
                    i = e
                    continue
                }
            }
            i++
        }
        atoms.sortBy { it.start }
        val kept = mutableListOf<Atom>()
        for (a in atoms) if (kept.isEmpty() || a.start >= kept.last().end) kept += a
        // each atom becomes one placeholder character for the markdown (one the sender typed shows as U+FFFD)
        val src = StringBuilder(text.length)
        var last = 0
        fun plain(to: Int) {
            for (k in last until to) src.append(if (text[k] == ATOM) '\uFFFD' else text[k])
        }
        for (a in kept) {
            plain(a.start)
            src.append(ATOM)
            last = a.end
        }
        plain(text.length)
        val out = mutableListOf<ChatSpan>()
        var next = 0
        for (run in if (markdown) ChatInline.runs(src.toString()) else listOf(InlineRun(src.toString()))) {
            if (run.code) {
                val sb = StringBuilder()
                for (c in run.text) if (c == ATOM && next < kept.size) kept[next++].let { sb.append(text, it.start, it.end) } else sb.append(c)
                out += ChatSpan(sb.toString(), run.bold, run.italic, run.strike, code = true)
                continue
            }
            var from = 0
            for (k in run.text.indices) {
                if (run.text[k] != ATOM || next >= kept.size) continue
                if (k > from) out += ChatSpan(run.text.substring(from, k), run.bold, run.italic, run.strike)
                val a = kept[next++]
                out += if (a.link != null) {
                    ChatSpan(linkText(a.link.url), run.bold, run.italic, run.strike, url = a.link.url)
                } else {
                    ChatSpan(text.substring(a.start, a.end), bold = true, italic = run.italic, strike = run.strike)
                }
                from = k + 1
            }
            if (from < run.text.length) out += ChatSpan(run.text.substring(from), run.bold, run.italic, run.strike)
        }
        return out
    }

    /** RN formatJoinedMessage's name: `@name` of the added user (from props, else the users lookup), or null. */
    fun addedUsername(post: ChatPost, lookup: Map<String, ChatUser>): String? {
        val name = post.addedUsername ?: post.addedUserId?.let { id -> lookup[id]?.hiveName }
        return name?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("@")) it else "@$it" }
    }

    /** RN SystemMessageItem / GroupedSystemMessages: "joined 2 hours ago" ([FromNow] in the app language), or "joined" without a time. */
    fun joinedSuffix(timestamp: Long, nowMillis: Long): String =
        if (timestamp != 0L) L.native.chat.joinedWhen(time = FromNow.format(timestamp, nowMillis)) else L.native.chat.joined

    /** RN formatPostBody: a join notice reads "@name joined 2 hours ago", any other post its body. */
    fun body(post: ChatPost, lookup: Map<String, ChatUser>, nowMillis: Long): String {
        if (post.isSystemJoin) {
            val name = addedUsername(post, lookup)
            if (name != null) return "$name ${joinedSuffix(post.timestamp, nowMillis)}"
        }
        return post.body
    }

    /** RN ReplyPreview: at most 100 characters, then "...". */
    fun quote(text: String): String = if (text.length > 100) text.substring(0, 100) + "..." else text

    /** RN TypingIndicator's sentence; null when nobody types. */
    fun typing(names: List<String>): String? = when (names.size) {
        0 -> null
        1 -> L.native.chat.typingOne(name = names[0])
        2 -> L.native.chat.typingTwo(first = names[0], second = names[1])
        3 -> L.native.chat.typingThree(first = names[0], second = names[1], third = names[2])
        else -> L.native.chat.typingMany(first = names[0], second = names[1], count = names.size - 2)
    }

    /**
     * RN _updateMentionState: an `@query` being typed at the end of the text, after white space or at the start.
     * Returns the index of the "@" and the query, or null.
     */
    fun mentionQuery(text: String): Pair<Int, String>? {
        var e = text.length
        while (e > 0 && text[e - 1].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.' || it == '-' }) e--
        val at = e - 1
        if (at < 0 || text[at] != '@') return null
        if (at > 0 && !Js.isSpace(text[at - 1])) return null
        return at to text.substring(e)
    }

    /** RN _handleSelectMention: the text with the `@query` at [start] replaced by `@username` and a space as needed. */
    fun insertMention(text: String, start: Int?, query: String?, username: String): String {
        val s = start ?: text.length
        val afterStart = s + 1 + (query?.length ?: 0)
        val before = if (start != null) text.substring(0, minOf(s, text.length)) else text
        val after = if (start != null && afterStart <= text.length) text.substring(afterStart) else ""
        val space = if (after.isEmpty()) " " else if (after.startsWith(" ") || after.startsWith("\n")) "" else " "
        return "$before@$username$space$after"
    }

    /** RN getMentionUsername: the user's Hive name, else username, nickname or name. */
    fun mentionName(user: ChatUser): String = firstText(user.hiveName, user.username, user.safeNickname, user.safeName).orEmpty()

    /** RN mentionSuggestions: names containing the query (any case), in name order, at most 8. */
    fun suggestions(users: Collection<ChatUser>, query: String?): List<ChatUser> {
        if (query == null) return emptyList()
        val q = Js.lower(query)
        return users.filter { u -> mentionName(u).let { it.isNotEmpty() && (q.isEmpty() || Js.lower(it).contains(q)) } }
            .sortedWith { a, b -> Collate.compare(mentionName(a), mentionName(b)) }
            .take(8)
    }
}

/** The posting ban notice (RN chatBanNotice.ts, kept identical to the web client's wording). */
object ChatBan {
    /** RN BAN_NOTICE_TICK_MS: the notice counts down on this tick. */
    const val TICK_MILLIS = 30_000L

    /** A live ban from a refused send, or null (no expiry, an unreadable one, or already over). */
    fun info(bannedUntil: Double?, reason: String?, nowMillis: Long): ChatBanInfo? {
        val until = bannedUntil ?: return null
        if (until == 0.0 || !until.isFinite() || until <= nowMillis) return null
        return ChatBanInfo(until.toLong(), reason)
    }

    /** RN formatBanRemaining: rounded up to minutes, then hours, then days (plural messages: "1 hour" at 90 minutes). */
    fun remaining(bannedUntil: Long, nowMillis: Long): String {
        val ms = max(0L, bannedUntil - nowMillis).toDouble()
        val minutes = ceil(ms / 60_000).toLong()
        if (minutes <= 1) return L.chats.banRemainingSoon
        if (minutes < 90) return L.native.plurals.chats.banRemainingMinutes(count = minutes.toInt())
        val hours = jsRound(ms / 3_600_000)
        if (hours < 48) return L.native.plurals.chats.banRemainingHours(count = hours.toInt())
        return L.native.plurals.chats.banRemainingDays(count = jsRound(ms / 86_400_000).toInt())
    }

    private fun jsRound(v: Double): Long = floor(v + 0.5).toLong()

    private fun reasonSentence(reason: String?): String = when (reason) {
        "spray" -> L.chats.banReasonSpray
        "mass-dm" -> L.chats.banReasonMassDm
        else -> L.chats.banReasonGeneric
    }

    /** What happened, that reading still works, and when it lifts. */
    fun notice(info: ChatBanInfo, nowMillis: Long): String =
        L.native.chat.banned(reason = reasonSentence(info.reason), until = remaining(info.bannedUntil, nowMillis))

    /** RN `chats.banned_from_chat`: a ban without a usable expiry. */
    val GENERIC: String get() = L.chats.bannedFromChat
}

data class ChatBanInfo(val bannedUntil: Long, val reason: String?)

/**
 * The ordering JS `localeCompare` gives the names chats sorts (ICU root collation, primary level first): white
 * space, then punctuation and symbols, then digits, then letters, case-insensitive with lower case first on a tie.
 * Close to ICU for account, channel and emoji names; not a full collation.
 */
object Collate {
    /** ICU root order of the punctuation and symbols in names, lowest first. */
    private const val ORDER = "_-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$"
    private const val SYMBOLS_FROM = 24

    private const val ACCENTED = "àáâãäåçèéêëìíîïñòóôõöùúûüýÿ"
    private const val BASE = "aaaaaaceeeeiiiinooooouuuuyy"

    private fun fold(c: Char): Char {
        val l = c.lowercaseChar()
        val k = ACCENTED.indexOf(l)
        return if (k >= 0) BASE[k] else l
    }

    private fun cls(c: Char): Int = when {
        Js.isSpace(c) -> 0
        c in ORDER -> if (ORDER.indexOf(c) < SYMBOLS_FROM) 1 else 2
        c.isDigit() -> 3
        c.isLetter() -> 4
        else -> 5
    }

    private fun key(c: Char): Int = ORDER.indexOf(c).takeIf { it >= 0 } ?: c.code

    fun compare(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            val fa = fold(a[i])
            val fb = fold(b[i])
            if (fa == fb) continue
            val ka = cls(fa)
            val kb = cls(fb)
            if (ka != kb) return ka.compareTo(kb)
            return key(fa).compareTo(key(fb))
        }
        if (a.length != b.length) return a.length.compareTo(b.length)
        // secondary: an accent sorts after its plain letter; tertiary: lower case before upper case
        for (i in 0 until n) if (a[i].lowercaseChar() != b[i].lowercaseChar()) return if (a[i].lowercaseChar() == fold(a[i])) -1 else 1
        for (i in 0 until n) if (a[i] != b[i]) return if (a[i].isLowerCase()) -1 else 1
        return 0
    }
}

/** RN emoji helpers (utils/emoji.ts, utils/emojiMapper.ts, the emoji picker) over the app's emoji map. */
object ChatEmoji {
    /** Mattermost name to emoji, in the map's order. */
    val map: Map<String, String> by lazy {
        val m = LinkedHashMap<String, String>()
        for (chunk in ChatEmojiData.CHUNKS) for (line in chunk.split('\n')) {
            val tab = line.indexOf('\t')
            if (tab > 0) m[line.substring(0, tab)] = line.substring(tab + 1)
        }
        m
    }

    private val EMOJI_NAME = Regex(""":([a-zA-Z0-9_+\-]+):""")

    /** RN emojifyMessage: `:name:` (any case) becomes its emoji; unknown names stay as typed. */
    fun emojify(message: String): String {
        if (message.isEmpty()) return ""
        return EMOJI_NAME.replace(message) { m -> map[Js.lower(m.groupValues[1])] ?: m.value }
    }

    /** RN mattermostToUnicode: the emoji for a reaction name, else the name itself. */
    fun display(name: String): String = map[name] ?: name

    /** One picker entry: the shortest name of an emoji and its other names (search aliases). */
    data class Entry(val name: String, val emoji: String, val aliases: List<String>)

    /** RN emojiPickerSheet ALL_EMOJIS: one entry per emoji (shortest name, first on a tie), text fallbacks skipped, by name. */
    val picker: List<Entry> by lazy {
        val byEmoji = LinkedHashMap<String, Pair<String, MutableList<String>>>()
        for ((name, emoji) in map) {
            if (emoji.isEmpty() || emoji == name) continue
            val existing = byEmoji[emoji]
            if (existing == null) {
                byEmoji[emoji] = name to mutableListOf(name)
                continue
            }
            existing.second += name
            if (name.length < existing.first.length) byEmoji[emoji] = name to existing.second
        }
        byEmoji.map { (emoji, v) -> Entry(v.first, emoji, v.second.filter { it != v.first }) }
            .sortedWith { a, b -> Collate.compare(a.name, b.name) }
    }

    /** The picker's search: names or aliases containing the query, any case. */
    fun search(query: String): List<Entry> {
        val q = Js.lower(Js.trim(query))
        if (q.isEmpty()) return picker
        return picker.filter { e -> Js.lower(e.name).contains(q) || e.aliases.any { Js.lower(it).contains(q) } }
    }

    /** RN ChatOptionsSheet's quick reactions. */
    val QUICK: List<Pair<String, String>> = listOf("+1" to "👍", "-1" to "👎", "smile" to "😄", "heart" to "❤️")
}
