// Golden fixtures for the native DOM layer (xmldom parse + serialize, the htmlparser2 repair
// path) and the sanitizer, produced by the exact JS that @ecency/render-helper 2.5.42 runs.
// All inputs are synthetic. JS never runs in the app.
//
//   cd native/tools/render && npm ci
//   node generate-dom-fixtures.mjs ../../shared/src/commonTest/kotlin/com/ecency/core/render/dom
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createRequire } from 'node:module'

const here = dirname(fileURLToPath(import.meta.url))
const require = createRequire(import.meta.url)
const outDir = process.argv[2]
if (!outDir) throw new Error('usage: generate-dom-fixtures.mjs <output dir>')

// render-helper does not export its internals; expose them from a copy of its own bundle.
globalThis.window = globalThis
const bundlePath = join(here, 'node_modules/@ecency/render-helper/dist/node/index.mjs')
const internalsPath = join(here, 'node_modules/.rh-internals.mjs')
writeFileSync(internalsPath, readFileSync(bundlePath, 'utf8') +
  '\nexport { sanitizeHtml as __sanitizeHtml, removeDuplicateAttributes as __rda, DOMParser as __DOMParser,' +
  ' decodeEntities as __decodeEntities, fixBlockLevelTagsInParagraphs as __fixBlock, setProxyBase as __setProxyBase };\n')
const rh = await import(pathToFileURL(internalsPath).href)
const { XMLSerializer } = require('@xmldom/xmldom')
const htmlparser2 = await import('htmlparser2')
const domSerializerModule = await import('dom-serializer')
const domSerializer = domSerializerModule.default || domSerializerModule
const { Remarkable } = require('remarkable')
const { linkify } = require('remarkable/linkify')

const PROXY_BASE = 'https://images.ecency.com'
rh.__setProxyBase(PROXY_BASE)

// ---- the step under test --------------------------------------------------------------

function cleanup(output) {
  return output
    .replace(/ xmlns="http:\/\/www.w3.org\/1999\/xhtml"/g, '')
    .replace(/^<\?xml[^?]*\?>/, '')
    .replace(/^<!DOCTYPE[^>]*>/i, '')
    .replace(/<\/?html[^>]*>/g, '')
    .replace(/<head[^>]*>[\s\S]*?<\/head>/g, '')
    .replace('<body id="root">', '')
    .replace('</body>', '')
    .trim()
}

function step(html, opts) {
  const wrap = (h) => `<body id="root">${rh.__rda(h)}</body>`
  let doc = null
  let path
  let repaired = ''
  let serialized
  try {
    doc = rh.__DOMParser.parseFromString(wrap(html), 'text/html')
    path = 'xmldom'
  } catch {
    try {
      const pre = rh.__sanitizeHtml(html, opts)
      const dom = htmlparser2.parseDocument(pre, { lowerCaseTags: false, lowerCaseAttributeNames: false })
      repaired = domSerializer(dom.children)
      doc = rh.__DOMParser.parseFromString(wrap(repaired), 'text/html')
      path = 'repaired'
    } catch {
      path = 'sanitized'
      serialized = rh.__sanitizeHtml(html, opts)
    }
  }
  if (doc) serialized = new XMLSerializer().serializeToString(doc)
  return { path, repaired, serialized, final: rh.__sanitizeHtml(cleanup(serialized), opts) }
}

// ---- synthetic corpus ---------------------------------------------------------------------

const md = new Remarkable({ html: true, breaks: true, typographer: false }).use(linkify)
md.core.ruler.enable(['abbr'])
md.block.ruler.enable(['footnote', 'deflist'])
md.inline.ruler.enable(['footnote_inline', 'ins', 'mark', 'sub', 'sup'])

const markdown = {
  heading_para: '# Title\n\nFirst paragraph with **bold** and *em*.\nSecond line.',
  headings_all: '# H1\n## H2\n### H3\n#### H4\n##### H5\n###### H6',
  lists_nested: '1. one\n2. two\n   - a\n   - b\n     1. deep\n3. three',
  list_loose: '- item one\n\n- item two\n\n  continued para\n\n- item three',
  tasks: '- [ ] open task\n- [x] done task',
  table_basic: '| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |',
  table_aligned: '| Left | Center | Right |\n|:-----|:------:|------:|\n| l | c | r |\n| *x* | `y` | **z** |',
  code_fence: '```js\nconst a = 1 < 2 && b > 3;\nconsole.log("x & y")\n```',
  code_indent: '    indented code\n    with <tags> & stuff',
  code_inline: 'Use `a < b` and `c && d` inline.',
  blockquote: '> quoted line\n> second line\n>\n> another para',
  blockquote_nested: '> outer\n>> inner\n>>> innermost',
  hr: 'above\n\n---\n\nbelow\n\n***\n\n___',
  links: '[text](https://example.com/a?b=1&c=2) and <https://example.org/x> plus https://example.net/path_(x)',
  images_md: '![alt text](https://images.example.com/a.png)\n\n![](https://images.example.com/b.jpg "title")',
  emphasis_mix: '***both*** __strong__ _em_ ~~strike~~ ++ins++ ==mark== H~2~O x^2^',
  footnotes: 'Text with note[^1] and inline^[inline note].\n\n[^1]: The note body.',
  deflist: 'Term\n: Definition one\n: Definition two',
  abbr: '*[HTML]: Hyper Text Markup Language\nHTML is fine.',
  breaks: 'line one\nline two\nline three',
  html_center: '<center>\n\n![img](https://images.example.com/c.png)\n\nCentered caption\n\n</center>',
  html_div_pull: '<div class="pull-left">\n\n![l](https://images.example.com/l.png)\n\n</div>\n\nBody text next to it.',
  html_div_right: '<div class="pull-right">\nright text\n</div>',
  html_justify: '<div class="text-justify">\n\nJustified paragraph one.\n\nParagraph two.\n\n</div>',
  html_table_raw: '<table>\n<tr><td>a</td><td>b</td></tr>\n<tr><td>c</td><td>d</td></tr>\n</table>',
  html_details: '<details>\n<summary>Click</summary>\n\nHidden **content**.\n\n</details>',
  html_mixed_inline: 'Text <sub>sub</sub> and <sup>sup</sup> and <span class="x">span</span>.',
  html_iframe: '<iframe src="https://www.youtube.com/embed/abc123" width="560" height="315" frameborder="0" allowfullscreen></iframe>',
  html_unclosed_div: '<div class="phishy">\nnever closed\n\nnext para',
  html_stray_close: 'para one</p>\n\nstray closer </div> here',
  html_br_variants: 'a<br>b<br/>c<br />d<BR>e',
  html_img_inline: 'Look <img src="https://images.example.com/i.png" alt="i"> inline',
  html_comment: 'before <!-- a comment --> after\n\n<!-- block comment -->',
  html_script: '<script>alert("x")</script>\n\nafter script',
  html_style: '<style>.a{color:red}</style>\n\nstyled?',
  html_entities: 'Fish &amp; chips &lt;tag&gt; &copy; 2026 &nbsp;&nbsp; &#169; &#x1F41D; &bogus; &amp',
  html_center_table: '<center>\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n</center>',
  html_nested_center_div: '<center><div class="pull-right">\n\ntext\n\n</div></center>',
  mention_tag: 'Hi @example-user and #hive-tag in text.',
  urls_bare: 'https://example.com/one https://example.com/two?x=1&y=2#frag',
  autolink_email: 'Mail <user@example.com> now',
  rtl: '\u0645\u0631\u062D\u0628\u0627 **\u0628\u0627\u0644\u0639\u0627\u0644\u0645**\n\n\u05E9\u05DC\u05D5\u05DD *\u05E2\u05D5\u05DC\u05DD*',
  emoji: 'Bees \uD83D\uDC1D\uD83D\uDC1D and flags \uD83C\uDDFA\uD83C\uDDF8 and ZWJ \uD83D\uDC69\u200D\uD83D\uDCBB.',
  cjk: '\u4F60\u597D\uFF0C\u4E16\u754C\u3002\u65E5\u672C\u8A9E\u306E\u30C6\u30AD\u30B9\u30C8\u3002\uD55C\uAD6D\uC5B4 \uD14D\uC2A4\uD2B8.',
  heading_html: '## Title with <span>html</span> & amp',
  list_with_html: '- <b>bold</b> item\n- <i>italic</i> item\n- <a href="https://example.com">link</a>',
  quote_html: '> <center>quoted center</center>',
  table_html_cells: '| a | b |\n|---|---|\n| <b>x</b> | <br> |',
  para_html_block: 'Text\n<div>block in para</div>\nmore text',
  html_uppercase: '<CENTER><B>Upper</B></CENTER>',
  html_attrs_odd: '<div class=pull-left data-x = "1" id=\'q\'>odd attrs</div>',
  html_dup_attrs: '<img src="https://images.example.com/a.png" src="https://images.example.com/b.png" alt="a" ALT="b">',
  html_p_in_p: '<p>outer <p>inner</p> tail</p>',
  html_misnested: '<b><i>wrong</b></i> order',
  html_sub_details: '<details><summary>S</summary><sub>x</sub></details>',
  long_para: Array.from({ length: 40 }, (_, i) => `Sentence ${i} of the long paragraph.`).join(' '),
  hashes_in_code: '```\n# not a heading\n<div>not html</div>\n```',
  escape_chars: '\\*not em\\* \\# \\<b\\> 1\\. item',
  setext: 'Title\n=====\n\nSub\n-----',
  html_inline_block_mix: '<span><div>block in inline</div></span>',
  html_empty_elems: '<p></p><div></div><span></span>',
  nbsp_heavy: 'a&nbsp;b&nbsp;&nbsp;c\u00A0d',
  html_video_audio: '<video src="https://example.com/v.mp4" controls poster="https://example.com/p.jpg"></video><audio src="https://example.com/a.mp3" controls preload="auto"></audio>',
  html_picture: '<picture><source type="image/webp" srcset="https://images.ecency.com/p/abc.webp"><img src="https://images.ecency.com/p/abc.png"></picture>',
  html_blockquote_class: '<blockquote class="twitter-tweet"><p>tweet text</p></blockquote>',
  html_a_attrs: '<a href="https://example.com" target="_blank" rel="noopener" data-author="example-user" onclick="x()">a</a>',
}

