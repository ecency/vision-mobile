// Golden vectors for waves and shorts, from the @ecency/sdk the RN app ships and from the RN app's own
// composer helpers (copied below word for word, with the file they come from). Run from the RN app root:
//   node native/tools/golden/generate-waves.mjs native/shared/src/commonTest/kotlin/com/ecency/core/api/waves/WavesGoldens.kt
//
// Offline: fetch is replaced by a recorder. Synthetic accounts and values only; the key derives from a
// public throwaway seed and controls nothing.
import { createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

const require = createRequire(join(process.cwd(), 'package.json'))
const sdk = await import(require.resolve('@ecency/sdk'))
const CryptoJS = require('crypto-js')
const { secp256k1 } = await import(require.resolve('@noble/curves/secp256k1.js'))
const { HiveTxTransaction, PrivateKey, Signature } = sdk

const out = process.argv[2]
if (!out) throw new Error('usage: generate-waves.mjs <output.kt>')
const k = (s) => (s === null || s === undefined ? 'null' : JSON.stringify(s).replace(/\$/g, '\\$'))
const hex = (b) => Buffer.from(b).toString('hex')

// Every helper below is copied from the RN app (ecency/ecency-mobile) at commit cbcca86791; the section headers name
// the file. Re-copy them when the RN source changes, then regenerate.

// ---- RN src/utils/editor.ts @ cbcca86791: extractUrls, extractImageUrls, generateContentBasedPermlink, makeJsonMetadata,
//      extractMetadata (its two network reads, linked posts and image sizes, are handed in) ----
const extractUrls = (body) => {
  const urlReg = /(\b(https?|ftp):\/\/[A-Z0-9+&@#/%?=~_|!:,.;-]*[-A-Z0-9+&@#/%=~_|])/gim
  const mUrls = body && body.match(urlReg)
  return mUrls || []
}
const extractImageUrls = ({ body, urls }) => {
  const imgReg = /(https?:\/\/.*\.(?:png|jpg|jpeg|gif|heic|webp))/gim
  const imgUrls = []
  const mUrls = urls || extractUrls(body || '')
  mUrls.forEach((url) => {
    const isImage = url.match(imgReg)
    if (isImage) imgUrls.push(url)
  })
  return imgUrls
}
const generateContentBasedPermlink = (prefix, contentKey) => {
  if (!prefix) return ''
  const hash = CryptoJS.SHA256(contentKey).toString(CryptoJS.enc.Hex).slice(0, 16)
  return `${prefix}-${hash}`
}
const makeJsonMetadata = (meta, tags, appVersion) =>
  Object.assign({}, meta, { tags, app: `ecency/${appVersion}-mobile`, format: 'markdown+html' })
// extractMetadata({ body, videoThumbUrls, fetchRatios: true, postType: 'wave' }) with the two network
// reads handed in: `linked` is what getPostQueryOptions(author, permlink, '') answered per post link (the
// raw entry, so only `title` survives JSON.stringify; null when the post is unknown) and `ratios` is what
// Image.getSize gave per image (NaN on failure, which JSON.stringify writes as null).
const extractMetadata = ({ body, videoThumbUrls, linked, ratios }) => {
  let out = { content_type: undefined }
  const mUrls = extractUrls(body)
  const mImageUrls = extractImageUrls({ body, urls: mUrls })
  const matchedImages = [...mImageUrls, ...(videoThumbUrls || [])]
  const filteredUrls = mUrls.filter((url) => !mImageUrls.includes(url)).slice(0, 5)
  out.links = filteredUrls
  filteredUrls.forEach((url) => {
    if (!(url in linked)) return
    const linkedPost = linked[url]
    out.links_meta = {
      ...(out.links_meta || {}),
      [url]: linkedPost ? { title: linkedPost.title, summary: linkedPost.summary, image: linkedPost.image } : null,
    }
  })
  if (matchedImages.length) out.image = matchedImages.slice(0, 10)
  if (out.image) out.image_ratios = out.image.slice(0, 5).map((url) => (url in ratios ? ratios[url] : NaN))
  out.type = 'wave'
  return out
}
// ---- RN src/utils/extractHashTags.ts @ cbcca86791 ----
const extractHashTags = (text) => {
  const pattern = /#(\w+)/g
  const hashtags = []
  let match
  while ((match = pattern.exec(text)) !== null) hashtags.push(match[1])
  return hashtags
}
// ---- RN src/screens/waves/children/wavesReelItem.tsx @ cbcca86791: sanitizeCaptionText, buildReelCaption ----
function sanitizeCaptionText(value) {
  return value
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[[^\]]*\]\((?:https?:\/\/)?(?:play\.)?3speak[^)]*\)/gi, ' ')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/<[^>]+>/g, ' ')
    .replace(/https?:\/\/\S+/gi, ' ')
    .replace(/▶️?/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}
