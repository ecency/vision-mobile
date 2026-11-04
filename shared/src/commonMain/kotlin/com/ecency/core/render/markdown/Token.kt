package com.ecency.core.render.markdown

/** remarkable's token: one loose bag of fields, most of which only some types use. */
internal class Token(
    val type: String,
    var level: Int = 0,
    var content: String = "",
) {
    var children: MutableList<Token> = ArrayList(0)
    var tight = false
    var block = false
    var hLevel = 0
    var order = ""          // ordered_list_open: the start number as written (digits)
    var params = ""         // fence
    var href = ""
    var title: String? = null
    var src = ""
    var alt = ""
    var align = ""
    var id = 0
    var subId = 0
    var label = ""

    companion object {
        fun inline(content: String, level: Int) = Token("inline", level, content)
    }
}

/** A footnote list entry. [count] is null where JS would hold undefined/NaN (inline notes). */
internal class Footnote(val label: String?, var count: Int?, val tokens: MutableList<Token>? = null)

/** The parse environment (`env`) shared by all rules for one render. */
internal class Env {
    val references = HashMap<String, Pair<String, String>>() // label -> (href, title)
    var abbreviations: LinkedHashMap<String, String>? = null  // label -> title, in definition order
    var abbrMatcher: AbbrMatcher? = null
    var footnotesStarted = false
    var footnoteRefs: HashMap<String, Int>? = null
    var footnoteList: MutableList<Footnote?>? = null
}