const raw = {
  center_simple: '<center>Centered text</center>',
  center_img: '<center><img src="https://images.example.com/c.jpg" /></center>',
  div_pull_left: '<div class="pull-left"><img src="https://images.example.com/l.jpg"></div>',
  div_pull_right: '<div class="pull-right">Right</div>',
  div_justify: '<div class="text-justify"><p>Justified</p></div>',
  div_phishy: '<div class="phishy">Phishy link</div>',
  table_no_tbody: '<table><tr><th>H</th></tr><tr><td>D</td></tr></table>',
  table_thead_tbody: '<table><thead><tr><th>a</th></tr></thead><tbody><tr><td>1</td></tr></tbody></table>',
  table_unclosed_td: '<table><tr><td>a<td>b</tr></table>',
  details_summary: '<details><summary>More</summary><p>Hidden</p></details>',
  sub_sup: '<p>H<sub>2</sub>O and E=mc<sup>2</sup></p>',
  span_class: '<span class="wr">x</span><span class="ok" id="good-id">y</span><span id="1bad">z</span>',
  iframe_youtube: '<iframe width="560" height="315" src="https://www.youtube.com/embed/abc" frameborder="0" allow="autoplay" allowfullscreen></iframe>',
  iframe_vimeo: '<iframe src="https://player.vimeo.com/video/123" webkitallowfullscreen mozallowfullscreen allowfullscreen></iframe>',
  iframe_evil: '<iframe src="https://evil.example.com/x" sandbox="allow-scripts"></iframe>',
  iframe_selfclose: '<iframe src="https://www.youtube.com/embed/x" />',
  iframe_text: '<iframe src="https://www.youtube.com/embed/x">fallback &lt;b&gt; text</iframe>',
  iframe_dup: '<iframe allowfullscreen allowfullscreen src="https://www.youtube.com/embed/y"></iframe>',
  img_odd_attrs: '<img src="https://images.example.com/a.png" width="100" height="50" style="x" onerror="alert(1)" alt=\'single\' title=unq>',
  img_no_src: '<img alt="nothing">',
  img_js_src: '<img src="javascript:alert(1)">',
  img_data_src: '<img src="data:image/png;base64,AAAA">',
  img_srcset: '<img src="https://images.example.com/a.png" srcset="https://images.example.com/a.png 1x, https://images.example.com/b.png 2x">',
  img_srcset_bad: '<img src="https://images.example.com/a.png" srcset="https://images.example.com/a.png 1x, javascript:x 2x">',
  img_dynsrc: '<img src="https://images.example.com/a.png" dynsrc="https://x" lowsrc="https://y">',
  img_upper: '<IMG SRC="https://images.example.com/U.PNG" ALT="Up">',
  img_entity_src: '<img src="https&#58;//images.example.com/e.png">',
  unquoted_attrs: '<div class=pull-left id=box>unquoted</div>',
  duplicate_attrs: '<div class="a" class="b" CLASS="c">dup</div>',
  duplicate_attrs_case: '<a href="https://a.example.com" HREF="https://b.example.com">x</a>',
  uppercase_tags: '<DIV><P>Upper</P></DIV>',
  mixed_case_close: '<Div>mixed</DIV>',
  unclosed_p: '<p>one<p>two',
  unclosed_b: '<b>bold never closed',
  misnested: '<b><i>text</b></i>',
  stray_p_close: 'text</p>more',
  stray_div_close: '</div>leading close',
  comment_simple: '<!-- hidden -->visible',
  comment_double_dash: '<!-- a -- b -->x',
  comment_unclosed: 'x<!-- never closed',
  comment_in_p: '<p>a<!--c-->b</p>',
  script_tag: '<script>var a = "<b>";</script>after',
  script_unclosed: '<script>no end',
  style_tag: '<style>p { color: red }</style><p>x</p>',
  style_upper: '<STYLE>p{}</STYLE>y',
  event_handlers: '<div onclick="x()" onmouseover="y()">ev</div><a href="#" onClick="z()">a</a>',
  js_href: '<a href="javascript:alert(1)">j</a><a href=" JaVaScRiPt:alert(1)">k</a>',
  data_href: '<a href="data:text/html,x">d</a>',
  vbscript_href: '<a href="vbscript:msgbox">v</a>',
  mailto_tel: '<a href="mailto:a@example.com">m</a><a href="tel:123">t</a>',
  relative_links: '<a href="/@example-user">u</a><a href="#anchor">h</a><a href="./rel">r</a><a href="../up">u</a>',
  data_attrs: '<a data-href="javascript:x" data-author="example-user" data-permlink="some-post" data-tag="hive">d</a>',
  data_embed_src_ok: '<a class="markdown-video-link" data-embed-src="https://www.youtube.com/embed/abc?autoplay=1">v</a>',
  data_embed_src_bad: '<a data-embed-src="https://www.youtube.com/watch?v=abc">v</a><a data-embed-src="javascript:alert(1)">w</a>',
  data_video_href: '<a data-video-href="https://play.3speak.tv/watch?v=a/b&mode=iframe">s</a><a data-video-href="https://3speak.tv.evil.example.com/x">e</a>',
  named_entities: '&amp; &lt; &gt; &quot; &apos; &copy; &reg; &trade; &hellip; &mdash; &ndash; &euro;',
  numeric_entities: '&#65; &#x42; &#x1F600; &#128512; &#0; &#xD800; &#1114112; &#X41;',
  nbsp_entities: 'a&nbsp;b &nbsp; c&NBSP;d',
  bare_entities: '&amp &lt &copy2026 &notit; &notin; &ampfoo',
  entity_in_attr: '<a href="https://example.com/?a=1&amp;b=2&c=3" title="&quot;q&quot; &lt;x&gt;">e</a>',
  unicode_rtl: '<p dir="auto">\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645</p><p>\u05E9\u05DC\u05D5\u05DD</p>',
  unicode_emoji: '<p>\uD83D\uDC1D \uD83C\uDDE9\uD83C\uDDEA \uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67 \u2728</p>',
  lone_surrogate: 'a\ud800b\udc00c',
  control_chars: 'a\u0001b\u0008c\u000bd\u000ce',
  line_separators: 'a\u2028b\u2029c\r\nd\re\u0085f',
  lt_text: 'a < b and c > d, 1<2, x<y z',
  lt_digit: 'I <3 Hive <3',
  amp_text: 'Tom & Jerry && more & & &',
  cdata: '<![CDATA[raw <stuff>]]>after',
  cdata_lower: '<![cdata[x]]>',
  doctype: '<!DOCTYPE html><p>x</p>',
  processing_instruction: '<?php echo 1; ?>after',
  xml_pi: '<?xml version="1.0"?><p>x</p>',
  bang_other: '<!foo>bar',
  namespaced_tag: '<o:p>word junk</o:p>',
  namespaced_attr: '<div xml:lang="en">lang</div>',
  xlink_attr: '<a xlink:href="https://example.com">x</a>',
  xmlns_attr: '<div xmlns="http://example.com/ns">ns</div>',
  xmlns_prefix_attr: '<div xmlns:foo="urn:x" foo:bar="1">pfx</div>',
  xmlns_tag: '<xmlns>bad</xmlns>',
  body_in_body: '<body>inner body</body>',
  body_close_early: 'a</body>b',
  html_tag_inside: '<html><head><title>T</title></head><body>B</body></html>',
  head_tag: '<head><meta charset="utf-8"></head>after',
  header_tag: '<header>Header</header><footer>Footer</footer>',
  title_tag: '<title>a &amp; b</title>',
  textarea_tag: '<textarea>x &lt; y <b>raw</b></textarea>',
  void_closed: '<br></br><hr></hr><img src="https://images.example.com/v.png"></img>',
  void_with_slash: '<br/><hr/><br />',
  self_closed_div: '<div/>after div',
  empty_tag: '<>empty<>',
  lt_space: '< p>not a tag</ p>',
  tag_with_lt: '<a<b>x</b>',
  attr_no_value_eq: '<div class=>x</div>',
  attr_unterminated_quote: '<div class="open>x</div>',
  attr_quote_in_name: '<div a"b"=c>x</div>',
  attr_slash_end: '<a href=https://example.com/>x</a>',
  attr_no_space: '<div class="a"id="b">x</div>',
  attr_newlines: '<div\nclass="a"\n\tid="b">x</div>',
  attr_value_newline: '<div title="line1\nline2\ttab">x</div>',
  attr_lt_in_value: '<div title="a<b">x</div>',
  attr_gt_in_value: '<div title="a>b">x</div>',
  end_tag_space: '<b>x</b >y',
  end_tag_garbage: '<b>x</b foo="bar">y',
  end_tag_no_gt: '<b>x</b',
  end_tag_empty: 'a</>b',
  deep_nesting: '<div>'.repeat(30) + 'deep' + '</div>'.repeat(30),
  deep_unclosed: '<div><span><b><i>'.repeat(5) + 'x',
  table_in_p: '<p><table><tr><td>x</td></tr></table></p>',
  list_raw: '<ul><li>a<li>b</ul><ol><li>c</li></ol>',
  li_unclosed_nested: '<ul><li>one<ul><li>two</ul></ul>',
  blockquote_raw: '<blockquote><p>q</p></blockquote>',
  pre_code_raw: '<pre><code>x &lt; y\n  indented</code></pre>',
  hr_raw: 'a<hr>b',
  h_tags_attrs: '<h2 id="sec-1" dir="rtl" data-align="center" class="no">H</h2>',
  p_attrs: '<p dir="auto" id="p1" data-align="left" style="color:red">P</p>',
  font_tag: '<font color="red">old</font>',
  marquee: '<marquee>scroll</marquee>',
  form_elems: '<form><input type="text" value="v"><button>b</button><select><option>o</select></form>',
  object_embed: '<object data="x"><embed src="y"></object>',
  svg_inline: '<svg><circle r="1"/></svg>after',
  math_inline: '<math><mi>x</mi></math>',
  source_bad: '<source srcset="https://evil.example.com/a.webp" type="image/webp">',
  source_no_type: '<picture><source srcset="https://images.ecency.com/p/x.webp"><img src="https://images.ecency.com/p/x.png"></picture>',
  source_good: '<picture><source srcset="https://images.ecency.com/p/x 1x" type="image/avif"><img src="https://images.ecency.com/p/x.png"></picture>',
  audio_preload: '<audio src="https://example.com/a.mp3" preload="metadata"></audio><audio src="https://example.com/b.mp3" preload="auto"></audio>',
  video_bad_src: '<video src="javascript:x" poster="data:x"></video>',
  span_data_align: '<span data-align="right">r</span><div data-align="center">c</div>',
  a_id_attr: '<a id="ok-id">x</a><a id="9bad">y</a><a id="bad id">z</a>',
  a_title_entities: '<a title="&#106;avascript">t</a>',
  ins_del_strike: '<ins>i</ins><del>d</del><strike>s</strike><s>s2</s>',
  nested_quotes_attr: '<a title=\'He said "hi"\'>q</a>',
  empty_input: '',
  whitespace_only: '   \n\t ',
  text_only: 'just text',
  long_word: 'x'.repeat(5000),
  long_text: Array.from({ length: 300 }, (_, i) => `word${i}`).join(' '),
  many_paragraphs: Array.from({ length: 120 }, (_, i) => `<p>Paragraph ${i} &amp; text</p>`).join('\n'),
  many_entities: '&amp;&lt;&gt;&nbsp;&copy;'.repeat(200),
  many_lt: '<'.repeat(300) + 'x',
  many_amp: '&'.repeat(300),
  many_quotes_tag: '<div ' + '"'.repeat(200) + '>',
  long_attr: `<div title="${'v'.repeat(4000)}">x</div>`,
  json_like: '{"a": "<b>", "c": [1, 2]}',
  markdown_leftover: '**not rendered** _x_ [l](u)',
  iframe_text_broken: '<iframe src="https://www.youtube.com/embed/x">&lt;b&gt;</iframe><p>',
  iframe_text_broken_2: '<div><iframe src="https://player.vimeo.com/video/1">a &lt;/iframe&gt; b</iframe>',
  repair_then_fail: '<b>x<iframe src="https://www.youtube.com/embed/x">&lt;i&gt;</iframe>',
  // Iframe host allow list and sandbox on each parse path (security deviation in the port:
  // the native sanitizer blanks non-allow-listed iframe src and drops author sandbox tokens).
  iframe_attack_xmldom: '<iframe src="https://evil.example.com/x" sandbox="allow-scripts allow-same-origin allow-top-navigation allow-forms"></iframe>',
  iframe_attack_repaired: '<p><iframe src="https://evil.example.com/x" sandbox="allow-scripts allow-top-navigation"></iframe></b>',
  iframe_attack_sanitized: '<iframe src="https://evil.example.com/x" sandbox="allow-scripts allow-same-origin allow-top-navigation allow-forms">&lt;b&gt;</iframe><p>',
  iframe_attack_protocol_relative: '<iframe src="//evil.example.com/x"></iframe>',
  iframe_attack_lookalike: '<iframe src="https://www.youtube.com.evil.example.com/embed/x"></iframe>',
  iframe_attack_entity_scheme: '<iframe src="javascript&colon;alert(1)"></iframe>',
  iframe_allowed_protocol_relative: '<iframe src="//www.youtube.com/embed/x" sandbox="allow-scripts allow-same-origin allow-popups"></iframe>',
  iframe_allowed_http: '<iframe src="http://player.twitch.tv/?channel=x&amp;parent=ecency.com" frameborder="0"></iframe>',
  iframe_allowed_author_sandbox: '<iframe src="https://www.youtube.com/embed/x" sandbox="allow-scripts allow-top-navigation"></iframe>',
  zero_width: 'a\u200Bb\u200Cc\u200Dd\uFEFFe',
  fullwidth_tag: '\uFF1Cdiv\uFF1Ex\uFF1C/div\uFF1E',
}

