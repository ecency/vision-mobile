package com.ecency.core.render.url

/**
 * WHATWG URL parsing and serialization for absolute URLs (no base), matching the `URL` class that
 * render-helper relies on in browsers and Node. Only what post rendering needs: validity, the
 * component getters, `searchParams.append` and `href`.
 *
 * Golden-tested against Node's URL on the Web Platform Tests URL suite plus IDNA validity cases.
 * Hostnames go through [Idna], a port of the IDNA code inside that Node, with its tables.
 */
class WebUrl private constructor(
    val scheme: String,
    private var username: String,
    private var password: String,
    private val hostValue: String?,
    val port: Int?,
    private val pathSegments: MutableList<String>?,
    private val opaquePath: String?,
    private var query: String?,
    private val fragment: String?,
) {
    val protocol: String get() = "$scheme:"
    val hostname: String get() = hostValue ?: ""
    val host: String get() = if (hostValue == null) "" else if (port == null) hostValue else "$hostValue:$port"
    val pathname: String get() = opaquePath ?: pathSegments!!.joinToString("") { "/$it" }
    val search: String get() = if (query.isNullOrEmpty()) "" else "?$query"
    val hash: String get() = if (fragment.isNullOrEmpty()) "" else "#$fragment"
    val origin: String get() = if (scheme in SPECIAL_PORTS && scheme != "file") "$scheme://$host" else "null"

    /** Same as `new URLSearchParams(url.search)` iteration. */
    val searchParams: List<Pair<String, String>> get() = FormUrlEncoded.parse(query ?: "")

    /** `url.searchParams.append(name, value)`: re-serializes the whole query as form data, like the browser. */
    fun appendSearchParam(name: String, value: String) {
        val params = searchParams + (name to value)
        query = FormUrlEncoded.serialize(params).ifEmpty { null }
    }

    /**
     * `url.searchParams.set(name, value)`: the first pair named [name] takes [value], later ones are removed,
     * or the pair is appended; the query is re-serialized as form data.
     */
    fun setSearchParam(name: String, value: String) {
        val params = searchParams
        val index = params.indexOfFirst { it.first == name }
        val next = if (index < 0) params + (name to value)
        else params.filterIndexed { i, p -> i == index || p.first != name }.map { if (it.first == name) name to value else it }
        query = FormUrlEncoded.serialize(next).ifEmpty { null }
    }

    val href: String
        get() = buildString {
            append(scheme).append(':')
            if (hostValue != null) {
                append("//")
                if (username.isNotEmpty() || password.isNotEmpty()) {
                    append(username)
                    if (password.isNotEmpty()) append(':').append(password)
                    append('@')
                }
                append(hostValue)
                if (port != null) append(':').append(port)
            } else if (opaquePath == null && pathSegments!!.size > 1 && pathSegments[0].isEmpty()) {
                append("/.")
            }
            append(pathname)
            if (query != null) append('?').append(query)
            if (fragment != null) append('#').append(fragment)
        }

    override fun toString(): String = href

    companion object {
        /** `new URL(input)`; null where the browser throws. */
        fun parse(input: String): WebUrl? = Parser(input).run()

        fun canParse(input: String): Boolean = parse(input) != null

        internal val SPECIAL_PORTS = mapOf("ftp" to 21, "file" to null, "http" to 80, "https" to 443, "ws" to 80, "wss" to 443)
    }

    private class Parser(raw: String) {
        private val input: String = stripControls(raw)

        fun run(): WebUrl? {
            // Scheme: ASCII alpha followed by alphanumerics, + - .; anything else needs a base we never have.
            val colon = input.indexOf(':')
            if (colon <= 0 || !input[0].isAsciiAlpha()) return null
            for (i in 1 until colon) {
                val c = input[i]
                if (!(c.isAsciiAlphanumeric() || c == '+' || c == '-' || c == '.')) return null
            }
            val scheme = input.substring(0, colon).lowercase()
            val rest = input.substring(colon + 1)
            val special = scheme in SPECIAL_PORTS
            return when {
                scheme == "file" -> parseFile(rest)
                special -> parseSpecialAuthority(scheme, rest)
                rest.startsWith("//") -> parseAuthority(scheme, rest.substring(2), special = false)
                rest.startsWith("/") -> parsePathQueryFragment(scheme, "", "", null, null, rest, special = false)
                else -> parseOpaque(scheme, rest)
            }
        }

        private fun parseSpecialAuthority(scheme: String, rest: String): WebUrl? {
            // Special schemes ignore any run of slashes and backslashes before the authority.
            var i = 0
            while (i < rest.length && (rest[i] == '/' || rest[i] == '\\')) i++
            return parseAuthority(scheme, rest.substring(i), special = true)
        }

        private fun parseAuthority(scheme: String, rest: String, special: Boolean): WebUrl? {
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || (special && it == '\\') }
                .let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, end)
            val at = authority.lastIndexOf('@')
            var username = ""
            var password = ""
            val hostPort: String
            if (at >= 0) {
                val userinfo = authority.substring(0, at)
                val sep = userinfo.indexOf(':')
                username = PercentEncode.encode(if (sep < 0) userinfo else userinfo.substring(0, sep), PercentEncode.USERINFO)
                password = if (sep < 0) "" else PercentEncode.encode(userinfo.substring(sep + 1), PercentEncode.USERINFO)
                hostPort = authority.substring(at + 1)
            } else {
                hostPort = authority
            }
            // Port separator is the last ':' outside IPv6 brackets.
            var bracket = false
            var portSep = -1
            for (i in hostPort.indices) {
                when (hostPort[i]) {
                    '[' -> bracket = true
                    ']' -> bracket = false
                    ':' -> if (!bracket) { portSep = i; break }
                }
            }
            val rawHost = if (portSep < 0) hostPort else hostPort.substring(0, portSep)
            if (rawHost.isEmpty()) {
                if (special) return null
                if (at >= 0 || portSep >= 0) return null
            }
            val host = if (rawHost.isEmpty()) "" else Host.parse(rawHost, special) ?: return null
            var port: Int? = null
            if (portSep >= 0) {
                val digits = hostPort.substring(portSep + 1)
                if (digits.isNotEmpty()) {
                    if (!digits.all { it in '0'..'9' }) return null
                    val value = digits.trimStart('0').ifEmpty { "0" }
                    if (value.length > 5 || value.toInt() > 65535) return null
                    port = value.toInt().takeIf { it != SPECIAL_PORTS[scheme] }
                }
            }
            return parsePathQueryFragment(scheme, username, password, host, port, rest.substring(end), special)
        }

        private fun parseFile(rest: String): WebUrl? {
            var r = rest
            if (r.length >= 2 && (r[0] == '/' || r[0] == '\\') && (r[1] == '/' || r[1] == '\\')) {
                r = r.substring(2)
                val end = r.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }.let { if (it < 0) r.length else it }
                val rawHost = r.substring(0, end)
                if (isWindowsDriveLetter(rawHost, normalizedOnly = false)) {
                    return parsePathQueryFragment("file", "", "", "", null, r, special = true)
                }
                var host = if (rawHost.isEmpty()) "" else Host.parse(rawHost, special = true) ?: return null
                if (host == "localhost") host = ""
                return parsePathQueryFragment("file", "", "", host, null, r.substring(end), special = true)
            }
            return parsePathQueryFragment("file", "", "", "", null, r, special = true)
        }

        private fun parseOpaque(scheme: String, rest: String): WebUrl {
            val (beforeFragment, fragment) = splitOnce(rest, '#')
            val (path, query) = splitOnce(beforeFragment, '?')
            return WebUrl(
                scheme, "", "", null, null, null,
                PercentEncode.encode(path, PercentEncode.C0_CONTROL),
                query?.let { PercentEncode.encode(it, PercentEncode.QUERY) },
                fragment?.let { PercentEncode.encode(it, PercentEncode.FRAGMENT) },
            )
        }

        private fun parsePathQueryFragment(
            scheme: String,
            username: String,
            password: String,
            host: String?,
            port: Int?,
            rest: String,
            special: Boolean,
        ): WebUrl {
            val (beforeFragment, fragment) = splitOnce(rest, '#')
            val (rawPath, query) = splitOnce(beforeFragment, '?')
            val segments = mutableListOf<String>()
            val path = if (special) rawPath.replace('\\', '/') else rawPath
            if (path.isNotEmpty() || special) {
                val parts = (if (path.startsWith("/")) path.substring(1) else path).split('/')
                for ((index, part) in parts.withIndex()) {
                    val last = index == parts.lastIndex
                    when {
                        isDoubleDot(part) -> {
                            if (segments.isNotEmpty() && !(scheme == "file" && segments.size == 1 && isWindowsDriveLetter(segments[0], normalizedOnly = true))) {
                                segments.removeAt(segments.lastIndex)
                            }
                            if (last) segments.add("")
                        }
                        isSingleDot(part) -> if (last) segments.add("")
                        else -> {
                            var seg = PercentEncode.encode(part, PercentEncode.PATH)
                            if (scheme == "file" && segments.isEmpty() && isWindowsDriveLetter(seg, normalizedOnly = false)) {
                                seg = seg.substring(0, 1) + ":"
                            }
                            segments.add(seg)
                        }
                    }
                }
            }
            val encodedQuery = query?.let { PercentEncode.encode(it, if (special) PercentEncode.SPECIAL_QUERY else PercentEncode.QUERY) }
            return WebUrl(
                scheme, username, password, host, port, segments, null, encodedQuery,
                fragment?.let { PercentEncode.encode(it, PercentEncode.FRAGMENT) },
            )
        }

        private fun isSingleDot(s: String) = s == "." || s.equals("%2e", ignoreCase = true)

        private fun isDoubleDot(s: String) = when (s.lowercase()) {
            "..", ".%2e", "%2e.", "%2e%2e" -> true
            else -> false
        }

        private fun isWindowsDriveLetter(s: String, normalizedOnly: Boolean) =
            s.length == 2 && s[0].isAsciiAlpha() && (s[1] == ':' || (!normalizedOnly && s[1] == '|'))

        private fun splitOnce(s: String, c: Char): Pair<String, String?> {
            val i = s.indexOf(c)
            return if (i < 0) s to null else s.substring(0, i) to s.substring(i + 1)
        }

        companion object {
            /** Leading and trailing C0 controls and spaces are trimmed; tabs and newlines vanish everywhere. */
            fun stripControls(s: String): String {
                var start = 0
                var end = s.length
                while (start < end && s[start] <= ' ') start++
                while (end > start && s[end - 1] <= ' ') end--
                val trimmed = s.substring(start, end)
                return if (trimmed.any { it == '\t' || it == '\n' || it == '\r' }) {
                    trimmed.filterNot { it == '\t' || it == '\n' || it == '\r' }
                } else {
                    trimmed
                }
            }
        }
    }
}

internal fun Char.isAsciiAlpha() = this in 'a'..'z' || this in 'A'..'Z'

internal fun Char.isAsciiAlphanumeric() = isAsciiAlpha() || this in '0'..'9'
