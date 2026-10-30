// Golden fixtures for the Kotlin markdown port (com.ecency.core.render.markdown): every case is
// rendered by remarkable 2.0.1 configured exactly as @ecency/render-helper configures it. The
// Kotlin port must reproduce each output byte for byte. JS never runs in the app.
//
//   cd native/tools/render && npm ci
//   node generate-markdown-fixtures.mjs
//
// All inputs are synthetic. Never paste real user posts here; this repo is public.
import { createHash } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const require = createRequire(import.meta.url)
const outDir = join(here, '../../shared/src/commonTest/kotlin/com/ecency/core/render/markdown')

const { Remarkable } = require('remarkable')
const { linkify } = require('remarkable/linkify')

// Same configuration as render-helper's markdownToHTML in the app (lolight is not loaded there,
// so highlight returns the code unchanged).
function createMd() {
  const md = new Remarkable({ html: true, breaks: true, typographer: false, highlight: (str) => str }).use(linkify)
  md.core.ruler.enable(['abbr'])
  md.block.ruler.enable(['footnote', 'deflist'])
  md.inline.ruler.enable(['footnote_inline', 'ins', 'mark', 'sub', 'sup'])
  return md
}
const md = createMd()
const render = (src) => {
  try {
    return md.render(src)
  } catch {
    return null // the Kotlin port must throw too
  }
}

// ---------------------------------------------------------------------------------------------
// Hand-written cases, grouped by feature. Names are "group/n".

