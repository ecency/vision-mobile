// Golden vectors for the vote-free bridge v2 reads (BridgeV2Goldens.kt), from @ecency/sdk's own code:
// - normalizeEntry, entryVoteOf, isCastVote and voteDirection over v1 and v2 post responses (shapes recorded from
//   api.hive.blog, names and values synthetic) and edge fixtures;
// - callRPCWithFallback (the per-node v2 to v1 fallback and the node-level error classifier) over a scripted fetch:
//   which node is asked for which method, and whether the read answers, fails over or throws;
// - getEntryVote (one account's vote, database_api.list_votes lower-bound seek) over scripted rows;
// - getEntryVotes (the voters list: bridge.list_votes pages, database_api.list_votes on a node without it).
//
// The SDK comes from a scratch install:
//   npm install --prefix /tmp/golden-bridge-v2 @ecency/sdk@2.5.0 @tanstack/react-query@5 react
//   GOLDEN_MODULES=/tmp/golden-bridge-v2 node tools/golden/generate-bridge-v2.mjs \
//     shared/src/commonTest/kotlin/com/ecency/core/api/BridgeV2Goldens.kt
//
// Offline: fetch is replaced by a script, every node URL is a .test host. Synthetic accounts only.
import { createRequire } from 'node:module'
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const base = process.env.GOLDEN_MODULES
if (!base) throw new Error('set GOLDEN_MODULES to the scratch install (see the header)')
const require = createRequire(join(base, 'package.json'))
const sdk = await import(require.resolve('@ecency/sdk'))
const sdkVersion = JSON.parse(readFileSync(join(base, 'node_modules', '@ecency/sdk', 'package.json'), 'utf8')).version
const { normalizeEntry, entryVoteOf, isCastVote, voteDirection, callRPCWithFallback, getEntryVote, getEntryVotes, hiveTxConfig } = sdk

const out = process.argv[2]
if (!out) throw new Error('usage: generate-bridge-v2.mjs <output.kt>')

// ---- rows: compact specs both sides expand the same way ----

// `{ count, observerAt?, observerRow?, start? }`: `count` rows `spike-v0000`... with rshares 1000 + i and no percent
// (a v1 bridge row); `observerRow` replaces row `observerAt` (its voter is the observer).
function expandRows(spec, observer) {
  const rows = []
  for (let i = 0; i < spec.count; i++) rows.push({ voter: `spike-v${String(i).padStart(4, '0')}`, rshares: 1000 + i })
  if (spec.observerAt !== undefined) rows[spec.observerAt] = { voter: observer, ...spec.observerRow }
  return rows
}

// What a v1 bridge.get_post answer looks like (keys as api.hive.blog sends them), synthetic values.
const v1Post = (extra = {}) => ({
  author: 'spike-author', permlink: 'spike-post', category: 'hive-100001', title: 'A post', body: 'Body',
  json_metadata: { tags: ['hive-100001'], app: 'ecency/4.0.0-mobile' }, created: '2026-11-11T15:09:06',
  updated: '2026-11-11T15:09:06', depth: 0, children: 7, net_rshares: 359391106419416, is_paidout: false,
  payout_at: '2026-11-18T15:09:06', payout: 21.992, pending_payout_value: '21.992 HBD', author_payout_value: '0.000 HBD',
  curator_payout_value: '0.000 HBD', promoted: '0.000 HBD', replies: [], reblogs: 6, author_reputation: 64.7,
  stats: { hide: false, gray: false, total_votes: 3, flag_weight: 0.0 }, beneficiaries: [], max_accepted_payout: '1000000.000 HBD',
  percent_hbd: 10000, url: '/hive-100001/@spike-author/spike-post', blacklists: [], community: 'hive-100001',
  community_title: 'Spike Community', author_role: 'guest', author_title: '',
  active_votes: [
    { voter: 'spike-a', rshares: 70014934904448 },
    { voter: 'spike-me', rshares: 69837949364309 },
    { voter: 'spike-b', rshares: -1200 },
  ],
  ...extra,
})

// The same post as bridge.get_post_v2 answers it: no active_votes, observer_vote and top_votes instead.
const v2Post = (extra = {}) => {
  const { active_votes, ...rest } = v1Post()
  return {
    ...rest,
    observer_vote: { rshares: 69837949364309, percent: 10000 },
    top_votes: [{ voter: 'spike-a', percent: 10000, rshares: 70014934904448 }],
    ...extra,
  }
}