const entities = [
  '&amp;', '&amp', '&AMP;', '&lt;', '&lt', '&gt', '&quot;', '&nbsp;', '&nbsp', '&NBSP;', '&copy;', '&copy',
  '&copy2026', '&notit;', '&notin;', '&not', '&ampx;', '&amp;x', '&#65;', '&#65', '&#0065;', '&#x41;', '&#X41;', '&#x0041;',
  '&#x;', '&#;', '&#', '&', '&;', '& amp;', '&#x110000;', '&#1114112;', '&#xD800;', '&#55296;', '&#0;', '&#x80;', '&#128;',
  '&#x9F;', '&#159;', '&#129;', '&#99999999999999999999;', '&#x' + 'f'.repeat(300) + ';', '&#' + '9'.repeat(320) + ';',
  '&#00000000000000000065;', '&#x000000000000000041;', '&#00;', '&#x00;', '&#x0;', '&Afr;', '&fjlig;', '&NotEqualTilde;',
  '&acE;', '&ThickSpace;', '&bne;', '&lt;&gt;&amp;', 'a&b&c', '&unknownentity;', '&eacute;&Eacute;&eacute',
  '&frac12;&frac12&frac1', '&times;&divide', '&hearts;&spades;', '&rarr;&larr;&harr;', '&zwnj;&zwj;&lrm;&rlm;',
  'text &amp; more &lt;b&gt; bold', '&#x1F41D;&#128029;', '&#x1f41d', '&#X1F41D;', '&#38;&#x26;', '&#60;script&#62;',
]