function buildReelCaption(item) {
  const caption = sanitizeCaptionText(item.title ?? '') || sanitizeCaptionText(item.body ?? '')
  return caption.slice(0, 140)
}
// ---- RN src/components/wavesTagPickerModal/wavesTagPickerModal.tsx @ cbcca86791: normalizeTag ----
const normalizeTag = (raw) => (raw || '').trim().toLowerCase().replace(/^#+/, '').replace(/[^a-z0-9-]/g, '')
// ---- RN src/utils/postUrlParser.ts @ cbcca86791: WAVE_COMPOSE_TEXT_MAX, parseWavesComposeUrl ----
const WAVE_COMPOSE_TEXT_MAX = 2000
const parseWavesComposeUrl = (url) => {
  if (!url) return null
  let parsed
  try {
    parsed = new URL(url.replace(/^(ecency|esteem):\/\//i, 'https://ecency.com/'))
  } catch (e) {
    return null
  }
  if (
    !/^https?:$/i.test(parsed.protocol) || parsed.port !== '' ||
    !/^(?:www\.)?(?:ecency\.com|esteem\.app|estm\.to)$/i.test(parsed.hostname) || !/^\/waves\/?$/i.test(parsed.pathname)
  ) return null
  const text = (parsed.searchParams.get('text') || '').trim()
  return { text: Array.from(text).slice(0, WAVE_COMPOSE_TEXT_MAX).join('') }
}

// ---- feed requests: the SDK's own query functions with fetch recorded ----
const calls = []
let answer = []
globalThis.fetch = async (url) => {
  calls.push(String(url))
  return { ok: true, status: 200, json: async () => answer }
}
const feedParams = [
  ['for_you_guest', {}, undefined],
  ['for_you_observer', { observer: ' Spike-Alice ' }, undefined],
  ['following', { following: 'Spike-Alice', observer: 'spike-alice' }, 'MjAyNi0xMC0xMlQxNjo0MDo1NyswMDowMHw2NDk0NjY='],
  ['tag', { tag: ' hive gaming&x=1 ', observer: 'spike-alice' }, undefined],
  ['tag_unicode', { tag: 'пчела🐝' }, 'a b+c/d=='],
  ['containers', { containers: ['ecency.waves', 'peak.snaps'], observer: 'spike-alice' }, undefined],
  ['author', { author: ' Spike-Bob ' }, undefined],
  ['limit', { limit: 5, tag: '' }, undefined],
]
const feedUrls = []
for (const [name, params, cursor] of feedParams) {
  calls.length = 0
  await sdk.getWavesFeedQueryOptions(params).queryFn({ pageParam: cursor, signal: undefined })
  feedUrls.push(`        UrlCase(${k(name)}, ${k(calls[0])}),`)
}
const shortsParams = [
  ['shorts_guest', {}, undefined],
  ['shorts_observer', { observer: 'Spike-Alice' }, 'abc=='],
  ['shorts_filters', { containers: ['ecency.waves'], tag: 'art', author: 'Spike-Bob', observer: 'spike-alice', limit: 7 }, undefined],
]
const shortsUrls = []
for (const [name, params, cursor] of shortsParams) {
  calls.length = 0
  await sdk.getShortsFeedQueryOptions(params).queryFn({ pageParam: cursor, signal: undefined })
  shortsUrls.push(`        UrlCase(${k(name)}, ${k(calls[0])}),`)
}
calls.length = 0
await sdk.getPromotedPostsQuery('waves').queryFn({})
const promotedUrl = calls[0]
calls.length = 0
await sdk.getWavesTrendingTagsQueryOptions(undefined, 24).queryFn({ signal: undefined })
const trendingUrl = calls[0]
calls.length = 0
await sdk.getWavesTrendingTagsQueryOptions(' ecency.waves ', 12).queryFn({ signal: undefined })
const trendingHostUrl = calls[0]

// ---- feed rows as the SDK normalizes them (normalizeWaveEntryFromApi) and its paging rule ----
const rows = [
  { id: 101, author: 'spike-bob', permlink: 'wave-0011223344556677', title: '', body: 'Hello #waves', json_metadata: { app: 'ecency/3.5.10-mobile', tags: ['waves'] },
    timestamp: '2026-10-12T16:40:57+00:00', pending_payout_value: '0.120 HBD', author_payout_value: '0.000 HBD', curator_payout_value: '0.000 HBD',
    net_votes: 3, children: 2, tip_count: 1, tipped_by_viewer: false, host: 'ecency.waves', _cursor: 'cursor-101' },
  { id: 102, author: 'spike-carol', permlink: 'snap-1', title: '', body: 'No payouts here', json_metadata: {}, created: '2026-10-12T16:00:00',
    timestamp: '2026-10-12T16:39:00+00:00', pending_payout_value: '', net_votes: 0, children: 0, host: 'peak.snaps', _cursor: 'cursor-102' },
  { post_id: 103, author: 'spike-dan', permlink: 'thread-1', body: 'no host, no cursor' },
]
answer = rows
const feedOptions = sdk.getWavesFeedQueryOptions({ limit: 4 })
const normalized = await feedOptions.queryFn({ pageParam: undefined, signal: undefined })
const normalizedRows = normalized.map((e) =>
  `        RowCase(${k(e.author)}, ${k(e.permlink)}, ${k(e.created ?? null)}, ${k(e.host)}, ${k(e._cursor ?? null)}, ${k(e.max_accepted_payout)}, ${k(e.pending_payout_value)}, ${k(e.author_payout_value)}, ${k(e.curator_payout_value)}),`)
const pagingCases = []
for (const [name, limit, page] of [
  ['full_page_with_cursor', 2, [{ _cursor: 'c1' }, { _cursor: 'c2' }]],
  ['short_page', 3, [{ _cursor: 'c1' }, { _cursor: 'c2' }]],
  ['full_page_without_cursor', 2, [{ _cursor: 'c1' }, {}]],
  ['empty_page', 2, []],
]) {
  const next = sdk.getWavesFeedQueryOptions({ limit }).getNextPageParam(page)
  pagingCases.push(`        PagingCase(${k(name)}, ${limit}, listOf(${page.map((r) => k(r._cursor ?? null)).join(', ')}), ${k(next ?? null)}),`)
}
answer = []

// ---- publishing a wave: RN src/components/quickPostModal/quickPostModalContent.tsx (_submitPost) and
//      usePostSubmitter.ts (_submitReply, _submitWave) @ cbcca86791, over the SDK's op builders (@ecency/sdk 2.4.11) ----
const SEED = 'ecency-native-spike-golden-vector-throwaway'
const key = PrivateKey.fromSeed(SEED)
const pub = key.createPublic().toString()
const HEADER = { ref_block_num: 12345, ref_block_prefix: 3367463123, expiration: '2026-10-06T12:00:00' }
const APP_VERSION = '4.0.0'
const container = { author: 'ecency.waves', permlink: 'waves-20261012abcdef', tags: ['waves-20261012', 'hive-125125'] }
const flow = { author: 'hive.flow', permlink: 'flow-2026-10-12', tags: null }
const publishInputs = [
  { name: 'plain', author: 'spike-alice', container, text: 'Hello waves', image: null, linked: {}, ratios: {} },
  { name: 'hashtags_unicode', author: 'spike-alice', container, text: 'Привет 🐝 #hive #Ecency_2026 #пчела #a-b и #1', image: null, linked: {}, ratios: {} },
  { name: 'image_attached', author: 'spike-alice', container, text: 'Look at this', image: 'https://images.ecency.com/DQmTest/photo.JPG',
    linked: {}, ratios: { 'https://images.ecency.com/DQmTest/photo.JPG': 1.3333333333333333 } },
  { name: 'image_only', author: 'spike-alice', container, text: '', image: 'https://images.ecency.com/DQmTest/only.webp',
    linked: {}, ratios: {} },
  { name: 'links_and_post_link', author: 'spike-bob', container,
    text: 'Read https://ecency.com/hive-125125/@spike-carol/my-post and https://example.com/page?x=1&y=2, also ftp://files.example.org/a.zip. xhttp://nope.example https://peakd.com/@gone/missing!',
    image: null,
    linked: { 'https://ecency.com/hive-125125/@spike-carol/my-post': { title: 'My "post" title' }, 'https://peakd.com/@gone/missing': null }, ratios: {} },
  { name: 'many_links', author: 'spike-bob', container,
    text: [1, 2, 3, 4, 5, 6, 7].map((i) => `https://example.com/l${i}`).join(' ') + ' https://example.com/a.png https://example.com/b.gif?x=1',
    image: null, linked: {}, ratios: { 'https://example.com/a.png': 0.5, 'https://example.com/b.gif?x=1': 2 } },
  { name: 'three_speak_embed', author: 'spike-alice', container, text: 'Short clip #fyp\n\nhttps://play.3speak.tv/embed?v=spike-alice/abcd1234', image: null, linked: {}, ratios: {} },
  { name: 'flow_container_no_tags', author: 'spike.dot-user', container: flow, text: 'Into hive.flow | with pipes', image: null, linked: {}, ratios: {} },
  { name: 'empty_container_tags', author: 'spike-alice', container: { author: 'ecency.waves', permlink: 'waves-20261012abcdef', tags: [] }, text: 'No tags on the container #solo', image: null, linked: {}, ratios: {} },
  // quickPostModalContent: an uploaded 3Speak video (the embed after the text, never the image) and its thumbnail
  { name: 'video_attached', author: 'spike-alice', container, text: 'My short #fyp', image: null, video: 'https://play.3speak.tv/embed?v=spike-alice/vid123',
    videoThumb: 'https://images.ecency.com/DQmThumb/thumbnail.jpg', linked: {}, ratios: { 'https://images.ecency.com/DQmThumb/thumbnail.jpg': 1.7777777777777777 } },
  { name: 'video_wins_over_image', author: 'spike-alice', container, text: 'Both attached', image: 'https://images.ecency.com/DQmTest/photo.JPG',
    video: 'https://play.3speak.tv/embed?v=spike-alice/vid123', videoThumb: null, linked: {}, ratios: {} },
  { name: 'video_only_with_body_image', author: 'spike-alice', container: flow, text: 'https://example.com/a.png', image: null,
    video: 'https://play.3speak.tv/embed?v=spike-alice/vid123', videoThumb: 'https://images.ecency.com/DQmThumb/t.png', linked: {}, ratios: { 'https://example.com/a.png': 0.75 } },
  { name: 'multiline_quotes', author: 'spike-alice', container, text: 'Line one\n"quoted" \\ back\tslash\nline <b>three</b> $ dollar', image: null, linked: {}, ratios: {} },
]
// ---- RN src/providers/speak/speak.ts @ cbcca86791: extractPermlink ----
function extractPermlink(embedUrl) {
  const vParam = embedUrl.match(/[?&]v=([^&]+)/)
  if (vParam?.[1]) {
    const parts = vParam[1].split('/')
    const permlink = parts[parts.length - 1]
    if (permlink) return permlink
  }
  const atFormat = embedUrl.match(/@[^/]+\/([a-zA-Z0-9]+)/)
  if (atFormat?.[1]) return atFormat[1]
  const lastSegment = embedUrl.split('/').pop() ?? ''
  return lastSegment.split('?')[0].split('#')[0]
}
// ---- RN src/providers/hive/hive.ts @ cbcca86791: isMissingEcencyPostingAuthorityError (usePostSubmitter's recovery) ----
const isMissingEcencyPostingAuthorityError = (error) => {
  if (!error) return false
  const code = String(error.error || '')
  const haystack = `${error.error_description || ''} ${error.message || ''} ${typeof error === 'string' ? error : ''}`.toLowerCase()
  return haystack.includes('permission to broadcast') || (code === 'unauthorized_client' && haystack.includes('ecency.app'))
}
const authorityInputs = [
  { error: 'unauthorized_client', error_description: "The app @ecency.app doesn't have permission to broadcast for @spike-alice" },
  { error: 'unauthorized_client', error_description: 'The token has expired' },
  { error: 'unauthorized_client' },
  { error: 'unauthorized_client', message: 'ECENCY.APP is not authorised' },
  { error: 'invalid_grant', error_description: 'ecency.app' },
  { message: 'No Permission To Broadcast for this account' },
  { error: 'server_error', message: 'timeout talking to ecency.app' },
  { message: '' },
  {},
]
const authorityCases = authorityInputs.map((e) =>
  `        AuthorityCase(${k(e.error ?? null)}, ${k(e.error_description ?? null)}, ${k(e.message ?? null)}, ${isMissingEcencyPostingAuthorityError(e)}),`)
// the embed RN's _submitReply finds in a body and the video permlink it links (null: no call)
const linkInputs = [
  'Clip\n\nhttps://play.3speak.tv/embed?v=spike-alice/vid123', 'https://3speak.tv/embed?v=spike-alice/abc&autoplay=1 tail', 'http://play.3speak.tv/embed/@spike-alice/abc123',
  'https://play.3speak.tv/embed/xyz789?x=1#frag', 'HTTPS://PLAY.3SPEAK.TV/EMBED?v=a/b', 'https://play.3speak.tv/embed?v=spike-alice/', 'https://3speak.tv/watch?v=a/b',
  'two https://play.3speak.tv/embed?v=a/first and https://play.3speak.tv/embed?v=a/second', '<iframe src="https://play.3speak.tv/embed?v=a/quoted"></iframe>', 'no video',
  'https://a-b.3speak.tv/embed?v=a/dash', 'https://play.3speak.tv/embed?x=1&v=own.er/with-dash_1',
]
const linkCases = linkInputs.map((body) => {
  let permlink = null
  if (sdk.hasThreeSpeakEmbed(body)) {
    const m = body.match(/https?:\/\/[a-z.]*3speak\.tv\/embed[?/][^\s<"']*/)
    if (m) permlink = extractPermlink(m[0]) || null
  }
  return `        ${k(body)} to ${k(permlink)},`
})
const publishCases = []
for (const c of publishInputs) {
  // quickPostModalContent._submitPost
  let body = c.text
  if (c.video) body = `${body}\n\n${c.video}`
  else if (c.image) body = `${body}\n\n ![](${c.image})`
  // usePostSubmitter._submitReply (postType wave, no poll)
  const permlink = generateContentBasedPermlink('wave', [c.author, c.container.author, c.container.permlink, body, ''].join('|'))
  const parentTags = c.container.tags || ['ecency']
  const tags = [...parentTags, ...extractHashTags(body)]
  const meta = extractMetadata({ body, videoThumbUrls: c.videoThumb ? [c.videoThumb] : undefined, linked: c.linked, ratios: c.ratios })
  const jsonMetadata = makeJsonMetadata(meta, tags, APP_VERSION)
  const ops = [sdk.buildCommentOp(c.author, permlink, c.container.author, c.container.permlink, '', body, jsonMetadata)]
  if (sdk.hasThreeSpeakEmbed(body)) {
    const beneficiaries = sdk.enforceThreeSpeakBeneficiary([], body)
    // SDK useComment with options { beneficiaries }
    const sorted = [...beneficiaries].sort((a, b) => a.account.localeCompare(b.account))
    ops.push(sdk.buildCommentOptionsOp(c.author, permlink, '1000000.000 HBD', 10000, true, true,
      [[0, { beneficiaries: sorted.map((b) => ({ account: b.account, weight: b.weight })) }]]))
  }
  const raw = { ...HEADER, operations: ops, extensions: [] }
  const tx = new HiveTxTransaction({ transaction: raw })
  const { digest, txId } = tx.digest()
  const rv = secp256k1.sign(digest, key.key, { extraEntropy: false, format: 'recovered', prehash: false })
  const signature = (rv[0] + 31).toString(16) + hex(rv.subarray(1))
  if (Signature.from(signature).getPublicKey(digest).toString() !== pub) throw new Error(`${c.name}: signature does not recover`)
  // usePostSubmitter._submitReply after the broadcast: the body of linkVideoToHive's request (RN providers/speak/speak.ts),
  // without the access token; null when RN makes no call
  let link = null
  if (sdk.hasThreeSpeakEmbed(body)) {
    const embedMatch = body.match(/https?:\/\/[a-z.]*3speak\.tv\/embed[?/][^\s<"']*/)
    if (embedMatch) {
      const videoPermlink = extractPermlink(embedMatch[0])
      if (videoPermlink) link = JSON.stringify({ permlink: videoPermlink, hive_author: c.author, hive_permlink: permlink, hive_tags: tags })
    }
  }
  const linkedKt = Object.entries(c.linked).map(([u, p]) => `${k(u)} to ${p ? k(p.title) : 'null'}`).join(', ')
  const ratiosKt = Object.entries(c.ratios).map(([u, r]) => `${k(u)} to ${Number.isInteger(r) ? r.toFixed(1) : r}`).join(', ')
  publishCases.push(`        PublishCase(
            name = ${k(c.name)}, author = ${k(c.author)}, containerAuthor = ${k(c.container.author)}, containerPermlink = ${k(c.container.permlink)},
            containerTags = ${c.container.tags ? `listOf<String>(${c.container.tags.map(k).join(', ')})` : 'null'},
            text = ${k(c.text)}, image = ${k(c.image)}, video = ${k(c.video ?? null)}, videoThumb = ${k(c.videoThumb ?? null)}, link = ${k(link)},
            linked = mapOf<String, String?>(${linkedKt}), ratios = mapOf<String, Double>(${ratiosKt}),
            body = ${k(body)}, permlink = ${k(permlink)}, jsonMetadata = ${k(JSON.stringify(jsonMetadata))},
            ops = ${k(JSON.stringify(ops))},
            digest = ${k(hex(digest))}, txId = ${k(txId)}, signature = ${k(signature)},
        ),`)
}

const captionInputs = [
  { title: '', body: 'Another quick sketch.\n#fyp #ecency\n\nhttps://play.3speak.tv/embed?v=spike-alice/abcd1234' },
  { title: 'RE: Ecency waves Oct 11', body: 'ignored body' },
  { title: 'https://example.com/only-a-link', body: 'Body wins <br>https://play.3speak.tv/embed?v=a/b ▶️ [Watch on 3Speak](https://3speak.tv/watch?v=a/b)' },
  { title: '![](https://example.com/a.png)', body: 'See [my site](https://example.com) and ![alt](https://example.com/b.png) ▶ now' },
  { title: null, body: 'x'.repeat(200) },
  { title: '', body: '🐝'.repeat(80) },
  { title: '', body: '' },
  { title: '   ', body: '\tTabs and wide   spaces\n\nhere ' },
  { title: '', body: '[play](play.3speak.tv/watch?v=a/b) [3s](HTTP://3SPEAK.tv/x) <a href="x">link</a> text' },
]
const captionCases = captionInputs.map((c) => `        CaptionCase(${k(c.title)}, ${k(c.body)}, ${k(buildReelCaption(c))}),`)

const tagInputs = ['  #Hive ', '##ecency-waves', 'Hive Gaming!', 'пчела', '#', '', 'a_b-c.d', 'ÀBC1', '-x-', 'tag#two']
const tagCases = tagInputs.map((t) => `        ${k(t)} to ${k(normalizeTag(t))},`)

const hashInputs = ['#one #two', 'no tags', '#', 'a#b c #d_e #f-g #9 ##h', 'Привет #пчела #ok🐝 #Ünï', 'https://example.com/#anchor #x']
const hashCases = hashInputs.map((t) => `        ${k(t)} to listOf(${extractHashTags(t).map(k).join(', ')}),`)

const questInputs = ['', 'Thank you', 'x'.repeat(25), 'x'.repeat(26), '🐝'.repeat(26), '🐝'.repeat(25), 'https://example.com/' + 'x'.repeat(60),
  'look https://example.com/a.png ' + 'y'.repeat(21), 'HTTPS://EXAMPLE.COM/' + 'x'.repeat(30), '   ' + ' '.repeat(30), 'a\nhttp://x.y\nb' + 'c'.repeat(22)]
const questCases = questInputs.map((t) => `        QuestCase(${k(t)}, ${sdk.measureQuestContentLength(t)}, ${sdk.earnsQuestContentCredit(t)}),`)

const speakInputs = ['https://play.3speak.tv/embed?v=a/b', 'http://3speak.tv/embed/abc', 'https://3speak.tv/watch?v=a/b', 'HTTPS://PLAY.3SPEAK.TV/EMBED?v=a',
  'https://evil3speak.tv/embed?v=a', 'https://a.b.3speak.tv/embed/x', 'text only', 'https://3speak.tv.evil.example/embed?x']
const speakCases = speakInputs.map((t) => `        ${k(t)} to ${sdk.hasThreeSpeakEmbed(t)},`)

const composeInputs = [
  'https://ecency.com/waves?text=Hello%20world', 'https://ecency.com/waves', 'https://www.ecency.com/waves/?text=%F0%9F%90%9D+bee&x=1',
  'ecency://waves?text=from+scheme', 'https://ecency.com/waves/spike-alice/wave-1', 'https://ecency.com:8443/waves?text=x', 'https://example.com/waves?text=x',
  'http://estm.to/WAVES?text=%20%20trim%20me%20%20', 'https://ecency.com/waves?text=' + encodeURIComponent('🐝'.repeat(2100)), 'not a url', '',
  'https://ecency.com/waves?text=a&text=b', 'https://ecency.com/wavesx?text=a', 'https://ecency.com/waves#text=a',
]
const composeCases = composeInputs.map((u) => {
  const r = parseWavesComposeUrl(u)
  return `        ComposeCase(${k(u)}, ${r ? 'true' : 'false'}, ${k(r ? r.text : null)}),`
})

writeFileSync(out, `// GENERATED by native/tools/golden/generate-waves.mjs from @ecency/sdk and the RN composer helpers. Do not edit by hand.
// The signing key derives from a public throwaway seed and controls no funds.
package com.ecency.core.api.waves

object WavesGoldens {
    data class UrlCase(val name: String, val url: String)
    data class RowCase(
        val author: String, val permlink: String, val created: String?, val host: String, val cursor: String?,
        val maxAcceptedPayout: String, val pending: String, val authorPayout: String, val curatorPayout: String,
    )
    data class PagingCase(val name: String, val limit: Int, val cursors: List<String?>, val next: String?)
    data class PublishCase(
        val name: String, val author: String, val containerAuthor: String, val containerPermlink: String, val containerTags: List<String>?,
        val text: String, val image: String?, val video: String?, val videoThumb: String?, val link: String?, val linked: Map<String, String?>, val ratios: Map<String, Double>,
        val body: String, val permlink: String, val jsonMetadata: String, val ops: String,
        val digest: String, val txId: String, val signature: String,
    )
    data class AuthorityCase(val code: String?, val description: String?, val message: String?, val missing: Boolean)
    data class CaptionCase(val title: String?, val body: String, val caption: String)
    data class QuestCase(val body: String, val length: Int, val earns: Boolean)
    data class ComposeCase(val url: String, val matches: Boolean, val text: String?)

    const val SEED = ${k(SEED)}
    const val PUBLIC_KEY = ${k(pub)}
    const val REF_BLOCK_NUM = ${HEADER.ref_block_num}
    const val REF_BLOCK_PREFIX = ${HEADER.ref_block_prefix}L
    const val EXPIRATION = ${k(HEADER.expiration)}
    const val APP_VERSION = ${k(APP_VERSION)}
    const val QUEST_MIN_CONTENT_LENGTH = ${sdk.QUEST_MIN_CONTENT_LENGTH}

    /** getWavesFeedQueryOptions(params).queryFn: the URL it fetched. */
    val feedUrls = listOf(
${feedUrls.join('\n')}
    )

    /** getShortsFeedQueryOptions(params).queryFn. */
    val shortsUrls = listOf(
${shortsUrls.join('\n')}
    )

    const val PROMOTED_URL = ${k(promotedUrl)}
    const val TRENDING_TAGS_URL = ${k(trendingUrl)}
    const val TRENDING_TAGS_HOST_URL = ${k(trendingHostUrl)}

    /** The rows the fake feed answered with. */
    const val ROWS_JSON = ${k(JSON.stringify(rows))}

    /** What normalizeWaveEntryFromApi made of [ROWS_JSON] . */
    val normalizedRows = listOf(
${normalizedRows.join('\n')}
    )

    /** getNextPageParam over a page whose rows carry these cursors. */
    val paging = listOf(
${pagingCases.join('\n')}
    )

    val publish = listOf(
${publishCases.join('\n')}
    )

    /** A body to the video permlink RN's _submitReply hands linkVideoToHive (null: no call). */
    val videoLinks = listOf<Pair<String, String?>>(
${linkCases.join('\n')}
    )

    /** isMissingEcencyPostingAuthorityError over HiveSigner's error, error_description and an error's message. */
    val authorityErrors = listOf(
${authorityCases.join('\n')}
    )

    val captions = listOf(
${captionCases.join('\n')}
    )

    val normalizedTags = listOf(
${tagCases.join('\n')}
    )

    val hashTags = listOf(
${hashCases.join('\n')}
    )

    val quest = listOf(
${questCases.join('\n')}
    )

    val threeSpeakEmbeds = listOf(
${speakCases.join('\n')}
    )

    val compose = listOf(
${composeCases.join('\n')}
    )
}
`)
console.log(`wrote ${out}`)