const strip = (o, ...keys) => { const c = { ...o }; for (const k of keys) delete c[k]; return c }

// [name, raw (object, or { rows } spec merged into a v1 post), observer, completeVoteList]
const normalizeInputs = [
  ['v1 recorded, observer voted', v1Post(), 'spike-me', false],
  ['v1 recorded, observer not in list', v1Post(), 'spike-c', false],
  ['v1 recorded, no observer', v1Post(), '', false],
  ['v1 recorded, observer downvoted', v1Post(), 'spike-b', false],
  ['v2 recorded, observer voted', v2Post(), 'spike-me', false],
  ['v2 recorded, observer not voted', v2Post({ observer_vote: null }), 'spike-c', false],
  ['v2 recorded, no observer', v2Post({ observer_vote: null }), '', false],
  ['v2 percent 0 is a removed vote', v2Post({ observer_vote: { rshares: 0, percent: 0 } }), 'spike-me', false],
  ['v2 weightless vote (rshares 0, percent set)', v2Post({ observer_vote: { rshares: 0, percent: 7600 } }), 'spike-me', false],
  ['v2 downvote', v2Post({ observer_vote: { rshares: -5000, percent: -5000 } }), 'spike-me', false],
  ['v2 rshares as text', v2Post({ observer_vote: { rshares: '123', percent: 100 } }), 'spike-me', false],
  ['v2 percent missing, rshares set', v2Post({ observer_vote: { rshares: 55 } }), 'spike-me', false],
  ['v2 percent missing, rshares 0', v2Post({ observer_vote: { rshares: 0 } }), 'spike-me', false],
  ['v2 observer_vote not an object', v2Post({ observer_vote: 5 }), 'spike-me', false],
  ['v2 observer_vote empty object', v2Post({ observer_vote: {} }), 'spike-me', false],
  ['v2 without stats', strip(v2Post(), 'stats'), 'spike-me', false],
  ['v1 row rshares 0 without percent is unknown', v1Post({ active_votes: [{ voter: 'spike-me', rshares: 0 }] }), 'spike-me', false],
  ['v1 row rshares 0 with percent is cast', v1Post({ active_votes: [{ voter: 'spike-me', rshares: 0, percent: 5000 }] }), 'spike-me', false],
  ['v1 row percent 0 is removed', v1Post({ active_votes: [{ voter: 'spike-me', rshares: 10, percent: 0 }] }), 'spike-me', false],
  ['v1 row rshares as text', v1Post({ active_votes: [{ voter: 'spike-me', rshares: '-77' }] }), 'spike-me', false],
  ['v1 rows with junk', v1Post({ active_votes: [null, { rshares: 5 }, { voter: 'spike-me', rshares: 9 }] }), 'spike-me', false],
  ['v1 empty list', v1Post({ active_votes: [] }), 'spike-me', false],
  ['v1 empty list, no stats', strip(v1Post({ active_votes: [] }), 'stats'), 'spike-me', false],
  ['v1 999 rows, observer absent, no stats', { rows: { count: 999 }, noStats: true }, 'spike-me', false],
  ['v1 1000 rows, observer absent, no stats', { rows: { count: 1000 }, noStats: true }, 'spike-me', false],
  ['v1 1000 rows, observer absent, stats', { rows: { count: 1000 }, totalVotes: 1225 }, 'spike-me', false],
  ['v1 1000 rows, observer last', { rows: { count: 1000, observerAt: 999, observerRow: { rshares: 42 } }, totalVotes: 1225 }, 'spike-me', false],
  ['complete list 1500 rows, observer absent', { rows: { count: 1500 }, noStats: true }, 'spike-me', true],
  ['complete list 1500 rows, observer row 1200', { rows: { count: 1500, observerAt: 1200, observerRow: { rshares: 7, percent: 300 } }, noStats: true }, 'spike-me', true],
  ['complete list 1000 rows, no observer', { rows: { count: 1000 }, noStats: true }, '', true],
  ['waves feed row (no list, no observer_vote)', strip(v2Post(), 'observer_vote', 'top_votes'), 'spike-me', false],
  ['normalized before for another account', { ...v2Post(), observer_vote_for: 'spike-other' }, 'spike-me', false],
  ['normalized before as unknown', { ...v2Post(), observer_vote_for: null, observer_vote: null }, 'spike-me', false],
  ['normalized before with empty account', { ...v2Post(), observer_vote_for: '' }, 'spike-me', false],
  ['normalized before for the observer, removed vote', { ...v2Post(), observer_vote_for: 'spike-me', observer_vote: { rshares: 3, percent: 0 } }, 'spike-me', false],
]

