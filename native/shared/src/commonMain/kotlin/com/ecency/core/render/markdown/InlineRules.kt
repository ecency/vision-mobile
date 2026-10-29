package com.ecency.core.render.markdown

import com.ecency.core.render.js.Js
/** remarkable's inline rules (lib/rules_inline) and link helpers (lib/helpers). */
internal object InlineRules {
    private const val MAX_NESTING = BlockRules.MAX_NESTING

    private fun isTerminatorChar(ch: Int): Boolean = when (ch) {
        0x0A, 0x5C, 0x60, 0x2A, 0x5F, 0x5E, 0x5B, 0x5D, 0x21, 0x26, 0x3C, 0x3E, 0x7B, 0x7D, 0x24, 0x25,
        0x40, 0x7E, 0x2B, 0x3D, 0x3A -> true
        else -> false
    }

    fun text(state: StateInline, silent: Boolean): Boolean {
        var pos = state.pos
        while (pos < state.posMax && !isTerminatorChar(state.src[pos].code)) pos++
        if (pos == state.pos) return false
        if (!silent) state.pending.append(state.src, state.pos, pos)
        state.pos = pos
        return true
    }

    fun newline(state: StateInline, silent: Boolean): Boolean {
        var pos = state.pos
        if (Js.cc(state.src, pos) != 0x0A) return false
        val pending = state.pending
        val pmax = pending.length - 1
        val max = state.posMax
        if (!silent) {
            if (pmax >= 0 && pending[pmax].code == 0x20) {
                if (pmax >= 1 && pending[pmax - 1].code == 0x20) {
                    var i = pmax - 2
                    while (i >= 0) {
                        if (pending[i].code != 0x20) {
                            pending.setLength(i + 1)
                            break
                        }
                        i--
                    }
                    state.push(Token("hardbreak", state.level))
                } else {
                    pending.setLength(pmax)
                    state.push(Token("softbreak", state.level))
                }
            } else {
                state.push(Token("softbreak", state.level))
            }
        }
        pos++
        while (pos < max && Js.cc(state.src, pos) == 0x20) pos++
        state.pos = pos
        return true
    }

    private const val ESCAPED = "\\!\"#$%&'()*+,./:;<=>?@[]^_`{|}~-"

    fun escape(state: StateInline, silent: Boolean): Boolean {
        var pos = state.pos
        val max = state.posMax
        if (Js.cc(state.src, pos) != 0x5C) return false
        pos++
        if (pos < max) {
            val ch = state.src[pos]
            if (ESCAPED.indexOf(ch) >= 0) {
                if (!silent) state.pending.append(ch)
                state.pos += 2
                return true
            }
            if (ch.code == 0x0A) {
                if (!silent) state.push(Token("hardbreak", state.level))
                pos++
                while (pos < max && Js.cc(state.src, pos) == 0x20) pos++
                state.pos = pos
                return true
            }
        }
        if (!silent) state.pending.append('\\')
        state.pos++
        return true
    }

    fun backticks(state: StateInline, silent: Boolean): Boolean {
        var pos = state.pos
        if (Js.cc(state.src, pos) != 0x60) return false
        val start = pos
        pos++
        val max = state.posMax
        while (pos < max && Js.cc(state.src, pos) == 0x60) pos++
        val markerLen = pos - start
        var matchEnd = pos
        while (true) {
            val matchStart = state.src.indexOf('`', matchEnd)
            if (matchStart == -1) break
            matchEnd = matchStart + 1
            while (matchEnd < max && Js.cc(state.src, matchEnd) == 0x60) matchEnd++
            if (matchEnd - matchStart == markerLen) {
                if (!silent) {
                    val raw = Js.slice(state.src, pos, matchStart)
                    state.push(Token("code", state.level, Js.trim(collapseSpaceNewline(raw))).also { it.block = false })
                }
                state.pos = matchEnd
                return true
            }
        }
        if (!silent) state.pending.append(state.src, start, pos)
        state.pos += markerLen
        return true
    }

