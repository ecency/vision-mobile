// Golden fixtures for chat's inline markdown (com.ecency.core.render.markdown.ChatInline): the runs
// remarkable 2.0.1 gives a message with only its text, escape, backticks, del and emphasis inline
// rules on (render-helper's simpleMarkdownToHTML is remarkable's default preset; chat keeps the
// inline marks of it). Every single line case whose web output is one paragraph of those marks is
// also checked here against simpleMarkdownToHTML itself, so the vectors are web's rendering.
//
//   cd native/tools/render && npm ci
//   node generate-chat-markdown-fixtures.mjs
//
// All inputs are synthetic.
import { writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { kt } from './kotlin-literal.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const require = createRequire(import.meta.url)
const out = join(here, '../../shared/src/commonTest/kotlin/com/ecency/core/render/markdown/ChatInlineFixtures.kt')

const { Remarkable } = require('remarkable')
const { parseDocument } = require('htmlparser2')
globalThis.window = globalThis
const rh = await import('@ecency/render-helper')

const md = new Remarkable({ html: true, breaks: true, typographer: false })
md.inline.ruler.enable(['text', 'escape', 'backticks', 'del', 'emphasis'], true)

const BLANK = /\n(?:[ \t]*\n)+/g

const runsOf = (src) => {
  const runs = []
  const add = (r) => {
    if (!r.text) return
    const p = runs[runs.length - 1]
    if (p && p.b === r.b && p.i === r.i && p.s === r.s && p.c === r.c) p.text += r.text
    else runs.push(r)
  }
  const paragraph = (s) => {
    if (!s) return
    const tokens = []
    md.inline.parse(s, md.options, {}, tokens)
    let b = 0, i = 0, st = 0
    for (const t of tokens) {
      const style = { b: b > 0, i: i > 0, s: st > 0 }
      switch (t.type) {
        case 'strong_open': b++; break
        case 'strong_close': b--; break
        case 'em_open': i++; break
        case 'em_close': i--; break
        case 'del_open': st++; break
        case 'del_close': st--; break
        case 'text': add({ text: t.content, ...style, c: false }); break
        case 'code': add({ text: t.content, ...style, c: true }); break
        case 'hardbreak': case 'softbreak': add({ text: '\n', ...style, c: false }); break
        default: throw new Error('unexpected token ' + t.type)
      }
    }
  }
  let last = 0
  for (const m of src.matchAll(BLANK)) {
    paragraph(src.slice(last, m.index))
    add({ text: m[0], b: false, i: false, s: false, c: false })
    last = m.index + m[0].length
  }
  paragraph(src.slice(last))
  return runs
}

// web's html for one paragraph, flattened to the same runs; null when it is not one paragraph of inline marks
const webRuns = (src) => {
  const html = rh.simpleMarkdownToHTML(src).trim()
  if (!/^<p>[\s\S]*<\/p>$/.test(html)) return null
  const runs = []
  let ok = true
  const walk = (node, style) => {
    for (const n of node.children || []) {
      if (n.type === 'text') {
        const p = runs[runs.length - 1]
        if (p && p.b === style.b && p.i === style.i && p.s === style.s && p.c === style.c) p.text += n.data
        else if (n.data) runs.push({ text: n.data, ...style })
      } else if (n.type === 'tag') {
        const s = { ...style }
        if (n.name === 'strong') s.b = true
        else if (n.name === 'em') s.i = true
        else if (n.name === 'del') s.s = true
        else if (n.name === 'code') s.c = true
        else if (n.name !== 'p') ok = false
        walk(n, s)
      } else ok = false
    }
  }
  walk(parseDocument(html, { decodeEntities: true }), { b: false, i: false, s: false, c: false })
  return ok ? runs : null
}

const cases = [
  // RN's chat cases (tools/golden/generate-chats.mjs)
  'just chatting', 'this is **important** stuff', '**very *very* bold**', '`a **b** c`', 'a *b* c', 'a _b_ c',
  'snake_case_name', 'the file_name_here is set', '2 * 3 * 4', '2 * 3 and *italic*', '***', '** **', '~~gone~~ now',
  '~~ not ~~', '__bold__ text', 'x__y__z', '_a_b_', '*unterminated', 'a ** b **', '**a**b**c**', '`code` and `more`',
  '`unterminated code', '*a **b** c*', '**a *b* c**', '_*a*_', '*_a_*', '~~**a**~~', '**~~a~~**', '`*a*`',
  'a*b*c', 'a_b_c', '(*a*)', '[_a_]', '*a* *b* *c*', '**a** __b__', 'end with *', '* start', 'tab\t*a*\tb',
  'nbsp\u00a0*a*\u00a0b', '* a*', '**' + '*'.repeat(10) + 'x' + '*'.repeat(10) + '**', '*a*' + '*b*'.repeat(3),
  '_' + 'a_'.repeat(6), '**a __b *c ~~d `e` d~~ c* b__ a**', '*1 *2 *3 *4 *5 *6 x* y* z* w* v* u*',
  'emoji \u{1F642} *with* style', 'under_score and *star*', '~~~a~~~', '``', '`` `a` ``',
  // the moderation help text and other angle brackets stay text
  '!ban <user> [30d]', '**!ban** <user> [30d] *reason*', 'a <b>c</b> d', '1 < 2 and 3 > 2',
  // escapes, entities, code spans
  '\\*not italic\\*', '\\_\\_init\\_\\_', 'C:\\path\\to\\file', '`` code with ` inside ``', '` spaced `', '`a\nb`',
  '&amp; &lt;x&gt;', 'a ` b', '**`code` in bold**', '`**` and `__`',
  // words and markers
  '__init__.py', 'my_var_name and __dunder__', 'foo*bar*baz', 'foo_bar_ baz_', '*a**', '**a*', '***a***', '***a** b*',
  '**a *b** c*', '~~a~b~~', '~~~~a~~~~', '~a~', '==mark==', '++ins++', '^sup^', 'H~2~O', 'x^2^',
  '*a\nb*', '**a\n\nb**', 'line one\nline *two*', 'para *one*\n\n  \npara _two_', '*a*\\\nb',
  'it\'s *really* "quoted"', '*\u00e9t\u00e9*', '_\u00e9t\u00e9_', 'caf\u00e9_bar_', '\u0634\u0643\u0631\u0627 *\u0639\u0631\u0628\u064a*',
]

// seeded random cases over the marker alphabet
let seed = 42
const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff }
const ALPHA = ['a', 'b', ' ', '*', '*', '_', '_', '~', '`', '\\', 'x_y', '**', '~~', '\n']
for (let k = 0; k < 300; k++) {
  const n = 2 + Math.floor(rnd() * 12)
  let s = ''
  for (let j = 0; j < n; j++) s += ALPHA[Math.floor(rnd() * ALPHA.length)]
  cases.push(s)
}