const groups = {
  paragraph: [
    'plain text', 'two\nlines', 'two\n\nparagraphs', '  leading spaces', 'trailing spaces   ', '\n\n\nblank lead',
    'a\n\n\n\nb', 'line one  \nline two', 'line one\\\nline two', 'soft\nbreak\nthree', '', ' ', '\n', 'x',
    'tab\there', '\tindented by tab', 'a\r\nb', 'a\rb', 'a b', 'a\u0085b', 'nb sp', 'a␤b',
    'mixed \t tabs\t\tand spaces', 'trailing newline\n', 'three spaces   \nbreak', '1 space \nno hard break',
  ],
  heading: [
    '# h1', '## h2', '### h3', '#### h4', '##### h5', '###### h6', '####### h7', '#nospace', '# closing ###',
    '# trailing #s and spaces ##   ', '#', '# ', '## #', '   # indented 3', '    # indented 4', 'Setext\n===',
    'Setext\n---', 'Setext two\n=  ', 'para\n# heading after', '# h\npara', '#\tTab heading', '# *emph* in **head**',
    'Title\n=====\n\nbody', 'not\n- - -', '# head #not closing', '###### six ######',
  ],
  emphasis: [
    '*em*', '_em_', '**strong**', '__strong__', '***both***', '___both___', '*unclosed', '**unclosed', 'snake_case_word',
    'a * b * c', '*a **b** c*', '**a *b* c**', '****four****', '*a*b', 'a*b*c', '_a_b', 'a_b_c', '* not em *',
    '**bold**text', '*em\nacross*', '*mismatch**', '**mismatch*', '_*mixed*_', '*_mixed_*', '__a__b', 'x__a__',
    '*-*', '**', '*', '_', '* *', '***', 'a ***b*** c', '*a* *b*', '**a** **b**', '*(*paren*)*',
  ],
  strike_ins_mark: [
    '~~del~~', '~~ space~~', '~~a~~b', '~~~triple~~~', '~~~~four~~~~', '~~un', 'a~~b~~c', '~~a ~~b~~ c~~',
    '++ins++', '++ space++', '+++no+++', '++a++b', '==mark==', '== space==', '===no===', '==a==b', '~~**mix**~~',
    '++==nest==++', '==~~nest~~==', '~~a\nb~~', '++++', '====', 'a ~~ b ~~ c', '1 + 1 == 2', 'x++ y++',
  ],
  sub_sup: [
    'H~2~O', 'x^2^', '~a b~', '^a b^', 'a~b', 'a^b', '~~', '^^', '~x\\ y~', '^x\\ y^', '2^10^ = 1024', '~sub~^sup^',
    'C~6~H~12~O~6~', '^\\^^', '~\\~~', 'e^i*pi^', '^[note]', '~1~', 'a^b^c^d^', '~`code`~', '^**b**^',
  ],
  code: [
    '`code`', '``co`de``', '` spaced `', '`a\nb`', '`unclosed', 'x `a` y `b` z', '```inline```', '`<tag>`',
    '`&amp;`', '``', '` `', 'a ` b', '``a``b`', '`*no em*`', '`\\`', '`` ` ``', 'many ```` backticks ````',
  ],
  code_block: [
    '    indented code', '    line1\n    line2', '    a\n\n    b', '\tcode by tab', '    <b>&</b>', 'para\n    not code',
    '    code\npara', '      six spaces', '    trailing\n    ', '-   list\n\n        code in list',
    '>     code in quote', '    x\n  \n    y',
  ],
  fence: [
    '```\ncode\n```', '```js\nconst a = 1 < 2 && "q"\n```', '~~~\ntilde\n~~~', '````\n```\n````', '```\nunclosed',
    '```ruby startline=3\ndef x; end\n```', '``` js\nspaced lang\n```', '```\n\n```', '```\n```', '``\nnot fence\n``',
    '```a`b\nx\n```', '  ```\n  indented fence\n  ```', '```\nx\n  ```', '```\nx\n    ```\n```', '~~~\n```\n~~~',
    '```html\n<div>&amp;</div>\n```', '```&lt;\nx\n```', '```c\\+\\+\nx\n```', '```\na\n```\npara', 'para\n```\ncode\n```',
    '- ```\n  in list\n  ```', '> ```\n> in quote\n> ```', '```\n\tTab\n```', '```   \nx\n```   ', '```\nx\n```   trailing',
    '~~~~\na\n~~~', '```py\nprint("hi")\n```\n\n```\nsecond\n```',
  ],
  blockquote: [
    '> quote', '> a\n> b', '> a\nlazy', '> a\n\n> b', '>no space', '>', '> > nested', '> a\n>\n> b', '> # head',
    '> - list\n> - items', '>     code', '> a\n---', '> a\n- b', '   > indented', '> **bold** quote', '>! spoiler',
    '> a\n> > b\n> c', '> quote\n\npara', '>> double', '> 1. one\n> 2. two', '> ```\n> x\n> ```', 'para\n> quote',
  ],
  hr: [
    '---', '***', '___', '- - -', '* * *', '_ _ _', '--', '----------', '-- -', '***text', '   ---', '    ---',
    'a\n***', '- - - -', '*-*', '_\t_\t_',
  ],
  list: [
    '- a\n- b', '* a\n* b', '+ a\n+ b', '1. a\n2. b', '1) a\n2) b', '3. three\n4. four', '0. zero', '- a\n* b',
    '1. a\n1) b', '- a\n\n- b', '- a\n  b', '- a\nb lazy', '- a\n  - b\n    - c', '1. a\n   - b', '-a', '1.a',
    '- ', '-', '- \n  x', '-    five spaces', '- a\n\n\n- b', '- a\n\n  para in item', '10. ten\n11. eleven',
    '007. leading zeros', '12345678901234567890. huge', '9007199254740993. above 2^53', '99999999999999999999999. exp',
    '9007199254740995. odd', '18446744073709551616. 2^64', '123456789012345678901234. long', '999999999999999999999. nines',
    '1000000000000000000000. 1e21', '100000000000000000000. 1e20', '1234567890123456. sixteen', '99999999999999999. seventeen',
    '- [ ] task\n- [x] done', '- a\n> quote', '- a\n---', '- a\n```\nfence\n```', '* a\n\n  * b', '1. a\n\n2. b',
    '- one\n- two\n\n  para', '2. b\n1. a', '- a\n - b\n  - c\n   - d', '- # head in list', '1.\n2.', '* * *\n* a',
    '- a\n\n    code', '- a\n- b\n\n\n- c', '+ a\n- b\n* c', '1. a\n2.b', '- a  \n  b', '+',
  ],
  table: [
    '| a | b |\n|---|---|\n| 1 | 2 |', 'a | b\n--|--\n1 | 2', '| a |\n|:--|\n| x |', '| l | c | r |\n|:--|:-:|--:|\n| 1 | 2 | 3 |',
    '| a | b |\n|---|---|', '| a | b |\n|---|\n| 1 | 2 |', '| a | b |\n|---|---|\n| 1 |', '| a | b |\n|---|---|\n| 1 | 2 | 3 |',
    '| a | b |\n| --- | --- |\n| `|` | x |', '|a|b|\n|-|-|\n|*e*|**s**|', 'no pipe\n---|---', '| a | b |\n|---|---|\ntext',
    '| a | b |\n|---||---|\n| 1 | 2 |', '| a |\n| :-: |\n| x |\n\npara', '| h |\n|---|\n|  |', '|a|\n|-|\n|b|\n|c|',
    '| a | b |\n|-x-|---|', '  | a | b |\n  |---|---|\n  | 1 | 2 |', '| ![i](https://images.example.com/a.png) | [l](https://example.com) |\n|---|---|\n| @user | #tag |',
    '| a |\n|---|\n| https://example.com/x |', 'a|b\n-|-', '|a\n|-\n|b', '| a | b |\n|:--:|---:|\n| 1 | 2 |',
  ],
  html_block: [
    '<div>block</div>', '<div>\n\n*md*\n\n</div>', '<center>centered</center>', '<center>\n\n# Head\n\n</center>',
    '<div class="pull-right">r</div>', '<div class="text-justify">\n\njustified *md*\n\n</div>', '<table><tr><td>x</td></tr></table>',
    '<!-- comment -->', '<!-- multi\nline -->', '<?php echo 1; ?>', '<!DOCTYPE html>', '<p>para</p>', '</div>', '<DIV>upper</DIV>',
    '<span>inline start</span>', '<iframe src="https://www.youtube.com/embed/abc" width="640"></iframe>', '<hr/>', '<hr>',
    '   <div>3 spaces</div>', '    <div>4 spaces</div>', '<div', '<div\nattr>', '<abcdefghijklmnop>', '<h1>head</h1>\ntext',
    'text\n<div>after</div>', '<details><summary>S</summary>hidden</details>', '<blockquote>bq</blockquote>', '<video src="v.mp4"></video>',
    '<script>alert(1)</script>', '<style>p{}</style>', '<pre>\n  pre\n</pre>', '<ul><li>a</li></ul>', '<a href="x">link</a> text',
    '<img src="https://images.example.com/x.png">', '<sub>s</sub><sup>p</sup>', '<!X>', '<?', '<div>\n<div>\nnested\n</div>\n</div>',
  ],
  inline_html: [
    'a <b>bold</b> c', 'a <span class="x">s</span>', '<a href="https://example.com">l</a>', 'x <br> y', 'x <br/> y', 'x <img src="a.png" /> y',
    'a <!-- c --> b', 'a <!----> b', 'a <!---> b', 'a <!-- a--b --> c', 'a <?pi?> b', 'a <!DOC x> b', 'a <![CDATA[ x ]]> b',
    'a </b> c', 'a < b > c', 'a <b c', 'a <1> b', 'a <b attr=unq> c', 'a <b attr=\'sq\'> c', 'a <b attr="dq"> c',
    'a <b attr = "sp"> c', 'a <b a b c> d', 'a <b a=> c', 'a <b data-x="1" _y=2 :z> c', 'a <b/> c', 'a <b /> c',
    'a <x-y> c', 'a <b\nattr="nl"> c', 'a <b attr="a>b"> c', 'a <!-- unclosed', 'a <? unclosed', 'a <![CDATA[ unclosed',
    '<em>start</em> of para', 'a <a href="javascript:alert(1)">x</a>', 'x<sup>2</sup>', 'a <i>i</i><b>b</b>',
    'a <b attr=x/> c', 'a <b attr=`x`> c', 'a <!--x--y--> b', 'a <!-- - --> b',
  ],
  links: [
    '[text](https://example.com)', '[text](https://example.com "title")', "[t](https://e.com 'single')", '[t](https://e.com (paren))',
    '[t](<https://e.com/a b>)', '[t]( https://e.com )', '[t](https://e.com/a(b)c)', '[t](https://e.com/a(b(c)))', '[t]()',
    '[t](#anchor)', '[t](/relative/path)', '[t](javascript:alert(1))', '[t](JAVASCRIPT:x)', '[t](vbscript:x)', '[t](data:text/html,x)',
    '[t](file:///etc)', '[t](javascript&#58;x)', '[t](https://e.com/%20space)', '[t](https://e.com/ü)', '[t](https://e.com/a%zz)',
    '[t](https://e.com/a\\_b)', '[t](https://e.com "ti\\"tle")', '[t](https://e.com "a &amp; b")', '[t](https://e.com/?a=1&b=2)',
    '[**bold** link](https://e.com)', '[nested [brackets]](https://e.com)', '[unclosed](https://e.com', '[t] (https://e.com)',
    '[t](https://e.com "unclosed)', '[](https://e.com)', '[a](b)[c](d)', '[link with `code`](https://e.com)', '[t](<bad\n>)',
    '[t](https://e.com\n"title")', '[t](\nhttps://e.com)', '[t](https://e.com/&amp;x)', '[t](https://e.com/a"b)', '[t](<a>b>)',
    '[t](https://e.com/été)', '[t](%E2%82%AC)', '[t](%E2%82)', '[t](%C0%AF)', '[t](%ED%A0%80)', '[t](%2F%3F%23)',
    '[t](a%25b)', '[t](https://e.com/a#frag ment)', '[t](<https://e.com/a#b c>)', '[t](mailto:someone@example.com)',
    '[[nested](https://a.com)](https://b.com)', '[t](https://e.com/🐝)', '[t](a\ud800b)', '[t](<a\ud800b>)',
    '<https://e.com/\ud800>', '[t](<a\udc00>)',
  ],
  images: [
    '![alt](https://images.example.com/a.png)', '![alt](https://images.example.com/a.png "t")', '![](https://images.example.com/a.png)',
    '![a *b*](x.png)', '![a\\_b](x.png)', '![a &amp; b](x.png)', '![alt]', '![alt][ref]\n\n[ref]: https://images.example.com/r.png',
    '[![img](https://images.example.com/i.png)](https://example.com)', '![a](<x y.png>)', '!not image', '! [no](x.png)',
    '![x](https://images.example.com/a.png)![y](https://images.example.com/b.png)', '![alt "q"](x.png)',
  ],
  references: [
    '[foo]\n\n[foo]: https://example.com', '[foo][bar]\n\n[bar]: https://example.com "T"', '[Foo Bar]\n\n[foo   bar]: /url',
    '[foo][]\n\n[foo]: /url', '[foo] [bar]\n\n[bar]: /url', '[undefined]', '[a]: /url\n[b]: /url2\n\n[a] [b]',
    '[a]: /first\n[a]: /second\n\n[a]', '[a]:\n/url\n\n[a]', '[a]: /url "title"\n\n[a]', "[a]: /url 'title'\n\n[a]",
    '[a]: /url (title)\n\n[a]', '[a]: <url with spaces>\n\n[a]', '[a]: /url "title" junk\n\n[a]', '[a]: /url\njunk\n\n[a]',
    '[ä]: /umlaut\n\n[Ä]', '[ß]: /eszett\n\n[SS]', '[a]: javascript:x\n\n[a]', '> [a]: /in-quote\n\n[a]', '- [a]: /in-list\n\n[a]',
    '[a][b][c]\n\n[c]: /c', '[__proto__]: /p\n\n[__proto__]', '[constructor]\n\n[constructor]: /c', '[x]: /url\n[y]: /url2',
    '![i][x] and [l][x]\n\n[x]: https://images.example.com/x.png', '[a]\n\n[a]: /url\npara after', '[a]: /u "t"   \n\n[a]',
  ],
  autolink: [
    '<https://example.com>', '<http://example.com/a?b=c&d=e>', '<mailto:a@example.com>', '<a@example.com>', '<foo@bar.example.com>',
    '<javascript:alert(1)>', '<irc://irc.example.org/chan>', '<made-up://x>', '<https://example.com/a b>', '<https://e.com/<x>',
    '<a.b-c@ex-ample.co>', '<a@-bad.com>', '<a@b>', '<HTTPS://EXAMPLE.COM>', '<https://e.com/ü>', '<ftp://f.example.com>',
    '<data:text/plain,x>', '<a@b.c-d.e>', '<a+b@example.com>', '<https://e.com/%zz>', '<https://e.com/🐝>',
  ],
  entities: [
    '&amp; &lt; &gt; &quot;', '&copy; &reg; &trade;', '&nbsp;x', '&#65; &#x41; &#X41;', '&#0;', '&#xD800;', '&#xFFFE;', '&#x110000;',
    '&#99999999;', '&#x1F41D;', '&#128029;', '&unknown;', '&amp', '& alone', '&#;', '&#x;', '&#123456789;', '&AMP;', '&Amp;',
    '&ThickSpace;', '&zopf;', '&#x0041;', '&#65', '&#9;', '&#11;', '&#127;', '&#159;', '&#xFDD0;', '&#x1FFFF;', 'a&amp;b',
    '[l](https://e.com?a=1&amp;b=2)', '`&amp;`', '&#x26;amp;', '&#38;', '&lt;b&gt;not tag&lt;/b&gt;', '&abcdefghijklmnopqrstuvwxyzabcdefg;',
    '&ab1;', '&1ab;', '&#x41g;', '&#00065;',
  ],
  escapes: [
    '\\*not em\\*', '\\_x\\_', '\\# not head', '\\- not list', '1\\. not list', '\\`not code\\`', '\\[not link\\](x)', '\\\\backslash',
    '\\<not tag>', '\\&amp;', '\\a not escapable', 'end\\', '\\!\\"\\#\\$\\%\\&\\\'\\(\\)\\*\\+\\,\\-\\.\\/\\:\\;\\<\\=\\>\\?\\@\\[\\\\\\]\\^\\_\\`\\{\\|\\}\\~',
    '\\~\\~x\\~\\~', '\\+\\+x\\+\\+', '\\=\\=x\\=\\=', '\\^x\\^', 'a\\\nb', '\\ space',
  ],
  footnotes: [
    'Text[^1].\n\n[^1]: Note.', 'A[^a] B[^b]\n\n[^a]: First\n[^b]: Second', 'Twice[^x] and[^x]\n\n[^x]: Shared', 'Inline^[note text] here',
    'Inline^[with *em*] and^[second]', 'Ref[^missing]', '[^1]: Defined only', 'X[^1]\n\n[^1]: Para one\n\n    Para two', '[^ space]: no',
    'X[^1]\n\n[^1]:no space', 'A^[n1 ^[nested]] B', 'A[^1]\n\n[^1]: > quote in note', '^[]', '^[unclosed', 'X[^1]\n\n[^1]:',
    '[^]: empty', 'X[^a]\n\n[^a]: note with [link](https://example.com)', 'X[^1] Y^[inline]\n\n[^1]: Def', '- item[^1]\n\n[^1]: In list',
    'X[^long-label_1]\n\n[^long-label_1]: L', 'a^[b] c[^d]\n\n[^d]: D', '[^1]: a\n[^1]: b\n\nX[^1]', 'X[^1]\n\n[^1]: line one\nline two lazy',
    'X[^1]\n\n[^1]: https://example.com/in-note', 'a\n\n[^x]: Unused\n\nb^[used]', 'X[^a]\n\n[^a]: outer\n    [^b]: inner',
    'X[^b]\n\n[^a]: outer\n    [^b]: inner', 'A^[x [^r] y] [^r]\n\n[^r]: R',
  ],
  deflist: [
    'Term\n: Definition', 'Term\n~ Tilde def', 'Term\n\n: Loose def', 'Term\n: Def 1\n: Def 2', 'T1\nT2\n: Def', 'Term\n:no space',
    'Term\n:   ', 'Term\n: *em* def\n\nOther\n: Def', 'Term\n: Def\n\n    more def', ': no term', 'Term\n: def\nlazy line',
    'A\n: a\nB\n: b', 'Term\n: - list in def', 'Term\n\n: A\n\n: B', 'Term **bold**\n: def', '- item\n\n  Term\n  : nested def',
  ],
  abbr: [
    '*[HTML]: Hyper Text Markup Language\n\nHTML is fun', '*[W3C]: World Wide Web Consortium\nThe W3C rules.', '*[ABC]: x\n\nABCD ABC (ABC) ABC.',
    '*[a b]: spaced\n\nsay a b now', '*[HTML]: first\n*[HTML]: second\n\nHTML', '*[]: empty label\n\n x', '*[X]:\n\nX', '*[C++]: plus\n\nC++ code',
    '*[e.g.]: for example\n\nsee e.g. this', '*[HTML]: H\n*[HTML5]: H5\n\nHTML5 and HTML', '*[A]: a & b "q"\n\nA', '*[x]: y\ntext x here',
    '*[Hive]: blockchain\n\n# Hive heading\n\n- Hive item\n\n**Hive** bold', '*[AB]: t\n\n[AB](https://e.com) AB', '*[url]: u\n\nurl https://url.com',
    '*[a]: 1\n*[b]: 2\n\na-b', '*[$]: dollar\n\npay $ now', '*[(x)]: p\n\nsee (x) here', '*[a\\]]: esc\n\na]',
  ],
  linkify: [
    'https://example.com', 'Visit https://example.com/path?q=1#frag now', 'http://example.com.', 'https://example.com,', 'https://example.com!',
    'https://example.com?', '(https://example.com)', '(see https://example.com/a_(b))', '[https://example.com]', 'https://example.com/a]',
    '<https://example.com> vs https://example.com', 'www.example.com', 'www.example.com/path', 'example.com', 'Example.COM/Page',
    'someone@example.com', 'mailto:someone@example.com', 'MAILTO:someone@example.com', 'a@b', 'user@sub.example.co.uk', 'x@y.zzzzzz',
    'ftp://ftp.example.com/file', 'git://example.com/repo', 'javascript://example.com', 'nowhitespacehttp://www.example.com',
    'link:http://www.example.com', 'example.com:8080/x', 'http://localhost', 'http://localhost:3000/x', 'http://127.0.0.1/', '192.168.1.1',
    'http://192.168.1.1:80', 'https://example.com/a..b', 'a..example.com', 'https://例え.jp/パス', 'https://xn--r8jz45g.jp', 'пример.рф',
    'ПРИМЕР.РФ', 'https://example.com/🐝', '🐝.com', 'https://example.com/%E2%82%AC', 'https://example.com/a%22b',
    '//example.com/protocol-relative', 'a//example.com', 'Text with @mention and #tag', '@user.name', 'email@x.com.', 'see: https://a.com, https://b.com',
    'https://a.com https://a.com', 'https://a.com/x https://a.com', '*https://example.com*', '**https://example.com**', '`https://example.com`',
    '[link](https://a.com) https://b.com', '[https://a.com](https://b.com)', '<a href="https://a.com">https://b.com</a>', '<a href="x">in</a> www.c.com',
    '<b>https://a.com</b>', 'tel: +1 (555) 123-4567 https://a.com', '+1 555 123 4567 www.ex.com', '#hashtag.com', 'x #y.com/z', '# not.com https://a.com',
    'see # https://example.com', 'C# https://example.com', 'foo@bar.com/x', 'http://example.com/path/', 'https://example.com/a?b=c&d=e',
    'HTTPS://EXAMPLE.COM', 'https://example.com/a~b_c-d.e', 'www.example.com.', 'www.e', 'https://e', 'http://', 'http://.com',
    'example.co.uk/path?x=(1)', 'https://example.com/{x}', 'https://example.com/a|b', 'https://example.com/a\'b', 'https://example.com/a"b',
    'https://example.com/<x>', 'https://example.com/&lt;x', '&lt;https://example.com&gt;', 'https://example.com&nbsp;x', 'x&#39;https://a.com',
    'mailto:a@b.com?subject=x', 'xmailto:a@b.com', 'm@x.com', 'mail@ma.co', 'a.b@c.d.museum', 'a@b.c', 'a_b@c-d.com', 'a@b.com-',
    'a@b-.com', 'a.@b.com', '.a@b.com', 'a@@b.com', 'a@b..com', 'https://example.com/✓', 'https://example.com:abc',
    'www.example.com:8080/path', 'example.vermögensberatung', 'example.ελ', 'example.中国', 'https://ecency.com/@user/permlink',
    'https://ecency.com/hive-125125/@user/post-title-123', 'Check @hive-user and #hive-tag on https://peakd.com/@x', 'x.y.z.example.com',
    'http://ex ample.com', 'http://a.b.c.d.e.f.g.example.com/x', 'https://example.com/' + 'a'.repeat(300), 'word' + 'x'.repeat(80) + '.com',
    'https://example.com\nhttps://second.example.com', 'one https://a.com two\nthree www.b.com', 'xn--80ak6aa92e.com',
    'a.co', 'a.com-x', 'a.comx', 'a.com_x', 'x.co.uk', '1.2.3.4.com', '999.1.1.1.com', '1.2.3.4567.com', 'http://1.2.3.4567',
    'http://1.2.3.4/x', 'www.1.2.3.4', 'a_b.com', '-a.com', 'a-.com', 'a.b-c.com', 'foo.COM', 'foo.Com/Path', 'foo.Рф', 'x.ΕΛ',
    'x.' + 'a'.repeat(63) + '.com', 'x.' + 'a'.repeat(64) + '.com', 'a.'.repeat(130) + 'com', 'a'.repeat(63) + 'b://x.com',
    'a'.repeat(64) + 'b://x.com', 'a:1', 'git:1.0', 'x:http://y.com', 'http://x.com:8080:90', 'http://x.com/a.b.,',
    'https://x.com/a?', 'https://x.com/(a)', 'https://x.com/a)', 'http://x.com/#!/a', 'http:x.com', 'ftp:/x.com', '//www.x.com',
    'xx//www.x.com', 'WWW.EXAMPLE.COM', 'wWw.example.com', 'www..com', 'www.-x.com', 'https://-x.com', 'https://x', 'https://x:80',
    'x.museum.', 'example.travel/p', 'example.xn--p1ai', 'example.ninja', 'example.co.jp?', 'a.b.c', 'v1.2.3', 'file.txt',
    'README.md', 'node.js', 'e.g. this', 'i.e. that', 'Mr.Smith', 'U.S.A.', '3.14', '10.0.0.1', 'example.com:abc/x',
    'example.com:/x', 'example.com?q', 'example.com#h', 'example.com/a_(b)_c', 'https://a.com/[x]', 'https://a.com/x]',
    'https://a.com/{y}', 'https://a.com/y}', 'https://a.com/;x;', 'https://a.com/^x^', 'https://a.com/x!', 'https://a.com/x.',
    'https://a.com/x,y', 'https://a.com/✓', 'https://a.com/x✓y', 'ſite.com', 'test.ſite', 'K.com', 'ı.com',
    'https://a.com/%', 'https://a.com/%E2', 'https://a.com/%25', 'a@b.com.', 'a@b.co', 'mailto:a@b.co', 'mailto:', 'mailto:@x.com',
    'user+tag@example.org', 'user@localhost', 'x@x.x.com', 'üser@exämple.com', 'a@b.c.d.e.f.com', 'a@xn--p1ai.com',
  ],
  hive: [
    '@ecency-user wrote a post', 'Hello @user1, @user2 and @user.three!', '#hive #ecency #photography', 'Tag at end #tag', 'mid#word',
    '<center>![image](https://images.ecency.com/p/abc.png)</center>', '<center>\n\n![image](https://images.ecency.com/p/abc.png)\n\n</center>',
    '<div class="pull-left">\n\n![l](https://images.ecency.com/l.png)\n\n</div>\n<div class="pull-right">\n\ntext\n\n</div>',
    '<div class="text-justify">Long justified text with **bold**.</div>', '<center><h2>Title</h2></center>', '<center>\nA\n</center>',
    '| Coin | Price |\n|:-----|------:|\n| HIVE | 0.30 |\n| HBD | 1.00 |', '<iframe width="560" height="315" src="https://www.youtube.com/embed/x" frameborder="0" allowfullscreen></iframe>',
    'https://www.youtube.com/watch?v=dQw4w9WgXcQ', 'https://3speak.tv/watch?v=user/abcdefgh', 'https://images.ecency.com/p/xyz.jpg',
    '![](https://files.peakd.com/file/peakd-hive/user/abc.jpg)', '---\n\n<sub>Posted with Ecency</sub>', '<sup>small</sup> text',
    '>! spoiler', '<details>\n<summary>More</summary>\n\nHidden **md**\n\n</details>', 'مرحبا بالعالم', 'שלום עולם', 'Привет мир',
    '你好世界', 'こんにちは', '안녕하세요', '🐝 Hive bee 🐝', '👍🏽 thumbs', '👨‍👩‍👧 family', 'Mixed العربية and English',
    '<p dir="rtl">نص</p>', '**مرحبا** _عالم_', '# عنوان', '- عنصر\n- آخر', '| ع | ب |\n|---|---|\n| 1 | 2 |', 'x​zero​width',
    '<div class="phishy">x</div>', '<span style="color:red">red</span>', '<a href="https://ecency.com/@user">@user</a>',
    '[@user](https://ecency.com/@user)', 'https://hive.blog/@user/post', '![img](https://images.hive.blog/0x0/https://example.com/a.png)',
    '<img src="https://images.ecency.com/p/x.png" alt="x" width="400">', '<center><img src="https://images.ecency.com/p/y.png"></center>',
    'Posted using [Ecency](https://ecency.com)', '<hr>\n\n**Footer**', '<br>\n<br>', 'line<br>line', '![a](https://images.ecency.com/a.png)\n![b](https://images.ecency.com/b.png)',
    '<div class="text-center">\n\n## Centered heading\n\n</div>', '<table>\n<tr><td>raw</td></tr>\n</table>\n\n| md | table |\n|---|---|\n| a | b |',
    '$HIVE and $HBD prices', '100% sure', '~~$5~~ $3', '50% ==sale==', 'Hive Power (HP)', '@user/permlink', 'https://ecency.com/@user/permlink#@commenter/re',
  ],
  mixed: [
    '# Title\n\nIntro *text* with [link](https://e.com).\n\n- a\n- b\n\n> quote\n\n```\ncode\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |',
    '1. **Step one**: do `x`\n2. *Step two*: see https://example.com\n3. Done ~~maybe~~',
    '> # Quote head\n> - item **b**\n>\n> ```js\n> x\n> ```', '- [link](https://a.com)\n  - ![img](https://images.example.com/i.png)\n    - `code`',
    'Term\n: Def with https://example.com and @user\n\n*[HP]: Hive Power\n\nHP is HP',
    'Text[^1] with ==mark== and ++ins++ and H~2~O and x^2^\n\n[^1]: Note https://example.com',
    '<div>\n\n# Head\n\n[l](https://e.com)\n\n</div>', '***\n\n---\n\n___', '> a\n> b\nc\n\nd', '- a\n\n  > q\n\n- b',
    '| x |\n|---|\n| *e* **s** ~~d~~ `c` [l](https://e.com) |', '# A\nB\n## C\nD', 'a\n\n    code\n\nb', '* a\n\n      code in list\n\n* b',
    '<!-- hidden -->\n\nVisible', 'para\n<!-- inline comment -->\npara', '> > > deep\n> > back\n> out', '1. a\n   1. b\n      1. c\n         1. d',
  ],
  pathological: [
    '*'.repeat(50), '_'.repeat(50), '['.repeat(50), ']'.repeat(50), '<'.repeat(50), '`'.repeat(50), '~'.repeat(50), '+'.repeat(50),
    '='.repeat(50), '^'.repeat(50), '&'.repeat(50), '\\'.repeat(51), '>'.repeat(30) + ' deep', '- '.repeat(30) + 'deep',
    '*a '.repeat(30), '[a '.repeat(30), '![a](b '.repeat(10), '<!--'.repeat(10), '<a '.repeat(20), '**a*'.repeat(20),
    '~~a~'.repeat(20), '`a``'.repeat(20), '[a](<'.repeat(10), '1. '.repeat(25) + 'x', '> - '.repeat(15) + 'x',
    'a\n'.repeat(40), '|'.repeat(40) + '\n' + '-|'.repeat(20) + '\n' + 'x|'.repeat(20), '^[' + 'a^['.repeat(10) + ']'.repeat(11),
    '*'.repeat(25) + 'x' + '*'.repeat(25), '[x]('.repeat(15) + ')'.repeat(15),
  ],
}