    /** `.replace(/[ \n]+/g, ' ')`. */
    private fun collapseSpaceNewline(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '\n') {
                while (i < s.length && (s[i] == ' ' || s[i] == '\n')) i++
                sb.append(' ')
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * del (`~~`), ins (`++`) and mark (`==`) share one algorithm; only the marker, the token names
     * and the length at which a marker run is taken literally differ.
     */
    private fun pair(state: StateInline, silent: Boolean, marker: Int, open: String, close: String, literalRunFrom: Int): Boolean {
        val max = state.posMax
        val start = state.pos
        if (Js.cc(state.src, start) != marker) return false
        if (silent) return false
        if (start + 4 >= max) return false
        if (Js.cc(state.src, start + 1) != marker) return false
        if (state.level >= MAX_NESTING) return false
        var lastChar = if (start > 0) Js.cc(state.src, start - 1) else -1
        var nextChar = Js.cc(state.src, start + 2)
        if (lastChar == marker) return false
        if (nextChar == marker) return false
        if (nextChar == 0x20 || nextChar == 0x0A) return false
        var pos = start + 2
        while (pos < max && Js.cc(state.src, pos) == marker) pos++
        if (pos >= start + literalRunFrom) {
            state.pos += pos - start
            state.pending.append(state.src, start, pos)
            return true
        }
        state.pos = start + 2
        var stack = 1
        var found = false
        while (state.pos + 1 < max) {
            if (Js.cc(state.src, state.pos) == marker) {
                if (Js.cc(state.src, state.pos + 1) == marker) {
                    lastChar = Js.cc(state.src, state.pos - 1)
                    nextChar = if (state.pos + 2 < max) Js.cc(state.src, state.pos + 2) else -1
                    if (nextChar != marker && lastChar != marker) {
                        if (lastChar != 0x20 && lastChar != 0x0A) {
                            stack--
                        } else if (nextChar != 0x20 && nextChar != 0x0A) {
                            stack++
                        }
                        if (stack <= 0) {
                            found = true
                            break
                        }
                    }
                }
            }
            state.parser.skipToken(state)
        }
        if (!found) {
            state.pos = start
            return false
        }
        state.posMax = state.pos
        state.pos = start + 2
        state.push(Token(open, state.level++))
        state.parser.tokenize(state)
        state.push(Token(close, --state.level))
        state.pos = state.posMax + 2
        state.posMax = max
        return true
    }

    // del: `pos > start + 3` is literal; ins and mark: `pos !== start + 2`.
    fun del(state: StateInline, silent: Boolean) = pair(state, silent, 0x7E, "del_open", "del_close", 4)
    fun ins(state: StateInline, silent: Boolean) = pair(state, silent, 0x2B, "ins_open", "ins_close", 3)
    fun mark(state: StateInline, silent: Boolean) = pair(state, silent, 0x3D, "mark_open", "mark_close", 3)

    private fun isAlphaNum(code: Int) = (code in 0x30..0x39) || (code in 0x41..0x5A) || (code in 0x61..0x7A)

    private class Delims(val canOpen: Boolean, val canClose: Boolean, val delims: Int)

    private fun scanDelims(state: StateInline, start: Int): Delims {
        var pos = start
        var canOpen = true
        var canClose = true
        val max = state.posMax
        val marker = Js.cc(state.src, start)
        val lastChar = if (start > 0) Js.cc(state.src, start - 1) else -1
        while (pos < max && Js.cc(state.src, pos) == marker) pos++
        if (pos >= max) canOpen = false
        val count = pos - start
        if (count >= 4) {
            canOpen = false
            canClose = false
        } else {
            val nextChar = if (pos < max) Js.cc(state.src, pos) else -1
            if (nextChar == 0x20 || nextChar == 0x0A) canOpen = false
            if (lastChar == 0x20 || lastChar == 0x0A) canClose = false
            if (marker == 0x5F) {
                val word = state.parser.extraWordChar
                if (isAlphaNum(lastChar) || (word >= 0 && lastChar == word)) canOpen = false
                if (isAlphaNum(nextChar) || (word >= 0 && nextChar == word)) canClose = false
            }
        }
        return Delims(canOpen, canClose, count)
    }

    fun emphasis(state: StateInline, silent: Boolean): Boolean {
        val max = state.posMax
        val start = state.pos
        val marker = Js.cc(state.src, start)
        if (marker != 0x5F && marker != 0x2A) return false
        if (silent) return false
        var res = scanDelims(state, start)
        var startCount = res.delims
        if (!res.canOpen) {
            state.pos += startCount
            state.pending.append(state.src, start, state.pos)
            return true
        }
        if (state.level >= MAX_NESTING) return false
        state.pos = start + startCount
        val stack = ArrayList<Int>()
        stack.add(startCount)
        var found = false
        while (state.pos < max) {
            if (Js.cc(state.src, state.pos) == marker) {
                res = scanDelims(state, state.pos)
                val count = res.delims
                if (res.canClose) {
                    var oldCount = stack.removeAt(stack.size - 1)
                    var newCount = count
                    while (oldCount != newCount) {
                        if (newCount < oldCount) {
                            stack.add(oldCount - newCount)
                            break
                        }
                        newCount -= oldCount
                        if (stack.isEmpty()) break
                        state.pos += oldCount
                        oldCount = stack.removeAt(stack.size - 1)
                    }
                    if (stack.isEmpty()) {
                        startCount = oldCount
                        found = true
                        break
                    }
                    state.pos += count
                    continue
                }
                if (res.canOpen) stack.add(count)
                state.pos += count
                continue
            }
            state.parser.skipToken(state)
        }
        if (!found) {
            state.pos = start
            return false
        }
        state.posMax = state.pos
        state.pos = start + startCount
        if (startCount == 2 || startCount == 3) state.push(Token("strong_open", state.level++))
        if (startCount == 1 || startCount == 3) state.push(Token("em_open", state.level++))
        state.parser.tokenize(state)
        if (startCount == 1 || startCount == 3) state.push(Token("em_close", --state.level))
        if (startCount == 2 || startCount == 3) state.push(Token("strong_close", --state.level))
        state.pos = state.posMax + startCount
        state.posMax = max
        return true
    }

    /** `/(^|[^\\])(\\\\)*\s/`: an unescaped white space character. */
    private fun hasUnescapedSpace(content: String): Boolean {
        var backslashes = 0
        for (ch in content) {
            if (Js.isSpace(ch.code) && backslashes % 2 == 0) return true
            backslashes = if (ch == '\\') backslashes + 1 else 0
        }
        return false
    }

    /** sub (`~x~`) and sup (`^x^`). */
    private fun script(state: StateInline, silent: Boolean, marker: Int, type: String): Boolean {
        val max = state.posMax
        val start = state.pos
        if (Js.cc(state.src, start) != marker) return false
        if (silent) return false
        if (start + 2 >= max) return false
        if (state.level >= MAX_NESTING) return false
        state.pos = start + 1
        var found = false
        while (state.pos < max) {
            if (Js.cc(state.src, state.pos) == marker) {
                found = true
                break
            }
            state.parser.skipToken(state)
        }
        if (!found || start + 1 == state.pos) {
            state.pos = start
            return false
        }
        val content = Js.slice(state.src, start + 1, state.pos)
        if (hasUnescapedSpace(content)) {
            state.pos = start
            return false
        }
        state.posMax = state.pos
        state.pos = start + 1
        state.push(Token(type, state.level, Utils.unescapeWithSpace(content)))
        state.pos = state.posMax + 1
        state.posMax = max
        return true
    }

    fun sub(state: StateInline, silent: Boolean) = script(state, silent, 0x7E, "sub")
    fun sup(state: StateInline, silent: Boolean) = script(state, silent, 0x5E, "sup")

    fun parseLinkDestination(state: StateInline, startPos: Int): Boolean {
        var pos = startPos
        val start = pos
        val max = state.posMax
        if (Js.cc(state.src, pos) == 0x3C) {
            pos++
            while (pos < max) {
                val code = Js.cc(state.src, pos)
                if (code == 0x0A) return false
                if (code == 0x3E) {
                    val link = Utils.normalizeLink(Utils.unescapeMd(Js.slice(state.src, start + 1, pos)))
                    if (!Utils.validateLink(link)) return false
                    state.pos = pos + 1
                    state.linkContent = link
                    return true
                }
                if (code == 0x5C && pos + 1 < max) {
                    pos += 2
                    continue
                }
                pos++
            }
            return false
        }
        var level = 0
        while (pos < max) {
            val code = Js.cc(state.src, pos)
            if (code == 0x20) break
            if (code < 0x20 || code == 0x7F) break
            if (code == 0x5C && pos + 1 < max) {
                pos += 2
                continue
            }
            if (code == 0x28) {
                level++
                if (level > 1) break
            }
            if (code == 0x29) {
                level--
                if (level < 0) break
            }
            pos++
        }
        if (start == pos) return false
        val link = Utils.unescapeMd(Js.slice(state.src, start, pos))
        if (!Utils.validateLink(link)) return false
        state.linkContent = link
        state.pos = pos
        return true
    }

    fun parseLinkTitle(state: StateInline, startPos: Int): Boolean {
        var pos = startPos
        val start = pos
        val max = state.posMax
        var marker = Js.cc(state.src, pos)
        if (marker != 0x22 && marker != 0x27 && marker != 0x28) return false
        pos++
        if (marker == 0x28) marker = 0x29
        while (pos < max) {
            val code = Js.cc(state.src, pos)
            if (code == marker) {
                state.pos = pos + 1
                state.linkContent = Utils.unescapeMd(Js.slice(state.src, start + 1, pos))
                return true
            }
            if (code == 0x5C && pos + 1 < max) {
                pos += 2
                continue
            }
            pos++
        }
        return false
    }

    private fun isSpaceOrNewline(code: Int) = code == 0x20 || code == 0x0A

    fun links(state: StateInline, silent: Boolean): Boolean {
        var isImage = false
        val oldPos = state.pos
        val max = state.posMax
        var start = state.pos
        var marker = Js.cc(state.src, start)
        if (marker == 0x21) {
            isImage = true
            marker = Js.cc(state.src, ++start)
        }
        if (marker != 0x5B) return false
        if (state.level >= MAX_NESTING) return false
        val labelStart = start + 1
        val labelEnd = state.parser.parseLinkLabel(state, start)
        if (labelEnd < 0) return false

        var pos = labelEnd + 1
        val href: String
        val title: String
        if (pos < max && Js.cc(state.src, pos) == 0x28) {
            pos++
            while (pos < max && isSpaceOrNewline(Js.cc(state.src, pos))) pos++
            if (pos >= max) return false
            if (parseLinkDestination(state, pos)) {
                href = state.linkContent
                pos = state.pos
            } else {
                href = ""
            }
            start = pos
            while (pos < max && isSpaceOrNewline(Js.cc(state.src, pos))) pos++
            if (pos < max && start != pos && parseLinkTitle(state, pos)) {
                title = state.linkContent
                pos = state.pos
                while (pos < max && isSpaceOrNewline(Js.cc(state.src, pos))) pos++
            } else {
                title = ""
            }
            if (pos >= max || Js.cc(state.src, pos) != 0x29) {
                state.pos = oldPos
                return false
            }
            pos++
        } else {
            if (state.linkLevel > 0) return false
            while (pos < max && isSpaceOrNewline(Js.cc(state.src, pos))) pos++
            var label: String? = null
            if (pos < max && Js.cc(state.src, pos) == 0x5B) {
                start = pos + 1
                pos = state.parser.parseLinkLabel(state, pos)
                if (pos >= 0) {
                    label = Js.slice(state.src, start, pos++)
                } else {
                    pos = start - 1
                }
            }
            if (label.isNullOrEmpty()) {
                if (label == null) pos = labelEnd + 1
                label = Js.slice(state.src, labelStart, labelEnd)
            }
            val ref = state.env.references[Utils.normalizeReference(label)]
            if (ref == null) {
                state.pos = oldPos
                return false
            }
            href = ref.first
            title = ref.second
        }

        if (!silent) {
            state.pos = labelStart
            state.posMax = labelEnd
            if (isImage) {
                state.push(Token("image", state.level).also {
                    it.src = href
                    it.title = title
                    it.alt = Js.substr(state.src, labelStart, labelEnd - labelStart)
                })
            } else {
                state.push(Token("link_open", state.level++).also {
                    it.href = href
                    it.title = title
                })
                state.linkLevel++
                state.parser.tokenize(state)
                state.linkLevel--
                state.push(Token("link_close", --state.level))
            }
        }
        state.pos = pos
        state.posMax = max
        return true
    }

    fun footnoteInline(state: StateInline, silent: Boolean): Boolean {
        val max = state.posMax
        val start = state.pos
        if (start + 2 >= max) return false
        if (Js.cc(state.src, start) != 0x5E) return false
        if (Js.cc(state.src, start + 1) != 0x5B) return false
        if (state.level >= MAX_NESTING) return false
        val labelStart = start + 2
        val labelEnd = state.parser.parseLinkLabel(state, start + 1)
        if (labelEnd < 0) return false
        if (!silent) {
            state.env.footnotesStarted = true
            val list = state.env.footnoteList ?: ArrayList<Footnote?>().also { state.env.footnoteList = it }
            val footnoteId = list.size
            state.pos = labelStart
            state.posMax = labelEnd
            state.push(Token("footnote_ref", state.level).also { it.id = footnoteId })
            state.linkLevel++
            val oldLength = state.tokens.size
            state.parser.tokenize(state)
            val sub = state.tokens.subList(oldLength, state.tokens.size)
            val taken = ArrayList(sub)
            sub.clear()
            // `list[footnoteId] = ...` may land past the end if nested notes were added meanwhile
            while (list.size <= footnoteId) list.add(null)
            list[footnoteId] = Footnote(label = null, count = null, tokens = taken)
            state.linkLevel--
        }
        state.pos = labelEnd + 1
        state.posMax = max
        return true
    }

    fun footnoteRef(state: StateInline, silent: Boolean): Boolean {
        val max = state.posMax
        val start = state.pos
        if (start + 3 > max) return false
        if (!state.env.footnotesStarted || state.env.footnoteRefs == null) return false
        if (Js.cc(state.src, start) != 0x5B) return false
        if (Js.cc(state.src, start + 1) != 0x5E) return false
        if (state.level >= MAX_NESTING) return false
        var pos = start + 2
        while (pos < max) {
            val c = Js.cc(state.src, pos)
            if (c == 0x20) return false
            if (c == 0x0A) return false
            if (c == 0x5D) break
            pos++
        }
        if (pos == start + 2) return false
        if (pos >= max) return false
        pos++
        val label = Js.slice(state.src, start + 2, pos - 1)
        val refs = state.env.footnoteRefs!!
        val refValue = refs[label] ?: return false
        if (!silent) {
            val list = state.env.footnoteList ?: ArrayList<Footnote?>().also { state.env.footnoteList = it }
            val footnoteId: Int
            if (refValue < 0) {
                footnoteId = list.size
                list.add(Footnote(label = label, count = 0))
                refs[label] = footnoteId
            } else {
                footnoteId = refValue
            }
            val note = list[footnoteId]!!
            val footnoteSubId = note.count ?: 0 // undefined renders like 0
            note.count = note.count?.plus(1)
            state.push(Token("footnote_ref", state.level).also {
                it.id = footnoteId
                it.subId = footnoteSubId
            })
        }
        state.pos = pos
        state.posMax = max
        return true
    }

    private val URL_SCHEMAS = setOf(
        "coap", "doi", "javascript", "aaa", "aaas", "about", "acap", "cap", "cid", "crid", "data", "dav", "dict",
        "dns", "file", "ftp", "geo", "go", "gopher", "h323", "http", "https", "iax", "icap", "im", "imap", "info",
        "ipp", "iris", "iris.beep", "iris.xpc", "iris.xpcs", "iris.lwz", "ldap", "mailto", "mid", "msrp", "msrps",
        "mtqp", "mupdate", "news", "nfs", "ni", "nih", "nntp", "opaquelocktoken", "pop", "pres", "rtsp", "service",
        "session", "shttp", "sieve", "sip", "sips", "sms", "snmp", "soap.beep", "soap.beeps", "tag", "tel", "telnet",
        "tftp", "thismessage", "tn3270", "tip", "tv", "urn", "vemmi", "ws", "wss", "xcon", "xcon-userid",
        "xmlrpc.beep", "xmlrpc.beeps", "xmpp", "z39.50r", "z39.50s", "adiumxtra", "afp", "afs", "aim", "apt",
        "attachment", "aw", "beshare", "bitcoin", "bolo", "callto", "chrome", "chrome-extension",
        "com-eventbrite-attendee", "content", "cvs", "dlna-playsingle", "dlna-playcontainer", "dtn", "dvb", "ed2k",
        "facetime", "feed", "finger", "fish", "gg", "git", "gizmoproject", "gtalk", "hcp", "icon", "ipn", "irc",
        "irc6", "ircs", "itms", "jar", "jms", "keyparc", "lastfm", "ldaps", "magnet", "maps", "market", "message",
        "mms", "ms-help", "msnim", "mumble", "mvn", "notes", "oid", "palm", "paparazzi", "platform", "proxy", "psyc",
        "query", "res", "resource", "rmi", "rsync", "rtmp", "secondlife", "sftp", "sgn", "skype", "smb", "soldat",
        "spotify", "ssh", "steam", "svn", "teamspeak", "things", "udp", "unreal", "ut2004", "ventrilo",
        "view-source", "webcal", "wtai", "wyciwyg", "xfire", "xri", "ymsgr",
    )

    private fun isAsciiAlnum(c: Int) = isAlphaNum(c)

    /** AUTOLINK_RE `^<([a-zA-Z.\-]{1,25}):([^<>\x00-\x20]*)>` at [pos]; returns the match end. */
    private fun matchAutolink(src: String, pos: Int): Int {
        var i = pos + 1
        while (i < src.length && (Js.cc(src, i).let { (it in 0x41..0x5A) || (it in 0x61..0x7A) || it == 0x2E || it == 0x2D })) i++
        val n = i - pos - 1
        if (n < 1 || n > 25 || Js.cc(src, i) != 0x3A) return -1
        i++
        while (i < src.length) {
            val c = src[i].code
            if (c == 0x3C || c == 0x3E || c <= 0x20) break
            i++
        }
        return if (Js.cc(src, i) == 0x3E) i + 1 else -1
    }

    private const val EMAIL_LOCAL_EXTRA = ".!#$%&'*+/=?^_`{|}~-"

    /** EMAIL_RE at [pos]; returns the match end. */
    private fun matchEmail(src: String, pos: Int): Int {
        var i = pos + 1
        while (i < src.length && (isAsciiAlnum(src[i].code) || EMAIL_LOCAL_EXTRA.indexOf(src[i]) >= 0)) i++
        if (i == pos + 1 || Js.cc(src, i) != 0x40) return -1
        i++
        val domainStart = i
        while (i < src.length && (isAsciiAlnum(src[i].code) || src[i] == '.' || src[i] == '-')) i++
        if (Js.cc(src, i) != 0x3E) return -1
        val labels = src.substring(domainStart, i).split('.')
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return -1
            if (!isAsciiAlnum(label[0].code) || !isAsciiAlnum(label[label.length - 1].code)) return -1
        }
        return i + 1
    }

