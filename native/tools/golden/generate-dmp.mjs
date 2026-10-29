// Generates golden vectors for the Kotlin port of diff-match-patch
// (native/shared/src/commonMain/kotlin/com/ecency/core/text/DiffMatchPatch.kt) from the exact
// npm package the RN app ships.
//
// Run from the RN app root so `diff-match-patch` resolves from its node_modules:
//   node <native>/tools/golden/generate-dmp.mjs <native>/shared/src/commonTest/kotlin/com/ecency/core/text
//
// All content is synthetic (seeded PRNG), nothing comes from real posts.
//
// Timeouts: JS diff_main bails out after Diff_Timeout seconds, which is not reproducible. Every
// case therefore runs either with Diff_Timeout = 0 (no deadline; JS then also skips half-match)
// or with Diff_Timeout = 1e6 (a deadline that is never reached, so the result is what the app's
// default 1.0 produces whenever the diff finishes in time, half-match included).
import { createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

const require = createRequire(join(process.cwd(), 'package.json'))
const DMP = require('diff-match-patch')

const outDir = process.argv[2]
if (!outDir) throw new Error('usage: generate-dmp.mjs <output dir>')

const LARGE_TIMEOUT = 1e6

// ---------- PRNG ----------
function mulberry32(seed) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
let rnd = mulberry32(20261008)
const ri = (n) => Math.floor(rnd() * n)
const pick = (arr) => arr[ri(arr.length)]
const chance = (p) => rnd() < p

// ---------- synthetic content ----------
const WORDS = (
  'the quick brown fox jumps over lazy dog hive blockchain post reply vote curation reward ' +
  'witness community wallet token stake power delegate author editor draft history version ' +
  'native mobile app screen feed wave chat market points boost promote bridge layer block ' +
  'transaction signature account profile follower following mute pin reblog bookmark tag ' +
  'summer winter coffee garden travel mountain river city photo camera light shadow music ' +
  'a an of to in on at by for with from is was are be this that it as or and not but'
).split(' ')
const UNI = [
  'naïve', 'café', 'Привет', 'мир', 'مرحبا', 'עולם', '你好', '世界', '日本語', '한국어', 'Ελληνικά',
  'ünïcödé', 'straße', 'ñandú', '🐝', '🎉', '😀', '😃', '😄', '👍🏽', '👨‍👩‍👧', '🇱🇹', '🔥', '💯',
  '𝔘𝔫𝔦𝔠𝔬𝔡𝔢', '漢字かな交じり文', '中文测试', 'ẞ', '…', '«quote»', '“smart”', '€100', '½',
]
const SYM = ['&', '&amp;', '<b>', 'a < b', 'x > y', '50%', '%20', '#tag', '@user', 'x=y;', 'e.g.',
  '(note)', '[1]', '$5', '+1', 'a/b?c:d', '~ok~', '*bold*', '_it_', '`code`', '\t', '100%25', 'q&a', '#',
  'no\u00a0break', 'wide\u3000space', '\ufeffbom', 'thin\u2009gap', 'nel\u0085x', 'vt\u000bff\u000c']

function word() {
  const r = rnd()
  if (r < 0.75) return pick(WORDS)
  if (r < 0.9) return pick(UNI)
  return pick(SYM)
}
function sentence(n = 4 + ri(12)) {
  const w = []
  for (let i = 0; i < n; i++) w.push(word())
  let s = w.join(' ')
  s = s.charAt(0).toUpperCase() + s.slice(1)
  return s + pick(['.', '.', '.', '!', '?', ':', '', '...'])
}
function paragraph() {
  const r = rnd()
  if (r < 0.4) {
    const n = 1 + ri(4)
    const s = []
    for (let i = 0; i < n; i++) s.push(sentence())
    return s.join(' ')
  }
  if (r < 0.5) return pick(['# ', '## ', '### ']) + sentence(2 + ri(5))
  if (r < 0.6) {
    const n = 2 + ri(4)
    const s = []
    for (let i = 0; i < n; i++) s.push(pick(['- ', '* ', `${i + 1}. `]) + sentence(2 + ri(6)))
    return s.join('\n')
  }
  if (r < 0.66) return `[${sentence(2)}](https://example.com/${pick(WORDS)}/${pick(WORDS)}?q=${ri(100)}&r=${pick(WORDS)})`
  if (r < 0.71) return `![${pick(WORDS)}](https://images.example.com/${ri(99999)}/${pick(WORDS)}.png)`
  if (r < 0.77) {
    const n = 1 + ri(4)
    const s = []
    for (let i = 0; i < n; i++) s.push(`  const ${pick(WORDS)}${i} = ${ri(1000)}; // ${pick(WORDS)}`)
    return '```js\n' + s.join('\n') + '\n```'
  }
  if (r < 0.82) return '> ' + sentence()
  if (r < 0.86) return `| ${pick(WORDS)} | ${pick(WORDS)} |\n|---|---|\n| ${ri(100)} | ${word()} |`
  if (r < 0.88) return '---'
  if (r < 0.94) return sentence(3 + ri(6)) + '\n' + sentence(3 + ri(6))
  return pick(UNI) + ' ' + pick(UNI) + ' ' + sentence(3)
}
function text(paragraphs) {
  const sep = chance(0.1) ? '\r\n\r\n' : chance(0.15) ? '\n' : '\n\n'
  const p = []
  for (let i = 0; i < paragraphs; i++) p.push(paragraph())
  let t = p.join(sep)
  if (chance(0.3)) t += '\n'
  return t
}

// ---------- edits (recorded as a script so the fixtures need not store text b) ----------
// A script is a list of [pos, deleteCount, insertText], applied in order.
function cpAlign(s, pos) {
  pos = Math.max(0, Math.min(pos, s.length))
  const c = s.charCodeAt(pos)
  if (pos > 0 && pos < s.length && c >= 0xdc00 && c <= 0xdfff) pos--
  return pos
}
function wordBoundary(s) {
  const spots = [0, s.length]
  for (let i = 1; i < s.length; i++) if (s[i - 1] === ' ' || s[i - 1] === '\n') spots.push(i)
  return pick(spots)
}
function paragraphSpans(s) {
  const spans = []
  const re = /\n\n|\r\n\r\n/g
  let last = 0
  let m
  while ((m = re.exec(s))) {
    spans.push([last, m.index + m[0].length])
    last = m.index + m[0].length
  }
  spans.push([last, s.length])
  return spans.filter(([a, b]) => b > a)
}
function lineSpans(s) {
  const spans = []
  let last = 0
  for (let i = 0; i < s.length; i++) if (s[i] === '\n') { spans.push([last, i + 1]); last = i + 1 }
  if (last < s.length) spans.push([last, s.length])
  return spans
}
function apply(s, script) {
  for (const [p, d, ins] of script) s = s.substring(0, p) + ins + s.substring(p + d)
  return s
}
function randomEdit(s) {
  const kind = ri(14)
  switch (kind) {
    case 0: { const p = wordBoundary(s); return [p, 0, word() + ' '] }
    case 1: { const p = cpAlign(s, ri(s.length + 1)); return [p, 0, pick([word(), sentence(3), ' ', '\n', '\n\n', pick(UNI)])] }
    case 2: { const p = cpAlign(s, ri(s.length + 1)); const e = cpAlign(s, p + 1 + ri(30)); return [p, e - p, ''] }
    case 3: { // replace a word
      const p = wordBoundary(s)
      let e = p
      while (e < s.length && s[e] !== ' ' && s[e] !== '\n') e++
      return [p, e - p, word()]
    }
    case 4: { const p = wordBoundary(s); return [p, 0, sentence() + ' '] }
    case 5: { const sp = paragraphSpans(s); if (sp.length < 2) return null; const [a, b] = pick(sp); return [a, b - a, ''] }
    case 6: { const sp = paragraphSpans(s); const [a] = pick(sp); return [a, 0, paragraph() + '\n\n'] }
    case 7: { // typo: swap two adjacent ASCII chars
      for (let t = 0; t < 10; t++) {
        const p = ri(Math.max(1, s.length - 1))
        if (/[a-z]{2}/.test(s.substr(p, 2))) return [p, 2, s[p + 1] + s[p]]
      }
      return null
    }
    case 8: { const p = cpAlign(s, ri(s.length + 1)); return [p, 0, pick(['🐝', '🎉', '😀', '😃', '😄', '🔥', '👍🏽'])] }
    case 9: { // capitalisation change
      const p = wordBoundary(s)
      if (p >= s.length) return null
      const c = s[p]
      const u = c.toUpperCase() === c ? c.toLowerCase() : c.toUpperCase()
      return u.length === 1 && u !== c ? [p, 1, u] : null
    }
    case 10: { const i = s.indexOf('\n', ri(s.length)); return i < 0 ? null : [i, 1, pick(['\r\n', '\n\n', ' ', ''])] }
    case 11: { const p = wordBoundary(s); return [p, 0, pick(['  ', '\t', ' ', '& ', '<br> ', '> '])] }
    case 12: { // replace a whole line
      const sp = lineSpans(s); if (!sp.length) return null
      const [a, b] = pick(sp)
      return [a, b - a, sentence() + (s[b - 1] === '\n' ? '\n' : '')]
    }
    default: { const p = cpAlign(s, ri(s.length + 1)); const e = cpAlign(s, p + ri(8)); return [p, e - p, word()] }
  }
}
function moveParagraph(s) {
  const sp = paragraphSpans(s)
  if (sp.length < 3) return []
  const [a, b] = pick(sp)
  const chunk = s.substring(a, b)
  const rest = s.substring(0, a) + s.substring(b)
  const sp2 = paragraphSpans(rest)
  const [t] = pick(sp2)
  return [[a, b - a, ''], [t, 0, chunk.endsWith('\n') ? chunk : chunk + '\n\n']]
}
function editScript(s, n, moves = 0) {
  const script = []
  let cur = s
  for (let i = 0; i < moves; i++) {
    for (const e of moveParagraph(cur)) { script.push(e); cur = apply(cur, [e]) }
  }
  for (let i = 0; i < n; i++) {
    const e = randomEdit(cur)
    if (!e) continue
    script.push(e)
    cur = apply(cur, [e])
  }
  return script
}

// ---------- outputs ----------
function fnv(s) {
  let h = 0x811c9dc5 | 0
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h | 0
}
// Short strings verbatim, long ones as length + FNV-1a over UTF-16 units (the Kotlin test computes the same).
const digest = (s) => (s.length <= 200 ? '=' + s : '#' + s.length + '/' + fnv(s))
const SIGN = { '-1': '-', 1: '+', 0: '=' }
const diffShape = (diffs) => diffs.map(([op, t]) => SIGN[op] + t.length).join(',')
const flags = (arr) => arr.map((b) => (b ? '1' : '0')).join('')

function makeDmp(timeout) {
  const dmp = new DMP()
  dmp.Diff_Timeout = timeout
  return dmp
}

// Token stream: every field is "<UTF-16 length>:<content>".
const tok = (v) => {
  const s = String(v)
  return s.length + ':' + s
}
const scriptTok = (script) => tok(script.length) + script.map(([p, d, ins]) => tok(p) + tok(d) + tok(ins)).join('')

function runCase(name, a, script, a2script, mode) {
  const b = apply(a, script)
  const timeout = mode[0] === 'L' ? LARGE_TIMEOUT : 0
  const checkLines = mode[1] === '1'
  const dmp = makeDmp(timeout)
  const raw = dmp.diff_main(a, b, checkLines)
  if (dmp.diff_text1(raw) !== a || dmp.diff_text2(raw) !== b) throw new Error('bad diff ' + name)
  const rawShape = diffShape(raw)
  dmp.diff_cleanupSemantic(raw)
  const semShape = diffShape(raw)
  const html = dmp.diff_prettyHtml(raw)

  let patchText = '!'
  let applyA = '!'
  let applyA2 = '!'
  try {
    patchText = dmp.patch_toText(dmp.patch_make(a, b))
  } catch (e) {
    if (!(e instanceof URIError)) throw e
  }
  if (patchText !== '!') {
    const patches = dmp.patch_fromText(patchText)
    const [t1, f1] = dmp.patch_apply(patches, a)
    applyA = flags(f1) + '|' + digest(t1)
    const [t2, f2] = dmp.patch_apply(patches, apply(a, a2script))
    applyA2 = flags(f2) + '|' + digest(t2)
  }
  return [tok(name), tok(mode), tok(a), scriptTok(script), tok(rawShape), tok(semShape), tok(digest(html)),
    tok(patchText), tok(applyA), scriptTok(a2script), tok(applyA2)].join('')
}

function driftScript(a) {
  // Small unrelated edits to the base text so patches must be located fuzzily.
  const r = ri(5)
  if (r === 0) return [[0, 0, sentence(3 + ri(20)) + '\n\n']] // everything shifts
  if (r === 1) return editScript(a, 1 + ri(3))
  if (r === 2) return [[0, 0, 'x'.repeat(1 + ri(60))], ...editScript(a, 1)].filter(Boolean)
  if (r === 3) { const p = cpAlign(a, ri(a.length + 1)); return [[p, 0, word()]] }
  return editScript(a, 2 + ri(6))
}

const cases = []
let n = 0
function mode() {
  const r = rnd()
  if (r < 0.65) return 'L1'
  if (r < 0.85) return 'Z1'
  if (r < 0.95) return 'L0'
  return 'Z0'
}
function add(kind, a, script, m = mode()) {
  // driftScript composes edits on the evolving text, so it needs the same sequential apply.
  const a2 = driftScript(a)
  cases.push(runCase(`${kind}-${n++}`, a, script, a2, m))
}

// Small texts, few edits.
for (let i = 0; i < 160; i++) { const a = text(1 + ri(3)); add('small', a, editScript(a, 1 + ri(3))) }
// Medium texts, more edits and moves.
for (let i = 0; i < 120; i++) { const a = text(3 + ri(6)); add('medium', a, editScript(a, 2 + ri(8), ri(2))) }
// Long texts (> 1000 chars): line mode and half-match territory.
for (let i = 0; i < 70; i++) {
  let a = text(10 + ri(15))
  while (a.length < 1100) a += '\n\n' + paragraph()
  add('long', a, editScript(a, 1 + ri(6), ri(3)))
}
// Long texts with a single small change in the middle (half-match), and with heavy rewrites.
for (let i = 0; i < 30; i++) {
  let a = text(12 + ri(10))
  while (a.length < 1200) a += '\n' + paragraph()
  add('halfmatch', a, editScript(a, 1), pick(['L1', 'L0', 'L1']))
}
for (let i = 0; i < 25; i++) { const a = text(6 + ri(6)); add('rewrite', a, editScript(a, 20 + ri(30), 1 + ri(2))) }
// Unicode-heavy texts.
for (let i = 0; i < 45; i++) {
  const w = []
  const k = 20 + ri(80)
  for (let j = 0; j < k; j++) w.push(chance(0.6) ? pick(UNI) : word())
  const a = w.join(chance(0.5) ? ' ' : '\n')
  add('unicode', a, editScript(a, 1 + ri(6)))
}
// Unrelated texts.
for (let i = 0; i < 20; i++) {
  const a = text(1 + ri(4))
  const b = text(1 + ri(4))
  add('unrelated', a, [[0, a.length, b]])
}
// Edge cases.
const edges = [
  ['', ''], ['', 'abc'], ['abc', ''], ['abc', 'abc'], ['a', 'b'], ['abc', 'ab'], ['ab', 'abc'],
  ['🐝', '🎉'], ['😀', '😃'], ['😀😃', '😃😀'], ['x😀y', 'x😃y'], ['\n', '\r\n'], ['a\n\nb', 'a\nb'],
  ['The cat', 'The big cat'], ['1234567890', 'abcdefghij'], ['&<>', '<>&'], ['line1\nline2\n', 'line2\nline1\n'],
  ['%', '%25'], ['a b c', 'a  b  c'], ['  ', '\t'],
]
// Sliding edits next to every character class JS /\s/ does or does not include, so the
// semantic boundary score is pinned for each of them.
for (const w of [' ', '\u00a0', '\u1680', '\u2000', '\u2009', '\u200a', '\u200b', '\u2028', '\u2029', '\u202f',
  '\u205f', '\u3000', '\ufeff', '\u0085', '\u180e', '\u000b', '\u000c', '\t', '.', '\u00e9']) {
  // Templates found by brute force where a whitespace W and a punctuation W slide differently.
  for (const [a, b] of [['aWb', 'aW.Wb'], ['aWb', 'aWa.Wb'], ['aW.', 'aWa.W.'], ['xyWz', 'xyW.Wz']]) {
    edges.push([a.split('W').join(w), b.split('W').join(w)])
  }
}
for (const [a, b] of edges) {
  for (const m of ['L1', 'Z1']) cases.push(runCase(`edge-${n++}`, a, [[0, a.length, b]], [[0, 0, 'drift ']], m))
}

// Hand-written patch texts: decodeURI corner cases, malformed input and odd headers.
const patchCases = []
const ptexts = [
  ['@@ -1,8 +1,9 @@\n abc\n-de\n+%3B%2F%3F%3A%40%26%3D%2B%24%2C%23\n fgh\n', 'abcdefgh'],
  ['@@ -1,5 +1,6 @@\n a%25b\n+%c3%a9\n c\n', 'a%bcxyz'],
  ['@@ -1,3 +1,4 @@\n ab\n+%F0%9F%90%9D\n c\n', 'abc'],
  ['@@ -1 +1 @@\n-a\n+b\n', 'a'],
  ['@@ -0,0 +1,3 @@\n+abc\n', ''],
  ['@@ -1,3 +0,0 @@\n-abc\n', 'abc'],
  ['@@ -1,3 +1,3 @@\n-abc\n+xyz\n\n', 'abc'],
  ['@@ -1,4 +1,4 @@\n a\n-%0A\n+%0D%0A\n b\n', 'a\nb'],
  ['@@ -1,3 +1,3 @@\n-a%ZZ\n', 'abc'],
  ['@@ -1,3 +1,3 @@\n-a%E9\n', 'abc'],
  ['@@ -1,3 +1,3 @@\n-a%C0%80\n', 'abc'],
  ['@@ -1,3 +1,3 @@\n-a%ED%A0%80\n', 'abc'],
  ['@@ -1,3 +1,3 @@\n-a%\n', 'abc'],
  ['@@ -1,3 +1,3 @@\n?abc\n', 'abc'],
  ['@@ -1,3 +1,3 @\n-abc\n', 'abc'],
  ['bad header\n', 'abc'],
  ['@@ -1,3 +1,3 @@\r\n-abc\n', 'abc'],
  ['@@ -2,4 +2,5 @@\n bc\n+X\n de\n@@ -10,3 +11,3 @@\n-jkl\n+JKL\n', 'abcdefghijklmn'],
  ['@@ -1,40 +1,4 @@\n-' + 'abcdefghij'.repeat(4) + '\n+test\n', 'abcdefghij'.repeat(4)],
  ['@@ -1,80 +1,0 @@\n-' + 'abcdefghij'.repeat(8) + '\n', 'abcdefghij'.repeat(8)],
  ['@@ -1,80 +1,0 @@\n-' + 'abcdefghij'.repeat(8) + '\n', 'abcdefghXj'.repeat(8)],
  ['@@ -1,80 +1,0 @@\n-' + 'abcdefghij'.repeat(8) + '\n', 'zyxwvutsrq'.repeat(8)],
  ['@@ -1,11 +1,11 @@\n-quick brown\n+QUICK BROWN\n', 'The quack brewn fox'],
  ['', 'abc'],
]
for (const [pt, target] of ptexts) {
  const dmp = makeDmp(LARGE_TIMEOUT)
  let parsed = '!'
  let applied = '!'
  try {
    const patches = dmp.patch_fromText(pt)
    parsed = dmp.patch_toText(patches)
    const [t, f] = dmp.patch_apply(patches, target)
    applied = flags(f) + '|' + digest(t)
  } catch (e) {
    if (!(e instanceof Error)) throw e
  }
  patchCases.push([tok(pt), tok(target), tok(parsed), tok(applied)].join(''))
}

// ---------- write Kotlin ----------
// Strings become Kotlin literals in chunks well under the JVM 65535 byte constant limit.
function kotlinLiteral(s) {
  let out = ''
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    const ch = s[i]
    if (ch === '\\') out += '\\\\'
    else if (ch === '"') out += '\\"'
    else if (ch === '$') out += '\\$'
    else if (ch === '\n') out += '\\n'
    else if (ch === '\r') out += '\\r'
    else if (ch === '\t') out += '\\t'
    else if (c < 0x20 || c === 0x7f || c === 0x2028 || c === 0x2029 || c === 0xfeff) out += '\\u' + c.toString(16).padStart(4, '0')
    else if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length && s.charCodeAt(i + 1) >= 0xdc00 && s.charCodeAt(i + 1) <= 0xdfff) {
      out += ch + s[i + 1]
      i++
    } else if (c >= 0xd800 && c <= 0xdfff) out += '\\u' + c.toString(16).padStart(4, '0')
    else out += ch
  }
  return '"' + out + '"'
}
function chunks(s, max = 20000) {
  // Split at UTF-16 boundaries that do not cut a surrogate pair; 20000 units is at most 60000
  // modified UTF-8 bytes, below the 65535 limit.
  const out = []
  let i = 0
  while (i < s.length) {
    let e = Math.min(s.length, i + max)
    const c = s.charCodeAt(e - 1)
    if (e < s.length && c >= 0xd800 && c <= 0xdbff) e--
    out.push(s.substring(i, e))
    i = e
  }
  return out
}
function writeObject(file, objName, data, doc) {
  const parts = chunks(data)
  let src = '// GENERATED by native/tools/golden/generate-dmp.mjs from the npm diff-match-patch the RN app ships.\n'
  src += '// Do not edit by hand. Synthetic content only.\n'
  src += 'package com.ecency.core.text\n\n'
  src += `/** ${doc} */\n`
  src += `internal object ${objName} {\n`
  src += `    val data: String get() = listOf(${parts.map((_, i) => `c${i}()`).join(', ')}).joinToString("")\n`
  parts.forEach((p, i) => { src += `\n    private fun c${i}(): String =\n        ${kotlinLiteral(p)}\n` })
  src += '}\n'
  writeFileSync(join(outDir, file), src)
  return Buffer.byteLength(src)
}

// Split the cases across a few files so no single source file or class grows too large.
const FILES = 3
const per = Math.ceil(cases.length / FILES)
let total = 0
for (let f = 0; f < FILES; f++) {
  const slice = cases.slice(f * per, (f + 1) * per)
  total += writeObject(`DmpFixtures${f}.kt`, `DmpFixtures${f}`, tok(slice.length) + slice.join(''),
    `Diff and patch cases ${f * per} until ${f * per + slice.length}, as a length-prefixed token stream.`)
}
total += writeObject('DmpPatchFixtures.kt', 'DmpPatchFixtures', tok(patchCases.length) + patchCases.join(''),
  'Hand-written patch texts (decodeURI corners, malformed input), as a length-prefixed token stream.')
console.log(`${cases.length} diff/patch cases, ${patchCases.length} patch text cases, ${total} bytes of Kotlin`)