const urls = [
  'https://www.youtube.com/embed/abc', 'https://youtube.com/embed/abc?x=1', 'https://www.youtube.com/watch?v=abc',
  'https://WWW.YOUTUBE.COM/embed/abc', 'http://www.youtube.com/embed/abc', '//www.youtube.com/embed/abc',
  'https://www.youtube.com.evil.example.com/embed/abc', 'https://evil.example.com/?u=https://www.youtube.com/embed/x',
  'https://user:pass@www.youtube.com/embed/abc', 'https://www.youtube.com@evil.example.com/embed/abc',
  'https://www.youtube.com:443/embed/abc', 'https://www.youtube.com:99999/embed/abc', 'https://www.youtube.com:abc/embed/x',
  'https://www.youtube.com/embed/../watch', 'https://www.youtube.com/x/../embed/abc', 'https://www.youtube.com/%2e%2e/embed/a',
  'https://www.youtube.com/./embed/a', 'https:\\\\www.youtube.com\\embed\\abc', 'https:///www.youtube.com/embed/abc',
  'https:www.youtube.com/embed/abc', '  https://www.youtube.com/embed/abc  ', 'https://www.you\ttube.com/embed/a',
  'https://www.%79outube.com/embed/abc', 'https://www.youtube.com%2F/embed/abc', 'https://www.youtube.com./embed/abc',
  'https://\uFF57\uFF57\uFF57.youtube.com/embed/abc', 'https://www.you\u00ADtube.com/embed/abc', 'https://www.youtube\u3002com/embed/a',
  'https://player.vimeo.com/video/123', 'https://player.vimeo.com/123', 'https://player.twitch.tv/?channel=x',
  'https://player.twitch.tv/x', 'https://emb.d.tube/#!/a/b', 'https://play.3speak.tv/watch?v=a/b', 'https://play.3speak.tv/embed?v=a',
  'https://play.3speak.tv/other', 'https://3speak.tv/watch?v=a', 'https://open.spotify.com/embed/track/x',
  'https://odysee.com/$/embed/x', 'https://odysee.com/%24/embed/x', 'https://odysee.com/@a/b', 'https://www.loom.com/embed/x',
  'https://rumble.com/embed/x', 'https://www.bitchute.com/embed/x', 'https://w.soundcloud.com/player/?url=x',
  'https://archive.org/embed/x', 'https://player.eu.dapplr.in/x', 'https://dapplr.in/x', 'https://x.dapplr.in',
  'https://\u00FCber.dapplr.in/x', 'https://xn--ber-goa.dapplr.in/x', 'https://ipfs.skatehive.app/ipfs/x',
  'javascript:alert(1)', 'data:text/html,x', 'https://', 'https://@/x', 'not a url', '', 'https://[::1]/embed/x',
  'https://127.0.0.1/embed/x', 'https://www.youtube.com/embed/a b', 'https://www.youtube.com/embed/\u00E9', 'HTTPS://www.youtube.com/embed/x',
  'https://brandnewtube.com/embed/x', 'https://embed.truvvl.com/x', 'https://aureal-embed.web.app/1',
  // Host parsing edge cases: userinfo and backslash tricks, percent-encoded and full-width
  // hosts, ignored code points, ports, dot segments, and IDNA validity (Punycode labels,
  // joiners, leading combining marks, bidi labels) under the .dapplr.in suffix.
  'https://www.youtube.com\\@evil.com/embed/x', 'https://evil.com\\@www.youtube.com/embed/x',
  'https://www.youtube.com.:443/embed/x', 'https://www.youtube.com:/embed/x', 'https://WWW.YOUTUBE.COM./embed/x',
  'https://www.youtube.com%2eevil.com/embed/x', 'https://www.yout%75be.com/embed/x',
  'https://www%2Eyoutube%2Ecom/embed/x', 'https://xn--www-youtube.com/embed/x',
  'https://www.youtube.com\u0000/embed/x', 'https://www.youtube.com/embed/..%2F..%2Fredirect',
  'https://www.youtube.com/embed/../../redirect?q=x', 'https://www.youtube.com/EMBED/x',
  'https://www.youtube.com/%65mbed/x', 'https://www.youtube.com/embed\\x', 'https:/\\www.youtube.com/embed/x',
  'https:\\/www.youtube.com/embed/x', 'https://www.youtu\u0299e.com/embed/x', 'https://www.\u0443outube.com/embed/x',
  'https://\uFF57\uFF57\uFF57\uFF0E\uFF59\uFF4F\uFF55\uFF54\uFF55\uFF42\uFF45\uFF0E\uFF43\uFF4F\uFF4D/embed/x',
  'https://www.youtube.com\u3002/embed/x', 'https://s\u212Aatehype.com/x', 'https://\u{1D432}outube.com/embed/x',
  'https://www.yo\u200Butube.com/embed/x', 'https://www.yo\u200Cutube.com/embed/x',
  'https://www.yo\u200Dutube.com/embed/x', 'https://www.yo\u00DFutube.com/embed/x', 'https://evil.dapplr.in',
  'https://evil.dapplr.in./x', 'https://evil.DAPPLR.IN/x', 'https://evil.dapplr\uFF0Ein/x',
  'https://a@b@www.youtube.com/embed/x', 'https://[::ffff:1.2.3.4]/embed/x', 'https://0x7f.1/embed/x',
  'https://1.2.3.4.dapplr.in/x', 'https://x.dapplr.in.1/x', 'https://x.dapplr.in:99/x',
  'https://www.youtube.com:0443/embed/x', 'https://www.youtube.com:65536/embed/x',
  'https://www.youtube.com:000000000443/embed/x', 'https://www.youtube.com /embed/x',
  'https://www.youtube.com\u00A0/embed/x', '\u00A0https://www.youtube.com/embed/x',
  '\uFEFFhttps://www.youtube.com/embed/x', 'https://www.youtube.com/embed/x\u00A0',
  'https://www.youtube.com/%2e/embed/x', 'https://odysee.com/%24/embed/x', 'https://odysee.com/%2524/embed/x',
  'https://odysee.com/$/embed/../download/x', 'https://player.twitch.tv', 'https://player.twitch.tv/.',
  'https://player.twitch.tv/%2e', 'https://player.twitch.tv/x/..', 'https://emb.d.tube/x/..',
  'https://emb.d.tube/./', 'https://emb.d.tube//', 'https://play.3speak.tv/watchevil',
  'https://www.yo\u00ADutube.com/embed/x', 'https://www.yo\u034Futube.com/embed/x',
  'https://www.yo\u180Eutube.com/embed/x', 'https://www.yo\u061Cutube.com/embed/x',
  'https://www.yo\u2060utube.com/embed/x', 'https://www.yo\u200Eutube.com/embed/x',
  'https://www.yo\uFE0Futube.com/embed/x', 'https://www.yo\u{E0100}utube.com/embed/x',
  'https://www.yo%C2%ADutube.com/embed/x', 'https://www.yo%E2%80%8Butube.com/embed/x',
  'https://www.yo%ADutube.com/embed/x', 'https://\u24E6\u24E6\u24E6.youtube.com/embed/x',
  'https://www.youtube.com\u2024/embed/x', 'https://\u1D21\u1D21\u1D21.youtube.com/embed/x',
  'https://www.youtube.\uFF43\uFF4F\uFF4D/embed/x', 'https://www.\u0130outube.com/embed/x',
  'https://archive.org\u3002/embed/x', 'https://ARCHIVE.org/x', 'https://archive.org%00/x',
  'https://www.youtube.com\u0009/embed/x', 'https://www.you\u000Atube.com/embed/x',
  'HtTpS://www.youtube.com/embed/x', 'https://user@/embed', 'https://:@www.youtube.com/embed/x',
  'https://www.youtube.com#@evil.com/embed/x', 'https://www.youtube.com?@evil/embed/',
  'https://evil.com/\\www.youtube.com/embed/x', 'https://\u2170.dapplr.in/x',
  'https://www.youtube.com/embed/x#/../../redirect', 'https://x\u0338.dapplr.in/', 'https://a.dapplr.in\u200B/x',
  'https://xn--a.dapplr.in/', 'https://xn--.dapplr.in/', 'https://a..dapplr.in/', 'https://-a-.dapplr.in/',
  'https://a\u200C.dapplr.in/', 'https://a\u200Cb.dapplr.in/', 'https://\u05D0a.dapplr.in/',
  'https://\u05D0.dapplr.in/', 'https://ab\u0301.dapplr.in/', 'https://\u0301a.dapplr.in/', 'https://a_b.dapplr.in/',
  'https://a*b.dapplr.in/', 'https://.dapplr.in/x', 'https://\u00E9.dapplr.in/', 'https://\u0130.dapplr.in/',
  'https://\u03A3.dapplr.in/', 'https://\uFB01.dapplr.in/', 'https://a\u3000.dapplr.in/',
  'https://xn--ls8h.dapplr.in/', 'https://XN--LS8H.dapplr.in/', 'https://xn--zz-.dapplr.in/',
  'https://a.xn--.dapplr.in/', 'https://\u0661\u0662.dapplr.in/',
]