    fun autolink(state: StateInline, silent: Boolean): Boolean {
        val pos = state.pos
        if (Js.cc(state.src, pos) != 0x3C) return false
        if (pos > state.lastGt) return false // tail.indexOf('>') < 0

        val linkEnd = matchAutolink(state.src, pos)
        if (linkEnd >= 0) {
            val scheme = state.src.substring(pos + 1, state.src.indexOf(':', pos))
            if (scheme.lowercase() !in URL_SCHEMAS) return false
            val url = state.src.substring(pos + 1, linkEnd - 1)
            val fullUrl = Utils.normalizeLink(url)
            if (!Utils.validateLink(url)) return false
            if (!silent) {
                state.push(Token("link_open", state.level).also { it.href = fullUrl })
                state.push(Token("text", state.level + 1, url))
                state.push(Token("link_close", state.level))
            }
            state.pos += linkEnd - pos
            return true
        }

        val emailEnd = matchEmail(state.src, pos)
        if (emailEnd >= 0) {
            val url = state.src.substring(pos + 1, emailEnd - 1)
            val fullUrl = Utils.normalizeLink("mailto:$url")
            if (!Utils.validateLink(fullUrl)) return false
            if (!silent) {
                state.push(Token("link_open", state.level).also { it.href = fullUrl })
                state.push(Token("text", state.level + 1, url))
                state.push(Token("link_close", state.level))
            }
            state.pos += emailEnd - pos
            return true
        }
        return false
    }