function rawOf(input, observer) {
  if (!input.rows) return input
  const raw = v1Post({ active_votes: expandRows(input.rows, observer) })
  if (input.noStats) delete raw.stats
  else raw.stats = { ...raw.stats, total_votes: input.totalVotes }
  return raw
}

const voteOut = (v) => (v === undefined ? 'unknown' : v === null ? null : { rshares: v.rshares, percent: v.percent })

const normalize = normalizeInputs.map(([name, input, observer, complete]) => {
  const raw = rawOf(input, observer)
  const e = normalizeEntry(raw, observer, complete ? { completeVoteList: true } : {})
  const own = entryVoteOf(e, observer)
  return {
    name,
    observer,
    complete,
    raw: input.rows ? undefined : input,
    rows: input.rows ? input : undefined,
    out: {
      observer_vote: e.observer_vote,
      observer_vote_for: e.observer_vote_for,
      has_active_votes: 'active_votes' in e,
      // what validateEntry gives a row without stats: total_votes 0
      total_votes: e.stats ? (e.stats.total_votes ?? 0) : 0,
      vote_of_observer: voteOut(own),
      vote_of_other: voteOut(entryVoteOf(e, 'spike-nobody')),
      direction: voteDirection(own),
    },
  }
})

const castInputs = [null, { rshares: 0, percent: 0 }, { rshares: 5, percent: 0 }, { rshares: 0, percent: null }, { rshares: null, percent: null },
  { rshares: 0, percent: 100 }, { rshares: -1, percent: null }, { rshares: 10, percent: -100 }, { rshares: null, percent: 2500 }, { rshares: -3, percent: 2500 }]
const casts = castInputs.map((v) => ({ vote: v, cast: isCastVote(v), direction: voteDirection(v) }))

// ---- scripted nodes ----

const realFetch = globalThis.fetch
let script = null
const requests = []
globalThis.fetch = async (url, init) => {
  const body = JSON.parse(String(init.body))
  const node = String(url)
  if (Array.isArray(body)) {
    requests.push({ node, batch: body.map((b) => ({ method: b.method, params: b.params })) })
    const answers = body.map((b) => ({ jsonrpc: '2.0', id: b.id, ...script(node, b.method, b.params) }))
    return new Response(JSON.stringify(answers), { status: 200, headers: { 'content-type': 'application/json' } })
  }
  requests.push({ node, method: body.method, params: body.params })
  const answer = script(node, body.method, body.params)
  return new Response(JSON.stringify({ jsonrpc: '2.0', id: body.id, ...answer }), { status: 200, headers: { 'content-type': 'application/json' } })
}
hiveTxConfig.retry = 2
hiveTxConfig.timeout = 5000
if (hiveTxConfig.resilience) hiveTxConfig.resilience.hedge = false

const UNKNOWN = (m) => `Assert Exception:unknown method: ${m}`
const err = (code, message, assertion) => ({
  error: { code, message, ...(assertion === undefined ? {} : { data: { code: 10, extension: { assertion_expression: assertion }, message: 'Assert Exception', name: 'assert_exception' } }) },
})
const ok = (result) => ({ result })

