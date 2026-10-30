// Golden vectors for the editor: RN's own src/utils/editor.ts and markdown format helpers, speakingurl, the
// SDK's comment / comment_options builders and RN's signImage, all run offline.
//
// Run from the RN app root (which has node_modules):
//   node --no-warnings native/tools/golden/generate-editor.mjs native/shared/src/commonTest/kotlin/com/ecency/core/editor/EditorGoldenVectors.kt
//
// Nothing here talks to a node or to the image host. Keys derive from the public throwaway seed of generate.mjs.
import { register, createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const out = process.argv[2]
if (!out) throw new Error('usage: generate-editor.mjs <output.kt>')

register(pathToFileURL(join(dirname(fileURLToPath(import.meta.url)), 'rn-hooks.mjs')))
globalThis.__appVersion = '4.0.0'
const log = console.log
console.log = () => {} // the RN sources log freely

const require = createRequire(join(process.cwd(), 'package.json'))
const sdk = await import(require.resolve('@ecency/sdk'))
const { secp256k1 } = await import(require.resolve('@noble/curves/secp256k1.js'))
const getSlug = require('speakingurl')
const rn = (p) => import(join(process.cwd(), 'src', p))
const ed = await rn('utils/editor.ts')
const { b64uEnc } = await rn('utils/b64.ts')
const applyWrap = (await rn('components/markdownEditor/children/formats/applyWrapFormat.ts')).default
const applyList = (await rn('components/markdownEditor/children/formats/applyListFormat.ts')).default
const applyWebLink = (await rn('components/markdownEditor/children/formats/applyWebLinkFormat.ts')).default
const applySnippet = (await rn('components/markdownEditor/children/formats/applySnippet.ts')).default
const applyUsername = (await rn('components/markdownEditor/children/formats/applyUsername.ts')).default
const applyMedia = (await rn('components/markdownEditor/children/formats/applyMediaLink.ts')).default
const formats = (await rn('components/markdownEditor/children/formats/formats.ts')).default
const { isStringWebLink } = await rn('components/markdownEditor/children/formats/utils.ts')
const { sweepUploadingPlaceholders } = await rn('components/markdownEditor/children/formats/sweepUploadingPlaceholders.ts')
const { extractUploadPlaceholderNames } = await rn('components/uploadsGalleryModal/uploadPlaceholder.ts')
const support = await rn('providers/ecency/supportBeneficiary.ts')

const kStr = (s) => JSON.stringify(s).replace(/\$/g, '\\$')
const kNull = (s) => (s === null || s === undefined ? 'null' : kStr(s))
const kList = (a) => `listOf(${a.map(kStr).join(', ')})`
const hex = (b) => Buffer.from(b).toString('hex')
// a list in chunks, each built by its own function (the JVM limits a method to 64 KB)
const chunked = (name, type, lines, size = 40) => {
  const parts = []
  for (let i = 0; i < lines.length; i += size) parts.push(lines.slice(i, i + size))
  return `    val ${name}: List<${type}> by lazy { ${parts.map((_, i) => `${name}${i}()`).join(' + ')} }\n` +
    parts.map((p, i) => `    private fun ${name}${i}(): List<${type}> = listOf(\n${p.join('\n')}\n    )`).join('\n')
}

// seeded generator (mulberry32) so reruns give the same file
let seed = 0x5eed1e
const rand = () => {
  seed |= 0; seed = (seed + 0x6d2b79f5) | 0
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296
}
const pick = (a) => a[Math.floor(rand() * a.length)]

// ---- slugs and permlinks ----
const titles = [
  'Hello World', 'Hello Wörld & Friends: a tale of 5 things!', '  Leading and trailing  ', 'Ünïcödé Tïtle — with dashes – and… dots',
  'Привет, мир! Как дела?', '日本語のタイトル', 'Emoji 🐝 in 🚀 title', '100% pure $$$ & more < less > equal =', 'C++ vs C# | which one?',
  'a-b--c---d', '---', '!!!', '', ' ', 'UPPER lower MiXeD', 'one two three four five six seven eight', "it's a dog's life", 'snake_case_title here',
  'tabs\tand\nnewlines', 'Ελληνικά κείμενο', 'العربية نص', 'Tiếng Việt có dấu', 'Česká řeč žluťoučký kůň', 'München ist schön, Straße & Fluß',
  'မြန်မာ စာ', 'ދިވެހި', '√ ∑ ∞ ♥ ♀ ♂ © ® ™ € £ ¥ ¢', 'a∞b', '∞∞', 'x & y', '&&', 'price: 5€', 'a.b.c', 'a/b/c', 'ǅ ǆ Ǌ', 'Ĳsselmeer œuvre Æther',
  'x'.repeat(300), 'word '.repeat(80), 'İstanbul ığdır', 'ß', 'ẞ', 'Ⅻ ½ ²', 'null undefined NaN', 'title with lone \ud83d surrogate',
]
const pool = ['a', 'B', '1', ' ', '-', '_', '&', '%', 'ö', 'é', 'я', 'ж', '日', '🐝', '.', ',', '!', '∞', '♥', '€', 'မ', 'ြ', 'န', '်', 'ް', 'ދ', 'ß', 'İ', '\t', '<', '$']
for (let i = 0; i < 300; i++) {
  let t = ''
  const n = 1 + Math.floor(rand() * 24)
  for (let j = 0; j < n; j++) t += pick(pool)
  titles.push(t)
}
const RND = '8f5c28f5c28f6'
Math.random = () => 0.56 // generateRndStr: (0.56 + 1).toString(16).substring(2)
if (ed.generateRndStr() !== RND) throw new Error('unexpected generateRndStr: ' + ed.generateRndStr())
const slugVectors = []
for (const t of titles) {
  let slug
  try { slug = getSlug(t) } catch { continue } // speakingurl throws on a few dangling diacritics; RN's publish fails there too
  slugVectors.push(`        SlugVector(${kStr(t)}, ${kStr(slug)}, ${kStr(ed.generatePermlink(t))}, ${kStr(ed.generatePermlink(t, true))}),`)
}

// ---- text formats ----
const run = async (fn, args) => {
  let result = null
  await fn({ ...args, setTextAndSelection: (r) => { result = r } })
  return result
}
const texts = [
  '', 'hello', 'hello world', 'line one\nline two\n', '\n', 'see https://ecency.com/@a/b here', 'https://ecency.com', 'ü 🐝 text', 'a\r\nb',
  'first\n\nsecond paragraph with words, commas, and @mention here', '@al', 'hi @ali', 'x'.repeat(120) + ' @bob tail ' + 'y'.repeat(120),
]
const selections = (t) => {
  const l = t.length
  const s = new Map()
  for (const [a, b] of [[0, 0], [l, l], [0, l], [Math.floor(l / 2), Math.floor(l / 2)], [1, Math.max(1, l - 1)], [Math.floor(l / 3), Math.floor((2 * l) / 3)]]) {
    if (a <= l && b <= l && a <= b) s.set(`${a},${b}`, { start: a, end: b })
  }
  return [...s.values()]
}
const fmt = []
const edit = (r) => (r ? `TextEdit(${kStr(r.text)}, TextSelection(${r.selection.start}, ${r.selection.end}))` : 'null')
const [bold, italic, table] = formats
if (bold.wrapper !== '**' || italic.wrapper !== '*' || table.key !== 'L') throw new Error('formats.ts changed')
for (const t of texts) {
  for (const sel of selections(t)) {
    const S = `TextSelection(${sel.start}, ${sel.end})`
    const T = kStr(t)
    fmt.push(`        FormatVector("bold", { EditorFormats.wrap(${T}, ${S}, EditorFormats.BOLD) }, ${edit(await run(applyWrap, { text: t, selection: sel, item: bold }))}),`)
    fmt.push(`        FormatVector("italic", { EditorFormats.wrap(${T}, ${S}, EditorFormats.ITALIC) }, ${edit(await run(applyWrap, { text: t, selection: sel, item: italic }))}),`)
    fmt.push(`        FormatVector("table", { EditorFormats.list(${T}, ${S}, EditorFormats.TABLE) }, ${edit(await run(applyList, { text: t, selection: sel, item: table }))}),`)
    fmt.push(`        FormatVector("link", { EditorFormats.webLink(${T}, ${S}) }, ${edit(await run(formats[3].onPress, { text: t, selection: sel }))}),`)
    for (const [label, url, img, vid] of [['Label', 'https://x.io/a.png', false, false], ['', 'https://x.io/v', false, true], ['a.png', 'https://x.io/a.png', true, false], ['', 'https://x.io/raw', false, false]]) {
      const r = await run(applyWebLink, { text: t, selection: sel, item: { text: label, url }, isImage: img, isVideo: vid })
      fmt.push(`        FormatVector("weblink", { EditorFormats.webLink(${T}, ${S}, ${kStr(label)}, ${kStr(url)}, ${img}, ${vid}) }, ${edit(r)}),`)
    }
    fmt.push(`        FormatVector("snippet", { EditorFormats.snippet(${T}, ${S}, "\\nSNIP \\$ body\\n") }, ${edit(await run(applySnippet, { text: t, selection: sel, snippetText: '\nSNIP $ body\n' }))}),`)
    if (sel.start === sel.end) {
      fmt.push(`        FormatVector("username", { EditorFormats.username(${T}, ${S}, "alice.b") }, ${edit(await run(applyUsername, { text: t, selection: sel, username: 'alice.b' }))}),`)
    }
  }
}
const wordVectors = []
for (const t of texts) for (let i = 0; i <= t.length; i += Math.max(1, Math.floor(t.length / 9))) wordVectors.push(`        Triple(${kStr(t)}, ${i}, ${kStr(ed.extractWordAtIndex(t, i))}),`)

// insert link sheet: applyWebLinkFormat over an empty text (insertLinkModal)
const linkVectors = []
for (const [label, url, type] of [['My label', 'https://ecency.com/x', 0], ['', 'https://ecency.com/x', 0], ['ignored', 'https://3speak.tv/embed?v=a/b', 1], ['ignored', 'https://img.io/path/pic.png', 2], ['', 'https://img.io', 2]]) {
  const labelText = type === 2 ? url.split('/').pop() : type === 1 ? '' : label
  const r = await run(applyWebLink, { text: '', selection: { start: 0, end: 0 }, item: { text: labelText, url }, isImage: type === 2, isVideo: type === 1 })
  linkVectors.push(`        LinkVector(${kStr(label)}, ${kStr(url)}, EditorFormats.LinkType.${['PLAIN', 'VIDEO', 'IMAGE'][type]}, ${kStr(r.text)}),`)
}

const webLinks = ['https://ecency.com', 'http://a.b', 'ftp://files.example.org/x', 'https://ecency.com/@a/b?x=1#y', 'ecency.com', 'https://', 'https://localhost', 'https://10.0.0.1', 'https://8.8.8.8:8080/x',
  'HTTPS://ECENCY.COM', 'https://ecency.com with space', ' https://ecency.com', 'https://пример.рф/путь', 'https://a.b.c.d.example.co.uk.', 'https://user:pass@example.com', 'https://192.168.1.1', 'https://223.255.255.254', 'https://exa_mple.com', 'javascript:alert(1)', '']
const webLinkVectors = webLinks.map((u) => `        ${kStr(u)} to ${isStringWebLink(u)},`)

// media inserts
const mediaBodies = [
  '', 'intro text', 'a\n![](Uploading... a.jpg)\nb', 'a\n![](Uploading... a.jpg)\n\n![](Uploading... b (1).jpg)\nz', 'x ![alt](Uploading... IMG (1).jpg) y',
  '![](Uploading...nospace.jpg) own text', 'a\r\n![](Uploading... a.jpg)\r\nb', '![](Uploading... a.jpg)', 'before\n![](Uploading... a.jpg2)\nafter', '![](Uploading... edited',
]
const U = 'UPLOADING', R = 'READY', F = 'FAILED'
const mediaBatches = [
  [[{ filename: 'a.jpg', url: '', text: '', status: U }], []],
  [[{ filename: 'a.jpg', url: '', text: '', status: U }, { filename: 'b (1).jpg', url: '', text: '', status: U }], []],
  [[{ filename: 'a.jpg', url: 'https://img.io/a.jpg', text: '', status: R }], []],
  [[{ filename: 'a.jpg', url: 'https://img.io/a.jpg', text: '', status: R }], ['b (1).jpg']],
  [[{ filename: 'zzz.jpg', url: 'https://img.io/z.jpg', text: '', status: R }], []],
  [[{ filename: 'zzz.jpg', url: 'https://img.io/z.jpg', text: '', status: R }], ['other.jpg']],
  [[{ filename: 'a.jpg', url: '', text: '', status: F }], []],
  [[{ filename: 'zzz.jpg', url: '', text: '', status: F }], []],
  [[{ filename: 'b (1).jpg', url: 'https://img.io/b.jpg', text: '', status: R }, { filename: 'a.jpg', url: '', text: '', status: F }], []],
  [[{ url: 'https://img.io/gallery.png', text: '', status: R }], []],
  [[{ url: 'https://3speak.tv/embed?v=a/b', text: '', status: R, mode: 1 }], []],
  [[{ url: '', text: '', status: R }], []],
]
const kItem = (i) => `MediaInsert(${kStr(i.url)}, ${kNull(i.filename)}, ${kStr(i.text)}, MediaInsertStatus.${i.status}, ${i.mode === 1})`
const mediaVectors = []
for (const body of mediaBodies) {
  for (const sel of selections(body)) {
    for (const [items, pending] of mediaBatches) {
      const r = await run(applyMedia, { text: body, selection: sel, items: items.map((i) => ({ ...i })), otherPending: pending })
      mediaVectors.push(`        MediaVector(${kStr(body)}, TextSelection(${sel.start}, ${sel.end}), listOf(${items.map(kItem).join(', ')}), ${kList(pending)}, ${edit(r)}),`)
    }
  }
}
const sweepVectors = []
for (const body of [...mediaBodies, 'a\n![](Uploading... a.jpg)\n![](Uploading... b.jpg)\nb', '![x](Uploading... a.jpg)\n\n\n![y](Uploading... b.jpg)']) {
  for (const caret of [undefined, 0, Math.floor(body.length / 2), body.length]) {
    const r = sweepUploadingPlaceholders(body, caret)
    sweepVectors.push(`        SweepVector(${kStr(body)}, ${caret ?? 'null'}, ${kStr(r.text)}, ${r.caret ?? 'null'}, ${kList(extractUploadPlaceholderNames(body))}),`)
  }
}

// ---- urls ----
const bodies = [
  '', 'no links here', 'one https://ecency.com/@alice/post-one and http://x.io/pic.PNG.', 'https://img.io/a.jpg https://img.io/b.jpeg?x=1 https://img.io/c.gif, https://img.io/d.webp) ![](https://img.io/e.heic)',
  'xhttps://glued.io/a ahttp://b.c ftp://files.io/x.png FTP://UP.IO/Y https://', '<a href="https://ecency.com/hive-125125/@bob/post-two">x</a> (https://peakd.com/@carol/three)',
  'https://3speak.tv/embed?v=alice/abc and https://play.3speak.tv/embed/xyz', 'https://3speak.tv/watch?v=a/b https://not3speak.tv/embed?v=a http://a.b.3speak.tv/embed/', 'https://3SPEAK.TV/EMBED?v=x',
  'https://x.io/path/with.png/inside https://y.io/z.pngx http://png.io/.png', 'trailing https://ecency.com/@a/b... and https://ecency.com/@a/c!', 'unicode https://пример.рф/a.png https://ex.io/путь.jpg',
  'many ' + Array.from({ length: 14 }, (_, i) => `https://img.io/${i}.png https://ecency.com/@u${i}/p${i}`).join(' '),
]
const urlVectors = bodies.map((b) => `        UrlVector(${kStr(b)}, ${kList(ed.extractUrls(b))}, ${kList(ed.extractImageUrls({ body: b }))}, ${sdk.hasThreeSpeakEmbed(b)}, ${ed.getWordsCount(b)}),`)

// ---- metadata ----
const POSTS = { 'alice/post-one': { title: 'Post "one"', summary: 'Sum', image: 'https://img.io/one.png' }, 'bob/post-two': { title: 'Two' }, 'carol/three': null }
globalThis.__posts = (key) => {
  // SDK query key: ['posts', 'entry', '/@author/permlink']
  const path = String(key[key.length - 1]).replace(/^\/@/, '')
  if (!(path in POSTS)) return new Error('fetch failed')
  return POSTS[path]
}
const RATIOS = { 'https://img.io/a.jpg': [1200, 800], 'https://img.io/b.jpeg?x=1': [1, 3], 'https://img.io/c.gif': [640, 640], 'https://img.io/0.png': [16, 9], 'https://img.io/3.png': [1000, 333] }
globalThis.__imageSizes = RATIOS
const poll = (o = {}) => ({
  title: ' Best bee? ', choices: ['Honey', 'Bumble "B"'], endTime: '2026-10-19T12:00:00.783Z', voteChange: false, hideVotes: false, hideResults: true,
  interpretation: 'number_of_votes', maxChoicesVoted: 1, communityMembership: [], filters: { accountAge: 100 }, ...o,
})
const kPoll = (p) => p ? `PollDraft(${kStr(p.title)}, ${kList(p.choices)}, ${kStr(p.endTime)}, ${p.voteChange}, ${p.hideVotes}, ${p.hideResults}, ${kStr(p.interpretation)}, ${p.maxChoicesVoted}, ${kNull(p.token)}, ${kList(p.communityMembership)}, ${p.filters.accountAge})` : 'null'
const metaCases = [
  { body: bodies[0], tags: [] },
  { body: bodies[1], tags: ['hive-125125', 'ecency'] },
  { body: bodies[2], tags: ['a'], fetchRatios: true },
  { body: bodies[3], tags: ['photo'], fetchRatios: true, thumbUrl: 'https://img.io/c.gif' },
  { body: bodies[3], tags: ['photo'], fetchRatios: false, thumbUrl: 'https://img.io/not-in-body.png', videoThumbUrls: ['https://thumb.io/v1.jpg'] },
  { body: bodies[5], tags: ['links'], fetchRatios: true },
  { body: bodies[12], tags: ['many'], fetchRatios: true, thumbUrl: 'https://img.io/7.png' },
  { body: bodies[12], tags: ['many'], fetchRatios: true, thumbUrl: 'https://img.io/13.png', videoThumbUrls: ['https://thumb.io/v1.jpg'] },
  { body: 'poll post https://img.io/a.jpg', tags: ['poll'], fetchRatios: true, pollDraft: poll() },
  { body: 'poll post', tags: ['poll'], pollDraft: poll({ interpretation: 'tokens', token: 'HIVE:HP', voteChange: true, hideVotes: true, hideResults: false, maxChoicesVoted: 3, filters: { accountAge: 0 }, endTime: '2026-10-19T14:00:00+02:00' }) },
  { body: 'empty poll title is no poll', tags: ['poll'], pollDraft: poll({ title: '' }) },
  { body: 'a reply https://ecency.com/@alice/post-one', tags: ['parent', 'tags'], fetchRatios: true, postType: 'comment' },
  { body: 'edit keeps type', tags: ['x'], fetchRatios: true, postType: 'wave', contentType: 'poll' },
  { body: 'tricky "quotes" \\ back\nline 🐝 https://img.io/a.jpg', tags: ['tag "q"', 'é'], fetchRatios: true },
]
const metaVectors = []
for (const c of metaCases) {
  const meta = await ed.extractMetadata({ body: c.body, thumbUrl: c.thumbUrl, videoThumbUrls: c.videoThumbUrls, fetchRatios: !!c.fetchRatios, postType: c.postType, contentType: c.contentType, pollDraft: c.pollDraft })
  const json = JSON.stringify(ed.makeJsonMetadata(meta, c.tags))
  metaVectors.push(`        MetaVector(${kStr(c.body)}, ${kNull(c.thumbUrl)}, ${kList(c.videoThumbUrls || [])}, ${!!c.fetchRatios}, ${kNull(c.postType)}, ${kNull(c.contentType)}, ${kPoll(c.pollDraft)}, ${kList(c.tags)}, ${kStr(json)}),`)
}
// edits: makeJsonMetadataForUpdate over the post's own metadata
const oldJsons = [
  { tags: ['old'], app: 'peakd/2024.1', format: 'markdown', image: ['https://old.io/x.png'], custom: { keep: true }, description: 'old desc' },
  { app: 'ecency/3.5.0-mobile', format: 'markdown+html', tags: ['a', 'b'], type: 'post', links: [], meta: { nested: 'value', image: ['https://nested.io/n.png'] }, ai_tools: { writing_edit: true } },
  { '2': 'two', b: 1, '1': 'one', tags: ['x'], content_type: 'poll', question: 'Q?', choices: ['a', 'b'], type: 'post' },
  {},
]
const updateVectors = []
for (const old of oldJsons) {
  for (const c of [metaCases[2], metaCases[3], metaCases[1]]) {
    const meta = await ed.extractMetadata({ body: c.body, thumbUrl: c.thumbUrl, fetchRatios: true, postType: old.type, contentType: old.content_type })
    const json = JSON.stringify(ed.makeJsonMetadataForUpdate(JSON.parse(JSON.stringify(old)), meta, c.tags))
    updateVectors.push(`        UpdateVector(${kStr(JSON.stringify(old))}, ${kStr(c.body)}, ${kNull(c.thumbUrl)}, ${kList(c.tags)}, ${kStr(json)}),`)
  }
}
// the thumbnail sort (an inconsistent comparator: whatever V8's sort leaves)
const sortVectors = []
for (const n of [1, 2, 3, 4, 5, 7, 10, 16, 22, 31, 32, 33, 47, 63]) {
  for (const at of [...new Set([0, 1, Math.floor(n / 2), n - 2, n - 1, -1])].filter((i) => i >= -1 && i < n)) {
    const items = Array.from({ length: n }, (_, i) => `i${i}`)
    const thumb = at < 0 ? 'missing' : items[at]
    const sorted = [...items].sort((item) => (item === thumb ? -1 : 1))
    sortVectors.push(`        SortVector(${n}, ${at}, ${kStr(sorted.map((s) => s.slice(1)).join(','))}),`)
  }
}
// edit bodies: the patch or the full body
const editPairs = [
  ['The quick brown fox jumps over the lazy dog. '.repeat(20), 'The quick brown fox jumps over the lazy dog. '.repeat(10) + 'A small change. ' + 'The quick brown fox jumps over the lazy dog. '.repeat(10)],
  ['short', 'completely different text that is much longer than the old one'],
  ['same body', 'same body'], ['same body', '  same body  \n'], ['', 'new body'], ['Привет мир 🐝 '.repeat(30), 'Привет мир 🐝 '.repeat(30) + 'ещё'],
  ['line one\nline two\nline three\n'.repeat(15), 'line one\nline 2\nline three\n'.repeat(15)], ['a'.repeat(400), 'a'.repeat(399) + 'b '],
  // the patch is longer than the old body in UTF-16 units but shorter than it in UTF-8 bytes: still a patch
  ['🐝'.repeat(30) + ' abc', '🐝'.repeat(30) + ' a rather different ascii ending for it'],
]
{
  const [o, n] = editPairs[editPairs.length - 1]
  const p = ed.createPatch(o, n.trim())
  if (!(p.length > o.length && p.length < Buffer.from(o, 'utf-8').length)) throw new Error('edit pair no longer separates units from bytes: ' + p.length + ' ' + o.length)
}
const editVectors = editPairs.map(([oldBody, newBody]) => {
  let body = newBody
  const patch = ed.createPatch(oldBody, newBody.trim())
  if (patch && patch.length < Buffer.from(oldBody, 'utf-8').length) body = patch
  return `        EditVector(${kStr(oldBody)}, ${kStr(newBody)}, ${kStr(body)}, ${body !== newBody}),`
})

// ---- beneficiaries ----
const routes = (a) => a.map(([account, weight]) => ({ account, weight }))
const kRoutes = (a) => `listOf(${a.map((r) => `BeneficiaryRoute(${kStr(r.account)}, ${r.weight})`).join(', ')})`
const benLists = [[], [['bob', 500]], [['ecency', 300], ['bob', 500]], [['Ecency', 100]], [['a', 1000], ['b', 1000], ['c', 1000], ['d', 1000], ['e', 1000], ['f', 1000], ['g', 1000], ['h', 500]], [['a', 9600]], [['threespeakfund', 500], ['bob', 100]], [['threespeakfund', 1100]]]
const supportVectors = []
for (const list of benLists) {
  for (const percent of [0, 1, 5, 2.5, 25, NaN, -1]) {
    supportVectors.push(`        SupportVector(${kRoutes(routes(list))}, ${Number.isNaN(percent) ? 'Double.NaN' : percent.toFixed(1)}, ${kRoutes(support.injectEcencySupportBeneficiary(routes(list), percent))}),`)
  }
}
const speakVectors = []
for (const list of benLists) {
  for (const body of ['plain', bodies[6]]) speakVectors.push(`        SpeakVector(${kRoutes(routes(list))}, ${kStr(body)}, ${kRoutes(sdk.enforceThreeSpeakBeneficiary(routes(list), body))}),`)
}

// ---- operations ----
const { HiveTxTransaction, PrivateKey, buildCommentOp, buildCommentOptionsOp, sha256 } = sdk
const key = PrivateKey.fromSeed('ecency-native-spike-golden-vector-throwaway')
const HEADER = { ref_block_num: 12345, ref_block_prefix: 3367463123, expiration: '2026-10-06T12:00:00' }
// the op building lambda of the SDK's useComment (minified there): comment, then comment_options when options are given
const useCommentSource = sdk.useComment.toString()
for (const marker of ['"1000000.000 HBD"', 'localeCompare', 'beneficiaries']) if (!useCommentSource.includes(marker)) throw new Error('useComment changed: ' + marker)
const useCommentOps = (n) => {
  const ops = [buildCommentOp(n.author, n.permlink, n.parentAuthor, n.parentPermlink, n.title, n.body, n.jsonMetadata)]
  if (n.options) {
    const { maxAcceptedPayout = '1000000.000 HBD', percentHbd = 1e4, allowVotes = true, allowCurationRewards = true, beneficiaries = [] } = n.options
    const ext = []
    if (beneficiaries.length > 0) {
      const sorted = [...beneficiaries].sort((f, g) => f.account.localeCompare(g.account))
      ext.push([0, { beneficiaries: sorted.map((f) => ({ account: f.account, weight: f.weight })) }])
    }
    ops.push(buildCommentOptionsOp(n.author, n.permlink, maxAcceptedPayout, percentHbd, allowVotes, allowCurationRewards, ext))
  }
  return ops
}
const sign = (ops) => {
  const tx = new HiveTxTransaction({ transaction: { ...HEADER, operations: ops, extensions: [] } })
  const { digest, txId } = tx.digest()
  const rv = secp256k1.sign(digest, key.key, { extraEntropy: false, format: 'recovered', prehash: false })
  return { txId, signature: (rv[0] + 31).toString(16) + hex(rv.subarray(1)) }
}
const AUTHOR = 'spike-voter'
const postCases = []
const benSets = [[], [['bob', 500]], [['zed', 100], ['bob', 500], ['alice.x', 250], ['a-b', 1], ['ab', 2], ['a1', 3], ['bob', 900]], [['ecency', 500], ['threespeakfund', 1100]]]
for (const reward of ['default', 'sp', 'dp']) {
  for (const bens of benSets) {
    const title = `Golden post ${reward} ${bens.length}`
    const body = 'Body with "quotes", a link https://ecency.com/@alice/post-one and an image https://img.io/a.jpg\n\nSecond 🐝 paragraph.'
    const tags = bens.length === 1 ? [] : ['hive-125125', 'ecency', ' ', 'golden']
    const meta = await ed.extractMetadata({ body, fetchRatios: true })
    const _tags = tags.filter((tag) => tag && tag !== ' ')
    const jsonMeta = ed.makeJsonMetadata(meta, _tags)
    const permlink = ed.generatePermlink(title)
    // RN _submitPost: makeOptions, then the mutation payload read back from it
    const options = ed.makeOptions({ author: AUTHOR, permlink, operationType: reward, beneficiaries: routes(bens) })
    const ops = useCommentOps({
      author: AUTHOR, permlink, parentAuthor: '', parentPermlink: _tags[0] || 'hive-125125', title, body, jsonMetadata: jsonMeta,
      options: {
        maxAcceptedPayout: options.max_accepted_payout, percentHbd: options.percent_hbd, allowVotes: options.allow_votes, allowCurationRewards: options.allow_curation_rewards,
        beneficiaries: Array.isArray(options.extensions?.[0]?.[1]?.beneficiaries) ? options.extensions[0][1].beneficiaries : routes(bens),
      },
    })
    const s = sign(ops)
    postCases.push(`        PostVector(${kStr(title)}, ${kStr(body)}, ${kList(tags)}, RewardType.${{ default: 'DEFAULT', sp: 'POWER_UP', dp: 'DECLINE' }[reward]}, ${kRoutes(routes(bens))}, ${kStr(permlink)}, ${kStr(JSON.stringify(ops))}, ${kStr(JSON.stringify(options))}, ${kStr(s.txId)}, ${kStr(s.signature)}),`)
  }
}
// an edit: one comment op, no options
const editOps = useCommentOps({ author: AUTHOR, permlink: 'golden-post', parentAuthor: '', parentPermlink: 'hive-125125', title: 'Edited', body: '@@ -1,4 +1,4 @@\n-old \n+new \n', jsonMetadata: { tags: ['hive-125125'], app: 'peakd/1', format: 'markdown' } })
const editSig = sign(editOps)

// ---- signImage (providers/hive/hive.ts) with the throwaway key ----
const signImage = (name, nowMs, deterministic) => {
  const message = { signed_message: { type: 'posting', app: 'ecency.app' }, authors: [name], timestamp: Math.floor(nowMs / 1000) }
  const hash = sha256(JSON.stringify(message))
  let signature
  if (deterministic) {
    const rv = secp256k1.sign(hash, key.key, { extraEntropy: false, format: 'recovered', prehash: false })
    signature = (rv[0] + 31).toString(16) + hex(rv.subarray(1))
  } else {
    signature = key.sign(hash).toString() // as RN: PrivateKey.fromString(key).sign(hash).toString()
  }
  message.signatures = [signature]
  return b64uEnc(JSON.stringify(message))
}
const NOW = 1791288000123
const signVectors = [['spike-voter', NOW], ['a.b-c', 1759800000999], ['spike-voter', 1000]].map(([n, t]) => `        SignVector(${kStr(n)}, ${t}L, ${kStr(signImage(n, t, true))}, ${kStr(signImage(n, t, false))}),`)

writeFileSync(out, `// GENERATED by native/tools/golden/generate-editor.mjs from the RN app's sources, speakingurl and @ecency/sdk. Do not edit by hand.
// All keys derive from public throwaway seeds and control no funds.
package com.ecency.core.editor

data class SlugVector(val title: String, val slug: String, val permlink: String, val randomPermlink: String)
class FormatVector(val name: String, val run: () -> TextEdit, val expected: TextEdit?)
data class LinkVector(val label: String, val url: String, val type: EditorFormats.LinkType, val expected: String)
data class MediaVector(val body: String, val selection: TextSelection, val items: List<MediaInsert>, val otherPending: List<String>, val expected: TextEdit?)
data class SweepVector(val body: String, val caret: Int?, val text: String, val newCaret: Int?, val names: List<String>)
data class UrlVector(val body: String, val urls: List<String>, val images: List<String>, val threeSpeak: Boolean, val words: Int)
data class MetaVector(
    val body: String, val thumbUrl: String?, val videoThumbUrls: List<String>, val fetchRatios: Boolean, val postType: String?, val contentType: String?,
    val poll: PollDraft?, val tags: List<String>, val json: String,
)
data class UpdateVector(val oldJson: String, val body: String, val thumbUrl: String?, val tags: List<String>, val json: String)
data class SortVector(val size: Int, val thumbAt: Int, val order: String)
data class EditVector(val oldBody: String, val newBody: String, val body: String, val isPatch: Boolean)
data class SupportVector(val list: List<BeneficiaryRoute>, val percent: Double, val expected: List<BeneficiaryRoute>)
data class SpeakVector(val list: List<BeneficiaryRoute>, val body: String, val expected: List<BeneficiaryRoute>)
data class PostVector(
    val title: String, val body: String, val tags: List<String>, val reward: RewardType, val beneficiaries: List<BeneficiaryRoute>,
    val permlink: String, val opsJson: String, val optionsJson: String, val txId: String, val signature: String,
)
data class SignVector(val account: String, val nowMillis: Long, val token: String, val sdkSignedToken: String)

object EditorGoldenVectors {
    const val APP_VERSION = "4.0.0"
    const val RANDOM = ${kStr(RND)}
    const val AUTHOR = ${kStr(AUTHOR)}

    /** Linked posts by "author/permlink": a value, null (not found), or absent (the fetch fails). */
    val posts: Map<String, LinkedPost?> = mapOf(
${Object.entries(POSTS).map(([k, v]) => `        ${kStr(k)} to ${v ? `LinkedPost(${kNull(v.title)}, ${kNull(v.summary)}, ${kNull(v.image)})` : 'null'},`).join('\n')}
    )

    /** Image sizes (width, height) by url; anything else has no size. */
    val sizes: Map<String, Pair<Int, Int>> = mapOf(
${Object.entries(RATIOS).map(([k, v]) => `        ${kStr(k)} to (${v[0]} to ${v[1]}),`).join('\n')}
    )

${chunked('slugs', 'SlugVector', slugVectors)}

${chunked('formats', 'FormatVector', fmt)}

${chunked('words', 'Triple<String, Int, String>', wordVectors)}

    val links = listOf(
${linkVectors.join('\n')}
    )

    val webLinks = listOf(
${webLinkVectors.join('\n')}
    )

${chunked('media', 'MediaVector', mediaVectors)}

${chunked('sweeps', 'SweepVector', sweepVectors)}

    val urls = listOf(
${urlVectors.join('\n')}
    )

    val metas = listOf(
${metaVectors.join('\n')}
    )

    val updates = listOf(
${updateVectors.join('\n')}
    )

    val sorts = listOf(
${sortVectors.join('\n')}
    )

    val edits = listOf(
${editVectors.join('\n')}
    )

    val support = listOf(
${supportVectors.join('\n')}
    )

    val speak = listOf(
${speakVectors.join('\n')}
    )

    val newPosts = listOf(
${postCases.join('\n')}
    )

    const val EDIT_OPS_JSON = ${kStr(JSON.stringify(editOps))}
    const val EDIT_TX_ID = ${kStr(editSig.txId)}
    const val EDIT_SIGNATURE = ${kStr(editSig.signature)}

    val signs = listOf(
${signVectors.join('\n')}
    )
}
`)
log(`slugs ${slugVectors.length}, formats ${fmt.length}, media ${mediaVectors.length}, metas ${metaVectors.length}, posts ${postCases.length}`)