    private fun isLetter(ch: Int): Boolean {
        val lc = ch or 0x20
        return lc in 0x61..0x7A
    }

    fun htmltag(state: StateInline, silent: Boolean): Boolean {
        val pos = state.pos
        val max = state.posMax
        if (Js.cc(state.src, pos) != 0x3C || pos + 2 >= max) return false
        val ch = Js.cc(state.src, pos + 1)
        if (ch != 0x21 && ch != 0x3F && ch != 0x2F && !isLetter(ch)) return false
        val end = HtmlTag.match(state.src, pos)
        if (end < 0) return false
        if (!silent) state.push(Token("htmltag", state.level, state.src.substring(pos, end)))
        state.pos = end
        return true
    }

    private fun isHex(c: Int) = (c in 0x30..0x39) || (c in 0x41..0x46) || (c in 0x61..0x66)

    fun entity(state: StateInline, silent: Boolean): Boolean {
        val pos = state.pos
        val max = state.posMax
        val src = state.src
        if (Js.cc(src, pos) != 0x26) return false
        if (pos + 1 < max) {
            val ch = Js.cc(src, pos + 1)
            if (ch == 0x23) {
                // DIGITAL_RE /^&#((?:x[a-f0-9]{1,8}|[0-9]{1,8}));/i
                var matchEnd = -1
                var code = 0L
                val c2 = Js.cc(src, pos + 2)
                if (c2 == 0x78 || c2 == 0x58) {
                    var i = pos + 3
                    while (i < src.length && isHex(src[i].code)) i++
                    val n = i - pos - 3
                    if (n in 1..8 && Js.cc(src, i) == 0x3B) {
                        matchEnd = i + 1
                        code = src.substring(pos + 3, i).toLong(16)
                    }
                } else {
                    var i = pos + 2
                    while (i < src.length && src[i] in '0'..'9') i++
                    val n = i - pos - 2
                    if (n in 1..8 && Js.cc(src, i) == 0x3B) {
                        matchEnd = i + 1
                        code = src.substring(pos + 2, i).toLong()
                    }
                }
                if (matchEnd >= 0) {
                    if (!silent) {
                        state.pending.append(if (Utils.isValidEntityCode(code)) Js.fromCodePoint(code.toInt()) else Js.fromCodePoint(0xFFFD))
                    }
                    state.pos = matchEnd
                    return true
                }
            } else {
                // NAMED_RE /^&([a-z][a-z0-9]{1,31});/i
                if ((ch in 0x41..0x5A) || (ch in 0x61..0x7A)) {
                    var i = pos + 2
                    while (i < src.length && isAsciiAlnum(src[i].code)) i++
                    val n = i - pos - 2
                    if (n in 1..31 && Js.cc(src, i) == 0x3B) {
                        val name = src.substring(pos + 1, i)
                        val decoded = Utils.decodeEntity(name)
                        if (name != decoded) {
                            if (!silent) state.pending.append(decoded)
                            state.pos = i + 1
                            return true
                        }
                    }
                }
            }
        }
        if (!silent) state.pending.append('&')
        state.pos++
        return true
    }
}