// Each scenario: the pool, what node A answers for the primary method (node B and fallbacks answer), and the reads.
// A fresh pool per scenario: the SDK's marks and node health are process-wide.
let poolN = 0
const scenarios = [
  ['unknown method: v2 then v1 on the same node, then v1 only', err(-32602, UNKNOWN('bridge_apiget_post_v2'), 'unknown method: bridge_apiget_post_v2'), ['get_post', 'get_post']],
  ['unknown method in the assertion only', err(-32602, 'Assert Exception', 'unknown method: bridge_apiget_post_v2'), ['get_post']],
  ['bare unknown method text with spaces', err(-32602, '  unknown method: bridge_apiget_post_v2 '), ['get_post']],
  ['one unknown method marks the whole family', err(-32602, UNKNOWN('bridge_apiget_post_v2')), ['get_post', 'get_ranked_posts', 'get_account_posts', 'get_discussion']],
  ['unknown method naming another method is a request error', err(-32602, UNKNOWN('bridge_apiget_ranked_posts_v2')), ['get_post']],
  ['echoed unknown method text is a request error', err(-32602, 'Assert Exception:Post spike-author/unknown method: bridge_apiget_post_v2 does not exist'), ['get_post']],
  ['unknown method text under another code', err(-32000, UNKNOWN('bridge_apiget_post_v2')), ['get_post']],
  ['method not found fails over with v2', err(-32601, 'Method not found'), ['get_post']],
  ['missing API assert fails over', err(-32003, 'Assert Exception:api_itr != data._registered_apis.end(): Could not find API bridge_api'), ['get_post']],
  ['missing method assert fails over', err(-32003, 'Assert Exception:method_itr != api_itr->second.end(): Could not find method get_post_v2'), ['get_post']],
  ['hivemind redirect assert fails over', err(-32003, 'Assert Exception:false: Supported by hivemind'), ['get_post']],
  ['capability text later in the message is a request error', err(-32003, 'Assert Exception:Post a/x Could not find API does not exist'), ['get_post']],
  ['echoed internal in an assert is a request error', err(-32602, 'Assert Exception:Post spike-author/internal-notes does not exist'), ['get_post']],
  ['hivemind backend down fails over', err(-32602, 'Unable to parse endpoint data'), ['get_post']],
  ['internal error fails over', err(-32603, 'Internal Error'), ['get_post']],
  ['server error fails over', err(-32000, 'Server error'), ['get_post']],
  ['plain invalid params is a request error', err(-32602, 'Invalid parameters'), ['get_post']],
]

const PARAMS = { author: 'spike-author', permlink: 'spike-post', observer: 'spike-me' }
const fallback = []
for (const [name, answerA, reads] of scenarios) {
  poolN++
  const A = `https://node-a${poolN}.test`
  const B = `https://node-b${poolN}.test`
  hiveTxConfig.nodes = [A, B]
  script = (node, method) => (node === A && method.endsWith('_v2') ? answerA : ok({ answeredBy: method }))
  const results = []
  for (const endpoint of reads) {
    requests.length = 0
    let outcome
    try {
      const r = await callRPCWithFallback({ method: `bridge.${endpoint}_v2`, params: PARAMS }, { method: `bridge.${endpoint}`, params: PARAMS })
      outcome = `answered ${r.method}`
    } catch (e) {
      outcome = `threw ${e.code ?? e.name}`
    }
    results.push({ endpoint, calls: requests.map((r) => [r.node === A ? 'A' : 'B', r.method]), outcome })
  }
  fallback.push({ name, answer: answerA, reads: results })
}

// ---- one account's vote (database_api.list_votes, limit 1) ----

const row = (voter, author, permlink, rshares, percent) => ({ voter, author, permlink, rshares, vote_percent: percent, weight: 1, last_update: '2026-11-07T06:45:09' })
const lookupInputs = [
  ['the account voted', [row('spike-me', 'spike-author', 'spike-post', 900, 10000)]],
  ['weightless vote', [row('spike-me', 'spike-author', 'spike-post', 0, 7600)]],
  ['downvote, rshares as text', [row('spike-me', 'spike-author', 'spike-post', '-500', -2000)]],
  ['removed vote', [row('spike-me', 'spike-author', 'spike-post', 0, 0)]],
  ['next voter (did not vote)', [row('spike-next', 'spike-author', 'spike-post', 1, 100)]],
  ['next post (did not vote)', [row('spike-me', 'spike-author', 'spike-post-2', 1, 100)]],
  ['next author (did not vote)', [row('spike-me', 'spike-author2', 'spike-post', 1, 100)]],
  ['no rows', []],
]
const lookups = []
for (const [name, votes] of lookupInputs) {
  poolN++
  hiveTxConfig.nodes = [`https://node-a${poolN}.test`]
  script = () => ok({ votes })
  requests.length = 0
  const vote = await getEntryVote('spike-author', 'spike-post', 'spike-me')
  lookups.push({ name, votes, request: requests[0].batch[0], vote })
}