// ---------------------------------------------------------------------------------------------
// Pseudo-random combinations (seeded, deterministic).

function mulberry32(seed) {
  return function () {
    seed |= 0
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
const rand = mulberry32(0x5eed1)
const pick = (arr) => arr[Math.floor(rand() * arr.length)]

const fragments = [
  'plain words', '**bold**', '*em*', '_u_', '~~del~~', '++ins++', '==mark==', 'H~2~O', 'x^2^', '`code`', '[link](https://example.com/a)',
  '![img](https://images.example.com/i.png)', 'https://example.com/path', 'www.example.org', 'someone@example.com', '@hive-user', '#hive',
  '<center>', '</center>', '<div class="pull-right">', '</div>', '<b>', '</b>', '<br>', '<!-- c -->', '&amp;', '&copy;', '&#x1F41D;',
  '\\*', '\\_', '# Head', '## Sub', '> quote', '- item', '1. first', '* star', '+ plus', '---', '```', '~~~', '    indented',
  '| a | b |', '|---|---|', '| 1 | 2 |', 'Term', ': def', '[^1]', '[^1]: note', '^[inline note]', '*[HP]: Hive Power', 'HP',
  '[ref]', '[ref]: https://example.com/ref', '🐝', 'مرحبا', 'Привет', '你好', '(paren)', '"quotes"', "'single'", '<', '>', '&',
  '  ', '\t', 'a  ', '\\', '<https://example.com/auto>', '<a@b.com>', '<a href="https://x.com">x</a>', 'https://a.com)', '(https://b.com)',
  'example.com', 'x@y', '+1 555 123 4567', '[', ']', '(', ')', '*', '_', '~', '^', '=', '+', '|', ':',
]
const separators = ['', ' ', ' ', '\n', '\n', '\n\n', '\n\n', '\n> ', '\n- ', '\n    ', '\t', '  \n']

const randomCases = []
for (let n = 0; n < 360; n++) {
  const parts = 2 + Math.floor(rand() * 7)
  let s = ''
  for (let p = 0; p < parts; p++) s += (p ? pick(separators) : '') + pick(fragments)
  randomCases.push([`random/${n}`, s])
}

// ---------------------------------------------------------------------------------------------

const cases = []
for (const [group, inputs] of Object.entries(groups)) inputs.forEach((input, i) => cases.push([`${group}/${i}`, input]))
cases.push(...randomCases)

const names = new Set()
for (const [name] of cases) {
  if (names.has(name)) throw new Error('duplicate case ' + name)
  names.add(name)
}

const htmls = new Map(cases.map(([name, input]) => [name, render(input)]))

// The large document for the performance test: every non-throwing case input, joined, repeated
// past 200 KB. The Kotlin test rebuilds it from the fixtures and compares a SHA-256 of the output.
let big = ''
const joined = cases.filter(([name]) => htmls.get(name) !== null).map(([, input]) => input).join('\n\n')
let repeats = 0
while (big.length < 200 * 1024) { big += (repeats ? '\n\n' : '') + joined; repeats++ }
const t0 = performance.now()
const bigHtml = md.render(big)
const jsMillis = performance.now() - t0
const bigSha = createHash('sha256').update(Buffer.from(bigHtml, 'utf16le')).digest('hex')

// ---------------------------------------------------------------------------------------------
// Kotlin output

const k = (s) => {
  if (s === null) return 'null'
  let out = '"'
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c === 0x22) out += '\\"'
    else if (c === 0x5c) out += '\\\\'
    else if (c === 0x24) out += '\\$'
    else if (c === 0x0a) out += '\\n'
    else if (c >= 0x20 && c < 0x7f) out += s[i]
    else out += '\\u' + c.toString(16).padStart(4, '0')
  }
  return out + '"'
}