// Seeded PRNG (mulberry32) for combinations.
function prng(seed) {
  return function () {
    seed |= 0
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const TAGS = ['p', 'div', 'span', 'b', 'i', 'em', 'strong', 'center', 'a', 'img', 'br', 'hr', 'table', 'tr', 'td', 'th',
  'tbody', 'thead', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'sub', 'sup', 'details', 'summary', 'iframe', 'h1', 'h3',
  'del', 'ins', 'strike', 'u', 'font', 'script', 'style', 'video', 'audio', 'source', 'picture', 'CENTER', 'P', 'Div', 'o:p']
const ATTRS = ['class="pull-left"', 'class="pull-right"', 'class="text-justify"', 'class=phishy', 'id="x1"', 'id="1x"',
  'href="https://example.com/a"', 'href="javascript:alert(1)"', "href='/@example-user'", 'src="https://images.example.com/r.png"',
  'src=data:x', 'onclick="x()"', 'onerror=alert(1)', 'title="a &amp; b"', 'title="&quot;q&quot;"', 'alt=\'a "b"\'', 'width="100"',
  'height="20"', 'style="color:red"', 'data-href="https://example.com"', 'data-embed-src="https://www.youtube.com/embed/r"',
  'dir="auto"', 'data-align="center"', 'allowfullscreen', 'frameborder="0"', 'class', 'CLASS="up"', 'x:y="1"', 'a=b=c', '"stray"']
const TEXTS = ['hello', 'world', 'a & b', '1 < 2', 'x > y', '&amp;', '&nbsp;', '&copy;', '&#169;', '&#x1F41D;', '\u043C\u0438\u0440', '\u05E2\u05D5\u05DC\u05DD',
  '\uD83D\uDC1D', ' ', '\n', '\n\n', 'https://example.com/x', '@example-user', '#tag', 'text with "quotes"', "it's", '&bogus;', '&lt',
  'caf\u00E9', '\u00A0', 'tab\there']

function randomCase(rnd) {
  const parts = []
  const open = []
  const n = 3 + Math.floor(rnd() * 14)
  for (let k = 0; k < n; k++) {
    const r = rnd()
    if (r < 0.32) {
      const tag = TAGS[Math.floor(rnd() * TAGS.length)]
      const attrs = []
      const na = Math.floor(rnd() * 3)
      for (let a = 0; a < na; a++) attrs.push(ATTRS[Math.floor(rnd() * ATTRS.length)])
      const self = rnd() < 0.1 ? ' /' : ''
      parts.push(`<${tag}${attrs.length ? ' ' + attrs.join(' ') : ''}${self}>`)
      if (!self) open.push(tag)
    } else if (r < 0.52 && open.length) {
      // Usually close the innermost tag, sometimes another one (mis-nesting).
      const idx = rnd() < 0.8 ? open.length - 1 : Math.floor(rnd() * open.length)
      parts.push(`</${open[idx]}>`)
      open.splice(idx, 1)
    } else if (r < 0.56) {
      parts.push(['</p>', '</div>', '<!-- c -->', '<br>', '<br/>', '<hr />', '<!-- x -- y -->', '<![CDATA[z]]>', '</b>'][Math.floor(rnd() * 9)])
    } else {
      parts.push(TEXTS[Math.floor(rnd() * TEXTS.length)])
    }
  }
  // Close the rest most of the time so both parser paths get exercised.
  if (rnd() < 0.7) while (open.length) parts.push(`</${open.pop()}>`)
  return parts.join('')
}

// Character soup: short runs of HTML-significant tokens, for tokenizer edge cases.
const SOUP = ['<', '<', '>', '>', '/', '=', '"', "'", ' ', ' ', '&', ';', '#', 'x', '!', '-', '?', '\n', '\t', 'a', 'b', 'p',
  'P', 'i', 'div', 'br', 'img', 'src', 'href', 'on', 'class', 'id', 'style', 'script', 'iframe', 'amp', 'lt', 'nbsp', '1', '0',
  'F', '\u00e9', '\u00a0', '\u2028', '\ud83d', '\udc1d', 'javascript:', 'https://', ':', '[CDATA[', ']]', 'body', 'html',
  'head', 'xmlns', 'o:p', 'center', 'table', 'td', 'tr', 'li', 'ul', '\\', '`', '\u000c', '\r', 'data-href', 'title']

function randomSoup(rnd) {
  const n = 1 + Math.floor(rnd() * Number(process.env.SOUP_LEN || 40))
  let s = ''
  for (let k = 0; k < n; k++) s += SOUP[Math.floor(rnd() * SOUP.length)]
  return s
}

// ---- build fixtures -------------------------------------------------------------------------

// RANDOM_COUNT / SOUP_COUNT / SEED override the committed set for local fuzzing runs.
const RANDOM_COUNT = Number(process.env.RANDOM_COUNT || 260)
const SOUP_COUNT = Number(process.env.SOUP_COUNT || 160)
const SEED = Number(process.env.SEED || 0x5eed)

const domInputs = []
for (const [name, src] of Object.entries(markdown)) domInputs.push([`md_${name}`, rh.__fixBlock(md.render(src))])
for (const [name, html] of Object.entries(raw)) domInputs.push([`raw_${name}`, html])
entities.forEach((e, i) => domInputs.push([`entity_${i}`, `<p>${e}</p>`]))
const rnd = prng(SEED)
for (let i = 0; i < RANDOM_COUNT; i++) domInputs.push([`random_${i}`, randomCase(rnd)])
for (let i = 0; i < SOUP_COUNT; i++) domInputs.push([`soup_${i}`, randomSoup(rnd)])

const pathCount = { xmldom: 0, repaired: 0, sanitized: 0 }
// Every fifth case runs with preserveImageDimensions (render-helper's only sanitizer option).
const withDims = (i) => i % 5 === 4
const domCases = domInputs.map(([name, html], i) => {
  const opts = withDims(i) ? { preserveImageDimensions: true } : undefined
  const r = step(html, opts)
  pathCount[r.path]++
  return { name, html, dims: !!opts, ...r }
})

const sanitizeInputs = []
domInputs.forEach(([name, html], i) => sanitizeInputs.push([name, html, withDims(i)]))
const dimsCases = {
  dims_pair: '<img src="https://images.example.com/a.png" width="800" height="600">',
  dims_single: '<img src="https://images.example.com/a.png" width="800">',
  dims_percent: '<img src="https://images.example.com/a.png" width="50%" height="20">',
  dims_px: '<img src="https://images.example.com/a.png" width="10px" height="20">',
  dims_ratio: '<img src="https://images.example.com/a.png" width="8000" height="100">',
  dims_zero: '<img src="https://images.example.com/a.png" width="0" height="10">',
  dims_big: '<img src="https://images.example.com/a.png" width="9000" height="9000">',
  dims_leading_zero: '<img src="https://images.example.com/a.png" width="0800" height="0600">',
  dims_no_src: '<img width="100" height="100">',
  dims_bad_src: '<img src="javascript:x" width="100" height="100">',
  dims_entity: '<img src="https://images.example.com/a.png" width="&#49;00" height="100">',
  dims_alt_trap: '<img alt="Set width=" src="https://images.example.com/a.png" width="10" height="10">',
  dims_upper: '<IMG SRC="https://images.example.com/a.png" WIDTH="10" HEIGHT="20">',
  dims_multiple: '<p><img src="https://images.example.com/1.png" width="1" height="2"><img src="https://images.example.com/2.png" width="1"></p>',
}
for (const [name, html] of Object.entries(dimsCases)) {
  sanitizeInputs.push([name, html, false])
  sanitizeInputs.push([name + '_preserve', html, true])
}
const attrCases = [
  ['a', 'href', 'https://example.com'], ['a', 'href', ' https://example.com '], ['a', 'href', 'HTTP://EXAMPLE.COM'],
  ['a', 'href', 'javascript&colon;alert(1)'], ['a', 'href', 'java&#115;cript:x'], ['a', 'href', '&#x6A;avascript:x'],
  ['a', 'href', 'ftp://x'], ['a', 'href', 'data:image/png;base64,x'], ['a', 'href', '#'], ['a', 'href', ''],
  ['a', 'href', '\u0001https://x'], ['a', 'href', 'https://x&newline;y'], ['a', 'href', '&#0000106;avascript:x'],
  ['a', 'title', 'a "q" <b>'], ['a', 'title', "it's"], ['a', 'title', '&#xZZ;'], ['a', 'title', '&#;'], ['a', 'title', '&#x0x41;'],
  ['a', 'target', '_blank'], ['a', 'rel', 'noopener noreferrer'], ['a', 'class', 'markdown-author-link'],
  ['a', 'data-href', 'hive://sign/op'], ['a', 'data-href', 'web+hive:x'], ['a', 'data-href', 'web+:x'], ['a', 'data-href', 'relative/path'],
  ['a', 'data-href', '?q=1'], ['a', 'data-href', 'vbscript:x'], ['a', 'data-href', ' java\tscript:x'], ['a', 'data-href', 'file:///etc'],
  ['a', 'data-href', 'a.b-c_d'], ['a', 'data-href', 'a b'], ['a', 'data-href', 'mailto:x@example.com'],
  ['a', 'data-id', '123'], ['a', 'data-proposal', '7'], ['a', 'data-is-inline', 'true'], ['a', 'data-youtube', 'abc'],
  ['a', 'data-start-time', '30'], ['a', 'data-community', 'hive-1'], ['a', 'data-filter', 'trending'], ['a', 'unknown', 'x'],
  ['img', 'src', 'HTTPS://IMAGES.EXAMPLE.COM/A.PNG'], ['img', 'src', '//images.example.com/a.png'], ['img', 'src', '/local.png'],
  ['img', 'loading', 'lazy'], ['img', 'fetchpriority', 'high'], ['img', 'decoding', 'async'], ['img', 'itemprop', 'image'],
  ['img', 'sizes', '(max-width: 768px) 100vw, 700px'], ['img', 'srcset', ''], ['img', 'srcset', 'HTTPS://A 1x'],
  ['source', 'srcset', 'https://images.ecency.com/p/a 1x, https://images.ecency.com/p/b 2x'],
  ['source', 'srcset', 'https://images.ecency.com/p/a, https://images.ecency.com/q/b'], ['source', 'type', 'IMAGE/WEBP'],
  ['source', 'type', 'image/png'], ['span', 'class', ' WR '], ['span', 'class', 'wrx'], ['div', 'id', 'a-b_c9'], ['div', 'id', '-a'],
  ['div', 'id', '&#97;bc'], ['iframe', 'sandbox', 'allow-scripts allow-same-origin'], ['iframe', 'src', 'http://x'],
  ['iframe', 'src', 'https://www.youtube.com/embed/x'], ['video', 'controls', ''], ['audio', 'preload', 'NONE'],
  ['h1', 'dir', 'rtl'], ['p', 'data-align', 'justify'], ['blockquote', 'class', 'twitter-tweet'], ['td', 'colspan', '2'],
]
attrCases.forEach(([tag, attr, value], i) => {
  const q = value.includes('"') ? "'" : '"'
  sanitizeInputs.push([`attr_${i}_${tag}_${attr}`, `<${tag} ${attr}=${q}${value}${q}>x</${tag}>`, false])
})
const xssQuirks = {
  quirk_unclosed_tag_eof: 'text <a href="x"',
  quirk_lt_in_tag: '<a <b>x</b>',
  quirk_quote_no_eq: '<a title "x>y" href="https://a">z</a>',
  quirk_quote_eq_space: '<a title = "x>y">z</a>',
  quirk_single_quote: "<a title='x>y'>z</a>",
  quirk_gt_in_quote: '<img alt="a>b" src="https://images.example.com/a.png">',
  quirk_style_body: 'a<style>b{}</style>c<style>d</style>e',
  quirk_style_at_start: '<style>x</style>after',
  quirk_style_unclosed: 'a<style>never',
  quirk_style_close_only: 'a</style>b',
  quirk_comment_unclosed: 'keep<!-- drop all of this <b>x</b>',
  quirk_comment_multiple: 'a<!--1-->b<!--2-->c',
  quirk_attr_eq_only: '<a =x>y</a>',
  quirk_attr_trailing_eq: '<a href=>y</a>',
  quirk_attr_spaces: '<a   href   =   "https://example.com"   >y</a>',
  quirk_attr_newline_value: '<a title="a\nb" href="https://example.com">y</a>',
  quirk_attr_tab_sep: '<a\ttitle="t"\thref="https://example.com">y</a>',
  quirk_attr_nbsp: '<a\u00A0title="t">y</a>',
  quirk_attr_illegal_chars: '<a ti$tle="t" h(ref)="https://example.com">y</a>',
  quirk_attr_uppercase: '<a TITLE="t" Href="https://example.com">y</a>',
  quirk_self_close: '<br/><img src="https://images.example.com/a.png"/><hr />',
  quirk_closing_with_attrs: '<b>x</b class="y">',
  quirk_tag_space_name: '< b>x</ b>',
  quirk_tag_slash_name: '</>x<//>',
  quirk_tag_unicode: '<caf\u00E9>x</caf\u00E9>',
  quirk_kelvin: '<\u212Abd>x',
  quirk_many_lt: '<<<<b>>>>',
  quirk_entity_text: 'a &lt; b &amp;&amp; c > d',
  quirk_backslash_attr: '<a ti\\tle="x">y</a>',
  quirk_quote_mismatch: '<a title="x\'>y</a>',
  quirk_unquoted_then_quote: '<a title=x"y" href="https://example.com">z</a>',
  quirk_empty_tag: '<>',
  quirk_only_lt: '<',
  quirk_trailing_lt: 'abc<',
}
for (const [name, html] of Object.entries(xssQuirks)) sanitizeInputs.push([name, html, false])

const sanitizeCases = sanitizeInputs.map(([name, html, dims]) => ({
  name, html, dims, expected: rh.__sanitizeHtml(html, dims ? { preserveImageDimensions: true } : undefined),
}))

// Extra generated decode/embed inputs (EXTRA_FUZZ=n for local fuzzing runs).
const ENT_SOUP = ['&', '&', '#', 'x', 'X', '0', '1', '9', '6', 'a', 'f', 'F', 'amp', 'lt', 'not', 'in', ';', ';', 'nbsp', 'copy',
  'Afr', 'acE', 'frac1', '2', ' ', 'q', 'D8', '00', '\ud83d']
const HOSTS = ['www.youtube.com', 'youtube.com', 'player.vimeo.com', 'odysee.com', 'x.dapplr.in', 'play.3speak.tv', 'evil.example.com',
  'emb.d.tube', 'player.twitch.tv', 'www.loom.com', 'archive.org', 'WWW.YouTube.COM', 'www.%79outube.com', '\uff59outube.com']
const PATHS = ['/embed/x', '/embed/', '/video/1', '/', '', '/watch?v=a', '/$/embed/x', '/%24/embed/x', '/a/../embed/x', '/embed/../a',
  '/%2E%2e/embed/x', '\\embed\\x', '/./embed/x', '/embed/x y', '/embed/\u00e9', '?q=/embed/', '#/embed/', '/EMBED/x']
const extra = Number(process.env.EXTRA_FUZZ || 0)
const extraRnd = prng(SEED + 1)
const pick = (arr) => arr[Math.floor(extraRnd() * arr.length)]
const entityInputs = [...entities]
const urlInputs = [...urls]
for (let i = 0; i < extra; i++) {
  let e = ''
  const n = 1 + Math.floor(extraRnd() * 12)
  for (let j = 0; j < n; j++) e += pick(ENT_SOUP)
  entityInputs.push(e)
  const pre = pick(['https://', 'https:', 'https:///', 'HTTPS://', 'https:\\\\', ' https://', 'http://', 'https://u:p@', 'https://@'])
  const port = pick(['', '', '', ':443', ':0', ':65536', ':x'])
  urlInputs.push(pre + pick(HOSTS) + port + pick(PATHS) + pick(['', '', '?a=1', '#f']))
}
const decodeCases = entityInputs.map((e, i) => ({ name: `decode_${i}`, input: e, expected: rh.__decodeEntities(e) }))
const embedCases = urlInputs.map((u, i) => ({ name: `embed_${i}`, input: u, expected: rh.isAllowedEmbedSrc(u) }))
const blockFixCases = []
for (const [name, src] of Object.entries(markdown)) blockFixCases.push({ name: `md_${name}`, input: md.render(src) })
const blockRaw = [
  '<p><center></p>\n<p>x</p>\n<p></center></p>', '<p><div class="a"></p>', '<P><DIV></P>', '<p><divx></p>',
  '<p><center><br>   text</p>', '<p>text<br>\n</center></p>', '<p>a   </div></p>', '<p></p><p> \n </p><p><br> </p>',
  '<p><table></p><p></table></p>', '<p><section id="s">s</section></p>', '<p><main></p><p><nav> x</p>', '<p>\u00A0</p>',
  '<p><header>h</p>', '<p>x</CENTER></p>', '<p><br>\n<br>\n</div></p>',
]
blockRaw.forEach((h, i) => blockFixCases.push({ name: `raw_${i}`, input: h }))
for (const c of blockFixCases) c.expected = rh.__fixBlock(c.input)

// Large inputs: only a hash is stored. buildLarge must stay in sync with the Kotlin test.
function largeBlock(i) {
  return `<p>Paragraph ${i} with <strong>bold</strong>, <em>em</em> &amp; a <a href="https://example.com/${i}">link</a>` +
    ` and text &nbsp; caf\u00E9 \uD83D\uDC1D.</p>\n<div class="pull-left"><img src="https://images.example.com/${i}.png" alt="x"></div>\n` +
    `<table><tr><td>${i}</td><td>v</td></tr></table>\n`
}
function buildLarge(broken) {
  let s = ''
  let i = 0
  while (s.length < 200000) s += largeBlock(i++)
  return broken ? s + '<b>unclosed</p>' : s
}
function fnv(s) {
  let h = 0x811c9dc5
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return (h >>> 0).toString(16)
}
const large = [false, true].map((broken) => {
  const r = step(buildLarge(broken))
  return { broken, path: r.path, serializedLength: r.serialized.length, serializedHash: fnv(r.serialized), finalLength: r.final.length, finalHash: fnv(r.final) }
})

// Pathological shapes (quadratic traps in naive ports): prefix + unit * count + suffix. In a
// numbered unit, {i} is replaced by the repetition index (distinct attribute names, say).
const expand = (prefix, unit, count, suffix, numbered = false) => {
  if (!numbered) return prefix + unit.repeat(count) + suffix
  const parts = []
  for (let i = 0; i < count; i++) parts.push(unit.replaceAll('{i}', String(i)))
  return prefix + parts.join('') + suffix
}
const pathological = [
  ['open_tags', '', '<a ', 4000, ''],
  ['unclosed_quotes', '', "<a b='", 4000, ''],
  ['unclosed_script', '', '<script>x', 20000, ''],
  ['attr_space_run', '<a title=', ' ', 150000, '>x</a>'],
  ['attr_space_run_names', '<a b', ' ', 150000, 'c>x</a>'],
  ['deep_unclosed', '', '<div>', 2000, 'x'],
  ['deep_closed', '<p>', '<span>', 5000, 'x' + '</span>'.repeat(5000) + '</p>'],
  ['entities', '<p>', '&amp;&#x1F41D;&nbsp', 20000, '</p>'],
  ['long_numeric_ref', '<p>&#', '9', 100000, ';</p>'],
  ['open_comments', 'a', '<!--', 30000, ''],
  ['html_tags', '', '<html', 30000, ''],
  ['lt_space', '', '< ', 60000, ''],
  ['p_wrapped_centers', '', '<p><center></p>\n<p>t</p>\n<p></center></p>\n', 4000, ''],
  // One element with many distinct attributes: attribute insertion must not scan the list.
  ['distinct_attrs', '<a', ' a{i}="1"', 15000, '>x</a>', true],
].map(([name, prefix, unit, count, suffix, numbered = false]) => {
  const input = expand(prefix, unit, count, suffix, numbered)
  const t = Date.now()
  const r = step(input)
  const ms = Date.now() - t
  return { name, prefix, unit, count, suffix, numbered, ms, path: r.path, serializedLength: r.serialized.length,
    serializedHash: fnv(r.serialized), finalLength: r.final.length, finalHash: fnv(r.final) }
})
console.log('pathological JS ms', pathological.map((p) => `${p.name}=${p.ms}`).join(' '))

// Nesting deeper than the JS stack allows: dom-serializer overflows in the repair step (around
// 2,500 to 5,000 levels in Node) and render-helper drops to sanitize-only output. The Kotlin
// port repairs iteratively instead, a documented deviation; the JS result is kept for reference.
const deepNesting = [['deep_unclosed_6000', '', '<div>', 6000, 'x']].map(([name, prefix, unit, count, suffix]) => {
  const r = step(expand(prefix, unit, count, suffix))
  return { name, prefix, unit, count, suffix, jsPath: r.path }
})
console.log('deep nesting JS paths', deepNesting.map((d) => `${d.name}=${d.jsPath}`).join(' '))

// ---- write Kotlin -------------------------------------------------------------------------

function k(s) {
  let out = '"'
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    const ch = s[i]
    if (c >= 0x20 && c < 0x7f && ch !== '"' && ch !== '\\' && ch !== '$') out += ch
    else if (ch === '\n') out += '\\n'
    else out += '\\u' + c.toString(16).toUpperCase().padStart(4, '0')
  }
  return out + '"'
}

function parts(items, render, size = 25) {
  const fns = []
  for (let i = 0; i < items.length; i += size) {
    fns.push(`    private fun part${fns.length}() = listOf(\n${items.slice(i, i + size).map((x) => '        ' + render(x) + ',').join('\n')}\n    )`)
  }
  return { list: fns.map((_, i) => `part${i}()`).join(' + '), fns: fns.join('\n\n') }
}

mkdirSync(outDir, { recursive: true })
const header = '// GENERATED by native/tools/render/generate-dom-fixtures.mjs from @ecency/render-helper 2.5.42. Do not edit by hand.\n'

const dp = parts(domCases, (c) => `DomCase(${k(c.name)}, ${k(c.html)}, ${c.dims}, ${k(c.path)}, ${k(c.repaired)}, ${k(c.serialized)}, ${k(c.final)})`)
writeFileSync(join(outDir, 'DomFixtures.kt'), `${header}package com.ecency.core.render.dom

/** Parse + serialize golden cases: [path] is "xmldom", "repaired" or "sanitized". */
internal class DomCase(
    val name: String,
    val html: String,
    val preserveImageDimensions: Boolean,
    val path: String,
    val repaired: String,
    val serialized: String,
    val final: String,
)

internal class LargeCase(
    val broken: Boolean,
    val path: String,
    val serializedLength: Int,
    val serializedHash: String,
    val finalLength: Int,
    val finalHash: String,
)

internal class PathologicalCase(
    val name: String,
    val prefix: String,
    val unit: String,
    val count: Int,
    val suffix: String,
    val path: String,
    val serializedLength: Int,
    val serializedHash: String,
    val finalLength: Int,
    val finalHash: String,
    /** When true, every \`{i}\` in [unit] is replaced by the repetition index. */
    val numbered: Boolean = false,
) {
    fun build(times: Int = count): String {
        if (!numbered) return prefix + unit.repeat(times) + suffix
        val sb = StringBuilder(prefix)
        for (i in 0 until times) sb.append(unit.replace("{i}", i.toString()))
        return sb.append(suffix).toString()
    }
}

internal class DeepNestingCase(val name: String, val prefix: String, val unit: String, val count: Int, val suffix: String, val jsPath: String) {
    fun build(): String = prefix + unit.repeat(count) + suffix
}

internal object DomFixtures {
    const val PROXY_BASE = ${k(PROXY_BASE)}

    // Paths taken: ${JSON.stringify(pathCount)}
    val cases: List<DomCase> by lazy { ${dp.list} }

    val large = listOf(
${large.map((l) => `        LargeCase(${l.broken}, ${k(l.path)}, ${l.serializedLength}, ${k(l.serializedHash)}, ${l.finalLength}, ${k(l.finalHash)}),`).join('\n')}
    )

    val pathological = listOf(
${pathological.map((l) => `        PathologicalCase(${k(l.name)}, ${k(l.prefix)}, ${k(l.unit)}, ${l.count}, ${k(l.suffix)}, ${k(l.path)}, ${l.serializedLength}, ${k(l.serializedHash)}, ${l.finalLength}, ${k(l.finalHash)}${l.numbered ? ', numbered = true' : ''}),`).join('\n')}
    )

    val deepNesting = listOf(
${deepNesting.map((d) => `        DeepNestingCase(${k(d.name)}, ${k(d.prefix)}, ${k(d.unit)}, ${d.count}, ${k(d.suffix)}, ${k(d.jsPath)}),`).join('\n')}
    )

${dp.fns}
}
`)

const sp = parts(sanitizeCases, (c) => `SanitizeCase(${k(c.name)}, ${k(c.html)}, ${c.dims}, ${k(c.expected)})`)
const decp = parts(decodeCases, (c) => `StringCase(${k(c.name)}, ${k(c.input)}, ${k(c.expected)})`)
const bp = parts(blockFixCases, (c) => `StringCase(${k(c.name)}, ${k(c.input)}, ${k(c.expected)})`)
writeFileSync(join(outDir, 'SanitizeFixtures.kt'), `${header}package com.ecency.core.render.dom

internal class SanitizeCase(val name: String, val html: String, val preserveImageDimensions: Boolean, val expected: String)

internal class StringCase(val name: String, val input: String, val expected: String)

internal class EmbedCase(val name: String, val url: String, val allowed: Boolean)

internal object SanitizeFixtures {
    val cases: List<SanitizeCase> by lazy { ${sp.list} }

${sp.fns}
}

internal object DecodeFixtures {
    val cases: List<StringCase> by lazy { ${decp.list} }

${decp.fns}
}

internal object BlockFixFixtures {
    val cases: List<StringCase> by lazy { ${bp.list} }

${bp.fns}
}

internal object EmbedFixtures {
    val cases = listOf(
${embedCases.map((c) => `        EmbedCase(${k(c.name)}, ${k(c.input)}, ${c.expected}),`).join('\n')}
    )
}
`)
console.log('dom cases', domCases.length, pathCount, 'sanitize cases', sanitizeCases.length, 'decode', decodeCases.length,
  'embed', embedCases.length, 'blockfix', blockFixCases.length, 'large', JSON.stringify(large))
