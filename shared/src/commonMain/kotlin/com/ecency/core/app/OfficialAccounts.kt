package com.ecency.core.app

import com.ecency.core.api.chat.ChatPost
import com.ecency.core.api.chat.ChatUser
import com.ecency.core.l10n.L

/**
 * Ecency's own chat accounts (web `features/chat/official-accounts.ts`, ported as is): the list, the official badge
 * and the check that keeps other people's display names from posing as them. The badge is keyed only by the
 * account's username, never by a name someone chose.
 */
object OfficialAccounts {
    /** Esie, the Ecency AI helper. It answers in chat, so it needs a chat session. */
    const val ESIE_USERNAME = "esie"

    /** Accounts run by Ecency. They get the official badge wherever they appear in chat and on their profile. */
    val USERNAMES: List<String> = listOf(ESIE_USERNAME, "ecency")

    /** The badge's text ("Official"). */
    val BADGE: String get() = L.native.chat.officialBadge

    /** What screen readers say for the badge. */
    val BADGE_LABEL: String get() = L.native.chat.officialBadgeLabel

    /** Exactly one of [USERNAMES], ignoring a leading `@` and case (web isOfficialChatAccount). */
    fun isOfficial(username: String?): Boolean =
        !username.isNullOrEmpty() && username.removePrefix("@").lowercase() in USERNAMES

    /**
     * True when someone else's display name reads as an official account ("Esie", "Ecency Support", "E-s-i-e",
     * "Еcency" with a Cyrillic Е), so chat shows their username instead and the badge stays the only mark of the
     * real one. A word has to start with the name: "Decency" and "Jane Siegel" are left alone.
     */
    fun impersonates(displayName: String?, username: String?): Boolean {
        if (displayName.isNullOrEmpty() || isOfficial(username)) return false
        val words = nameWords(displayName)
        return USERNAMES.any { official -> words.any { startsLike(it, official) } }
    }

    /**
     * Web isOfficialPostAuthor: true only for a post by the official account's own chat user, looked up by the post's
     * user id in [users] (never the user a post embeds), never from a name the post carries (a webhook or bot post can
     * name any user) and never for a system notice.
     */
    fun postedByOfficial(post: ChatPost, users: Map<String, ChatUser>): Boolean {
        if (post.type.startsWith("system_") || post.relayed) return false
        return users[post.userId]?.isOfficial == true
    }

    /** A name someone chose, or null when it poses as an official account (web safeChatName). */
    fun safeName(name: String?, username: String?): String? =
        name?.takeIf { it.isNotEmpty() && !impersonates(it, username) }

    // Letters that render like Latin ones (Cyrillic, Greek, small capitals) and digits or symbols used in their place
    private val LOOKALIKES: Map<Int, Char> = buildMap {
        fun map(from: String, to: Char) = from.forEach { put(it.code, to) }
        map("\u0430\u03b14@", 'a') // а α 4 @
        map("\u0435\u0451\u03b5\u025b\u1d073", 'e') // е ё ε ɛ ᴇ 3
        map("\u043e\u03bf0", 'o') // о ο 0
        map("\u0441\u03f2\u1d04\u00a2", 'c') // с ϲ ᴄ ¢
        map("\u0443\u04af\u03b3\u028f", 'y') // у ү γ ʏ
        map("\u0456\u0457\u04cf\u03b9\u0131\u02691!|l", 'i') // і ї ӏ ι ı ɩ 1 ! | l
        map("\u0458", 'j') // ј
        map("\u0455\u03c2\ua7315\$", 's') // ѕ ς ꜱ 5 $
        map("\u043a\u03ba", 'k') // к κ
        map("\u043f\u0274", 'n') // п ɴ
        map("\u0440", 'p') // р
        map("\u0445", 'x') // х
        map("\u04bb", 'h') // һ
        map("\u0501", 'd') // ԁ
        map("\u03bd", 'v') // ν
    }