const PER_PART = 60
const parts = []
for (let i = 0; i < cases.length; i += PER_PART) parts.push(cases.slice(i, i + PER_PART))

let throwing = 0
const partFns = parts.map((part, p) => {
  const lines = part.map(([name, input]) => {
    const html = htmls.get(name)
    if (html === null) throwing++
    return `        MarkdownCase(${k(name)}, ${k(input)}, ${k(html)}),`
  })
  return `    private fun part${p}() = listOf(\n${lines.join('\n')}\n    )\n`
})

writeFileSync(join(outDir, 'MarkdownFixtures.kt'), `// GENERATED by native/tools/render/generate-markdown-fixtures.mjs from remarkable 2.0.1 + autolinker 3.16.2,
// configured as @ecency/render-helper 2.5.42 configures them. Do not edit by hand.
package com.ecency.core.render.markdown

/** [html] is null where remarkable throws for that input. */
data class MarkdownCase(val name: String, val markdown: String, val html: String?)

object MarkdownFixtures {
    /** Every non-throwing case input joined with "\\n\\n", repeated [BIG_REPEATS] times (also "\\n\\n" joined): ${big.length} chars. */
    const val BIG_REPEATS = ${repeats}
    const val BIG_LENGTH = ${big.length}
    const val BIG_SHA256 = ${k(bigSha)} // of the UTF-16LE output

    val cases: List<MarkdownCase> by lazy { ${parts.map((_, p) => `part${p}()`).join(' + ')} }

${partFns.join('\n')}}
`)

console.log(`${cases.length} cases (${throwing} throwing), big doc ${big.length} chars rendered by JS in ${jsMillis.toFixed(0)} ms`)
