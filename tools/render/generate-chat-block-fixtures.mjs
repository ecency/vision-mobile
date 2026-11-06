// Golden fixtures for chat's block markdown (com.ecency.core.render.markdown.ChatBlocks): the block
// tree remarkable 2.0.1 gives a message with chat's rules (simpleMarkdownToHTML's default preset with
// html, tables and footnotes left as typed, and chat's inline marks: text, escape, backticks, del,
// emphasis). Every case whose web output is made of paragraphs, headings, quotes, lists, code blocks,
// rules and those inline marks is also checked here against simpleMarkdownToHTML itself, so the
// vectors are web's rendering; ordered lists are checked without their start number (web's sanitizer
// drops `start`, so every ordered list there counts from 1).
//
//   cd tools/render && npm ci
//   node generate-chat-block-fixtures.mjs
//
// All inputs are synthetic.
import { writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { kt } from './kotlin-literal.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const require = createRequire(import.meta.url)
const out = join(here, '../../shared/src/commonTest/kotlin/com/ecency/core/render/markdown/ChatBlockFixtures.kt')

const { Remarkable } = require('remarkable')
const { parseDocument } = require('htmlparser2')
globalThis.window = globalThis
const rh = await import('@ecency/render-helper')

const md = new Remarkable({ html: true, breaks: true, typographer: false })
md.core.ruler.enable(['block', 'inline'], true)
md.block.ruler.disable(['htmlblock', 'table', 'footnote'])
md.inline.ruler.enable(['text', 'escape', 'backticks', 'del', 'emphasis'], true)

// The serialized form (the Kotlin test writes the same): p(runs) h2(runs) quote[...] ul[li[...],...]
// ol3[...] code("text") hr; a run is its marks (b i s c) and its text as a JSON string.
const str = (s) => JSON.stringify(s)
const runText = (runs) => runs.map((r) => (r.b ? 'b' : '') + (r.i ? 'i' : '') + (r.s ? 's' : '') + (r.c ? 'c' : '') + str(r.text)).join('')
// line breaks: web drops the spaces around them (its newline rule), chat keeps the text as typed
const norm = (runs) => {
  const outRuns = []
  for (const r of runs) {
    const text = r.text.replace(/[ ]*\n[ ]*/g, '\n')
    const p = outRuns[outRuns.length - 1]
    if (p && p.b === r.b && p.i === r.i && p.s === r.s && p.c === r.c) p.text += text
    else if (text) outRuns.push({ ...r, text })
  }
  return outRuns
}

const runsOfInline = (children) => {
  const runs = []
  let b = 0, i = 0, st = 0
  const add = (text, c) => {
    if (!text) return
    const p = runs[runs.length - 1]
    const r = { text, b: b > 0, i: i > 0, s: st > 0, c }
    if (p && p.b === r.b && p.i === r.i && p.s === r.s && p.c === r.c) p.text += text
    else runs.push(r)
  }
  for (const t of children) {
    switch (t.type) {
      case 'strong_open': b++; break
      case 'strong_close': b--; break
      case 'em_open': i++; break
      case 'em_close': i--; break
      case 'del_open': st++; break
      case 'del_close': st--; break
      case 'text': add(t.content, false); break
      case 'code': add(t.content, true); break
      case 'hardbreak': case 'softbreak': add('\n', false); break
      default: throw new Error('unexpected inline token ' + t.type)
    }
  }
  return runs
}

// chat's tree from remarkable's tokens
const chatTree = (src) => {
  const tokens = md.parse(src, {})
  let k = 0
  const blocks = (until) => {
    const outBlocks = []
    while (k < tokens.length && !until(tokens[k])) {
      const t = tokens[k++]
      switch (t.type) {
        case 'paragraph_open': {
          const inline = tokens[k++]
          k++ // paragraph_close
          outBlocks.push(`p(${runText(norm(runsOfInline(inline.children)))})`)
          break
        }
        case 'heading_open': {
          let runs = []
          if (tokens[k].type === 'inline') runs = runsOfInline(tokens[k++].children)
          k++ // heading_close
          outBlocks.push(`h${t.hLevel}(${runText(norm(runs))})`)
          break
        }
        case 'blockquote_open':
          outBlocks.push(`quote[${blocks((x) => x.type === 'blockquote_close').join(',')}]`)
          k++
          break
        case 'bullet_list_open': case 'ordered_list_open': {
          const items = []
          const close = t.type.replace('_open', '_close')
          while (tokens[k].type !== close) {
            k++ // list_item_open
            items.push(`li[${blocks((x) => x.type === 'list_item_close').join(',')}]`)
            k++
          }
          k++
          outBlocks.push((t.type === 'bullet_list_open' ? 'ul' : `ol${t.order}`) + `[${items.join(',')}]`)
          break
        }
        case 'code': case 'fence':
          outBlocks.push(`code(${str(t.content.replace(/\n$/, ''))})`)
          break
        case 'hr': outBlocks.push('hr'); break
        default: throw new Error('unexpected block token ' + t.type)
      }
    }
    return outBlocks
  }
  return blocks(() => false).join(',')
}

// web's tree from simpleMarkdownToHTML's html; null when it holds anything else
const webTree = (src) => {
  const html = rh.simpleMarkdownToHTML(src)
  let ok = true
  const inlineRuns = (nodes) => {
    const runs = []
    // the renderer writes a line break as "<br>\n": the "\n" after it is only the html's own layout
    let afterBreak = false
    const walk = (list, style) => {
      for (const n of list) {
        if (n.type === 'text') {
          const data = afterBreak && !n.br ? n.data.replace(/^\n/, '') : n.data
          afterBreak = !!n.br
          const p = runs[runs.length - 1]
          if (p && p.b === style.b && p.i === style.i && p.s === style.s && p.c === style.c) p.text += data
          else if (data) runs.push({ text: data, ...style })
        } else if (n.type === 'tag') {
          const s = { ...style }
          if (n.name === 'strong') s.b = true
          else if (n.name === 'em') s.i = true
          else if (n.name === 'del') s.s = true
          else if (n.name === 'code') s.c = true
          else if (n.name === 'br') { walk([{ type: 'text', data: '\n', br: true }], style); continue }
          else if (n.name !== 'a') ok = false
          afterBreak = false
          walk(n.children, s)
        } else ok = false
      }
    }
    walk(nodes, { b: false, i: false, s: false, c: false })
    return runs
  }
  const trimRuns = (runs) => {
    const r = runs.map((x) => ({ ...x }))
    if (r.length) r[0].text = r[0].text.replace(/^\s+/, '')
    if (r.length) r[r.length - 1].text = r[r.length - 1].text.replace(/\s+$/, '')
    return r.filter((x) => x.text)
  }
  const BLOCK = new Set(['p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'blockquote', 'ul', 'ol', 'pre', 'hr'])
  const blocks = (nodes, inItem) => {
    const outBlocks = []
    let pending = []
    const flush = () => {
      if (pending.some((n) => n.type !== 'text' || n.data.trim())) outBlocks.push(`p(${runText(norm(trimRuns(inlineRuns(pending))))})`)
      pending = []
    }
    for (const n of nodes) {
      if (n.type === 'tag' && BLOCK.has(n.name)) {
        flush()
        if (n.name === 'p') outBlocks.push(`p(${runText(norm(trimRuns(inlineRuns(n.children))))})`)
        else if (n.name[0] === 'h' && n.name !== 'hr') outBlocks.push(`${n.name}(${runText(norm(trimRuns(inlineRuns(n.children))))})`)
        else if (n.name === 'blockquote') outBlocks.push(`quote[${blocks(n.children, false).join(',')}]`)
        else if (n.name === 'hr') outBlocks.push('hr')
        else if (n.name === 'pre') {
          const code = n.children.length === 1 && n.children[0].name === 'code' ? n.children[0] : null
          if (!code || code.children.some((c) => c.type !== 'text')) ok = false
          else outBlocks.push(`code(${str(code.children.map((c) => c.data).join('').replace(/\n$/, ''))})`)
        } else {
          const items = n.children.filter((c) => c.type === 'tag')
          if (items.some((c) => c.name !== 'li')) ok = false
          outBlocks.push(`${n.name === 'ul' ? 'ul' : 'ol'}[${items.map((li) => `li[${blocks(li.children, true).join(',')}]`).join(',')}]`)
        }
      } else if (inItem) {
        pending.push(n)
      } else if (n.type === 'text' && !n.data.trim()) {
        // white space between blocks
      } else {
        ok = false
      }
    }
    flush()
    return outBlocks
  }
  const tree = blocks(parseDocument(html, { decodeEntities: true }).children, false).join(',')
  return ok ? tree : null
}

const cases = [
  // lists
  '- a\n- b', '-word', '- ', '-', '* a\n* b', '+ a', '* a\n+ b', '- a\n-b', '1. a\n2. b', '1.', '3. x', '1) a', '1.item',
  '1.5 is a number', '2024. was a good year', '10. ten\n11. eleven', '- a\n1. b', '1. a\n- b', '- a\n  - b\n- c',
  '- a\n  - b\n    - c', '- a\n\n- b', '- a\nlazy', '- a\n\n  more', 'para\n- item', 'para\n1. item', 'para\n2. item',
  '  - indented', '- [ ] task', '- **bold** item', '- *a\n- b*', '1. one\n\n\n2. two', 'a - b', '**- not a list**',
  '\\- escaped', '- `code` item', '- a\n  1. b\n  2. c',
  // headings
  '# introduceyourself', '#hashtag', '#', '# ', '## two', '###### six', '####### seven', '# h #', '# h#', '## **b** h',
  '#  spaced  ', '\\# escaped', 'text\n---', 'text\n===', 'a\nb\n---', 'Some text\n===\nmore', '# a\nb', '#1 on the list',
  '# @bob says hi',
  // quotes
  '> quote', '>no space', '> ', '> a\n> b', '> a\nlazy', '> a\n\n> b', '> > nested', '> - a\n> - b', '- > q', 'a\n> q',
  '> quote @bob\n> more', '> **bold** _it_', '> # heading in quote', '1 > 2', 'a > b',
  // code
  '```\ncode\n```', '```js\n**x**\n```', '```\nunclosed', '~~~\ncode\n~~~', '    code', '    a\n\n    b', 'p\n    not code',
  '- ```\n  x\n  ```', '```\n- not list\n# not heading\n```', '`inline` code', '``` inline ```', '```\n\n```',
  // rules
  '---', '***', '* * *', '___', '- - -', 'a\n***\nb', '--', '**',
  // paragraphs and the inline marks
  'line one\nline two', 'p1\n\np2', 'p1\n\n\n\np2', 'just chatting', 'this is **important** stuff', '~~gone~~ now',
  'snake_case_name', '**a\n\nb**', '*a\nb*', '  leading', 'trailing  ',
  // scripts, emoji, long text
  '- مرحبا *عربي*\n- hello', '# שלום', '> شكرا @bob',
  '- \u{1F642} smile', '1. ' + 'long words '.repeat(60).trim(),
]

// chat keeps these as typed (README "Chats"): html, tables, footnotes and reference definitions are not
// read, and the sanitizer's removal of `<user>` is not copied; they are not checked against web
const asTyped = new Set([
  '!ban <user> [30d]', '- !ban <user> [30d]', '<div>x</div>', 'a\n<div>x</div>', 'a | b\n--|--\n1 | 2', '[^1]: note',
  '[ref]: https://x.com', '<b>bold</b> text',
])
cases.push(...asTyped)

// seeded random messages over block-level pieces
let seed = 7
const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff }
const PIECES = ['- ', '* ', '1. ', '2) ', '> ', '# ', '## ', '```', '---', '    ', '  ', 'a', 'b c', '*b*', '**x**', '`k`', '\n', '\n', '\n\n', '===', '#t', '-w']
for (let k = 0; k < 400; k++) {
  const n = 2 + Math.floor(rnd() * 10)
  let s = ''
  for (let j = 0; j < n; j++) s += PIECES[Math.floor(rnd() * PIECES.length)]
  cases.push(s)
}

const seen = new Set()
let checked = 0
const lines = []
for (const text of cases) {
  if (seen.has(text)) continue
  seen.add(text)
  const tree = chatTree(text)
  const web = webTree(text)
  if (web !== null && !asTyped.has(text)) {
    const unnumbered = tree.replace(/ol\d+\[/g, 'ol[')
    if (unnumbered !== web) throw new Error(`chat blocks differ from simpleMarkdownToHTML for ${str(text)}:\n${unnumbered}\n${web}`)
    checked++
  }
  lines.push(`        Case(${kt(text)}, ${kt(tree)}),`)
}

writeFileSync(out, `package com.ecency.core.render.markdown

// Generated by tools/render/generate-chat-block-fixtures.mjs from remarkable 2.0.1 and
// @ecency/render-helper's simpleMarkdownToHTML (${checked} of ${lines.length} cases checked against it). Do not edit.
internal object ChatBlockFixtures {
    class Case(val input: String, val tree: String)

    val cases: List<Case> = listOf(
${lines.join('\n')}
    )
}
`)
console.log(`${lines.length} cases, ${checked} checked against simpleMarkdownToHTML`)
