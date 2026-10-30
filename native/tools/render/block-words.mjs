// Word oracle for the native block builder: the words a reader sees in render-helper HTML, read
// with htmlparser2 (independent of the Kotlin xmldom port). Block boundaries break words; content
// the block model replaces on purpose (inline post links that become preview cards) is left out,
// with the same validation rules as SafeTarget.kt.
//
//   node block-words.mjs corpus   # corpus/expected.jsonl -> corpus/words.jsonl (local only, gitignored)
// generate-block-inputs.mjs imports visibleWords() for the committed synthetic inputs.
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { parseDocument } from 'htmlparser2'

const SKIP = new Set(['script', 'style', 'head', 'title', 'noscript', 'template', 'colgroup', 'col', 'source', 'track', 'param', 'iframe'])
const BLOCK = new Set([
  'p', 'section', 'article', 'header', 'footer', 'main', 'aside', 'nav', 'figure', 'figcaption', 'address', 'dl', 'dt', 'dd',
  'hgroup', 'summary', 'body', 'html', 'form', 'fieldset', 'legend', 'caption',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'center', 'div', 'blockquote', 'ul', 'ol', 'li', 'pre', 'hr',
  'table', 'thead', 'tbody', 'tfoot', 'tr', 'td', 'th', 'details', 'iframe', 'video', 'img', 'br',
])

const classes = (el) => (el.attribs.class || '').split(/[ \t\n\r\f]+/).filter(Boolean)
const account = (a) => typeof a === 'string' && a.length >= 3 && a.length <= 16 &&
  a.split('.').every((s) => s.length >= 3 && /^[a-z][a-z0-9-]*[a-z0-9]$/.test(s) && !s.includes('--'))
const permlink = (p) => typeof p === 'string' && /^[a-z0-9-]{1,256}$/.test(p)
const hasImg = (n) => (n.children || []).some((c) => c.name === 'img' || hasImg(c))

function isCard(el) {
  if (el.attribs['data-is-inline'] !== 'true' || !classes(el).includes('markdown-post-link') || hasImg(el)) return false
  let a = el.attribs['data-author']
  let p = el.attribs['data-permlink']
  if (!p) return false
  const m = p.match(/(.*?#@)(.*)\/(.*)/)
  if (m) [, , a, p] = m
  const q = p.lastIndexOf('?')
  if (q > -1) p = p.substring(0, q)
  return account(a) && permlink(p)
}

const isVideoAnchor = (el) => {
  const c = classes(el)
  return c.includes('markdown-video-link') || (c.includes('markdown-video-link-youtube') && !!el.attribs['data-youtube'])
}

const textContent = (n) => n.type === 'text' ? n.data : (n.children || []).map(textContent).join('')

function walk(nodes, out) {
  for (const n of nodes) {
    if (n.type === 'text') out.push(n.data)
    else if (n.type === 'cdata') walk(n.children, out)
    else if (n.type === 'tag' || n.type === 'script' || n.type === 'style') {
      const t = n.name.toLowerCase()
      if (SKIP.has(t)) { if (BLOCK.has(t)) out.push(' '); continue }
      if (t === 'a' && isCard(n)) { out.push(' '); continue }
      const block = BLOCK.has(t) || (t === 'a' && isVideoAnchor(n))
      if (block) out.push(' ')
      if (t === 'pre') out.push(textContent(n))
      else walk(n.children || [], out)
      if (block) out.push(' ')
    }
  }
}

export function visibleWords(html) {
  const doc = parseDocument(html, { decodeEntities: true, lowerCaseTags: true, lowerCaseAttributeNames: true })
  const out = []
  walk(doc.children, out)
  return out.join('').split(/[\s​]+/u).filter(Boolean)
}

if (process.argv[2] === 'corpus') {
  const dir = new URL('./corpus/', import.meta.url)
  const src = new URL('expected.jsonl', dir)
  if (!existsSync(src)) throw new Error('run `node corpus.mjs render` first')
  const lines = readFileSync(src, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
  writeFileSync(new URL('words.jsonl', dir), lines.map(({ key, html }) => JSON.stringify({ key, words: visibleWords(html) })).join('\n') + '\n')
  console.log(`wrote words for ${lines.length} posts`)
}