// ---- the voters list ----

// `{ count, author }` voters of spike-author/spike-post, then `next` rows of the next post in the index.
const voterRow = (i) => ({ voter: `spike-v${String(i).padStart(4, '0')}`, rshares: 10 + i, percent: 10000, weight: 5 + i, time: '2026-11-07T06:45:09' })
const dbRow = (i, permlink = 'spike-post') => ({ ...row(`spike-v${String(i).padStart(4, '0')}`, 'spike-author', permlink, 10 + i, 10000), weight: 5 + i })
const votersInputs = [
  // bridge.list_votes pages: start is exclusive
  ['list_votes pages', { count: 1500, listVotes: 'paged' }],
  ['list_votes exactly one full page', { count: 1000, listVotes: 'paged' }],
  ['list_votes short page', { count: 3, listVotes: 'paged' }],
  // a cache that answers the full first page whatever the start: no new voter ends the list
  ['list_votes ignoring start', { count: 1000, listVotes: 'repeat' }],
  // a node without bridge.list_votes: database_api pages, start inclusive, ends at the next post
  ['database_api pages', { count: 1059, next: 3, listVotes: 'unknown' }],
  ['database_api exactly one full page', { count: 1000, next: 0, listVotes: 'unknown' }],
]
const voters = []
for (const [name, spec] of votersInputs) {
  poolN++
  hiveTxConfig.nodes = [`https://node-a${poolN}.test`]
  const list = Array.from({ length: spec.count }, (_, i) => voterRow(i))
  const db = [...Array.from({ length: spec.count }, (_, i) => dbRow(i)), ...Array.from({ length: spec.next ?? 0 }, (_, i) => dbRow(spec.count + i, 'spike-post-2'))]
  script = (_node, method, params) => {
    if (method === 'bridge.list_votes') {
      if (spec.listVotes === 'unknown') return err(-32602, UNKNOWN('bridge_apilist_votes'))
      const from = spec.listVotes === 'repeat' || params.start === undefined ? 0 : list.findIndex((v) => v.voter > params.start)
      return ok(from < 0 ? [] : list.slice(from, from + params.limit))
    }
    const voter = params.start[2]
    const from = voter ? db.findIndex((v) => v.voter >= voter) : 0
    return ok({ votes: from < 0 ? [] : db.slice(from, from + params.limit) })
  }
  requests.length = 0
  const votes = await getEntryVotes('spike-author', 'spike-post')
  voters.push({
    name,
    spec,
    requests: requests.map((r) => ({ method: r.method, params: r.params })),
    count: votes.length,
    first: votes[0] ?? null,
    last: votes[votes.length - 1] ?? null,
  })
}

globalThis.fetch = realFetch

// `rows` cases are v1Base with the expanded rows as active_votes, and stats as the spec says.
const golden = { sdk: sdkVersion, v1Base: v1Post(), normalize, casts, fallback, lookups, voters }
const json = JSON.stringify(golden)
if (json.includes('$') || json.includes('"""')) throw new Error('the golden JSON must hold no $ or triple quotes (Kotlin raw string)')
// Kotlin raw strings: split so no single constant passes the JVM's 65535 byte limit.
// A cut never sits next to a quote, so no chunk starts or ends with one.
const chunks = []
for (let from = 0; from < json.length;) {
  let to = Math.min(from + 30000, json.length)
  while (to < json.length && (json[to - 1] === '"' || json[to] === '"')) to++
  chunks.push(json.slice(from, to))
  from = to
}

writeFileSync(out, `// GENERATED by tools/golden/generate-bridge-v2.mjs from @ecency/sdk ${sdkVersion}. Do not edit by hand.
// Synthetic accounts and values; every node is a .test host.
package com.ecency.core.api

object BridgeV2Goldens {
    /** The SDK's answers as JSON (see the generator for each section). */
    val JSON: String get() = listOf(
${chunks.map((c) => `        """${c}""",`).join('\n')}
    ).joinToString("")
}
`)
console.log(`wrote ${out}`)