const seen = new Set()
let checked = 0
const lines = []
for (const text of cases) {
  if (seen.has(text)) continue
  seen.add(text)
  const runs = runsOf(text)
  if (!text.includes('\n') && !/[<>&]/.test(text)) {
    const web = webRuns(text)
    if (web) {
      const norm = (rs) => JSON.stringify(rs.map((r) => [r.text.replace(/[\t\u00a0]/g, ' '), r.b, r.i, r.s, r.c]))
      // remarkable's block step drops a paragraph's edge white space and turns tabs into spaces
      const trimmed = runs.map((r) => ({ ...r }))
      if (trimmed.length) {
        trimmed[0].text = trimmed[0].text.replace(/^[ \t]+/, '')
        trimmed[trimmed.length - 1].text = trimmed[trimmed.length - 1].text.replace(/[ \t]+$/, '')
      }
      if (norm(trimmed.filter((r) => r.text)) !== norm(web)) {
        throw new Error(`inline-only runs differ from simpleMarkdownToHTML for ${JSON.stringify(text)}: ${norm(trimmed)} vs ${norm(web)}`)
      }
      checked++
    }
  }
  const spans = runs.map((r) => `Run(${kt(r.text)}, ${r.b}, ${r.i}, ${r.s}, ${r.c})`).join(', ')
  lines.push(`        Case(${kt(text)}, listOf(${spans})),`)
}

writeFileSync(out, `package com.ecency.core.render.markdown

// Generated by native/tools/render/generate-chat-markdown-fixtures.mjs from remarkable 2.0.1 and
// @ecency/render-helper's simpleMarkdownToHTML (${checked} of ${lines.length} cases checked against it). Do not edit.
internal object ChatInlineFixtures {
    class Run(val text: String, val bold: Boolean, val italic: Boolean, val strike: Boolean, val code: Boolean)
    class Case(val input: String, val runs: List<Run>)

    val cases: List<Case> = listOf(
${lines.join('\n')}
    )
}
`)
console.log(`${lines.length} cases, ${checked} checked against simpleMarkdownToHTML`)