    // A letter or digit still unknown after the mapping above matches any one letter
    private const val ANY = '*'

    /** The name's words in Latin letters. A run of single letters ("E s i e", "E.s.i.e") is one word. */
    internal fun nameWords(text: String): List<String> {
        val visible = StringBuilder()
        for (cp in codePoints(text)) if (!isInvisible(cp)) appendCodePoint(visible, cp)
        val latin = StringBuilder()
        for (cp in codePoints(visible.toString().lowercase())) {
            // the table first: decomposing can turn a lookalike into something else (lunate ϲ into ς)
            val direct = LOOKALIKES[cp]
            val parts = if (direct != null) intArrayOf(direct.code) else decomposed(cp)
            for (part in parts) {
                val ch = LOOKALIKES[part]?.code ?: part
                latin.append(
                    when {
                        ch in 'a'.code..'z'.code -> ch.toChar()
                        UnicodeText.isLetterOrNumber(ch) -> ANY
                        else -> ' '
                    },
                )
            }
        }
        val words = ArrayList<String>()
        var letters = ""
        for (word in latin.split(' ').filter { it.isNotEmpty() }) {
            if (word.length == 1) {
                letters += word
                continue
            }
            if (letters.isNotEmpty()) words.add(letters)
            letters = ""
            words.add(word)
        }
        if (letters.isNotEmpty()) words.add(letters)
        return words
    }

    // A word starting with the official name, allowing one letter we could not read
    private fun startsLike(word: String, official: String): Boolean {
        if (word.length < official.length) return false
        var unknown = 0
        for (i in official.indices) {
            if (word[i] == ANY) unknown++ else if (word[i] != official[i]) return false
        }
        return unknown <= 1
    }

    /** JS `ch.normalize("NFKD")` without its marks, as code points. ASCII decomposes to itself. */
    private fun decomposed(cp: Int): IntArray {
        if (cp < 0x80) return intArrayOf(cp)
        val one = StringBuilder().also { appendCodePoint(it, cp) }.toString()
        return codePoints(UnicodeText.nfkd(one)).filter { !UnicodeText.isMark(it) }.toIntArray()
    }

    /** `\p{Default_Ignorable_Code_Point}` or `\p{Cf}`: zero-width spaces, joiners, soft hyphens, direction marks. */
    private fun isInvisible(cp: Int): Boolean = UnicodeText.isFormat(cp) || DEFAULT_IGNORABLE.any { cp in it }

    // DerivedCoreProperties.txt, Default_Ignorable_Code_Point (Unicode 15.1)
    private val DEFAULT_IGNORABLE: List<IntRange> = listOf(
        0x00AD..0x00AD, 0x034F..0x034F, 0x061C..0x061C, 0x115F..0x1160, 0x17B4..0x17B5, 0x180B..0x180F,
        0x200B..0x200F, 0x202A..0x202E, 0x2060..0x206F, 0x3164..0x3164, 0xFE00..0xFE0F, 0xFEFF..0xFEFF,
        0xFFA0..0xFFA0, 0xFFF0..0xFFF8, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0000..0xE0FFF,
    )

    private fun codePoints(s: String): List<Int> {
        val out = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                out.add(0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00))
                i += 2
            } else {
                out.add(c.code)
                i++
            }
        }
        return out
    }

    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp < 0x10000) {
            sb.append(cp.toChar())
        } else {
            val v = cp - 0x10000
            sb.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }
}

/** The Unicode facts [OfficialAccounts] needs that the common standard library lacks, from the platform. */
internal expect object UnicodeText {
    /** Compatibility decomposition (NFKD), as JS `normalize("NFKD")`. */
    fun nfkd(text: String): String

    /** General category M (Mn, Mc, Me). */
    fun isMark(codePoint: Int): Boolean

    /** General category L or N. */
    fun isLetterOrNumber(codePoint: Int): Boolean

    /** General category Cf. */
    fun isFormat(codePoint: Int): Boolean
}
